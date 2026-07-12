package com.arthvritt.platform.compliance;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.document.DocMeta;
import com.arthvritt.platform.document.DocumentPort;
import com.arthvritt.platform.shared.Ids;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC11 layer-4 — onboarding KYC documents (M20, DL-BE-073). Attaches an already-{@code stored} M18
 * {@code document_id} to an existing {@code comp_kyc_file}, typed by {@code doc_kind}. Capture-only:
 * nothing is mandatory and no gate reads this table (OD.3) — the suggested-kind list + coverage view are
 * purely advisory; Ops decides completeness of the KYC packet at approval time. Mirrors M19
 * {@link com.arthvritt.platform.listing.InvoiceDocumentService} (typed link table + {@link DocumentPort}
 * stored-gate). This service never writes {@code sys_document} directly — BC16 is reached only through
 * {@link DocumentPort} (bounded-context isolation, ARCH.1).
 */
@Service
public class KycDocumentService {

    private static final String CONTEXT = "compliance";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final DocumentPort documents;

    public KycDocumentService(JdbcTemplate jdbc, CommandGateway gateway, DocumentPort documents) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.documents = documents;
    }

    /** Attach — OD.1 (typed link to a stored document) / OD.6 (one active per kind, partial unique). */
    public CommandResult<Void> attach(CommandRequest request, UUID kycFileId, UUID documentId, String docKind) {
        return gateway.execute(request, OPS, () -> {
            requireStored(documentId);

            try {
                jdbc.update("INSERT INTO kyc_document (kyc_document_id, kyc_file_id, document_id, doc_kind, "
                                + "uploaded_by) VALUES (?, ?, ?, ?::kyc_doc_kind, ?)",
                        Ids.newId(), kycFileId, documentId, docKind, request.actorId());
            } catch (DuplicateKeyException e) {
                throw new ValidationException(
                        "a document of kind '" + docKind + "' is already attached (active) to kyc file "
                                + kycFileId + ", or this document is already linked");
            }
            documents.claimOwner(documentId, "bc11_compliance", "KycFile:" + kycFileId);

            CommandEvent event = new CommandEvent(CONTEXT + ".Onboarding.DocumentAttached", 1,
                    Map.of("kyc_file_id", kycFileId.toString(), "document_id", documentId.toString(),
                            "doc_kind", docKind),
                    Map.of(), Map.of("document_id", documentId.toString()), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /** Supersede (OD.6) — old link → superseded, new link → active (same doc_kind). */
    public CommandResult<Void> supersede(CommandRequest request, UUID kycFileId, UUID kycDocumentId,
                                         UUID newDocumentId) {
        return gateway.execute(request, OPS, () -> {
            String docKind = activeDocKind(kycFileId, kycDocumentId);
            requireStored(newDocumentId);

            // Order matters for the partial unique index: free the slot before inserting the replacement.
            int superseded = jdbc.update("UPDATE kyc_document SET status = 'superseded'::onboarding_doc_status, "
                            + "superseded_by = ? WHERE kyc_document_id = ? AND status = 'active'::onboarding_doc_status",
                    newDocumentId, kycDocumentId);
            if (superseded != 1) {
                throw new ValidationException("no active kyc document " + kycDocumentId
                        + " to supersede for kyc file " + kycFileId);
            }
            jdbc.update("INSERT INTO kyc_document (kyc_document_id, kyc_file_id, document_id, doc_kind, "
                            + "uploaded_by) VALUES (?, ?, ?, ?::kyc_doc_kind, ?)",
                    Ids.newId(), kycFileId, newDocumentId, docKind, request.actorId());
            documents.claimOwner(newDocumentId, "bc11_compliance", "KycFile:" + kycFileId);

            CommandEvent event = new CommandEvent(CONTEXT + ".Onboarding.DocumentSuperseded", 1,
                    Map.of("kyc_file_id", kycFileId.toString(), "old_kyc_document_id", kycDocumentId.toString(),
                            "new_document_id", newDocumentId.toString(), "doc_kind", docKind),
                    Map.of(), Map.of(), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /** OD.4: runtime-editable suggested-list entry. {@code mandatory} stays FALSE always (Ops decides). */
    public CommandResult<Void> setRequirement(CommandRequest request, String subjectType, String docKind,
                                              boolean active) {
        return gateway.execute(request, OPS, () -> {
            jdbc.update("INSERT INTO onboarding_doc_requirement (subject_type, doc_kind, mandatory, active) "
                            + "VALUES (?::onboarding_subject_type, ?::kyc_doc_kind, FALSE, ?) "
                            + "ON CONFLICT (subject_type, doc_kind) DO UPDATE SET active = EXCLUDED.active",
                    subjectType, docKind, active);

            CommandEvent event = new CommandEvent(CONTEXT + ".Onboarding.RequirementSet", 1,
                    Map.of("subject_type", subjectType, "doc_kind", docKind, "active", active),
                    Map.of(), Map.of(), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /** Link metadata only (no bytes) — every doc link ever attached to this kyc file, oldest first. */
    public List<Map<String, Object>> list(UUID kycFileId) {
        return jdbc.query(
                "SELECT kyc_document_id, document_id, doc_kind::text AS doc_kind, status::text AS status "
                        + "FROM kyc_document WHERE kyc_file_id = ? ORDER BY uploaded_at",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kyc_document_id", rs.getObject("kyc_document_id", UUID.class).toString());
                    row.put("document_id", rs.getObject("document_id", UUID.class).toString());
                    row.put("doc_kind", rs.getString("doc_kind"));
                    row.put("status", rs.getString("status"));
                    return row;
                },
                kycFileId);
    }

    /** OD.4: advisory coverage — suggested kinds for the file's subject type vs. active-attached kinds. */
    public Map<String, Boolean> coverage(UUID kycFileId) {
        String subjectType = jdbc.query(
                "SELECT subject_type::text AS subject_type FROM comp_kyc_file WHERE kyc_file_id = ?",
                rs -> rs.next() ? rs.getString("subject_type") : null,
                kycFileId);
        if (subjectType == null) {
            throw new NotFoundException("kyc file not found: " + kycFileId);
        }

        List<String> suggested = jdbc.query(
                "SELECT doc_kind::text AS doc_kind FROM onboarding_doc_requirement "
                        + "WHERE subject_type = ?::onboarding_subject_type AND active = TRUE",
                (rs, n) -> rs.getString("doc_kind"),
                subjectType);

        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String docKind : suggested) {
            Integer covered = jdbc.queryForObject(
                    "SELECT count(*) FROM kyc_document WHERE kyc_file_id = ? AND doc_kind = ?::kyc_doc_kind "
                            + "AND status = 'active'::onboarding_doc_status",
                    Integer.class, kycFileId, docKind);
            out.put(docKind, covered != null && covered > 0);
        }
        return out;
    }

    /** The suggested-list configuration for a subject type. */
    public List<Map<String, Object>> requirements(String subjectType) {
        return jdbc.query(
                "SELECT subject_type::text AS subject_type, doc_kind::text AS doc_kind, mandatory, active "
                        + "FROM onboarding_doc_requirement WHERE subject_type = ?::onboarding_subject_type "
                        + "ORDER BY doc_kind",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("subject_type", rs.getString("subject_type"));
                    row.put("doc_kind", rs.getString("doc_kind"));
                    row.put("mandatory", rs.getBoolean("mandatory"));
                    row.put("active", rs.getBoolean("active"));
                    return row;
                },
                subjectType);
    }

    // --- shared guards -------------------------------------------------------------------------------

    /** OD.1: the referenced document must resolve, in BC16, to a stored document. */
    private void requireStored(UUID documentId) {
        DocMeta meta = documents.resolve(documentId)
                .orElseThrow(() -> new ValidationException("document not found: " + documentId));
        if (!"stored".equals(meta.status())) {
            throw new ValidationException("document is not stored: " + documentId + " (is " + meta.status() + ")");
        }
    }

    private String activeDocKind(UUID kycFileId, UUID kycDocumentId) {
        String docKind = jdbc.query(
                "SELECT doc_kind::text AS doc_kind FROM kyc_document "
                        + "WHERE kyc_document_id = ? AND kyc_file_id = ? AND status = 'active'::onboarding_doc_status",
                rs -> rs.next() ? rs.getString("doc_kind") : null,
                kycDocumentId, kycFileId);
        if (docKind == null) {
            throw new NotFoundException("no active kyc document " + kycDocumentId + " for kyc file " + kycFileId);
        }
        return docKind;
    }
}
