package com.arthvritt.platform.listing;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.adminiam.RoleResolver;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRejectedException;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC1 Listing & Invoice (WS-4) — the first money-flow slice. Four commands through the
 * {@link CommandGateway}: create (invoice + draft listing), pass-ops-checks, snapshot-and-ready (the
 * <b>maker</b>: freezes the pricing snapshot + {@code funding_target}, stamps {@code golive_maker_id}), and
 * approve-go-live (the <b>checker</b>: enforces checker≠maker + MFA, transitions to {@code live}, and
 * creates the listing's virtual account inline). The go-live maker-checker is column-based on
 * {@code deal_listing} (DB CHECKs back the app guard — same shape as {@code comp_kyc_file}, no M4d gate).
 *
 * <p>Snapshot inputs that live in other contexts (the buyer credit limit, supplier exposure cap, active
 * pricing band) are read directly here as a <b>documented skeleton shortcut</b> — to be replaced by BC3/8/9
 * query ports + the event bus at Milestone 2 (DL-BE-034). The inline VA likewise stands in for BC4's real
 * subscription to {@code Listing.GoneLive}.
 */
@Service
public class ListingService {

    private static final String CONTEXT = "listing";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final Set<String> TREASURY = Set.of(AdminRole.TREASURY_AND_SETTLEMENT.wire());
    private static final int FUNDING_WINDOW_DAYS = 5; // L.8: 5 business days; skeleton uses calendar days.

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final RoleResolver roles;
    private final ObjectMapper mapper;

    public ListingService(JdbcTemplate jdbc, CommandGateway gateway, RoleResolver roles, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.roles = roles;
        this.mapper = mapper;
    }

    public CommandResult<UUID> create(CommandRequest request, UUID supplierId, UUID buyerId, String invoiceNumber,
                                      long faceValuePaise, LocalDate invoiceDate, int tenorDays) {
        if (tenorDays < 1 || tenorDays > 180) {
            throw new ValidationException("tenor_days must be between 1 and 180");
        }
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            UUID invoiceId = Ids.newId();
            LocalDate dueDate = invoiceDate.plusDays(tenorDays);
            try {
                jdbc.update("INSERT INTO deal_invoice (invoice_id, supplier_id, buyer_id, invoice_number, "
                                + "face_value, invoice_date, tenor_days, due_date, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'submitted')",
                        invoiceId, supplierId, buyerId, invoiceNumber, faceValuePaise, invoiceDate, tenorDays, dueDate);
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

    public CommandResult<Void> passOpsChecks(CommandRequest request) {
        return gateway.execute(request, OPS, () -> {
            Listing row = transitionListing(request, "draft", "awaiting_acknowledgment", "");
            // Invoice moves submitted → listed (ops-check outcome recorded; the in-progress micro-state is
            // collapsed for the skeleton). Guarded on the invoice's own status.
            int inv = jdbc.update("UPDATE deal_invoice SET status = 'listed', "
                            + "check_outcomes = '{\"ops\":{\"outcome\":\"passed\"}}'::jsonb, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE invoice_id = ? AND status = 'submitted'", row.invoiceId());
            if (inv != 1) {
                throw new ValidationException("invoice is not in 'submitted' for listing: " + request.aggregateId());
            }
            return new CommandOutcome<>(null, transitionEvent(request, CONTEXT + ".Listing.OpsChecksPassed",
                    "draft", "awaiting_acknowledgment"));
        });
    }

    public CommandResult<Void> snapshotAndReady(CommandRequest request, int rateBps) {
        return gateway.execute(request, OPS, () -> {
            UUID listingId = request.aggregateId();
            Listing row = load(listingId);
            requireStatus(row, "awaiting_acknowledgment", listingId);

            Band band = activeBand(row.buyerId(), tenorBucket(row.tenorDays()));
            if (band == null) {
                throw new ValidationException("no active pricing band for this buyer/tenor");
            }
            if (rateBps < band.minBps() || rateBps > band.maxBps()) { // L.10
                throw new ValidationException("rate_bps " + rateBps + " is outside the band ["
                        + band.minBps() + "," + band.maxBps() + "]");
            }
            long fundingTarget = FundingMath.fundingTargetPaise(row.faceValuePaise(), rateBps, row.tenorDays(),
                    band.feeBps());
            // Cross-context snapshot inputs (documented shortcut): buyer limit headroom + supplier cap.
            long buyerHeadroom = buyerCreditLimitPaise(row.buyerId());
            long supplierCap = supplierExposureCapPaise(row.supplierId());
            UUID makerAdminId = roles.adminUserId(request.actorId());

            int updated = jdbc.update("UPDATE deal_listing SET status = 'ready_for_review', "
                            + "pricing_snapshot = ?::jsonb, buyer_limit_headroom_snapshot = ?, "
                            + "supplier_exposure_cap_snapshot = ?, funding_target = ?, golive_maker_id = ?, "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'awaiting_acknowledgment' AND aggregate_version = ?",
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
            // Transition the listing FIRST (version-guarded) so a concurrent second go-live loses the
            // optimistic race with a clean 409 — before any VA is inserted. va_id has no FK, so it can name
            // the VA we insert next. (Real BC4 subscribes to Listing.GoneLive; we create it inline.)
            UUID vaId = Ids.newId();
            int updated = jdbc.update("UPDATE deal_listing SET status = 'live', golive_checker_id = ?, "
                            + "golive_mfa_assertion_id = ?, va_id = ?, "
                            + "funding_window_close_at = now() + (interval '1 day' * ?), "
                            + "aggregate_version = aggregate_version + 1 "
                            + "WHERE listing_id = ? AND status = 'ready_for_review' AND aggregate_version = ?",
                    checkerAdminId, request.session().mfaAssertionId().toString(), vaId, FUNDING_WINDOW_DAYS,
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

    /** A plain status-guarded transition with no extra columns; returns the loaded row for follow-on work. */
    private Listing transitionListing(CommandRequest request, String from, String to, String ignored) {
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

    // --- cross-context reads (documented skeleton shortcut — DL-BE-034) ----------------------------

    private long buyerCreditLimitPaise(UUID buyerId) {
        Long v = jdbc.queryForObject("SELECT credit_limit_paise FROM buyer_account WHERE buyer_id = ?",
                Long.class, buyerId);
        if (v == null) {
            throw new ValidationException("buyer has no credit limit set: " + buyerId);
        }
        return v;
    }

    private long supplierExposureCapPaise(UUID supplierId) {
        Long v = jdbc.queryForObject("SELECT credit_exposure_cap_paise FROM sup_account WHERE supplier_id = ?",
                Long.class, supplierId);
        if (v == null) {
            throw new ValidationException("supplier has no exposure cap set: " + supplierId);
        }
        return v;
    }

    private Band activeBand(UUID buyerId, String tenorBucket) {
        return jdbc.query("SELECT pricing_band_id, rate_range_min_bps, rate_range_max_bps, fee_bps "
                        + "FROM risk_pricing_policy "
                        + "WHERE buyer_id = ? AND tenor_bucket = ?::risk_tenor_bucket AND superseded_by IS NULL",
                rs -> rs.next()
                        ? new Band(rs.getObject("pricing_band_id", UUID.class), rs.getInt("rate_range_min_bps"),
                                rs.getInt("rate_range_max_bps"), rs.getInt("fee_bps"))
                        : null,
                buyerId, tenorBucket);
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
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise pricing_snapshot", e);
        }
    }

    private record Listing(String status, int version, UUID invoiceId, UUID supplierId, UUID buyerId,
                           UUID goliveMakerId, Long fundingTarget, long faceValuePaise, int tenorDays) {
    }

    private record Band(UUID bandId, int minBps, int maxBps, int feeBps) {
    }
}
