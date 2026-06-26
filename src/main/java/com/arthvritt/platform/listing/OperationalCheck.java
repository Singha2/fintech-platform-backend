package com.arthvritt.platform.listing;

import com.arthvritt.platform.shared.error.ValidationException;

import java.util.Arrays;
import java.util.List;

/**
 * DL-027 — the seven operational checks for a BC1 invoice. Each check has a wire name (used in the
 * {@code check_outcomes} JSONB key and in HTTP request bodies) and a {@link Kind} that determines
 * who attests the result and how the outcome is determined.
 *
 * <p><b>IRN validity (VENDOR):</b> per INV.7, the IRN can never be self-attested. When the invoice
 * has an {@code irn}, the platform calls {@code VerificationPort.verifyIrn} (BC17 ACL); the ACL
 * result wins unconditionally — any {@code outcome} the caller supplies is ignored. When {@code irn}
 * is NULL (manual-fallback path), the outcome is {@code not_applicable}.
 *
 * <p><b>OPS-attested checks (the remaining six):</b> the caller supplies "passed" or "failed". In
 * Phase 1 these are human-attested by an ops executive. DL-BE-041 defers wiring the following to
 * external ACL ports (listed for later pickup):
 * <ul>
 *   <li>{@link #EWAY_BILL_MATCH} → BC17 {@code VERIFY_EWAY_BILL} ACL
 *   <li>{@link #SUPPLIER_EXPOSURE_CAP} → BC8/BC9 exposure-cap query port
 *   <li>{@link #BUYER_LIMIT_HEADROOM} → BC3 credit-limit query port
 *   <li>{@link #DUPLICATE_CHECK} → in-DB duplicate query on {@code deal_invoice}
 * </ul>
 */
public enum OperationalCheck {

    /**
     * GST IRN validity — vendor-verified via BC17 {@code verify_irn}. Per INV.7, self-attestation
     * is prohibited; the ACL result is the single source of truth.
     */
    IRN_VALIDITY("irn_validity", Kind.VENDOR),

    /** E-way bill match — ops-attested in Phase 1; deferred wiring to BC17 ACL (DL-BE-041). */
    EWAY_BILL_MATCH("eway_bill_match", Kind.OPS),

    /** Buyer–supplier relationship established (no cold-start). */
    BUYER_SUPPLIER_RELATIONSHIP("buyer_supplier_relationship", Kind.OPS),

    /** No duplicate invoice in the system (INV.1 backstop). Deferred to DB query (DL-BE-041). */
    DUPLICATE_CHECK("duplicate_check", Kind.OPS),

    /** Supplier total exposure does not breach the cap. Deferred to BC8/BC9 query (DL-BE-041). */
    SUPPLIER_EXPOSURE_CAP("supplier_exposure_cap", Kind.OPS),

    /** Buyer credit limit has sufficient headroom. Deferred to BC3 query (DL-BE-041). */
    BUYER_LIMIT_HEADROOM("buyer_limit_headroom", Kind.OPS),

    /** All required documents are complete and attached. */
    DOCUMENT_COMPLETENESS("document_completeness", Kind.OPS);

    /** Who determines the check outcome. */
    public enum Kind {
        /** Outcome determined by a BC17 vendor call — the client-supplied value is always ignored. */
        VENDOR,
        /** Outcome supplied by the ops executive over the {@code record-ops-check} endpoint. */
        OPS
    }

    private final String wire;
    private final Kind kind;

    OperationalCheck(String wire, Kind kind) {
        this.wire = wire;
        this.kind = kind;
    }

    /** Wire name used in HTTP bodies and in the {@code check_outcomes} JSONB key. */
    public String wire() {
        return wire;
    }

    /** Who attests the outcome of this check. */
    public Kind kind() {
        return kind;
    }

    /**
     * Look up a check by its wire name.
     *
     * @throws com.arthvritt.platform.shared.error.ValidationException for an unrecognised name (→ 400).
     */
    public static OperationalCheck fromWire(String wire) {
        for (OperationalCheck check : values()) {
            if (check.wire.equals(wire)) {
                return check;
            }
        }
        throw new ValidationException("unknown operational check: " + wire);
    }

    /** All wire names in declaration order — used by {@code completeOpsChecks} to find missing keys. */
    public static List<String> allWireNames() {
        return Arrays.stream(values()).map(OperationalCheck::wire).toList();
    }
}
