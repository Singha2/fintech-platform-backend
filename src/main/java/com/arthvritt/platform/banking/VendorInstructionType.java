package com.arthvritt.platform.banking;

/**
 * The escrow instruction kinds (BC18, `vendor_instruction_type_enum`). M5b implements the money ops
 * (create VA, payouts, refund); {@code FETCH_MASTER_STATEMENT} is the reconciliation pull, owned by
 * BC4 Settlement (M13).
 */
public enum VendorInstructionType {
    CREATE_VA("create_va"),
    CLOSE_VA("close_va"),
    PAYOUT_SINGLE("payout_single"),
    PAYOUT_MULTI_LEG("payout_multi_leg"),
    REFUND("refund"),
    FETCH_MASTER_STATEMENT("fetch_master_statement");

    private final String wire;

    VendorInstructionType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
