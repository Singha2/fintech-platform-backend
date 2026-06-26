package com.arthvritt.platform.assignment;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.audit.Actor;
import com.arthvritt.platform.audit.AuditEnvelopes;
import com.arthvritt.platform.audit.AuditLog;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC5 Assignment &amp; Signing (M12, multi-investor). On a {@code fully_funded} listing, {@code request}
 * opens the {@code legal_assignment_set} (one per listing, AS.1) with {@code total_count = N} = the confirmed
 * subscriptions, building one leg + MIA + signature request per investor and initiating each signature via
 * the M5c {@link SignatureAclService} (inline — the signing webhook is deferred). {@code completeSigning}
 * completes ONE investor's leg; when {@code signed_count == total_count} the set flips to {@code all_signed}
 * and {@code deal_listing.all_signed = TRUE} — the C27 disbursement gate WS-7 reads. All commands route
 * through {@link CommandGateway}.
 */
@Service
public class AssignmentService {

    private static final String CONTEXT = "assignment";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final SignatureAclService signing;
    private final ObjectMapper mapper;
    private final AuditLog auditLog;

    public AssignmentService(JdbcTemplate jdbc, CommandGateway gateway, SignatureAclService signing,
                             ObjectMapper mapper, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.signing = signing;
        this.mapper = mapper;
        this.auditLog = auditLog;
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
            // total_count = every confirmed subscription on the listing (AS.1), one leg each.
            List<Subscriber> subs = jdbc.query(
                    "SELECT investor_id, amount FROM sub_subscription "
                            + "WHERE listing_id = ? AND status = 'confirmed' ORDER BY subscription_id",
                    (rs, i) -> new Subscriber(rs.getObject("investor_id", UUID.class), rs.getLong("amount")),
                    listingId);
            if (subs.isEmpty()) {
                throw new ValidationException("no confirmed subscription to assign on listing: " + listingId);
            }

            UUID assignmentSetId = request.aggregateId();
            int total = subs.size();
            List<Map<String, Object>> legObjs = new ArrayList<>();
            List<LegInit> inits = new ArrayList<>();
            for (Subscriber sub : subs) {
                UUID agreementId = Ids.newId();
                UUID signatureRequestId = Ids.newId();
                UUID vsrId = Ids.newId();
                byte[] docHash = sha256("MIA:" + assignmentSetId + ":" + sub.investorId());
                String docHashHex = HexFormat.of().formatHex(docHash);
                Map<String, Object> leg = new LinkedHashMap<>();
                leg.put("investor_id", sub.investorId().toString());
                leg.put("allocation_paise", sub.amount());
                leg.put("agreement_id", agreementId.toString());
                leg.put("signature_request_id", signatureRequestId.toString());
                leg.put("vsr_id", vsrId.toString());
                leg.put("status", "initiated");
                legObjs.add(leg);
                inits.add(new LegInit(sub.investorId(), agreementId, signatureRequestId, vsrId, docHash, docHashHex));
            }

            try {
                // One set per listing (AS.1, UNIQUE). counts: total=unsigned=N, signed=0 (AS.5). 24h time-box (AS.2).
                jdbc.update("INSERT INTO legal_assignment_set (assignment_set_id, listing_id, sign_deadline, "
                                + "status, signed_count, unsigned_count, total_count, legs) "
                                + "VALUES (?, ?, now() + interval '24 hours', 'in_progress', 0, ?, ?, ?::jsonb)",
                        assignmentSetId, listingId, total, total, toJson(legObjs));
            } catch (DuplicateKeyException e) {
                throw new ValidationException("an assignment set already exists for listing: " + listingId);
            }
            for (LegInit li : inits) {
                jdbc.update("INSERT INTO legal_master_agreement (agreement_id, party_id, party_type, kind, "
                                + "doc_hash, status) VALUES (?, ?, 'investor'::legal_master_agreement_party_type, "
                                + "'MIA'::legal_master_agreement_kind, ?, 'initiated')",
                        li.agreementId(), li.investorId(), li.docHashHex());
                jdbc.update("INSERT INTO legal_signature_request (signature_request_id, signer_id, signer_type, "
                                + "doc_hash, parent_aggregate_ref, status) "
                                + "VALUES (?, ?, 'investor'::legal_signer_type, ?, ?, 'initiated')",
                        li.signatureRequestId(), li.investorId(), li.docHashHex(),
                        assignmentSetId + ":" + li.investorId());
                signing.initiateSignature(li.vsrId(), li.signatureRequestId(), li.docHash(),
                        li.investorId().toString(), SignMethod.AADHAAR_OTP);
            }

            CommandEvent event = new CommandEvent(CONTEXT + ".AssignmentSet.Requested", 1,
                    Map.of("assignment_set_id", assignmentSetId.toString(), "listing_id", listingId.toString(),
                            "total_count", total),
                    Map.of(), Map.of("status", "in_progress"), true);
            return new CommandOutcome<>(assignmentSetId, event);
        });
    }

    public CommandResult<Void> completeSigning(CommandRequest request, UUID listingId, UUID investorId) {
        return gateway.execute(request, OPS, () -> {
            AssignmentSet set = loadSet(listingId);
            if (!"in_progress".equals(set.status())) {
                throw new ValidationException("assignment set is not in_progress for listing: " + listingId);
            }
            if (set.pastDeadline()) { // AS.4: no signing past the 24h time-box
                throw new ValidationException("the assignment signing window has closed for listing: " + listingId);
            }
            JsonNode leg = findLeg(legs(set.legsJson()), investorId);
            if (leg == null) {
                throw new ValidationException("no assignment leg for investor " + investorId + " on listing: " + listingId);
            }
            if ("signed".equals(leg.get("status").asText())) {
                throw new ValidationException("assignment leg already signed for investor: " + investorId);
            }
            UUID vsrId = UUID.fromString(leg.get("vsr_id").asText());
            UUID agreementId = UUID.fromString(leg.get("agreement_id").asText());
            UUID signatureRequestId = UUID.fromString(leg.get("signature_request_id").asText());

            // Complete via the M5c ACL (the stub completes in-process; idempotent on re-complete).
            SignatureResult result = signing.completeSignature(vsrId);
            String certSerial = result.certSerial();

            // Record on BC5's side: request completed + cert (SR.3), MIA signed + cert (MA.3).
            jdbc.update("UPDATE legal_signature_request SET status = 'completed', cert_serial = ?, "
                    + "aggregate_version = aggregate_version + 1 WHERE signature_request_id = ?",
                    certSerial, signatureRequestId);
            jdbc.update("UPDATE legal_master_agreement SET status = 'signed', signature_cert_serial = ?, "
                    + "aggregate_version = aggregate_version + 1 WHERE agreement_id = ?", certSerial, agreementId);

            // This leg signed; recompute counts. all_signed only when every leg is signed (AS.3/AS.5).
            int newSigned = set.signedCount() + 1;
            boolean allSigned = newSigned == set.totalCount();
            jdbc.update("UPDATE legal_assignment_set SET signed_count = ?, unsigned_count = total_count - ?, "
                            + "status = ?::legal_assignment_set_status, legs = ?::jsonb, "
                            + "aggregate_version = aggregate_version + 1 WHERE assignment_set_id = ?",
                    newSigned, newSigned, allSigned ? "all_signed" : "in_progress",
                    markLegStatus(set.legsJson(), investorId, "signed"), set.id());

            if (allSigned) {
                // C27 gate: flip the listing's all_signed flag (listing stays fully_funded, L.5). Assert the
                // flip touched the row — a 0-row flip must NOT leave the set all_signed with the gate false;
                // throw so the whole command rolls back cleanly (the WS-6 lesson).
                int flipped = jdbc.update("UPDATE deal_listing SET all_signed = TRUE, "
                        + "aggregate_version = aggregate_version + 1 "
                        + "WHERE listing_id = ? AND status = 'fully_funded'", listingId);
                if (flipped != 1) {
                    throw new ValidationException("listing is not fully_funded; cannot open the disbursement gate: "
                            + listingId);
                }
            }

            CommandEvent event = new CommandEvent(
                    allSigned ? CONTEXT + ".AssignmentSet.AllSigned" : CONTEXT + ".AssignmentSignature.Completed", 1,
                    Map.of("assignment_set_id", set.id().toString(), "investor_id", investorId.toString(),
                            "signed_count", newSigned, "all_signed", allSigned),
                    Map.of("status", "in_progress"),
                    Map.of("status", allSigned ? "all_signed" : "in_progress"), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /**
     * G13/AS.4: once the 24h time-box has passed with the set still short of {@code all_signed}, declare it
     * {@code incomplete} and hold the listing for review (HoldForReview) — the C27 gate never opens, so WS-7
     * disbursement stays blocked. Ops command (the automatic scheduler is deferred). The downstream refund of
     * the held subscriptions is a separate BC1/BC2 reaction (documented gap).
     */
    public CommandResult<Void> declareIncomplete(CommandRequest request, UUID listingId) {
        return gateway.execute(request, OPS, () -> {
            AssignmentSet set = loadSet(listingId);
            if (!"in_progress".equals(set.status())) {
                throw new ValidationException("assignment set is not in_progress for listing: " + listingId);
            }
            if (!set.pastDeadline()) {
                throw new ValidationException("the 24h signing window has not closed for listing: " + listingId);
            }
            if (set.signedCount() >= set.totalCount()) {
                throw new ValidationException("all legs are signed; the set is not incomplete: " + listingId);
            }
            jdbc.update("UPDATE legal_assignment_set SET status = 'incomplete'::legal_assignment_set_status, "
                    + "aggregate_version = aggregate_version + 1 WHERE assignment_set_id = ?", set.id());
            // HoldForReview: fully_funded → held_for_review (L.11/Listing.HeldForReview). Assert the flip.
            int held = jdbc.update("UPDATE deal_listing SET status = 'held_for_review'::deal_listing_status, "
                    + "aggregate_version = aggregate_version + 1 WHERE listing_id = ? AND status = 'fully_funded'",
                    listingId);
            if (held != 1) {
                throw new ValidationException("listing is not fully_funded; cannot hold for review: " + listingId);
            }
            auditLog.append(AuditEnvelopes.seed("listing", "Listing", listingId)
                    .eventType("listing.Listing.HeldForReview")
                    .actor(actor(request))
                    .payload(Map.of("listing_id", listingId.toString(), "reason", "assignment_incomplete"))
                    .beforeState(Map.of("status", "fully_funded")).afterState(Map.of("status", "held_for_review"))
                    .stateTransition(true).build());
            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".AssignmentSet.Incomplete", 1,
                    Map.of("assignment_set_id", set.id().toString(), "signed_count", set.signedCount(),
                            "total_count", set.totalCount()),
                    Map.of("status", "in_progress"), Map.of("status", "incomplete"), true));
        });
    }

    /**
     * Records a per-leg signing failure (AS.5/SR.2). The leg moves to {@code failed}; its MIA + signature
     * request are marked failed (retained for audit, MA.1). {@code signed_count} is unchanged, so the set
     * cannot reach {@code all_signed} until the leg is re-initiated and signed.
     */
    public CommandResult<Void> recordLegFailed(CommandRequest request, UUID listingId, UUID investorId, String reason) {
        return gateway.execute(request, OPS, () -> {
            AssignmentSet set = loadSet(listingId);
            if (!"in_progress".equals(set.status())) {
                throw new ValidationException("assignment set is not in_progress for listing: " + listingId);
            }
            JsonNode leg = findLeg(legs(set.legsJson()), investorId);
            if (leg == null) {
                throw new ValidationException("no assignment leg for investor " + investorId + " on listing: " + listingId);
            }
            if (!"initiated".equals(leg.get("status").asText())) {
                throw new ValidationException("only an initiated leg can fail (is " + leg.get("status").asText() + ")");
            }
            jdbc.update("UPDATE legal_signature_request SET status = 'failed', "
                    + "aggregate_version = aggregate_version + 1 WHERE signature_request_id = ?",
                    UUID.fromString(leg.get("signature_request_id").asText()));
            jdbc.update("UPDATE legal_master_agreement SET status = 'failed', failed_reason = ?, "
                    + "aggregate_version = aggregate_version + 1 WHERE agreement_id = ?",
                    reason, UUID.fromString(leg.get("agreement_id").asText()));
            jdbc.update("UPDATE legal_assignment_set SET legs = ?::jsonb, "
                    + "aggregate_version = aggregate_version + 1 WHERE assignment_set_id = ?",
                    markLegStatus(set.legsJson(), investorId, "failed"), set.id());
            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".AssignmentSignature.Failed", 1,
                    Map.of("assignment_set_id", set.id().toString(), "investor_id", investorId.toString()),
                    Map.of(), Map.of("leg_status", "failed"), false));
        });
    }

    /**
     * Retries a failed leg (SR.2, retry ≤ 3): a fresh MIA + signature request (the failed pair is retained
     * for audit, MA.1) carrying {@code retry_count + 1}, re-initiated via the M5c ACL; the leg points to the
     * new ids and returns to {@code initiated}. Rejected once retries are exhausted or past the deadline.
     */
    public CommandResult<Void> reinitiateLeg(CommandRequest request, UUID listingId, UUID investorId) {
        return gateway.execute(request, OPS, () -> {
            AssignmentSet set = loadSet(listingId);
            if (!"in_progress".equals(set.status())) {
                throw new ValidationException("assignment set is not in_progress for listing: " + listingId);
            }
            if (set.pastDeadline()) {
                throw new ValidationException("the assignment signing window has closed for listing: " + listingId);
            }
            JsonNode leg = findLeg(legs(set.legsJson()), investorId);
            if (leg == null || !"failed".equals(leg.path("status").asText())) {
                throw new ValidationException("no failed assignment leg to retry for investor: " + investorId);
            }
            int priorRetries = jdbc.queryForObject("SELECT retry_count FROM legal_signature_request "
                    + "WHERE signature_request_id = ?", Integer.class,
                    UUID.fromString(leg.get("signature_request_id").asText()));
            if (priorRetries >= 3) { // SR.2
                throw new ValidationException("signing retries exhausted for investor: " + investorId);
            }
            UUID agreementId = Ids.newId();
            UUID signatureRequestId = Ids.newId();
            UUID vsrId = Ids.newId();
            byte[] docHash = sha256("MIA:" + set.id() + ":" + investorId + ":retry" + (priorRetries + 1));
            String docHashHex = HexFormat.of().formatHex(docHash);
            jdbc.update("INSERT INTO legal_master_agreement (agreement_id, party_id, party_type, kind, doc_hash, status) "
                    + "VALUES (?, ?, 'investor'::legal_master_agreement_party_type, "
                    + "'MIA'::legal_master_agreement_kind, ?, 'initiated')", agreementId, investorId, docHashHex);
            jdbc.update("INSERT INTO legal_signature_request (signature_request_id, signer_id, signer_type, doc_hash, "
                    + "parent_aggregate_ref, status, retry_count) "
                    + "VALUES (?, ?, 'investor'::legal_signer_type, ?, ?, 'initiated', ?)",
                    signatureRequestId, investorId, docHashHex, set.id() + ":" + investorId, priorRetries + 1);
            signing.initiateSignature(vsrId, signatureRequestId, docHash, investorId.toString(), SignMethod.AADHAAR_OTP);
            jdbc.update("UPDATE legal_assignment_set SET legs = ?::jsonb, "
                    + "aggregate_version = aggregate_version + 1 WHERE assignment_set_id = ?",
                    reinitiateLegJson(set.legsJson(), investorId, agreementId, signatureRequestId, vsrId), set.id());
            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".AssignmentSignature.Initiated", 1,
                    Map.of("assignment_set_id", set.id().toString(), "investor_id", investorId.toString(),
                            "retry_count", priorRetries + 1),
                    Map.of("leg_status", "failed"), Map.of("leg_status", "initiated"), false));
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static Actor actor(CommandRequest request) {
        return new Actor("admin_user", request.actorId().toString(),
                request.session().sessionId().toString(), request.session().mfaAssertionId(), null);
    }

    /**
     * Loads the set and <b>locks the row</b> (FOR UPDATE) for the rest of the command transaction. Every
     * set-mutating command (complete / fail / reinitiate / declare-incomplete) blind-writes {@code
     * signed_count} and the whole {@code legs} JSONB from this snapshot, so they MUST serialize on the row —
     * otherwise two concurrent leg completions lost-update each other (a signature dropped, AS.5 violated, the
     * C27 gate wedged shut on a fully-signed deal). The lock makes the read-modify-write safe.
     */
    private AssignmentSet loadSet(UUID listingId) {
        AssignmentSet set = jdbc.query(
                "SELECT assignment_set_id, status::text AS status, signed_count, total_count, legs::text AS legs, "
                        + "(sign_deadline <= now()) AS past_deadline "
                        + "FROM legal_assignment_set WHERE listing_id = ? FOR UPDATE",
                rs -> rs.next()
                        ? new AssignmentSet(rs.getObject("assignment_set_id", UUID.class), rs.getString("status"),
                                rs.getInt("signed_count"), rs.getInt("total_count"), rs.getString("legs"),
                                rs.getBoolean("past_deadline"))
                        : null,
                listingId);
        if (set == null) {
            throw new NotFoundException("no assignment set for listing: " + listingId);
        }
        return set;
    }

    /** The leg whose {@code investor_id} matches, or null. */
    private JsonNode findLeg(JsonNode legsArr, UUID investorId) {
        if (legsArr.isArray()) {
            for (JsonNode leg : legsArr) {
                if (investorId.toString().equals(leg.path("investor_id").asText())) {
                    return leg;
                }
            }
        }
        return null;
    }

    /** Returns the legs JSON with the matching investor's leg status set to {@code status}. */
    private String markLegStatus(String legsJson, UUID investorId, String status) {
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(legsJson);
            for (JsonNode leg : arr) {
                if (investorId.toString().equals(leg.path("investor_id").asText())) {
                    ((ObjectNode) leg).put("status", status);
                }
            }
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            throw new IllegalStateException("failed to update assignment legs", e);
        }
    }

    /** Returns the legs JSON with the matching investor's leg pointed at a fresh signing attempt (retry). */
    private String reinitiateLegJson(String legsJson, UUID investorId, UUID agreementId, UUID signatureRequestId,
                                     UUID vsrId) {
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(legsJson);
            for (JsonNode leg : arr) {
                if (investorId.toString().equals(leg.path("investor_id").asText())) {
                    ObjectNode o = (ObjectNode) leg;
                    o.put("agreement_id", agreementId.toString());
                    o.put("signature_request_id", signatureRequestId.toString());
                    o.put("vsr_id", vsrId.toString());
                    o.put("status", "initiated");
                }
            }
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

    private record LegInit(UUID investorId, UUID agreementId, UUID signatureRequestId, UUID vsrId,
                           byte[] docHash, String docHashHex) {
    }

    private record AssignmentSet(UUID id, String status, int signedCount, int totalCount, String legsJson,
                                 boolean pastDeadline) {
    }
}
