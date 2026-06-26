package com.arthvritt.platform.verification;

import java.util.Map;
import java.util.UUID;

/**
 * BC17 Verification anti-corruption port — the domain operations BC1/BC7/BC8/BC9/BC11 call to verify a
 * subject against the aggregator. Callers never touch a vendor API; the vendor model stays behind the
 * port (A1/B1). {@link #verify} is the universal entry (supports all {@link VerificationApi} values);
 * the typed convenience methods are sugar for the common onboarding checks — more are added as
 * consumers land, all one-liners over {@link #verify}.
 */
public interface VerificationPort {

    VerificationResult verify(VerificationRequest request);

    default VerificationResult verifyPan(UUID subjectId, String pan) {
        return verify(new VerificationRequest(VerificationApi.VERIFY_PAN, subjectId, Map.of("pan", pan)));
    }

    default VerificationResult verifyGstin(UUID subjectId, String gstin) {
        return verify(new VerificationRequest(VerificationApi.VERIFY_GSTIN, subjectId, Map.of("gstin", gstin)));
    }

    default VerificationResult verifyIrn(UUID subjectId, String irn) {
        return verify(new VerificationRequest(VerificationApi.VERIFY_IRN, subjectId, Map.of("irn", irn)));
    }

    default VerificationResult verifyPennyDrop(UUID subjectId, String bankAccountLast4) {
        return verify(new VerificationRequest(VerificationApi.VERIFY_PENNY_DROP, subjectId,
                Map.of("bank_account_last4", bankAccountLast4)));
    }

    default VerificationResult fetchMca21(UUID subjectId, String cin) {
        return verify(new VerificationRequest(VerificationApi.FETCH_MCA21, subjectId, Map.of("cin", cin)));
    }

    default VerificationResult screenAmlPep(UUID subjectId, String name) {
        return verify(new VerificationRequest(VerificationApi.SCREEN_AML_PEP, subjectId, Map.of("name", name)));
    }
}
