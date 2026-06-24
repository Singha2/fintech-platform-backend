package com.arthvritt.platform.assignment;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.signing.SignMethod;
import com.arthvritt.platform.signing.SigningPort.SignatureResult;
import com.arthvritt.platform.signing.SignatureAclService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC5 Assignment &amp; Signing (WS-6, single-investor cut). On a {@code fully_funded} listing, {@code
 * request} opens the {@code legal_assignment_set} (one per listing, AS.1; {@code total_count} = confirmed
 * subscriptions = 1) and initiates the investor's MIA signature through the M5c {@link SignatureAclService}.
 * {@code completeSigning} drives the signature to completion (the stub completes in-process — inline, the
 * signing webhook is M12-full), marks the MIA {@code signed} (cert) + the leg {@code signed}, and when
 * {@code signed_count == total_count} flips the set to {@code all_signed} and {@code deal_listing.all_signed
 * = TRUE} — the C27 disbursement gate WS-7 depends on. All commands route through {@link CommandGateway}.
 */
@Service
public class AssignmentService {

    private static final String CONTEXT = "assignment";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final SignatureAclService signing;
    private final ObjectMapper mapper;

    public AssignmentService(JdbcTemplate jdbc, CommandGateway gateway, SignatureAclService signing,
                             ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.signing = signing;
        this.mapper = mapper;
    }

    public CommandResult<UUID> request(CommandRequest request, UUID listingId) {
        return gateway.execute(request, OPS, () -> {
            String listingStatus = jdbc.query("SELECT status::text FROM deal_listing WHERE listing_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, listingId);
            if (listingStatus == null) {
                throw new NotFoundException("listing not found: " + listingId);
            }
            if (!"fully_funded".equals(listingStatus)) {
                throw new ValidationException("listing is not fully_funded: " + listingId);
            }
            // total_count = confirmed subscriptions (AS.1). Skeleton: the single confirmed subscription.
            Subscriber sub = jdbc.query(
                    "SELECT investor_id, amount FROM sub_subscription "
                            + "WHERE listing_id = ? AND status = 'confirmed' LIMIT 1",
                    rs -> rs.next() ? new Subscriber(rs.getObject("investor_id", UUID.class), rs.getLong("amount")) : null,
                    listingId);
            if (sub == null) {
                throw new ValidationException("no confirmed subscription to assign on listing: " + listingId);
            }

            UUID assignmentSetId = request.aggregateId();
            UUID agreementId = Ids.newId();
            UUID signatureRequestId = Ids.newId();
            UUID vsrId = Ids.newId();
            byte[] docHash = sha256("MIA:" + assignmentSetId + ":" + sub.investorId());
            String docHashHex = java.util.HexFormat.of().formatHex(docHash);
            String parentRef = assignmentSetId + ":" + sub.investorId();

            try {
                // One set per listing (AS.1, UNIQUE). counts: total=unsigned=1, signed=0 (AS.5).
                jdbc.update("INSERT INTO legal_assignment_set (assignment_set_id, listing_id, sign_deadline, "
                                + "status, signed_count, unsigned_count, total_count, legs) "
                                + "VALUES (?, ?, now() + interval '24 hours', 'in_progress', 0, 1, 1, ?::jsonb)",
                        assignmentSetId, listingId, leg(sub, agreementId, signatureRequestId, vsrId, "initiated"));
            } catch (DuplicateKeyException e) {
                throw new ValidationException("an assignment set already exists for listing: " + listingId);
            }
            // The investor's MIA + signature request (BC5 records), then initiate via the M5c ACL.
            jdbc.update("INSERT INTO legal_master_agreement (agreement_id, party_id, party_type, kind, doc_hash, status) "
                            + "VALUES (?, ?, 'investor'::legal_master_agreement_party_type, "
                            + "'MIA'::legal_master_agreement_kind, ?, 'initiated')",
                    agreementId, sub.investorId(), docHashHex);
            jdbc.update("INSERT INTO legal_signature_request (signature_request_id, signer_id, signer_type, "
                            + "doc_hash, parent_aggregate_ref, status) "
                            + "VALUES (?, ?, 'investor'::legal_signer_type, ?, ?, 'initiated')",
                    signatureRequestId, sub.investorId(), docHashHex, parentRef);
            signing.initiateSignature(vsrId, signatureRequestId, docHash, sub.investorId().toString(),
                    SignMethod.AADHAAR_OTP);

            CommandEvent event = new CommandEvent(CONTEXT + ".AssignmentSet.Requested", 1,
                    Map.of("assignment_set_id", assignmentSetId.toString(), "listing_id", listingId.toString(),
                            "total_count", 1),
                    Map.of(), Map.of("status", "in_progress"), true);
            return new CommandOutcome<>(assignmentSetId, event);
        });
    }

    public CommandResult<Void> completeSigning(CommandRequest request, UUID listingId) {
        return gateway.execute(request, OPS, () -> {
            AssignmentSet set = loadSet(listingId);
            if (!"in_progress".equals(set.status())) {
                throw new ValidationException("assignment set is not in_progress for listing: " + listingId);
            }
            JsonNode legsArr = legs(set.legsJson());
            // Single-leg invariant (WS-6): completing one leg reaches all_signed. A multi-leg widening must
            // revisit this method (loop the legs) — guard so it can't silently mis-gate C27.
            if (!legsArr.isArray() || legsArr.size() != 1 || set.totalCount() != 1) {
                throw new IllegalStateException("multi-leg assignment is not supported in WS-6 (single-leg cut)");
            }
            JsonNode leg = legsArr.get(0);
            UUID vsrId = UUID.fromString(leg.get("vsr_id").asText());
            UUID agreementId = UUID.fromString(leg.get("agreement_id").asText());
            UUID signatureRequestId = UUID.fromString(leg.get("signature_request_id").asText());

            // Complete via the M5c ACL (the stub completes in-process; idempotent on re-complete).
            SignatureResult result = signing.completeSignature(vsrId);
            String certSerial = result.certSerial();

            // Record the signature on BC5's side: request completed + cert (SR.3), MIA signed + cert (MA.3).
            jdbc.update("UPDATE legal_signature_request SET status = 'completed', cert_serial = ?, "
                    + "aggregate_version = aggregate_version + 1 WHERE signature_request_id = ?",
                    certSerial, signatureRequestId);
            jdbc.update("UPDATE legal_master_agreement SET status = 'signed', signature_cert_serial = ?, "
                    + "aggregate_version = aggregate_version + 1 WHERE agreement_id = ?", certSerial, agreementId);

            // Leg signed; signed_count → total_count → all_signed (AS.3/AS.5).
            int newSigned = set.signedCount() + 1;
            String signedLegs = markLegSigned(set.legsJson());
            boolean allSigned = newSigned == set.totalCount();
            jdbc.update("UPDATE legal_assignment_set SET signed_count = ?, unsigned_count = total_count - ?, "
                            + "status = ?::legal_assignment_set_status, legs = ?::jsonb, "
                            + "aggregate_version = aggregate_version + 1 WHERE assignment_set_id = ?",
                    newSigned, newSigned, allSigned ? "all_signed" : "in_progress", signedLegs, set.id());

            if (allSigned) {
                // C27 gate: flip the listing's all_signed flag (listing stays fully_funded). L.5. Assert the
                // flip touched the row — a 0-row flip (listing not fully_funded) must NOT leave the set
                // all_signed while the gate flag stays false; throw so the whole command rolls back cleanly.
                int flipped = jdbc.update("UPDATE deal_listing SET all_signed = TRUE, "
                        + "aggregate_version = aggregate_version + 1 "
                        + "WHERE listing_id = ? AND status = 'fully_funded'", listingId);
                if (flipped != 1) {
                    throw new ValidationException("listing is not fully_funded; cannot open the disbursement gate: "
                            + listingId);
                }
            }

            CommandEvent event = new CommandEvent(
                    allSigned ? CONTEXT + ".AssignmentSet.AllSigned" : CONTEXT + ".AssignmentSet.LegSigned", 1,
                    Map.of("assignment_set_id", set.id().toString(), "signed_count", newSigned,
                            "all_signed", allSigned),
                    Map.of("status", "in_progress"),
                    Map.of("status", allSigned ? "all_signed" : "in_progress"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    private AssignmentSet loadSet(UUID listingId) {
        AssignmentSet set = jdbc.query(
                "SELECT assignment_set_id, status::text AS status, signed_count, total_count, legs::text AS legs "
                        + "FROM legal_assignment_set WHERE listing_id = ?",
                rs -> rs.next()
                        ? new AssignmentSet(rs.getObject("assignment_set_id", UUID.class), rs.getString("status"),
                                rs.getInt("signed_count"), rs.getInt("total_count"), rs.getString("legs"))
                        : null,
                listingId);
        if (set == null) {
            throw new NotFoundException("no assignment set for listing: " + listingId);
        }
        return set;
    }

    private String leg(Subscriber sub, UUID agreementId, UUID signatureRequestId, UUID vsrId, String status) {
        Map<String, Object> leg = Map.of(
                "investor_id", sub.investorId().toString(), "allocation_paise", sub.amount(),
                "agreement_id", agreementId.toString(), "signature_request_id", signatureRequestId.toString(),
                "vsr_id", vsrId.toString(), "status", status);
        return toJson(java.util.List.of(leg));
    }

    private String markLegSigned(String legsJson) {
        try {
            com.fasterxml.jackson.databind.node.ArrayNode arr =
                    (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(legsJson);
            ((com.fasterxml.jackson.databind.node.ObjectNode) arr.get(0)).put("status", "signed");
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            throw new IllegalStateException("failed to update assignment legs", e);
        }
    }

    private JsonNode legs(String legsJson) {
        try {
            return mapper.readTree(legsJson);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse assignment legs", e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise assignment legs", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Subscriber(UUID investorId, long amount) {
    }

    private record AssignmentSet(UUID id, String status, int signedCount, int totalCount, String legsJson) {
    }
}
