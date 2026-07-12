package com.arthvritt.platform.listing;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M19 · Invoice Artifacts (BC1) — RED tests (docs/modules/M19-invoice-artifacts.md §3/§7, DL-BE-071).
 *
 * <p>An Ops Executive uploads an invoice PDF via the M18 {@code /documents} API, <b>attaches</b> the
 * resulting {@code document_id} to the listing's invoice, that gates the existing {@code document_completeness}
 * ops-check, the artifact set freezes at {@code ready_for_review}, and it is downloadable once the listing is
 * live. Layer-4 over the M18 document service (link table + lifecycle + authZ; bytes stay in BC16).
 *
 * <p>Pure HTTP: RED until the implementer adds the V11 {@code deal_invoice_document} table, the
 * {@code /listings/{id}/invoice-documents} endpoints, and the {@code document_completeness} wiring.
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li><b>DOC.2</b> — {@code document_completeness} passes only against a real, {@code stored} attached document.</li>
 *   <li><b>DOC.3</b> — maker ≠ checker: the {@code document_completeness} recorder ≠ the artifact's {@code uploaded_by}.</li>
 *   <li><b>DOC.1</b> — at most one <i>active</i> invoice artifact; <b>DOC.7</b> — replace supersedes.</li>
 *   <li><b>DOC.4</b> — the artifact set freezes at {@code ready_for_review}.</li>
 *   <li><b>DOC.5</b> — the attached document must be a PDF.</li>
 *   <li><b>DOC.6</b> — download requires the listing to be in the live-set (the KYC'd-investor gate via
 *       {@code InvestorQueryPort} is deferred with investor login — see the class note).</li>
 * </ul>
 *
 * <p><b>Scoping note (DOC.6):</b> Phase 1 has no investor login — an ops/admin downloads on the investor's
 * behalf — so these tests gate download on <i>listing status</i> only. The investor-KYC eligibility
 * (new {@code InvestorQueryPort.isDownloadEligible}) lands with the investor portal.
 *
 * <p><b>Expected M9 breakage:</b> wiring DOC.2/DOC.3 breaks the existing listing tests
 * (ListingOpsChecksTest / ListingGoLiveTest / ListingAcknowledgmentTest) which record
 * {@code document_completeness=passed} with no attached document and a single ops admin — the implementer
 * must update them to attach a doc first and record the check with a <i>different</i> ops admin.
 */
class InvoiceArtifactTest extends AbstractEdgeHttpTest {

    private static final String[] OTHER_OPS_CHECKS =
            {"eway_bill_match", "buyer_supplier_relationship", "duplicate_check", "supplier_exposure_cap", "buyer_limit_headroom"};

    private Seeded opsAAdmin;   // uploads/attaches → deal_invoice_document.uploaded_by
    private Seeded opsBAdmin;   // records document_completeness (must differ from the uploader — DOC.3)
    private String opsA;
    private String opsB;
    private UUID supplierId;
    private UUID buyerId;
    private UUID listingId;
    private UUID invoiceId;
    private byte[] lastPdf;

    @BeforeEach
    void seed() throws Exception {
        notifier.clear();
        opsAAdmin = seedAdminWithRoles("ops_executive");
        opsBAdmin = seedAdminWithRoles("ops_executive");
        opsA = bearerFor(opsAAdmin);
        opsB = bearerFor(opsBAdmin);
        supplierId = seedActiveSupplier();
        buyerId = seedActiveBuyer(opsAAdmin.adminUserId());
        seedPricingBand(buyerId, "31_60d", 1000, 1500, 200);
        listingId = createListing();
        invoiceId = jdbc.queryForObject("SELECT invoice_id FROM deal_listing WHERE listing_id = ?", UUID.class, listingId);
    }

    // --- attach: link a stored document to the invoice, stamp ownership, list it ------------------------

    @Test
    void attach_links_the_document_stamps_owner_ref_and_lists_it() throws Exception {
        UUID doc = uploadInvoicePdf("application/pdf");

        attach(listingId, doc, opsA).andExpect(status().is2xxSuccessful());

        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM deal_invoice_document WHERE invoice_id = ? AND document_id = ?",
                String.class, invoiceId, doc)).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                "SELECT uploaded_by FROM deal_invoice_document WHERE document_id = ?", UUID.class, doc))
                .isEqualTo(opsAAdmin.identityId());
        assertThat(jdbc.queryForObject(
                "SELECT owner_ref FROM sys_document WHERE document_id = ?", String.class, doc))
                .isEqualTo("Invoice:" + invoiceId);

        mvc.perform(get("/listings/{id}/invoice-documents", listingId).header("Authorization", "Bearer " + opsA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].document_id").value(doc.toString()));
    }

    // --- DOC.2: document_completeness cannot pass without a real, stored attached document --------------

    @Test
    void document_completeness_without_an_attached_document_is_rejected() throws Exception {
        startOpsChecks();

        recordCheck(opsB, "document_completeness", "passed").andExpect(status().is4xxClientError());

        assertThat(docCompletenessOutcome()).isNull();
    }

    // --- DOC.3 (the load-bearing test): the uploader may not sign off their own document ----------------

    @Test
    void document_completeness_recorded_by_the_uploader_is_rejected() throws Exception {
        startOpsChecks();
        UUID doc = uploadInvoicePdf("application/pdf");
        attach(listingId, doc, opsA).andExpect(status().is2xxSuccessful());   // uploaded_by = opsA

        recordCheck(opsA, "document_completeness", "passed").andExpect(status().is4xxClientError());

        assertThat(docCompletenessOutcome()).isNull();
    }

    @Test
    void document_completeness_recorded_by_a_different_ops_passes() throws Exception {
        startOpsChecks();
        UUID doc = uploadInvoicePdf("application/pdf");
        attach(listingId, doc, opsA).andExpect(status().is2xxSuccessful());

        recordCheck(opsB, "document_completeness", "passed").andExpect(status().is2xxSuccessful());

        assertThat(docCompletenessOutcome()).isEqualTo("passed");
    }

    // --- DOC.1 / DOC.7: one active artifact; replace supersedes -----------------------------------------

    @Test
    void a_second_active_attach_is_rejected() throws Exception {
        attach(listingId, uploadInvoicePdf("application/pdf"), opsA).andExpect(status().is2xxSuccessful());

        attach(listingId, uploadInvoicePdf("application/pdf"), opsA).andExpect(status().is4xxClientError());

        assertThat(activeArtifactCount()).isEqualTo(1);
    }

    @Test
    void replace_supersedes_the_previous_active_document() throws Exception {
        UUID first = uploadInvoicePdf("application/pdf");
        attach(listingId, first, opsA).andExpect(status().is2xxSuccessful());
        UUID second = uploadInvoicePdf("application/pdf");

        mvc.perform(put("/listings/{id}/invoice-documents/{docId}", listingId, first)
                        .header("Authorization", "Bearer " + opsA)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("new_document_id", second.toString()))))
                .andExpect(status().is2xxSuccessful());

        assertThat(artifactStatus(first)).isEqualTo("superseded");
        assertThat(artifactStatus(second)).isEqualTo("active");
        assertThat(activeArtifactCount()).isEqualTo(1);
    }

    // --- DOC.5: only a PDF may be attached --------------------------------------------------------------

    @Test
    void attaching_a_non_pdf_document_is_rejected() throws Exception {
        UUID notPdf = uploadInvoicePdf("text/plain");   // content_type declared non-pdf at initiate

        attach(listingId, notPdf, opsA).andExpect(status().is4xxClientError());
    }

    // --- DOC.4: the artifact set freezes at ready_for_review --------------------------------------------

    @Test
    void attach_after_ready_for_review_is_rejected() throws Exception {
        driveToReadyForReview();

        attach(listingId, uploadInvoicePdf("application/pdf"), opsA).andExpect(status().is4xxClientError());
    }

    // --- DOC.6: download gated on listing status (live-set) ---------------------------------------------

    @Test
    void download_on_a_live_listing_returns_the_exact_bytes() throws Exception {
        UUID doc = uploadInvoicePdf("application/pdf");
        attach(listingId, doc, opsA).andExpect(status().is2xxSuccessful());
        // isolate the download gate from the go-live/MFA flow (covered by M9): flip status directly
        jdbc.update("UPDATE deal_listing SET status = 'live'::deal_listing_status WHERE listing_id = ?", listingId);

        byte[] body = mvc.perform(get("/listings/{id}/invoice-documents/{docId}/content", listingId, doc)
                        .header("Authorization", "Bearer " + opsA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(lastPdf);
    }

    @Test
    void download_before_the_listing_is_live_is_rejected() throws Exception {
        UUID doc = uploadInvoicePdf("application/pdf");
        attach(listingId, doc, opsA).andExpect(status().is2xxSuccessful());   // listing still 'draft'

        mvc.perform(get("/listings/{id}/invoice-documents/{docId}/content", listingId, doc)
                        .header("Authorization", "Bearer " + opsA))
                .andExpect(status().is4xxClientError());
    }

    // --- flow helpers -----------------------------------------------------------------------------------

    /** Full ops-check → ack → snapshot flow, with a real attached artifact + a distinct document_completeness recorder. */
    private void driveToReadyForReview() throws Exception {
        startOpsChecks();
        attach(listingId, uploadInvoicePdf("application/pdf"), opsA).andExpect(status().is2xxSuccessful());
        recordCheck(opsA, "irn_validity", null).andExpect(status().is2xxSuccessful());   // vendor; no IRN → not_applicable
        for (String check : OTHER_OPS_CHECKS) {
            recordCheck(opsA, check, "passed").andExpect(status().is2xxSuccessful());
        }
        recordCheck(opsB, "document_completeness", "passed").andExpect(status().is2xxSuccessful());   // DOC.3: ≠ uploader
        sendVersioned(post("/listings/{id}/complete-ops-checks", listingId), opsA, Map.of())
                .andExpect(status().is2xxSuccessful());
        sendVersioned(post("/listings/{id}/record-buyer-ack", listingId), opsA,
                Map.of("outcome", "acknowledged", "method", "email")).andExpect(status().is2xxSuccessful());
        sendVersioned(post("/listings/{id}/snapshot-and-ready", listingId), opsA, Map.of("rate_bps", 1200))
                .andExpect(status().is2xxSuccessful());
    }

    private void startOpsChecks() throws Exception {
        sendVersioned(post("/listings/{id}/start-ops-checks", listingId), opsA, Map.of())
                .andExpect(status().is2xxSuccessful());
    }

    private ResultActions recordCheck(String bearer, String checkName, String outcome) throws Exception {
        Map<String, Object> body = (outcome == null)
                ? Map.of("check_name", checkName)
                : Map.of("check_name", checkName, "outcome", outcome);
        return sendVersioned(post("/listings/{id}/record-ops-check", listingId), bearer, body);
    }

    // --- request helpers --------------------------------------------------------------------------------

    private UUID createListing() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "supplier_id", supplierId.toString(), "buyer_id", buyerId.toString(),
                "invoice_number", "INV-" + UUID.randomUUID(), "face_value_paise", 10_00_000_00L,
                "invoice_date", "2026-01-15", "tenor_days", 45));
        MvcResult res = mvc.perform(post("/listings")
                        .header("Authorization", "Bearer " + opsA)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    /** M18 two-phase upload → a stored document_id of the given declared content-type. */
    private UUID uploadInvoicePdf(String contentType) throws Exception {
        lastPdf = ("%PDF-1.4\ninvoice::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String initBody = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", contentType, "declared_size", lastPdf.length));
        MvcResult init = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + opsA)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(status().is2xxSuccessful()).andReturn();
        UUID docId = UUID.fromString(node(init).get("document_id").asText());
        mvc.perform(put("/documents/{id}/content", docId).header("Authorization", "Bearer " + opsA)
                .contentType(MediaType.APPLICATION_PDF).content(lastPdf)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/documents/{id}/finalize", docId).header("Authorization", "Bearer " + opsA))
                .andExpect(status().is2xxSuccessful());
        return docId;
    }

    private ResultActions attach(UUID listing, UUID documentId, String bearer) throws Exception {
        return mvc.perform(post("/listings/{id}/invoice-documents", listing)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("document_id", documentId.toString()))));
    }

    private ResultActions sendVersioned(MockHttpServletRequestBuilder builder, String bearer, Map<String, Object> body)
            throws Exception {
        return mvc.perform(builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(listingVersion()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    // --- state queries ----------------------------------------------------------------------------------

    private int listingVersion() {
        return jdbc.queryForObject("SELECT aggregate_version FROM deal_listing WHERE listing_id = ?", Integer.class, listingId);
    }

    private String docCompletenessOutcome() {
        return jdbc.queryForObject(
                "SELECT i.check_outcomes -> 'document_completeness' ->> 'outcome' "
                        + "FROM deal_invoice i JOIN deal_listing l ON l.invoice_id = i.invoice_id WHERE l.listing_id = ?",
                String.class, listingId);
    }

    private int activeArtifactCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM deal_invoice_document WHERE invoice_id = ? AND status::text = 'active'",
                Integer.class, invoiceId);
    }

    private String artifactStatus(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM deal_invoice_document WHERE document_id = ?", String.class, documentId);
    }

    // --- listing seeding (mirrors ListingOpsChecksTest) -------------------------------------------------

    private UUID seedActiveSupplier() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sup_account (supplier_id, legal_name, constitution_type, pan, status, "
                        + "credit_exposure_cap_paise) VALUES (?, ?, 'private_limited', ?::pan_type, 'active', ?)",
                id, "Supplier " + id, "ABCDE1234F", 5_00_00_000_00L);
        return id;
    }

    private UUID seedActiveBuyer(UUID nominatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, credit_limit_paise, nominated_by) "
                        + "VALUES (?, ?, 'active', ?, ?)", id, "Buyer " + id, 5_00_00_000_00L, nominatedBy);
        return id;
    }

    private void seedPricingBand(UUID buyer, String bucket, int min, int max, int fee) {
        jdbc.update("INSERT INTO risk_pricing_policy (pricing_band_id, buyer_id, tenor_bucket, "
                        + "rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from) "
                        + "VALUES (?, ?, ?::risk_tenor_bucket, ?, ?, ?, now()::date)",
                UUID.randomUUID(), buyer, bucket, min, max, fee);
    }
}
