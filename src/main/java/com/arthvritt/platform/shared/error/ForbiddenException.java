package com.arthvritt.platform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * An authenticated caller is not authorised for the addressed resource — a read-side 403, distinct from
 * {@code CommandRejectedException.roleNotHeld} (which gates a <i>command</i>). Maps to HTTP 403 with the
 * B4 §4.1 error body. One factory per distinct reason, mirroring {@code CommandRejectedException}'s shape.
 */
public class ForbiddenException extends PlatformException {

    private ForbiddenException(String errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * M10-D OWN-1: an investor bearer read another investor's owned resource (portfolio / KYC-gated
     * document). {@code error_code} matches the B4 §4.2 {@code tenant_isolation_violation} catalogue
     * entry ({@code cross_tenant_read}); per this module's DoR #3 the read emits no audit envelope
     * (reads are unaudited today — see docs/modules/M10-D-investor-self-login.md §5).
     */
    public static ForbiddenException crossInvestorRead(String resource) {
        return new ForbiddenException("cross_tenant_read",
                "caller may only read its own investor-owned " + resource);
    }

    /** M10-D KYC-1: the caller is tied to an investor account that has not yet reached KYC-approved. */
    public static ForbiddenException kycNotApproved() {
        return new ForbiddenException("kyc_not_approved",
                "investor is not KYC-approved for document download");
    }

    /**
     * M10-D OWN-1: the caller is authenticated but is <b>neither</b> the portfolio's owning investor
     * <b>nor</b> an admin — e.g. an ack-user/auditor kind (BE-15/BE-13) once those logins land. The
     * un-scoped view is reserved for admins by a positive check, never granted by default to "not an
     * investor".
     */
    public static ForbiddenException notAuthorisedForPortfolio() {
        return new ForbiddenException("cross_tenant_read",
                "caller is not authorised to read this investor's portfolio");
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.FORBIDDEN;
    }
}
