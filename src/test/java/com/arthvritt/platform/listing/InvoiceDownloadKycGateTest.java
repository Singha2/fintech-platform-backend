package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M10-D KYC-1 · the investor-facing invoice-PDF download gate (docs/modules/M10-D-investor-self-login.md
 * §3/§9 P4). {@link InvoiceDocumentService#download} already gates on the listing's live-set (DOC.6,
 * M19); this adds the investor-KYC layer via the new {@link InvestorQueryPort}: a KYC-approved investor
 * downloads normally, a not-yet-approved investor is rejected 403, and the admin path is unchanged.
 */
class InvoiceDownloadKycGateTest extends AbstractEdgeHttpTest {

    private String ops;
    private UUID listingId;
    private UUID documentId;
    private byte[] pdfBytes;

    @BeforeEach
    void seed() throws Exception {
        notifier.clear();
        ops = bearerFor(seedAdminWithRoles("ops_executive"));
        listingId = seedListing("draft").listingId();   // attachable (DOC.4) before it freezes
        documentId = uploadAndAttachPdf();
        // Isolate the KYC gate from the full go-live/ops-check flow (covered by M19/M9): flip directly.
        jdbc.update("UPDATE deal_listing SET status = 'live'::deal_listing_status WHERE listing_id = ?", listingId);
    }

    @Test
    void kyc_approved_investor_can_download() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin("kyc_approved");
        String bearer = bearerFor(investor.login());

        byte[] body = mvc.perform(get("/listings/{id}/invoice-documents/{docId}/content", listingId, documentId)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(pdfBytes);
    }

    @Test
    void non_kyc_investor_forbidden() throws Exception {
        InvestorLogin investor = seedActiveInvestorWithLogin("signed_up");
        String bearer = bearerFor(investor.login());

        mvc.perform(get("/listings/{id}/invoice-documents/{docId}/content", listingId, documentId)
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_download_unchanged() throws Exception {
        byte[] body = mvc.perform(get("/listings/{id}/invoice-documents/{docId}/content", listingId, documentId)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(pdfBytes);
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** M18 two-phase upload → attach (mirrors InvoiceArtifactTest's uploadInvoicePdf + attach). */
    private UUID uploadAndAttachPdf() throws Exception {
        pdfBytes = ("%PDF-1.4\ninvoice::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String initBody = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", "application/pdf", "declared_size", pdfBytes.length));
        MvcResult init = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(status().is2xxSuccessful()).andReturn();
        UUID docId = UUID.fromString(node(init).get("document_id").asText());
        mvc.perform(put("/documents/{id}/content", docId).header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_PDF).content(pdfBytes)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/documents/{id}/finalize", docId).header("Authorization", "Bearer " + ops))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(post("/listings/{id}/invoice-documents", listingId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("document_id", docId.toString()))))
                .andExpect(status().is2xxSuccessful());
        return docId;
    }
}
