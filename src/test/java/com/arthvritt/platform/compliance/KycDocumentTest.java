package com.arthvritt.platform.compliance;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M20-KYC · Onboarding KYC documents (BC11) — RED tests (docs/modules/M20-onboarding-documents.md §3/§4/§7,
 * DL-BE-073). Typed KYC documents (investor + supplier) that <b>attach</b> an M18 {@code document_id} to an
 * existing {@code comp_kyc_file}. Layer-4 over the BC16 document service; mirrors M19 {@code InvoiceDocumentService}
 * (typed link table + {@code DocumentPort} stored-gate), <i>not</i> the bare-ref buyer-KYB pattern.
 *
 * <p>Pure HTTP: RED until the implementer adds V13 (`onboarding_subject_type`/`kyc_doc_kind`/`onboarding_doc_status`
 * enums, `onboarding_doc_requirement` suggested-list + seed, `kyc_document` + partial-unique-per-kind), the
 * `.Onboarding.*` gateway commands, and the `/kyc/{kycFileId}/documents` + `/onboarding-doc-requirements` endpoints.
 *
 * <p><b>Finalized design (user 2026-07-12): nothing is mandatory; Ops decides KYC complete.</b> The checklist is a
 * <i>suggested</i> list; the coverage read is <b>advisory</b> (covered/not) and gates nothing.
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li><b>OD.3</b> — capture-only: attaching zero KYC docs never blocks {@code submit-kyc → approve}.</li>
 *   <li><b>OD.1/OD.2</b> — typed link ({@code doc_kind}) to a {@code stored} BC16 {@code document_id} (bare ref).</li>
 *   <li><b>OD.6</b> — supersede, never hard-delete; one <i>active</i> doc per kind (partial unique).</li>
 *   <li><b>OD.4</b> — advisory coverage (suggested vs uploaded), no verdict; runtime-editable suggested list.</li>
 *   <li><b>#3/#4</b> — attach is ops-gated + idempotent on {@code command_id}.</li>
 * </ul>
 *
 * <p><b>Scoping (OD.5):</b> compliance-only / investor-403 restricted download is deferred with the investor
 * portal (no investor login in Phase 1) — same as M19 DOC.6 / buyer-KYB.
 */
class KycDocumentTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private Seeded opsAdmin;      // attaches → kyc_document.uploaded_by
    private String ops;
    private String compliance;    // approves KYC; the disallowed role for attach (role-gate test)

    @BeforeEach
    void seedActors() {
        notifier.clear();
        opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        compliance = bearerFor(seedAdminWithRoles("compliance_reviewer"));
    }

    // --- attach: typed link to a stored document, owner stamped, listed -------------------------------

    @Test
    void attach_links_a_kyc_document_stamps_owner_and_lists_it() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        UUID doc = uploadKycDoc();

        attach(kycFile, doc, "pan_card", ops).andExpect(status().is2xxSuccessful());

        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM kyc_document WHERE kyc_file_id = ? AND document_id = ?",
                String.class, kycFile, doc)).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                "SELECT doc_kind::text FROM kyc_document WHERE document_id = ?", String.class, doc)).isEqualTo("pan_card");
        assertThat(jdbc.queryForObject(
                "SELECT uploaded_by FROM kyc_document WHERE document_id = ?", UUID.class, doc))
                .isEqualTo(opsAdmin.identityId());
        assertThat(jdbc.queryForObject(
                "SELECT owner_ref FROM sys_document WHERE document_id = ?", String.class, doc))
                .isEqualTo("KycFile:" + kycFile);

        mvc.perform(get("/kyc/{id}/documents", kycFile).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].document_id").value(doc.toString()))
                .andExpect(jsonPath("$[0].doc_kind").value("pan_card"));
    }

    // --- role gate: attach is ops_executive (capture); compliance is the reviewer, not the capturer ----

    @Test
    void a_non_ops_actor_may_not_attach() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        UUID doc = uploadKycDoc();

        attach(kycFile, doc, "pan_card", compliance)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));

        assertThat(activeCount(kycFile)).isZero();
    }

    // --- #4: idempotent on command_id -----------------------------------------------------------------

    @Test
    void attach_is_idempotent_on_command_id() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        UUID doc = uploadKycDoc();
        UUID commandId = UUID.randomUUID();

        attach(kycFile, doc, "pan_card", ops, commandId).andExpect(status().is2xxSuccessful());
        attach(kycFile, doc, "pan_card", ops, commandId).andExpect(status().isOk());   // replay

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kyc_document WHERE kyc_file_id = ? AND document_id = ?",
                Integer.class, kycFile, doc)).isEqualTo(1);
    }

    // --- OD.6 / partial unique: one active doc per kind ------------------------------------------------

    @Test
    void a_second_active_document_of_the_same_kind_is_rejected() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        attach(kycFile, uploadKycDoc(), "pan_card", ops).andExpect(status().is2xxSuccessful());

        attach(kycFile, uploadKycDoc(), "pan_card", ops).andExpect(status().is4xxClientError());

        assertThat(activeCountOfKind(kycFile, "pan_card")).isEqualTo(1);
    }

    @Test
    void replace_supersedes_the_previous_document_of_that_kind() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        UUID first = uploadKycDoc();
        attach(kycFile, first, "pan_card", ops).andExpect(status().is2xxSuccessful());
        UUID linkId = jdbc.queryForObject(
                "SELECT kyc_document_id FROM kyc_document WHERE document_id = ?", UUID.class, first);
        UUID second = uploadKycDoc();

        mvc.perform(put("/kyc/{id}/documents/{linkId}", kycFile, linkId)
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("new_document_id", second.toString()))))
                .andExpect(status().is2xxSuccessful());

        assertThat(jdbc.queryForObject("SELECT status::text FROM kyc_document WHERE document_id = ?",
                String.class, first)).isEqualTo("superseded");
        assertThat(jdbc.queryForObject("SELECT status::text FROM kyc_document WHERE document_id = ?",
                String.class, second)).isEqualTo("active");
        assertThat(activeCountOfKind(kycFile, "pan_card")).isEqualTo(1);
    }

    // --- OD.1: the document must resolve to a stored M18 document -------------------------------------

    @Test
    void attaching_a_document_that_is_not_stored_is_rejected() throws Exception {
        UUID kycFile = seedKycFile("supplier");
        UUID pending = initiatePendingDoc();   // initiated, never finalized → pending_upload

        attach(kycFile, pending, "pan_card", ops).andExpect(status().is4xxClientError());

        assertThat(activeCount(kycFile)).isZero();
    }

    // --- OD.3 (the load-bearing test): capture-only — zero docs never blocks approval -----------------

    @Test
    void zero_documents_does_not_block_submit_kyc_then_approve() throws Exception {
        UUID supplier = supplierAtKycSubmitted(ops);   // real onboarding flow, no KYC docs attached
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM kyc_document kd JOIN comp_kyc_file f ON f.kyc_file_id = kd.kyc_file_id "
                        + "WHERE f.subject_id = ?", Integer.class, supplier)).isZero();

        // a different actor (compliance ≠ the ops submitter) approves — the auto-approve stub, ungated by docs
        sendSupplier(post("/suppliers/{id}/record-kyc-approved", supplier), compliance, supplier, Map.of())
                .andExpect(status().is2xxSuccessful());

        assertThat(jdbc.queryForObject("SELECT status::text FROM sup_account WHERE supplier_id = ?",
                String.class, supplier)).isEqualTo("kyc_approved");
    }

    // --- OD.4: advisory coverage (investor persona) — covered vs suggested, no verdict, no gate --------

    @Test
    void coverage_reports_covered_and_uncovered_suggested_kinds() throws Exception {
        UUID kycFile = seedKycFile("investor");   // seeded suggested list: investor {pan_card, address_proof}
        attach(kycFile, uploadKycDoc(), "pan_card", ops).andExpect(status().is2xxSuccessful());

        MvcResult res = mvc.perform(get("/kyc/{id}/documents/coverage", kycFile)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk()).andReturn();

        JsonNode coverage = node(res);   // { "<doc_kind>": <covered bool>, ... } over active-suggested kinds
        assertThat(coverage.get("pan_card").asBoolean()).isTrue();
        assertThat(coverage.get("address_proof").asBoolean()).isFalse();
    }

    // --- runtime-editable suggested list ---------------------------------------------------------------

    @Test
    void a_newly_configured_suggested_kind_appears_in_the_list_and_coverage() throws Exception {
        UUID kycFile = seedKycFile("investor");

        mvc.perform(post("/onboarding-doc-requirements")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("subject_type", "investor", "doc_kind", "bank_statement", "active", true))))
                .andExpect(status().is2xxSuccessful());

        // it is now a suggested kind for investors...
        assertThat(jdbc.queryForObject(
                "SELECT mandatory FROM onboarding_doc_requirement WHERE subject_type = 'investor'::onboarding_subject_type "
                        + "AND doc_kind = 'bank_statement'::kyc_doc_kind", Boolean.class))
                .isFalse();   // nothing mandatory — Ops decides completeness
        mvc.perform(get("/onboarding-doc-requirements").param("subject_type", "investor")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.doc_kind == 'bank_statement')]").exists());

        // ...and the advisory coverage view reflects it (uncovered)
        MvcResult res = mvc.perform(get("/kyc/{id}/documents/coverage", kycFile)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk()).andReturn();
        assertThat(node(res).get("bank_statement").asBoolean()).isFalse();
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private ResultActions attach(UUID kycFile, UUID documentId, String docKind, String bearer) throws Exception {
        return attach(kycFile, documentId, docKind, bearer, UUID.randomUUID());
    }

    private ResultActions attach(UUID kycFile, UUID documentId, String docKind, String bearer, UUID commandId)
            throws Exception {
        return mvc.perform(post("/kyc/{id}/documents", kycFile)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "document_id", documentId.toString(), "doc_kind", docKind))));
    }

    /** A comp_kyc_file row in 'submitted' for a persona, without driving the full onboarding flow. */
    private UUID seedKycFile(String subjectType) {
        UUID kycFileId = UUID.randomUUID();
        jdbc.update("INSERT INTO comp_kyc_file (kyc_file_id, subject_id, subject_type, status, submitted_by) "
                        + "VALUES (?, ?, ?::comp_kyc_subject_type, 'submitted', ?)",
                kycFileId, UUID.randomUUID(), subjectType, opsAdmin.adminUserId());
        return kycFileId;
    }

    /** M18 two-phase upload → a stored document_id (KYC docs go up as application/pdf, like buyer-KYB). */
    private UUID uploadKycDoc() throws Exception {
        byte[] pdf = ("%PDF-1.4\nkyc::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        UUID docId = initiate(pdf.length);
        mvc.perform(put("/documents/{id}/content", docId).header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_PDF).content(pdf)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/documents/{id}/finalize", docId).header("Authorization", "Bearer " + ops))
                .andExpect(status().is2xxSuccessful());
        return docId;
    }

    /** Initiate only (no upload/finalize) → the document stays pending_upload. */
    private UUID initiatePendingDoc() throws Exception {
        return initiate(512);
    }

    private UUID initiate(long declaredSize) throws Exception {
        String initBody = json.writeValueAsString(Map.of(
                "kind", "kyc", "content_type", "application/pdf", "declared_size", declaredSize));
        MvcResult init = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return UUID.fromString(node(init).get("document_id").asText());
    }

    private int activeCount(UUID kycFile) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM kyc_document WHERE kyc_file_id = ? AND status = 'active'::onboarding_doc_status",
                Integer.class, kycFile);
    }

    private int activeCountOfKind(UUID kycFile, String docKind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM kyc_document WHERE kyc_file_id = ? AND doc_kind = ?::kyc_doc_kind "
                        + "AND status = 'active'::onboarding_doc_status", Integer.class, kycFile, docKind);
    }

    // --- supplier onboarding driver (for OD.3), mirrors SupplierKycRejectionTest ----------------------

    private UUID supplierAtKycSubmitted(String opsBearer) throws Exception {
        UUID supplier = createSupplier(opsBearer);
        sendSupplier(post("/suppliers/{id}/record-identity-verified", supplier), opsBearer, supplier, Map.of())
                .andExpect(status().is2xxSuccessful());
        sendSupplier(post("/suppliers/{id}/submit-kyc", supplier), opsBearer, supplier, Map.of())
                .andExpect(status().is2xxSuccessful());
        return supplier;
    }

    private UUID createSupplier(String bearer) throws Exception {
        String pan = letters(5) + String.format("%04d", RND.nextInt(10000)) + letters(1);
        Map<String, Object> body = Map.of(
                "legal_name", "Supplier " + UUID.randomUUID(), "constitution_type", "private_limited",
                "pan", pan, "gstin", "27" + pan + "1Z5",
                "cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)));
        MvcResult res = mvc.perform(post("/suppliers/create")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    private ResultActions sendSupplier(MockHttpServletRequestBuilder builder, String bearer, UUID supplier,
                                       Map<String, Object> body) throws Exception {
        return mvc.perform(builder
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", UUID.randomUUID().toString())
                .header("X-Aggregate-Version", String.valueOf(jdbc.queryForObject(
                        "SELECT aggregate_version FROM sup_account WHERE supplier_id = ?", Integer.class, supplier)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append((char) ('A' + RND.nextInt(26)));
        }
        return sb.toString();
    }
}
