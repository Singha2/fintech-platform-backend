package com.arthvritt.platform.buyer;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
 * M20 · Buyer KYB verification (BC9) — RED tests (docs/modules/M20-onboarding-documents.md §0.1/OD.8, DL-BE-073).
 *
 * <p>Buyer KYB is <b>minimal and asymmetric</b> to investor/supplier KYC: no typed document set, no
 * checklist. It is a single <b>manual attestation</b> — a {@code kyb_verified} boolean an
 * <b>Ops Executive</b> sets — layered <b>on top of</b> the untouched automated {@code identity_verified}
 * (GSTIN + CIN, M8), with <b>at most one optional free-form custom document</b> attached via the generic
 * M18 {@code /documents} API.
 *
 * <p>Pure HTTP: RED until the implementer adds the V12 {@code buyer_account} columns
 * ({@code kyb_verified}, {@code kyb_verified_by}, {@code kyb_verified_at}, {@code kyb_document_id}), the
 * {@code .Buyer.RecordKybVerified} command + service method, and the
 * {@code POST/GET /buyers/{id}/kyb-verification} endpoints.
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li><b>OD.8</b> — one Ops Executive sets {@code kyb_verified=true}, stamping {@code kyb_verified_by}/{@code _at}
 *       (MFA + audit, idempotent; <i>not</i> maker-checker).</li>
 *   <li><b>OD.8 / §0.1</b> — attestation may reference a single optional custom {@code document_id}.</li>
 *   <li><b>#4</b> — idempotent on {@code command_id} (replay does not re-stamp).</li>
 *   <li><b>OD.3 / §0.1</b> — {@code kyb_verified} is independent of and coexists with the automated
 *       {@code identity_verified}; it gates no state transition (the buyer machine is untouched).</li>
 *   <li><b>OD.7</b> — the buyer is never a KYC subject: no {@code comp_kyc_file} row is created.</li>
 *   <li><b>role gate</b> — only {@code ops_executive} may attest (a non-ops principal → 403).</li>
 * </ul>
 *
 * <p><b>Scoping note (OD.5):</b> the restricted, compliance/ops-only download of the KYB custom doc
 * (investor → 403) is deferred with the shared onboarding-download authZ + the investor portal (there is
 * no investor login in Phase 1) — mirrors M19's DOC.6 scoping. This slice proves the attestation + the
 * optional-doc linkage; the custom doc's bytes are resolvable through the generic M18 surface already.
 */
class BuyerKybVerificationTest extends AbstractEdgeHttpTest {

    private static final Random RND = new Random();

    private Seeded opsAdmin;   // the attesting Ops Executive → kyb_verified_by
    private String ops;
    private String credit;     // nominate is a Credit Reviewer command; also the wrong-role principal for the gate test

    @BeforeEach
    void seedActors() {
        notifier.clear();
        opsAdmin = seedAdminWithRoles("ops_executive");
        ops = bearerFor(opsAdmin);
        credit = bearerFor(seedAdminWithRoles("credit_reviewer"));
    }

    // --- OD.8: an Ops Executive attestation sets the flag, stamps the actor, and touches nothing else ----

    @Test
    void ops_attestation_sets_kyb_verified_and_stamps_the_actor() throws Exception {
        UUID buyer = nominate();   // status = 'nominated', identity NOT yet automated-verified

        attest(buyer, ops, Map.of("verified", true)).andExpect(status().is2xxSuccessful());

        assertThat(kybVerified(buyer)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT kyb_verified_by FROM buyer_account WHERE buyer_id = ?", UUID.class, buyer))
                .isEqualTo(opsAdmin.identityId());
        assertThat(jdbc.queryForObject(
                "SELECT kyb_verified_at FROM buyer_account WHERE buyer_id = ?", OffsetDateTime.class, buyer))
                .isNotNull();
        // OD.3 / §0.1 — the attestation is non-gating: the buyer's own lifecycle is untouched.
        assertThat(statusOf(buyer)).isEqualTo("nominated");
        // OD.7 — the buyer never becomes a KYC subject.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comp_kyc_file WHERE subject_id = ?", Integer.class, buyer)).isZero();
    }

    // --- OD.8 / §0.1: the attestation may carry one optional custom document ----------------------------

    @Test
    void attestation_links_the_optional_custom_document() throws Exception {
        UUID buyer = nominate();
        UUID doc = uploadCustomDoc();

        attest(buyer, ops, Map.of("verified", true, "document_id", doc.toString()))
                .andExpect(status().is2xxSuccessful());

        assertThat(kybVerified(buyer)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT kyb_document_id FROM buyer_account WHERE buyer_id = ?", UUID.class, buyer))
                .isEqualTo(doc);
    }

    // --- #4: idempotent on command_id — a replay must not re-stamp -------------------------------------

    @Test
    void attestation_is_idempotent_on_command_id() throws Exception {
        UUID buyer = nominate();
        UUID commandId = UUID.randomUUID();
        int version = versionOf(buyer);

        attest(buyer, ops, commandId, version, Map.of("verified", true)).andExpect(status().is2xxSuccessful());
        OffsetDateTime stampedAt = jdbc.queryForObject(
                "SELECT kyb_verified_at FROM buyer_account WHERE buyer_id = ?", OffsetDateTime.class, buyer);

        // replay: same command_id (idempotency short-circuits before the version check) → 200, no re-stamp
        attest(buyer, ops, commandId, version, Map.of("verified", true)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT kyb_verified_at FROM buyer_account WHERE buyer_id = ?", OffsetDateTime.class, buyer))
                .isEqualTo(stampedAt);
    }

    // --- OD.3 / §0.1: kyb_verified coexists with the automated identity_verified (both true, independent) -

    @Test
    void kyb_verified_coexists_with_the_automated_identity_verified() throws Exception {
        UUID buyer = seedActiveBuyer(opsAdmin.adminUserId());   // already past the automated identity flow

        attest(buyer, ops, Map.of("verified", true)).andExpect(status().is2xxSuccessful());

        assertThat(kybVerified(buyer)).isTrue();
        assertThat(statusOf(buyer)).isEqualTo("active");   // the manual flag changed no lifecycle state
    }

    // --- role gate: only an Ops Executive may attest ---------------------------------------------------

    @Test
    void a_non_ops_principal_may_not_attest() throws Exception {
        UUID buyer = nominate();

        attest(buyer, credit, Map.of("verified", true)).andExpect(status().isForbidden());

        assertThat(kybVerified(buyer)).isFalse();
    }

    // --- read-back: the KYB verification is queryable --------------------------------------------------

    @Test
    void kyb_verification_is_readable() throws Exception {
        UUID buyer = nominate();
        UUID doc = uploadCustomDoc();
        attest(buyer, ops, Map.of("verified", true, "document_id", doc.toString()))
                .andExpect(status().is2xxSuccessful());

        mvc.perform(get("/buyers/{id}/kyb-verification", buyer).header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kyb_verified").value(true))
                .andExpect(jsonPath("$.kyb_verified_by").value(opsAdmin.identityId().toString()))
                .andExpect(jsonPath("$.kyb_document_id").value(doc.toString()));
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private ResultActions attest(UUID buyer, String bearer, Map<String, Object> body) throws Exception {
        return attest(buyer, bearer, UUID.randomUUID(), versionOf(buyer), body);
    }

    private ResultActions attest(UUID buyer, String bearer, UUID commandId, int version, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/buyers/{id}/kyb-verification", buyer)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString())
                .header("X-Aggregate-Version", String.valueOf(version))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    /** Nominate a buyer via the Credit Reviewer command (mirrors BuyerIdentityVerificationTest). */
    private UUID nominate() throws Exception {
        Map<String, Object> body = Map.of(
                "legal_name", "Buyer " + UUID.randomUUID(),
                "mca_cin", "U72200KA2020PTC" + String.format("%06d", RND.nextInt(1_000_000)),
                "gstin", validGstin(), "sector", "manufacturing");
        MvcResult res = mvc.perform(post("/buyers/nominate")
                        .header("Authorization", "Bearer " + credit)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(node(res).get("aggregate_id").asText());
    }

    /** The single optional KYB document — a free-form custom PDF via the generic M18 two-phase upload. */
    private UUID uploadCustomDoc() throws Exception {
        byte[] pdf = ("%PDF-1.4\nkyb::" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String initBody = json.writeValueAsString(Map.of(
                "kind", "kyb_custom", "content_type", "application/pdf", "declared_size", pdf.length));
        MvcResult init = mvc.perform(post("/documents")
                        .header("Authorization", "Bearer " + ops)
                        .header("X-Command-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(initBody))
                .andExpect(status().is2xxSuccessful()).andReturn();
        UUID docId = UUID.fromString(node(init).get("document_id").asText());
        mvc.perform(put("/documents/{id}/content", docId).header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_PDF).content(pdf)).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/documents/{id}/finalize", docId).header("Authorization", "Bearer " + ops))
                .andExpect(status().is2xxSuccessful());
        return docId;
    }

    private UUID seedActiveBuyer(UUID nominatedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_account (buyer_id, legal_name, status, credit_limit_paise, nominated_by) "
                        + "VALUES (?, ?, 'active', ?, ?)", id, "Buyer " + id, 5_00_00_000_00L, nominatedBy);
        return id;
    }

    private static String validGstin() {
        StringBuilder pan = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            pan.append((char) ('A' + RND.nextInt(26)));
        }
        pan.append(String.format("%04d", RND.nextInt(10000))).append((char) ('A' + RND.nextInt(26)));
        return "27" + pan + "1Z5";
    }

    private boolean kybVerified(UUID buyer) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT kyb_verified FROM buyer_account WHERE buyer_id = ?", Boolean.class, buyer));
    }

    private String statusOf(UUID buyer) {
        return jdbc.queryForObject("SELECT status::text FROM buyer_account WHERE buyer_id = ?", String.class, buyer);
    }

    private int versionOf(UUID buyer) {
        return jdbc.queryForObject("SELECT aggregate_version FROM buyer_account WHERE buyer_id = ?",
                Integer.class, buyer);
    }
}
