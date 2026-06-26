package com.arthvritt.platform.listing;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.buyer.port.BuyerQueryPort;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.credit.port.PricingBand;
import com.arthvritt.platform.credit.port.PricingQueryPort;
import com.arthvritt.platform.notification.NotificationPort;
import com.arthvritt.platform.notification.NotificationRequest;
import com.arthvritt.platform.shared.BusinessDate;
import com.arthvritt.platform.shared.CalendarConfig;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.arthvritt.platform.supplier.port.SupplierQueryPort;
import com.arthvritt.platform.verification.VerificationPort;
import com.arthvritt.platform.verification.VerificationResult;
import com.arthvritt.platform.verification.VerificationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC1 Listing & Invoice (WS-4 / M9-C) — the full money-flow slice including the DL-027
 * operational-check sub-machine. Commands through the {@link CommandGateway}:
 * <ul>
 *   <li>create — invoice + draft listing (idempotent on command_id + body fields)</li>
 *   <li>startOpsChecks — draft → operational_checks_in_progress + invoice submitted → ops_checks_in_progress</li>
 *   <li>recordOpsCheck — atomic JSONB merge of one check outcome (non-transition)</li>
 *   <li>completeOpsChecks — INV.5/INV.7 gate: all 7 checks present → pass/fail terminal transition</li>
 *   <li>snapshotAndReady — awaiting_acknowledgment → ready_for_review, freezes pricing snapshot (maker)</li>
 *   <li>approveGoLive — ready_for_review → live, creates virtual account inline (checker, M4d)</li>
 * </ul>
 *
 * <p>Snapshot inputs from other contexts route through BC3/8/9 query ports ({@code BuyerQueryPort},
 * {@code SupplierQueryPort}, {@code PricingQueryPort}) per ARCH.1 (DL-BE-039). IRN verification
 * runs through the BC17 {@link VerificationPort} ACL (INV.7 — no self-attestation).
 * {@code funding_window_close_at} is computed as end-of-day IST on the 5th business day after
 * go-live (L.8, DL-BE-040).
 */
@Service
public class ListingService {

    private static final String CONTEXT = "listing";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> TREASURY = Set.of(AdminRole.TREASURY_AND_SETTLEMENT.wire());
    private static final int FUNDING_WINDOW_DAYS = 5; // L.8: 5 business days after go-live (BusinessDate, DL-BE-040).

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final ObjectMapper mapper;
    private final BuyerQueryPort buyer;
    private final SupplierQueryPort supplier;
    private final PricingQueryPort pricing;
    private final Clock clock;
    private final BusinessDate businessDate;
    private final VerificationPort verification;
    private final NotificationPort notifications;

    public ListingService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles, ObjectMapper mapper,
                          BuyerQueryPort buyer, SupplierQueryPort supplier, PricingQueryPort pricing,
                          Clock clock, BusinessDate businessDate, VerificationPort verification,
                          NotificationPort notifications) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.mapper = mapper;
        this.buyer = buyer;
        this.supplier = supplier;
        this.pricing = pricing;
        this.clock = clock;
        this.businessDate = businessDate;
        this.verification = verification;
        this.notifications = notifications;
    }

    /**
     * Creates an invoice + draft listing. The {@code irn} parameter is optional (null for manual-fallback
     * invoices). When present it is bound into {@code deal_invoice.irn} and included in the
     * {@code deriveAggregateId} join string so identity stays stable.
     */
    public CommandResult<UUID> create(CommandRequest request, UUID supplierId, UUID buyerId, String invoiceNumber,
                                      long faceValuePaise, LocalDate invoiceDate, int tenorDays, String irn) {
        if (tenorDays < 1 || tenorDays > 180) {
            throw new ValidationException("tenor_days must be between 1 and 180");
        }
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            UUID invoiceId = Ids.newId();
            LocalDate dueDate = invoiceDate.plusDays(tenorDays);
            try {
                jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, irn, invoice_number, "
                                + "face_value, invoice_date, tenor_days, due_date, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'submitted')",
                        invoiceId, supplierId, buyerId, irn, invoiceNumber, faceValuePaise,
                        invoiceDate, tenorDays, dueDate);
                jdbc.update("INSERT INTO deal_listing (listing_id, invoice_id, supplier_id, buyer_id, status) "
                                + "VALUES (?, ?, ?, ?, 'draft')",
                        listingId, invoiceId, supplierId, buyerId);
            } catch (DuplicateKeyException e) {
                throw new ValidationException("an invoice with these details already exists");
            }
            CommandEvent event = new CommandEvent(CONTEXT + ".Listing.Created", 1,
                    Map.of("listing_id", listingId.toString(), "invoice_id", invoiceId.toString()),
                    Map.of(), Map.of("status", "draft"), true);
            return new CommandOutcome<>(listingId, event);
        });
    }

    // --- DL-027 operational-check sub-machine (M9-C) -----------------------------------------------

    /**
     * Transitions listing {@code draft → operational_checks_in_progress} and invoice
     * {@code submitted → ops_checks_in_progress}. Both transitions are status-guarded + version-bumped.
     */
    public CommandResult<Void> startOpsChecks(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            Listing row = transitionListing(request, "draft", "operational_checks_in_progress");
            int inv = jdbc.update("UPDATE deal_invoice SET status = 'ops_checks_in_progress'::deal_invoice_status, "
                            + "aggregate_version = aggregate_version + 1, updated_at = now() "
                            + "WHERE invoice_id = ? AND status = 'submitted'::deal_invoice_status",
                    row.invoiceId());
            if (inv != 1) {
                throw new ValidationException("invoice is not 'submitted' for listing: " + request.aggregateId());
            }
            return new CommandOutcome<>(null, transitionEvent(request, CONTEXT + ".Listing.OpsChecksStarted",
                    "draft", "operational_checks_in_progress"));
        });
    }

    /**
     * Records one operational check outcome onto the invoice's {@code check_outcomes} JSONB via an
     * atomic merge (no read-modify-write, avoids lost updates). Non-transition — the listing version
     * is not bumped.
     *
     * <p>For VENDOR checks (irn_validity, INV.7): the caller's {@code outcomeOrNull} is ignored; the
     * platform calls {@link VerificationPort#verifyIrn} and uses the ACL result. If the invoice has
     * no IRN, the outcome is {@code not_applicable}.
     *
     * <p>For OPS checks: {@code outcomeOrNull} must be {@code "passed"} or {@code "failed"}.
     */
    public CommandResult<Void> recordOpsCheck(CommandRequest request, String checkName, String outcomeOrNull) {
        return gateway.execute(request, OPS, () -> {
            OperationalCheck check = OperationalCheck.fromWire(checkName); // unknown → 400

            InvoiceRow invoice = loadInvoice(request.aggregateId());
            // Reject if the invoice is not in the ops-checks-in-progress micro-state. This guards
            // against record-before-start (invoice still 'submitted') and post-completion replays.
            if (!"ops_checks_in_progress".equals(invoice.status())) {
                throw new ValidationException("invoice is not in 'ops_checks_in_progress' for listing: "
                        + request.aggregateId() + " (is " + invoice.status() + ")");
            }

            // Load current listing version (needed for the non-transition event).
            Listing listing = load(request.aggregateId());

            String outcome;
            UUID verificationId = null;

            if (check.kind() == OperationalCheck.Kind.VENDOR) {
                // INV.7: vendor-verified — ignore the caller-supplied value entirely.
                if (invoice.irn() != null) {
                    VerificationResult result = verification.verifyIrn(invoice.invoiceId(), invoice.irn());
                    verificationId = result.verificationId();
                    // Fail-closed if the ACL gives no fields (the record permits null) — a valid IRN can
                    // never be confirmed without a VALID irn_status, so default to "failed", never NPE.
                    Object irnStatus = result.extractedFields() == null
                            ? null : result.extractedFields().get("irn_status");
                    outcome = (result.status() == VerificationStatus.COMPLETED
                            && "VALID".equals(irnStatus)) ? "passed" : "failed";
                } else {
                    outcome = "not_applicable";
                }
            } else {
                // OPS: caller must supply "passed" or "failed".
                if (!"passed".equals(outcomeOrNull) && !"failed".equals(outcomeOrNull)) {
                    throw new ValidationException("outcome must be 'passed' or 'failed'");
                }
                outcome = outcomeOrNull;
            }

            // Atomic JSONB merge — avoids any lost-update from concurrent check recordings.
            String now = Instant.now(clock).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("outcome", outcome);
            entry.put("verification_id", verificationId == null ? null : verificationId.toString());
            entry.put("checked_at", now);
            String checkJson = toJson(Map.of(check.wire(), entry));

            int updated = jdbc.update("UPDATE deal_invoice SET "
                            + "check_outcomes = check_outcomes || ?::jsonb, "
                            + "aggregate_version = aggregate_version + 1, updated_at = now() "
                            + "WHERE invoice_id = ? AND status = 'ops_checks_in_progress'::deal_invoice_status",
                    checkJson, invoice.invoiceId());
            if (updated != 1) {
                throw new ValidationException("failed to record check — invoice not in ops_checks_in_progress");
            }

            // Non-transition event: the listing version is NOT bumped.
            CommandEvent event = new CommandEvent(CONTEXT + ".Invoice.CheckRecorded",
                    listing.version(),
                    Map.of("listing_id", request.aggregateId().toString(),
                            "check_name", check.wire(),
                            "outcome", outcome),
                    null, null, false);
            return new CommandOutcome<>(null, event);
        });
    }

    /**
     * INV.5/INV.7 gate: all 7 DL-027 checks must be present in {@code check_outcomes}. If any are
     * missing, throws {@link CommandRejectedException#operationalChecksIncomplete} (422, no state
     * change). Otherwise resolves pass/fail and performs the terminal transition:
     * <ul>
     *   <li>pass: invoice {@code ops_checks_in_progress → ops_checks_passed → listed}
     *       (two guarded UPDATEs per INV.4 — no skip); listing {@code operational_checks_in_progress
     *       → awaiting_acknowledgment}.</li>
     *   <li>fail: invoice {@code ops_checks_in_progress → ops_checks_failed};
     *       listing {@code operational_checks_in_progress → rejected_operational}.</li>
     * </ul>
     */
    public CommandResult<Void> completeOpsChecks(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            Listing listing = load(request.aggregateId());
            requireStatus(listing, "operational_checks_in_progress", request.aggregateId());

            InvoiceRow invoice = loadInvoice(request.aggregateId());
            if (!"ops_checks_in_progress".equals(invoice.status())) {
                throw new ValidationException("invoice is not in 'ops_checks_in_progress' for listing: "
                        + request.aggregateId());
            }

            // Parse check_outcomes JSONB to a Map.
            Map<String, Object> outcomes = parseCheckOutcomes(invoice.checkOutcomesJson());

            // INV.5: every DL-027 check must be recorded.
            List<String> missing = new ArrayList<>();
            for (String wire : OperationalCheck.allWireNames()) {
                if (!outcomes.containsKey(wire)) {
                    missing.add(wire);
                }
            }
            if (!missing.isEmpty()) {
                throw CommandRejectedException.operationalChecksIncomplete(String.join(", ", missing));
            }

            // Determine pass/fail FAIL-CLOSED: every one of the 7 checks must be a well-formed entry whose
            // outcome is "passed" or "not_applicable". A malformed/partial entry counts as NOT passed (it is
            // not silently dropped) — so a check that exists but was never validly determined blocks go-live.
            boolean pass = OperationalCheck.allWireNames().stream().allMatch(wire -> {
                Object entry = outcomes.get(wire);
                if (!(entry instanceof Map<?, ?> m)) {
                    return false;
                }
                Object o = m.get("outcome");
                return "passed".equals(o) || "not_applicable".equals(o);
            });

            if (pass) {
                // INV.4: two-step transition — no skip allowed.
                int step1 = jdbc.update("UPDATE deal_invoice SET status = 'ops_checks_passed'::deal_invoice_status, "
                                + "aggregate_version = aggregate_version + 1, updated_at = now() "
                                + "WHERE invoice_id = ? AND status = 'ops_checks_in_progress'::deal_invoice_status",
                        invoice.invoiceId());
                if (step1 != 1) {
                    throw new ValidationException("invoice transition to ops_checks_passed failed");
                }
                int step2 = jdbc.update("UPDATE deal_invoice SET status = 'listed'::deal_invoice_status, "
                                + "aggregate_version = aggregate_version + 1, updated_at = now() "
                                + "WHERE invoice_id = ? AND status = 'ops_checks_passed'::deal_invoice_status",
                        invoice.invoiceId());
                if (step2 != 1) {
                    throw new ValidationException("invoice transition to listed failed");
                }
                transitionListing(request, "operational_checks_in_progress", "awaiting_acknowledgment");
                return new CommandOutcome<>(null, transitionEvent(request,
                        CONTEXT + ".Invoice.OperationalChecksPassed",
                        "operational_checks_in_progress", "awaiting_acknowledgment"));
            } else {
                int inv = jdbc.update("UPDATE deal_invoice SET status = 'ops_checks_failed'::deal_invoice_status, "
                                + "aggregate_version = aggregate_version + 1, updated_at = now() "
                                + "WHERE invoice_id = ? AND status = 'ops_checks_in_progress'::deal_invoice_status",
                        invoice.invoiceId());
                if (inv != 1) {
                    throw new ValidationException("invoice transition to ops_checks_failed failed");
                }
                transitionListing(request, "operational_checks_in_progress", "rejected_operational");
                return new CommandOutcome<>(null, transitionEvent(request,
                        CONTEXT + ".Invoice.OperationalChecksFailed",
                        "operational_checks_in_progress", "rejected_operational"));
            }
        });
    }

    // --- M9-D buyer acknowledgment (admin-captured, DL-019) ----------------------------------------

    /**
     * Solicits the buyer's acknowledgment: notifies the buyer's active ack user (BC15) and stamps the
     * request on {@code check_outcomes.buyer_ack}. Non-transition — the listing stays
     * {@code awaiting_acknowledgment}. Requires the buyer to have an active acknowledgment user (BA.3).
     */
    public CommandResult<Void> requestBuyerAck(CommandRequest request, int slaHours) {
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            Listing listing = load(listingId);
            requireStatus(listing, "awaiting_acknowledgment", listingId);
            InvoiceRow invoice = loadInvoice(listingId);
            // Never downgrade an already-recorded acknowledgment: a (re)request after the buyer has
            // acknowledged/failed would otherwise overwrite buyer_ack.status back to 'requested' (the JSONB
            // merge replaces the whole key) and silently block the snapshot. A resend while still merely
            // 'requested' is harmless and allowed.
            String ackStatus = buyerAckStatus(invoice);
            if ("acknowledged".equals(ackStatus) || "failed".equals(ackStatus)) {
                throw new ValidationException("buyer acknowledgment already recorded for listing: " + listingId);
            }

            UUID ackIdentityId = activeAckUserIdentity(listing.buyerId());
            notifications.send(new NotificationRequest(ackIdentityId, "email", "buyer_ack_request",
                    Map.of("listing_id", listingId.toString(), "sla_hours", slaHours)));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", "requested");
            entry.put("sla_hours", slaHours);
            entry.put("requested_at", nowIso());
            mergeBuyerAck(invoice.invoiceId(), entry);

            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".Listing.AcknowledgmentRequested",
                    listing.version(),
                    Map.of("listing_id", listingId.toString(), "sla_hours", slaHours), null, null, false));
        });
    }

    /**
     * Records the buyer's acknowledgment, captured by Ops on the buyer's behalf (DL-019). {@code
     * acknowledged} stamps {@code check_outcomes.buyer_ack} and keeps the listing in
     * {@code awaiting_acknowledgment} (non-transition); {@code failed} transitions the listing to the
     * terminal {@code acknowledgment_failed}.
     */
    public CommandResult<Void> recordBuyerAck(CommandRequest request, String outcome, String method,
                                              String evidenceRef) {
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            Listing listing = load(listingId);
            requireStatus(listing, "awaiting_acknowledgment", listingId);
            InvoiceRow invoice = loadInvoice(listingId);

            if (!"acknowledged".equals(outcome) && !"failed".equals(outcome)) {
                throw new ValidationException("outcome must be 'acknowledged' or 'failed'");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", outcome);
            entry.put("method", method);
            entry.put("evidence_ref", evidenceRef);
            entry.put("captured_by", roles.adminUserId(request.actorId()).toString());
            entry.put("recorded_at", nowIso());
            mergeBuyerAck(invoice.invoiceId(), entry);

            if ("acknowledged".equals(outcome)) {
                return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".Listing.AcknowledgmentReceived",
                        listing.version(), Map.of("listing_id", listingId.toString()), null, null, false));
            }
            // failed → terminal-M9 branch, version-guarded transition.
            transitionListing(request, "awaiting_acknowledgment", "acknowledgment_failed");
            return new CommandOutcome<>(null, transitionEvent(request, CONTEXT + ".Listing.AcknowledgmentFailed",
                    "awaiting_acknowledgment", "acknowledgment_failed"));
        });
    }

    // --- remaining WS-4 commands -------------------------------------------------------------------

    public CommandResult<Void> snapshotAndReady(CommandRequest request, int rateBps) {
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            Listing row = load(listingId);
            requireStatus(row, "awaiting_acknowledgment", listingId);
            // M9-D: a recorded buyer acknowledgment (DL-019) is a precondition for the pricing snapshot.
            requireBuyerAcknowledged(listingId);

            PricingBand band = pricing.activeBand(row.buyerId(), tenorBucket(row.tenorDays()))
                    .orElseThrow(() -> new ValidationException("no active pricing band for this buyer/tenor"));
            if (!band.covers(rateBps)) { // L.10
                throw new ValidationException("rate_bps " + rateBps + " is outside the band ["
                        + band.minBps() + "," + band.maxBps() + "]");
            }
            long fundingTarget = FundingMath.fundingTargetPaise(row.faceValuePaise(), rateBps, row.tenorDays(),
                    band.feeBps());
            // Cross-context snapshot inputs via BC3/8/9 query ports (DL-BE-039): buyer limit headroom + supplier cap.
            long buyerHeadroom = buyer.creditLimitPaise(row.buyerId());
            long supplierCap = supplier.exposureCapPaise(row.supplierId());
            UUID makerAdminId = roles.adminUserId(request.actorId());

            int updated = jdbc.update("UPDATE deal_listing SET status = 'ready_for_review'::deal_listing_status, "
                            + "pricing_snapshot = ?::jsonb, buyer_limit_headroom_snapshot = ?, "
                            + "supplier_exposure_cap_snapshot = ?, funding_target = ?, golive_maker_id = ?, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'awaiting_acknowledgment'::deal_listing_status AND aggregate_version = ?",
                    pricingSnapshot(band.bandId(), rateBps, band.feeBps()), buyerHeadroom, supplierCap,
                    fundingTarget, makerAdminId, listingId, request.expectedVersion());
            requireUpdated(updated, listingId, "awaiting_acknowledgment", request.expectedVersion());

            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".Listing.SnapshotTaken",
                    request.expectedVersion() + 1,
                    Map.of("listing_id", listingId.toString(), "funding_target", fundingTarget),
                    Map.of("status", "awaiting_acknowledgment"), Map.of("status", "ready_for_review"), true));
        });
    }

    public CommandResult<Void> approveGoLive(CommandRequest request) {
        return gateway.execute(request, TREASURY, () -> {
            UUID listingId = request.aggregateId();
            Listing row = load(listingId);
            requireStatus(row, "ready_for_review", listingId);

            UUID checkerAdminId = roles.adminUserId(request.actorId());
            // Maker ≠ checker (C4) enforced in-app for a clean 409, with the DB CHECK as the backstop.
            if (checkerAdminId.equals(row.goliveMakerId())) {
                throw CommandRejectedException.checkerEqualsMaker();
            }
            // L.11: re-check both counterparties are still active at go-live (a suspension after snapshot
            // must not go live). If either is inactive, HOLD the listing for review instead — no VA, no
            // funding window, no checker stamp (the attempt is recorded in the audit envelope). The
            // asynchronous mid-flight-suspension subscriber is deferred to the event bus; this is the
            // synchronous guard at the gate.
            if (!buyer.isActive(row.buyerId()) || !supplier.isActive(row.supplierId())) {
                int held = jdbc.update("UPDATE deal_listing SET status = 'held_for_review'::deal_listing_status, "
                                + "aggregate_version = aggregate_version + 1 "
                                + "WHERE listing_id = ? AND status = 'ready_for_review'::deal_listing_status "
                                + "AND aggregate_version = ?",
                        listingId, request.expectedVersion());
                requireUpdated(held, listingId, "ready_for_review", request.expectedVersion());
                return new CommandOutcome<>(null, transitionEvent(request, CONTEXT + ".Listing.HeldForReview",
                        "ready_for_review", "held_for_review"));
            }
            // Transition the listing FIRST (version-guarded) so a concurrent second go-live loses the
            // optimistic race with a clean 409 — before any VA is inserted.
            // L.8: funding window closes at end-of-day IST on the 5th business day after today (DL-BE-040).
            LocalDate goLiveDate = LocalDate.now(clock);
            LocalDate closeDate = businessDate.plusBusinessDays(goLiveDate, FUNDING_WINDOW_DAYS);
            // Truncate to microseconds: pgjdbc rounds LocalTime.MAX (nanoseconds) → midnight on the next day.
            OffsetDateTime fundingWindowCloseAt = closeDate.atTime(LocalTime.MAX.truncatedTo(ChronoUnit.MICROS))
                    .atZone(CalendarConfig.PLATFORM_ZONE).toOffsetDateTime();
            UUID vaId = Ids.newId();
            int updated = jdbc.update("UPDATE deal_listing SET status = 'live'::deal_listing_status, "
                            + "golive_checker_id = ?, golive_mfa_assertion_id = ?, va_id = ?, "
                            + "funding_window_close_at = ?, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'ready_for_review'::deal_listing_status AND aggregate_version = ?",
                    checkerAdminId, request.session().mfaAssertionId().toString(), vaId, fundingWindowCloseAt,
                    listingId, request.expectedVersion());
            requireUpdated(updated, listingId, "ready_for_review", request.expectedVersion());

            // One VA per listing (DB UNIQUE), expected inflow = the frozen funding_target.
            jdbc.update("INSERT INTO cash_virtual_account (va_id, listing_id, status, expected_inflow_total) "
                            + "VALUES (?, ?, 'created', ?)", vaId, listingId, row.fundingTarget());

            return new CommandOutcome<>(null, new CommandEvent(CONTEXT + ".Listing.GoneLive",
                    request.expectedVersion() + 1,
                    Map.of("listing_id", listingId.toString(), "va_id", vaId.toString()),
                    Map.of("status", "ready_for_review"), Map.of("status", "live"), true));
        });
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Version-guarded listing status transition; returns the pre-transition row (for follow-on work).
     * Bumps {@code aggregate_version}.
     */
    private Listing transitionListing(CommandRequest request, String from, String to) {
        UUID listingId = request.aggregateId();
        Listing row = load(listingId);
        requireStatus(row, from, listingId);
        int updated = jdbc.update("UPDATE deal_listing SET status = ?::deal_listing_status, "
                        + "aggregate_version = aggregate_version + 1 "
                        + "WHERE listing_id = ? AND status = ?::deal_listing_status AND aggregate_version = ?",
                to, listingId, from, request.expectedVersion());
        requireUpdated(updated, listingId, from, request.expectedVersion());
        return row;
    }

    private CommandEvent transitionEvent(CommandRequest request, String eventType, String from, String to) {
        return new CommandEvent(eventType, request.expectedVersion() + 1,
                Map.of("listing_id", request.aggregateId().toString()),
                Map.of("status", from), Map.of("status", to), true);
    }

    private void requireStatus(Listing row, String expected, UUID listingId) {
        if (!expected.equals(row.status())) {
            throw new ValidationException("listing is not " + expected + ": " + listingId + " (is " + row.status() + ")");
        }
    }

    private void requireUpdated(int rows, UUID listingId, String expectedStatus, int expectedVersion) {
        if (rows == 1) {
            return;
        }
        Listing row = load(listingId);
        if (row == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        if (!expectedStatus.equals(row.status())) {
            throw new ValidationException("listing is not " + expectedStatus + ": " + listingId);
        }
        throw CommandRejectedException.versionConflict(expectedVersion, row.version());
    }

    private Listing load(UUID listingId) {
        Listing row = jdbc.query(
                "SELECT l.status::text AS status, l.aggregate_version, l.invoice_id, l.supplier_id, l.buyer_id, "
                        + "l.golive_maker_id, l.funding_target, i.face_value, i.tenor_days "
                        + "FROM deal_listing l JOIN deal_invoice i ON i.invoice_id = l.invoice_id "
                        + "WHERE l.listing_id = ?",
                rs -> rs.next()
                        ? new Listing(rs.getString("status"), rs.getInt("aggregate_version"),
                                rs.getObject("invoice_id", UUID.class), rs.getObject("supplier_id", UUID.class),
                                rs.getObject("buyer_id", UUID.class), rs.getObject("golive_maker_id", UUID.class),
                                (Long) rs.getObject("funding_target"), rs.getLong("face_value"), rs.getInt("tenor_days"))
                        : null,
                listingId);
        if (row == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        return row;
    }

    private InvoiceRow loadInvoice(UUID listingId) {
        InvoiceRow row = jdbc.query(
                "SELECT i.invoice_id, i.status::text AS status, i.irn, i.check_outcomes::text AS check_outcomes_json "
                        + "FROM deal_invoice i JOIN deal_listing l ON l.invoice_id = i.invoice_id "
                        + "WHERE l.listing_id = ?",
                rs -> rs.next()
                        ? new InvoiceRow(rs.getObject("invoice_id", UUID.class), rs.getString("status"),
                                rs.getString("irn"), rs.getString("check_outcomes_json"))
                        : null,
                listingId);
        if (row == null) {
            throw new NotFoundException("invoice not found for listing: " + listingId);
        }
        return row;
    }

    /** Rejects unless {@code check_outcomes.buyer_ack.status == 'acknowledged'} (M9-D snapshot precondition). */
    private void requireBuyerAcknowledged(UUID listingId) {
        if (!"acknowledged".equals(buyerAckStatus(loadInvoice(listingId)))) {
            throw new ValidationException("buyer acknowledgment is required before the pricing snapshot: " + listingId);
        }
    }

    /** The {@code check_outcomes.buyer_ack.status} value, or null when no ack entry has been written yet. */
    private String buyerAckStatus(InvoiceRow invoice) {
        Object ack = parseCheckOutcomes(invoice.checkOutcomesJson()).get("buyer_ack");
        return ack instanceof Map<?, ?> m ? (String) m.get("status") : null;
    }

    /** Atomic JSONB merge of the {@code buyer_ack} entry onto the (listed) invoice; no read-modify-write. */
    private void mergeBuyerAck(UUID invoiceId, Map<String, Object> entry) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("buyer_ack", entry);
        int updated = jdbc.update("UPDATE deal_invoice SET check_outcomes = check_outcomes || ?::jsonb, "
                        + "aggregate_version = aggregate_version + 1, updated_at = now() "
                        + "WHERE invoice_id = ? AND status = 'listed'::deal_invoice_status",
                toJson(wrapper), invoiceId);
        if (updated != 1) {
            throw new ValidationException("failed to record buyer acknowledgment — invoice not in 'listed'");
        }
    }

    /** The identity id of the buyer's active acknowledgment user via the BC9 port; rejects (BA.3) if none. */
    private UUID activeAckUserIdentity(UUID buyerId) {
        return buyer.activeAckUserIdentity(buyerId)
                .orElseThrow(() -> new ValidationException("buyer has no active acknowledgment user: " + buyerId));
    }

    private String nowIso() {
        return Instant.now(clock).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCheckOutcomes(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse check_outcomes JSON", e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise JSON", e);
        }
    }

    private static String tenorBucket(int tenorDays) {
        if (tenorDays <= 30) {
            return "lte_30d";
        }
        if (tenorDays <= 60) {
            return "31_60d";
        }
        if (tenorDays <= 90) {
            return "61_90d";
        }
        return "91_180d";
    }

    private String pricingSnapshot(UUID bandId, int rateBps, int feeBps) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pricing_band_id", bandId.toString());
        snapshot.put("rate_bps", rateBps);
        snapshot.put("fee_bps", feeBps);
        return toJson(snapshot);
    }

    private record Listing(String status, int version, UUID invoiceId, UUID supplierId, UUID buyerId,
                           UUID goliveMakerId, Long fundingTarget, long faceValuePaise, int tenorDays) {
    }

    private record InvoiceRow(UUID invoiceId, String status, String irn, String checkOutcomesJson) {
    }
}
