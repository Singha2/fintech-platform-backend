package com.arthvritt.platform.document;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M18b · Documents two-phase upload API — RED HTTP tests (docs/modules/M18-documents.md §2/§7, DL-BE-072/075).
 *
 * <p>Drives the generic {@code /documents} surface an operator/UI uploads through:
 * {@code POST /documents} (initiate → {@code pending_upload}) → {@code PUT /documents/{id}/content} (raw
 * {@code application/pdf} body) → {@code POST /documents/{id}/finalize} (→ {@code stored}), then
 * {@code GET /documents/{id}} (metadata) + {@code GET /documents/{id}/content} (bytes). Pure HTTP: RED until
 * the implementer adds {@code DocumentController} + the {@code initiate}/{@code uploadContent}/{@code finalize}
 * service methods (endpoints 404 today).
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li><b>round-trip</b> — the three steps produce a downloadable stored document with {@code doc_hash = SHA-256}.</li>
 *   <li><b>idempotency</b> — initiate is idempotent on {@code X-Command-Id} (same {@code document_id}); finalize is
 *       idempotent on {@code document_id}.</li>
 *   <li><b>STATUS-1 at the edge</b> — finalize before any upload is rejected; the row stays {@code pending_upload}.</li>
 *   <li><b>I4</b> — a non-PDF content-type and an over-cap body are rejected (guard reads {@code documents.max-upload-bytes}).</li>
 *   <li><b>DO.2 / control #5</b> — initiate + finalize audit envelopes, and none carries the raw bytes.</li>
 * </ul>
 *
 * <p>Uploads here are authenticated by the ordinary session bearer (I3 — no separate upload token until the
 * GCS/presigned path, M18c). The cap is pinned tiny for the size test; production default is 20 MB.
 */
@TestPropertySource(properties = "documents.max-upload-bytes=2048")
class DocumentApiTest extends AbstractEdgeHttpTest {

    private String bearer;

    @BeforeEach
    void seed() {
        notifier.clear();
        bearer = bearerFor(seedAdminWithRoles("ops_executive"));
    }

    // --- the two-phase round-trip: initiate → upload → finalize → resolve → download --------------------

    @Test
    void round_trip_initiate_upload_finalize_then_resolve_and_download() throws Exception {
        byte[] pdf = pdf("round-trip");

        UUID id = initiate("invoice", "application/pdf", pdf.length);
        assertThat(rowStatus(id)).isEqualTo("pending_upload");   // nothing stored yet

        upload(id, MediaType.APPLICATION_PDF, pdf).andExpect(status().is2xxSuccessful());
        finalize(id).andExpect(status().is2xxSuccessful());

        // metadata reads stored, with the size + hash filled in
        assertThat(rowStatus(id)).isEqualTo("stored");
        assertThat(jdbc.queryForObject("SELECT byte_size FROM sys_document WHERE document_id = ?", Long.class, id))
                .isEqualTo((long) pdf.length);
        assertThat(jdbc.queryForObject("SELECT doc_hash FROM sys_document WHERE document_id = ?", byte[].class, id))
                .isEqualTo(sha256(pdf));
        mvc.perform(get("/documents/{id}", id).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());

        // the bytes come back exactly
        byte[] body = mvc.perform(get("/documents/{id}/content", id).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(pdf);

        assertThat(envelopes("documents.Document.Stored", id)).isEqualTo(1);
    }

    // --- idempotency: initiate on X-Command-Id, finalize on document_id ---------------------------------

    @Test
    void initiate_is_idempotent_on_command_id() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID first = initiateWithCommandId("invoice", "application/pdf", 100, commandId);
        UUID replay = initiateWithCommandId("invoice", "application/pdf", 100, commandId);

        assertThat(replay).isEqualTo(first);   // deterministic document_id, no second handle
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_document WHERE document_id = ?", Integer.class, first))
                .isEqualTo(1);
    }

    @Test
    void finalize_is_idempotent_on_document_id() throws Exception {
        byte[] pdf = pdf("idem-finalize");
        UUID id = initiate("invoice", "application/pdf", pdf.length);
        upload(id, MediaType.APPLICATION_PDF, pdf).andExpect(status().is2xxSuccessful());

        finalize(id).andExpect(status().is2xxSuccessful());
        finalize(id).andExpect(status().is2xxSuccessful());   // no-op replay

        assertThat(rowStatus(id)).isEqualTo("stored");
        assertThat(envelopes("documents.Document.Stored", id)).isEqualTo(1);   // exactly one Stored event
    }

    // --- STATUS-1 at the edge: cannot finalize a document with no uploaded bytes ------------------------

    @Test
    void finalize_before_any_upload_is_rejected_and_stays_pending() throws Exception {
        UUID id = initiate("invoice", "application/pdf", 500);

        finalize(id).andExpect(status().is4xxClientError());

        assertThat(rowStatus(id)).isEqualTo("pending_upload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_document_blob WHERE document_id = ?", Integer.class, id))
                .isZero();
    }

    // --- I4: malformed uploads are rejected, the document stays pending ---------------------------------

    @Test
    void upload_with_non_pdf_content_type_is_rejected() throws Exception {
        UUID id = initiate("invoice", "application/pdf", 50);

        upload(id, MediaType.TEXT_PLAIN, "not a pdf".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().is4xxClientError());

        assertThat(rowStatus(id)).isEqualTo("pending_upload");
    }

    @Test
    void upload_over_the_size_cap_is_rejected() throws Exception {
        UUID id = initiate("invoice", "application/pdf", 4096);
        byte[] tooBig = new byte[4096];   // cap pinned to 2048 for this test class

        upload(id, MediaType.APPLICATION_PDF, tooBig).andExpect(status().is4xxClientError());

        assertThat(rowStatus(id)).isEqualTo("pending_upload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_document_blob WHERE document_id = ?", Integer.class, id))
                .isZero();
    }

    // --- DO.2: audit envelopes carry ids/hash, never the raw bytes --------------------------------------

    @Test
    void audit_envelopes_never_carry_the_raw_bytes() throws Exception {
        String marker = "MARKER-" + UUID.randomUUID();
        byte[] pdf = ("%PDF-1.4\n" + marker).getBytes(StandardCharsets.UTF_8);

        UUID id = initiate("invoice", "application/pdf", pdf.length);
        upload(id, MediaType.APPLICATION_PDF, pdf).andExpect(status().is2xxSuccessful());
        finalize(id).andExpect(status().is2xxSuccessful());

        assertThat(envelopes("documents.Document.Initiated", id)).isEqualTo(1);
        assertThat(envelopes("documents.Document.Stored", id)).isEqualTo(1);
        String payloads = jdbc.queryForObject(
                "SELECT string_agg(payload::text, '|') FROM sys_audit_event WHERE aggregate_id = ?",
                String.class, id);
        assertThat(payloads).doesNotContain(marker);
    }

    // --- unknown document -------------------------------------------------------------------------------

    @Test
    void get_unknown_document_is_404() throws Exception {
        mvc.perform(get("/documents/{id}", UUID.randomUUID()).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
    }

    // --- initiate body validation (the requiredLong / requiredString edge, B4 400) -----------------------

    @Test
    void initiate_without_declared_size_is_rejected() throws Exception {
        String body = json.writeValueAsString(Map.of("kind", "invoice", "content_type", "application/pdf"));

        mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void initiate_with_a_non_numeric_declared_size_is_rejected() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", "application/pdf", "declared_size", "not-a-number"));

        mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void initiate_without_kind_is_rejected() throws Exception {
        String body = json.writeValueAsString(Map.of("content_type", "application/pdf", "declared_size", 100));

        mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("validation_failed"));
    }

    @Test
    void initiate_without_a_command_id_header_is_rejected() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", "application/pdf", "declared_size", 100));

        mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("missing_header"));
    }

    // --- upload / finalize against a document that was never initiated -----------------------------------

    @Test
    void upload_to_an_unknown_document_is_404() throws Exception {
        upload(UUID.randomUUID(), MediaType.APPLICATION_PDF, pdf("orphan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("not_found"));
    }

    @Test
    void finalize_of_an_unknown_document_is_404() throws Exception {
        finalize(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("not_found"));
    }

    // --- STORE-1: bytes are unreadable until the document is finalized (stored) --------------------------

    @Test
    void content_download_before_finalize_is_404() throws Exception {
        byte[] pdf = pdf("uploaded-not-final");
        UUID id = initiate("invoice", "application/pdf", pdf.length);
        upload(id, MediaType.APPLICATION_PDF, pdf).andExpect(status().is2xxSuccessful());   // uploaded, still pending

        mvc.perform(get("/documents/{id}/content", id).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());

        assertThat(rowStatus(id)).isEqualTo("pending_upload");
    }

    // --- metadata body carries the resolvable view (kind/status/content_type/byte_size), never bytes -----

    @Test
    void resolve_returns_the_metadata_view_after_finalize() throws Exception {
        byte[] pdf = pdf("meta");
        UUID id = initiate("invoice", "application/pdf", pdf.length);
        upload(id, MediaType.APPLICATION_PDF, pdf).andExpect(status().is2xxSuccessful());
        finalize(id).andExpect(status().is2xxSuccessful());

        mvc.perform(get("/documents/{id}", id).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value(id.toString()))
                .andExpect(jsonPath("$.kind").value("invoice"))
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.content_type").value("application/pdf"))
                .andExpect(jsonPath("$.byte_size").value(pdf.length));
    }

    // --- I3: the upload surface is session-authenticated; an anonymous caller is turned away -------------

    @Test
    void an_unauthenticated_initiate_is_rejected() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "kind", "invoice", "content_type", "application/pdf", "declared_size", 100));

        mvc.perform(post("/documents")
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private UUID initiate(String kind, String contentType, long declaredSize) throws Exception {
        return initiateWithCommandId(kind, contentType, declaredSize, UUID.randomUUID());
    }

    private UUID initiateWithCommandId(String kind, String contentType, long declaredSize, UUID commandId)
            throws Exception {
        String body = json.writeValueAsString(Map.of(
                "kind", kind, "content_type", contentType, "declared_size", declaredSize));
        String response = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + bearer)
                        .header("X-Command-Id", commandId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("document_id").asText());
    }

    private ResultActions upload(UUID id, MediaType contentType, byte[] bytes) throws Exception {
        return mvc.perform(put("/documents/{id}/content", id)
                .header("Authorization", "Bearer " + bearer)
                .contentType(contentType).content(bytes));
    }

    private ResultActions finalize(UUID id) throws Exception {
        return mvc.perform(post("/documents/{id}/finalize", id)
                .header("Authorization", "Bearer " + bearer));
    }

    private String rowStatus(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM sys_document WHERE document_id = ?", String.class, id);
    }

    private int envelopes(String eventType, UUID aggregateId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, aggregateId);
    }

    private static byte[] pdf(String tag) {
        return ("%PDF-1.4\n" + tag + "::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
