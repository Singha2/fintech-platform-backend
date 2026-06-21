package com.arthvritt.platform.verification;

import java.util.Map;
import java.util.UUID;

/**
 * A request to verify {@code subjectId} via an aggregator {@code api}. {@code inputs} carries the
 * identifier(s) the API needs (e.g. {@code {"pan": "ABCDE1234F"}}). Domain-shaped — no vendor model
 * leaks across the {@link VerificationPort} (the ACL rule, A1/B1).
 */
public record VerificationRequest(VerificationApi api, UUID subjectId, Map<String, Object> inputs) {

    public VerificationRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
}
