package com.arthvritt.platform.auth;

/**
 * Outcome of {@link AuthService#verifyOtp}. A wrong/expired/locked code is <b>normal flow</b>, not an
 * exception — returning a result (rather than throwing) keeps the durable side effects of a failed
 * attempt (the {@code attempts} increment, the audit event) committed instead of rolled back.
 *
 * @param verified  true only when the code matched and an assertion was minted
 * @param assertion the minted MFA assertion (null unless {@code verified})
 * @param reason    machine-readable failure reason when not verified (e.g. bad_code, expired)
 */
public record OtpResult(boolean verified, MfaAssertion assertion, String reason) {

    public static OtpResult verified(MfaAssertion assertion) {
        return new OtpResult(true, assertion, null);
    }

    public static OtpResult failed(String reason) {
        return new OtpResult(false, null, reason);
    }
}
