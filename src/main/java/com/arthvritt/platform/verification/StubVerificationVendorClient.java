package com.arthvritt.platform.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-1 fake aggregator (DL-026, DL-047). <b>Auto-passes</b> every call with deterministic
 * extracted fields per API and a deterministic raw payload. No real vendor, no network, no webhook —
 * the {@code VerificationService} completes in-process. The real adapter is a drop-in replacement at
 * the Production gate.
 */
@Component
public class StubVerificationVendorClient implements VerificationVendorClient {

    private final ObjectMapper mapper;

    public StubVerificationVendorClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public VendorResponse call(VerificationApi api, UUID subjectId, Map<String, Object> inputs) {
        Map<String, Object> fields = deterministicFields(api);
        // Verbatim "vendor payload" — deterministic given (api, subject, inputs) so the hash is
        // reproducible AND varies with the request (matters for one-shot APIs, which aren't cached).
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("stub", true);
        raw.put("api", api.wire());
        raw.put("subject_id", subjectId.toString());
        raw.put("inputs", inputs);
        raw.put("fields", fields);
        return new VendorResponse(fields, toBytes(raw));
    }

    private static Map<String, Object> deterministicFields(VerificationApi api) {
        return switch (api) {
            case VERIFY_PAN -> Map.of("name", "STUB HOLDER", "pan_status", "VALID", "aadhaar_seeded", true);
            case VERIFY_AADHAAR_EKYC -> Map.of("name", "STUB HOLDER", "ekyc_status", "VERIFIED");
            case VERIFY_GSTIN -> Map.of("legal_name", "STUB ENTERPRISES PVT LTD", "gstin_status", "ACTIVE");
            case FETCH_MCA21 -> Map.of("company_status", "ACTIVE", "cin_valid", true);
            case FETCH_GST_RETURNS -> Map.of("last_filed_period", "STUB", "filing_status", "REGULAR");
            case FETCH_BUREAU -> Map.of("score", 750, "bureau", "STUB");
            case FETCH_AA_BANK_STMT -> Map.of("account_status", "ACTIVE", "months", 12);
            case VERIFY_PENNY_DROP -> Map.of("account_status", "VALID", "name_match", "EXACT");
            case VERIFY_IRN -> Map.of("irn_status", "VALID");
            case VERIFY_EWAY_BILL -> Map.of("eway_status", "ACTIVE");
            case SCREEN_AML_PEP -> Map.of("match", false, "risk_band", "LOW");
        };
    }

    private byte[] toBytes(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise stub vendor payload", e);
        }
    }
}
