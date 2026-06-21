package com.arthvritt.platform.adminiam;

import com.arthvritt.platform.shared.error.ValidationException;

/**
 * The five composable admin roles (DL-032, the {@code admin_role} DB enum). In Phase-1 RBAC these
 * are the permission units themselves (C18): effective authority is the union of an admin's active
 * role assignments. {@link #wire} is the DB/wire value; the enum is the typed API surface.
 */
public enum AdminRole {

    OPS_EXECUTIVE("ops_executive"),
    CREDIT_REVIEWER("credit_reviewer"),
    COMPLIANCE_REVIEWER("compliance_reviewer"),
    TREASURY_AND_SETTLEMENT("treasury_and_settlement"),
    SUPER_ADMIN("super_admin");

    private final String wire;

    AdminRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static AdminRole fromWire(String wire) {
        for (AdminRole r : values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }
        throw new ValidationException("unknown admin role: " + wire);
    }
}
