package com.arthvritt.platform.tax;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M16 Form 16A issuance (BC12, DL-BE-069). A single Compliance command (not maker-checker, mirrors
 * {@link com.arthvritt.platform.settlement.MaturityService}) renders the investor's annual TDS
 * certificate from the frozen {@code tax_year_profile} cumulatives + {@code tax_tds_deduction} lines,
 * registers the document (BC16), and stamps the profile issued. Download re-renders deterministically
 * and verifies the stored hash.
 */
class Form16aTest extends AbstractEdgeHttpTest {

    private static final String FY = "FY2026-27";

    private UUID investorId;
    private UUID listingId;
    private UUID adminUserId;
    private String compliance;

    @BeforeEach
    void seed() {
        notifier.clear();
        Seeded complianceAdmin = seedAdminWithRoles("compliance_reviewer");
        compliance = bearerFor(complianceAdmin);
        adminUserId = complianceAdmin.adminUserId();
        investorId = seedActiveInvestor("ABCDE1234F");
        listingId = UUID.randomUUID();
        seedTaxYearProfile(investorId, FY, 1000, true, 6_00_000L, 60_000L);
        seedTdsDeduction(investorId, listingId, FY, 6_00_000L, 60_000L, 5_40_000L, "CHLN-1");
    }

    @Test
    void issue_stamps_the_profile_writes_the_statement_and_the_document_and_audits() throws Exception {
        issue(compliance, investorId, FY).andExpect(status().isOk());

        assertThat(formIssued(investorId, FY)).isTrue();
        byte[] storedHash = jdbc.queryForObject(
                "SELECT form_16a_doc_hash FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                byte[].class, investorId, FY);
        assertThat(storedHash).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT form_16a_issued_at IS NOT NULL FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                Boolean.class, investorId, FY)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tax_investor_statement WHERE investor_id = ? AND period = ? AND kind = 'form_16a'::tax_investor_statement_kind",
                Integer.class, investorId, FY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_document_object WHERE doc_hash = ?",
                Integer.class, (Object) storedHash)).isEqualTo(1);

        assertThat(envelopes(investorId, FY)).isEqualTo(1);
    }

    @Test
    void download_returns_bytes_whose_hash_matches_the_stored_doc_hash() throws Exception {
        issue(compliance, investorId, FY).andExpect(status().isOk());

        byte[] body = mvc.perform(get("/investors/{id}/tax/form-16a/{fy}", investorId, FY)
                        .header("Authorization", "Bearer " + compliance))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] storedHash = jdbc.queryForObject(
                "SELECT form_16a_doc_hash FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                byte[].class, investorId, FY);
        assertThat(sha256(body)).isEqualTo(storedHash);
    }

    @Test
    void download_returns_the_frozen_certificate_even_after_fy_totals_change() throws Exception {
        // Issue, capturing the certificate the investor was given.
        issue(compliance, investorId, FY).andExpect(status().isOk());
        byte[] original = mvc.perform(get("/investors/{id}/tax/form-16a/{fy}", investorId, FY)
                        .header("Authorization", "Bearer " + compliance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        // Simulate a later same-FY distribution: the profile cumulatives bump and a new deduction line lands.
        jdbc.update("UPDATE tax_year_profile SET cumulative_gross_paise = cumulative_gross_paise + 4_00_000, "
                + "cumulative_tds_paise = cumulative_tds_paise + 40_000 WHERE investor_id = ? AND fy_code = ?",
                investorId, FY);
        seedTdsDeduction(investorId, UUID.randomUUID(), FY, 4_00_000L, 40_000L, 3_60_000L, "CHLN-2");

        // download must still return the ORIGINAL frozen bytes — not a 500, not a re-derivation.
        byte[] afterChange = mvc.perform(get("/investors/{id}/tax/form-16a/{fy}", investorId, FY)
                        .header("Authorization", "Bearer " + compliance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(afterChange).isEqualTo(original);
    }

    @Test
    void issue_is_idempotent_on_command_id() throws Exception {
        UUID commandId = UUID.randomUUID();
        mvc.perform(issueBuilder(compliance, investorId, FY, commandId)).andExpect(status().isOk());
        mvc.perform(issueBuilder(compliance, investorId, FY, commandId)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tax_investor_statement WHERE investor_id = ? AND period = ?",
                Integer.class, investorId, FY)).isEqualTo(1);
        assertThat(envelopes(investorId, FY)).isEqualTo(1);
    }

    @Test
    void reissuing_an_already_issued_fy_with_a_new_command_id_is_rejected() throws Exception {
        issue(compliance, investorId, FY).andExpect(status().isOk());
        issue(compliance, investorId, FY).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tax_investor_statement WHERE investor_id = ? AND period = ?",
                Integer.class, investorId, FY)).isEqualTo(1);
    }

    @Test
    void issue_by_a_non_compliance_actor_is_403() throws Exception { // SoD
        String ops = bearerFor(seedAdminWithRoles("ops_executive"));
        issue(ops, investorId, FY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("role_not_held"));
        assertThat(formIssued(investorId, FY)).isFalse();
    }

    @Test
    void deductions_and_statements_return_the_seeded_rows() throws Exception {
        issue(compliance, investorId, FY).andExpect(status().isOk());

        mvc.perform(get("/investors/{id}/tax/deductions", investorId)
                        .header("Authorization", "Bearer " + compliance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listing_id").value(listingId.toString()))
                .andExpect(jsonPath("$[0].gross_paise").value(6_00_000))
                .andExpect(jsonPath("$[0].tds_amount_paise").value(60_000))
                .andExpect(jsonPath("$[0].net_paise").value(5_40_000));

        mvc.perform(get("/investors/{id}/tax/statements", investorId)
                        .header("Authorization", "Bearer " + compliance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].period").value(FY))
                .andExpect(jsonPath("$[0].kind").value("form_16a"));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions issue(String bearer, UUID investor, String fy)
            throws Exception {
        return mvc.perform(issueBuilder(bearer, investor, fy, UUID.randomUUID()));
    }

    private MockHttpServletRequestBuilder issueBuilder(String bearer, UUID investor, String fy, UUID commandId) {
        return post("/investors/{investorId}/tax/form-16a/{fy}/issue", investor, fy)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Command-Id", commandId.toString());
    }

    private boolean formIssued(UUID investor, String fy) {
        return jdbc.queryForObject("SELECT form_16a_issued FROM tax_year_profile WHERE investor_id = ? AND fy_code = ?",
                Boolean.class, investor, fy);
    }

    /** Same derivation as {@link Form16aController#issue} — scopes the envelope count to this investor/FY,
     *  since the audit table is not truncated between test methods (shared Testcontainers instance). */
    private int envelopes(UUID investor, String fy) {
        UUID aggregateId = UUID.nameUUIDFromBytes(("form16a:" + investor + ":" + fy).getBytes(StandardCharsets.UTF_8));
        return jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE event_type = 'tax.TaxYearProfile.Form16aIssued' "
                        + "AND aggregate_id = ?",
                Integer.class, aggregateId);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedTaxYearProfile(UUID investor, String fy, int rateBps, boolean panVerified,
                                    long cumulativeGross, long cumulativeTds) {
        jdbc.update("INSERT INTO tax_year_profile (investor_id, fy_code, tds_rate_bps, pan_verified, "
                        + "cumulative_gross_paise, cumulative_tds_paise) VALUES (?, ?, ?, ?, ?, ?)",
                investor, fy, rateBps, panVerified, cumulativeGross, cumulativeTds);
    }

    private void seedTdsDeduction(UUID investor, UUID listing, String fy, long gross, long tds, long net,
                                  String challanRef) {
        jdbc.update("INSERT INTO tax_tds_deduction (tds_deduction_id, investor_id, listing_id, fy_code, "
                        + "payout_instruction_id, challan_ref, gross_paise, tds_amount_paise, fee_paise, net_paise) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                UUID.randomUUID(), investor, listing, fy, UUID.randomUUID(), challanRef, gross, tds, net);
    }

    private UUID seedActiveInvestor(String pan) {
        UUID identityId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        UUID investor = UUID.randomUUID();
        String email = "inv-" + investor + "@arthvritt.test";
        jdbc.update("INSERT INTO auth_identity (identity_id, kind, email, phone_e164, display_name, status) "
                        + "VALUES (?, 'investor'::identity_kind_enum, ?, ?, 'Investor', 'active'::identity_status_enum)",
                identityId, email, phone());
        jdbc.update("INSERT INTO inv_invite (invite_id, email_hash, phone_hash, issued_by, expiry_at, status) "
                        + "VALUES (?, ?, ?, ?, now() + interval '14 days', 'pending')",
                inviteId, email.getBytes(StandardCharsets.UTF_8), "p".getBytes(StandardCharsets.UTF_8), adminUserId);
        jdbc.update("INSERT INTO inv_account (investor_id, identity_id, invite_id, sub_type, status, pan) "
                        + "VALUES (?, ?, ?, 'resident_individual'::inv_sub_type, 'active'::inv_account_status, ?)",
                investor, identityId, inviteId, pan);
        return investor;
    }
}
