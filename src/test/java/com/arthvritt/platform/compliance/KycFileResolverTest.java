package com.arthvritt.platform.compliance;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-2 · {@code GET /suppliers|investors/{id}/kyc-file} — resolves the {@code kyc_file_id} for a subject so the
 * UI can reach {@code POST /kyc/{kycFileId}/documents} (UI_INTEGRATION_BACKEND_SPEC §2). Native one-row read
 * over {@code comp_kyc_file} (UNIQUE per subject); 404 when the subject has not submitted KYC.
 */
class KycFileResolverTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    @Test
    void resolves_the_kyc_file_for_a_supplier_subject() throws Exception {
        UUID supplierId = UUID.randomUUID();
        UUID kycFileId = seedKycFile(supplierId, "supplier");

        MvcResult res = mvc.perform(get("/suppliers/{id}/kyc-file", supplierId)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = node(res);

        assertThat(body.get("kyc_file_id").asText()).isEqualTo(kycFileId.toString());
        assertThat(body.get("subject_id").asText()).isEqualTo(supplierId.toString());
        assertThat(body.get("subject_type").asText()).isEqualTo("supplier");
        assertThat(body.get("status").asText()).isEqualTo("submitted");
    }

    @Test
    void resolves_the_kyc_file_for_an_investor_subject() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID kycFileId = seedKycFile(investorId, "investor");

        JsonNode body = node(mvc.perform(get("/investors/{id}/kyc-file", investorId)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.get("kyc_file_id").asText()).isEqualTo(kycFileId.toString());
        assertThat(body.get("subject_type").asText()).isEqualTo("investor");
    }

    @Test
    void a_subject_that_has_not_submitted_kyc_is_404() throws Exception {
        mvc.perform(get("/suppliers/{id}/kyc-file", UUID.randomUUID())
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error_code").value("not_found"));
    }

    @Test
    void the_investor_route_does_not_resolve_a_supplier_subjects_file() throws Exception {
        UUID subjectId = UUID.randomUUID();
        seedKycFile(subjectId, "supplier");   // filed as a supplier...

        mvc.perform(get("/investors/{id}/kyc-file", subjectId)   // ...so the investor route must not find it
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
    }

    /** A {@code comp_kyc_file} row in 'submitted' for a subject (submitted_by omitted — nullable FK, not under test). */
    private UUID seedKycFile(UUID subjectId, String subjectType) {
        UUID kycFileId = UUID.randomUUID();
        jdbc.update("INSERT INTO comp_kyc_file (kyc_file_id, subject_id, subject_type, status) "
                        + "VALUES (?, ?, ?::comp_kyc_subject_type, 'submitted')",
                kycFileId, subjectId, subjectType);
        return kycFileId;
    }
}
