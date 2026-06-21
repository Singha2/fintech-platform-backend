package com.arthvritt.platform.signing;

/**
 * The e-Sign methods (BC19, `sign_method_enum`, DL-048): Aadhaar-OTP or DSC (digital signature
 * certificate). The stub treats both identically; the real adapter has distinct flows (A2 §3).
 */
public enum SignMethod {
    AADHAAR_OTP("aadhaar_otp"),
    DSC("dsc");

    private final String wire;

    SignMethod(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
