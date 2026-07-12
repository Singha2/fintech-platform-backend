package com.arthvritt.platform.listing;

import com.arthvritt.platform.adminiam.AdminRole;
import com.arthvritt.platform.command.CommandEvent;
import com.arthvritt.platform.command.CommandGateway;
import com.arthvritt.platform.command.CommandOutcome;
import com.arthvritt.platform.command.CommandRequest;
import com.arthvritt.platform.command.CommandResult;
import com.arthvritt.platform.document.DocMeta;
import com.arthvritt.platform.document.DocumentPort;
import com.arthvritt.platform.shared.error.NotFoundException;
import com.arthvritt.platform.shared.error.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BC1 layer-4 — the invoice-artifact linkage over the BC16 document service (M19, DL-BE-071). Attach
 * and supersede are ordinary {@link CommandGateway} commands (idempotent on {@code command_id}, MFA-fresh,
 * SoD, audited); maker-checker is satisfied via the existing {@code document_completeness} ops-check
 * (§0.3, wired in {@link ListingService#recordOpsCheck}), not a per-command checker here. This service
 * never writes {@code sys_document} directly — BC16 is reached only through {@link DocumentPort}
 * (bounded-context isolation, ARCH.1).
 */
@Service
public class InvoiceDocumentService {

    private static final String CONTEXT = "listing";
    private static final Set<String> OPS = Set.of(AdminRole.OPS_EXECUTIVE.wire());
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    /** DOC.4: the artifact set may be attached/replaced only before the pricing snapshot freezes it. */
    private static final Set<String> ATTACHABLE_LISTING_STATUSES =
            Set.of("draft", "operational_checks_in_progress", "awaiting_acknowledgment");

    /** DOC.6: download eligibility — the listing's live-set (investor-KYC gate deferred, see class docs). */
    private static final Set<String> DOWNLOADABLE_LISTING_STATUSES =
            Set.of("live", "fully_funded", "disbursed", "matured_payment_received", "distributed");

    private final JdbcTemplate jdbc;
    private final CommandGateway gateway;
    private final DocumentPort documents;

    public InvoiceDocumentService(JdbcTemplate jdbc, CommandGateway gateway, DocumentPort documents) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.documents = documents;
    }

    /** Attach — DOC.1 (declarative, one active per invoice) / DOC.4 (freeze) / DOC.5 (must be a stored PDF). */
    public CommandResult<Void> attach(CommandRequest request, UUID listingId, UUID documentId) {
        return gateway.execute(request, OPS, () -> {
            ListingRef listing = loadListingRef(listingId);
            requireAttachable(listing, listingId);
            requireStoredPdf(documentId);

            try {
                jdbc.update("INSERT INTO deal_invoice_document (invoice_id, document_id, uploaded_by) "
                                + "VALUES (?, ?, ?)",
                        listing.invoiceId(), documentId, request.actorId());
            } catch (DuplicateKeyException e) {
                throw new ValidationException(
                        "an active invoice artifact already exists for invoice " + listing.invoiceId());
            }
            documents.claimOwner(documentId, "bc1_listing", "Invoice:" + listing.invoiceId());

            CommandEvent event = new CommandEvent(CONTEXT + ".InvoiceArtifact.Attached", 1,
                    Map.of("listing_id", listingId.toString(), "invoice_id", listing.invoiceId().toString(),
                            "document_id", documentId.toString()),
                    Map.of(), Map.of("document_id", documentId.toString()), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /** Supersede (DOC.7) — old artifact → superseded, new → active. Same freeze/PDF/stored guards as attach. */
    public CommandResult<Void> replace(CommandRequest request, UUID listingId, UUID oldDocumentId, UUID newDocumentId) {
        return gateway.execute(request, OPS, () -> {
            ListingRef listing = loadListingRef(listingId);
            requireAttachable(listing, listingId);
            requireStoredPdf(newDocumentId);

            int superseded = jdbc.update("UPDATE deal_invoice_document SET "
                            + "status = 'superseded'::deal_invoice_doc_status, superseded_by = ? "
                            + "WHERE invoice_id = ? AND document_id = ? AND status = 'active'::deal_invoice_doc_status",
                    newDocumentId, listing.invoiceId(), oldDocumentId);
            if (superseded != 1) {
                throw new ValidationException("no active invoice artifact " + oldDocumentId
                        + " to supersede for listing " + listingId);
            }
            jdbc.update("INSERT INTO deal_invoice_document (invoice_id, document_id, uploaded_by) VALUES (?, ?, ?)",
                    listing.invoiceId(), newDocumentId, request.actorId());
            documents.claimOwner(newDocumentId, "bc1_listing", "Invoice:" + listing.invoiceId());

            CommandEvent event = new CommandEvent(CONTEXT + ".InvoiceArtifact.Superseded", 1,
                    Map.of("listing_id", listingId.toString(), "invoice_id", listing.invoiceId().toString(),
                            "old_document_id", oldDocumentId.toString(), "new_document_id", newDocumentId.toString()),
                    Map.of(), Map.of(), true);
            return new CommandOutcome<>(null, event);
        });
    }

    /** Link metadata only (no bytes) — every artifact ever attached to this invoice, newest first. */
    public List<Map<String, Object>> list(UUID listingId) {
        ListingRef listing = loadListingRef(listingId);
        List<ArtifactLink> links = jdbc.query(
                "SELECT document_id, status::text AS status FROM deal_invoice_document "
                        + "WHERE invoice_id = ? ORDER BY uploaded_at DESC",
                (rs, n) -> new ArtifactLink(rs.getObject("document_id", UUID.class), rs.getString("status")),
                listing.invoiceId());

        List<Map<String, Object>> out = new ArrayList<>(links.size());
        for (ArtifactLink link : links) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("document_id", link.documentId().toString());
            row.put("status", link.status());
            documents.resolve(link.documentId()).ifPresent(meta -> {
                row.put("kind", meta.kind());
                row.put("content_type", meta.contentType());
                row.put("byte_size", meta.byteSize());
                row.put("document_status", meta.status());
            });
            out.add(row);
        }
        return out;
    }

    /**
     * Download — DOC.6: only when the listing is in the live-set (the KYC'd-investor gate via
     * {@code InvestorQueryPort} is deferred with the investor portal — see M19 §0.2 / class docs).
     */
    public byte[] download(UUID listingId, UUID documentId) {
        ListingRef listing = loadListingRef(listingId);
        if (!DOWNLOADABLE_LISTING_STATUSES.contains(listing.status())) {
            throw new ValidationException("listing is not eligible for document download: " + listingId
                    + " (is " + listing.status() + ")");
        }
        Integer linked = jdbc.queryForObject(
                "SELECT count(*) FROM deal_invoice_document WHERE invoice_id = ? AND document_id = ?",
                Integer.class, listing.invoiceId(), documentId);
        if (linked == null || linked == 0) {
            throw new NotFoundException("document " + documentId + " is not attached to listing " + listingId);
        }
        return documents.retrieve(documentId);
    }

    // --- shared guards -------------------------------------------------------------------------------

    private void requireAttachable(ListingRef listing, UUID listingId) {
        if (!ATTACHABLE_LISTING_STATUSES.contains(listing.status())) {
            throw new ValidationException("the invoice artifact set is frozen for listing " + listingId
                    + " (status " + listing.status() + ", DOC.4)");
        }
    }

    /** DOC.5/DOC.2: the referenced document must resolve, in BC16, to a stored PDF. */
    private void requireStoredPdf(UUID documentId) {
        DocMeta meta = documents.resolve(documentId)
                .orElseThrow(() -> new ValidationException("document not found: " + documentId));
        if (!"stored".equals(meta.status())) {
            throw new ValidationException("document is not stored: " + documentId + " (is " + meta.status() + ")");
        }
        if (!PDF_CONTENT_TYPE.equals(meta.contentType())) {
            throw new ValidationException("invoice artifacts must be application/pdf: " + documentId
                    + " (is " + meta.contentType() + ")");
        }
    }

    private ListingRef loadListingRef(UUID listingId) {
        ListingRef row = jdbc.query(
                "SELECT invoice_id, status::text AS status FROM deal_listing WHERE listing_id = ?",
                rs -> rs.next()
                        ? new ListingRef(rs.getObject("invoice_id", UUID.class), rs.getString("status"))
                        : null,
                listingId);
        if (row == null) {
            throw new NotFoundException("listing not found: " + listingId);
        }
        return row;
    }

    private record ListingRef(UUID invoiceId, String status) {
    }

    private record ArtifactLink(UUID documentId, String status) {
    }
}
