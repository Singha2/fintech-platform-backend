package com.arthvritt.platform.document;

import com.arthvritt.platform.shared.error.NotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * BC16 Documents — local {@link DocumentStorePort} backend: raw bytes in {@code sys_document_blob} (M18a,
 * DL-BE-075). The default backend everywhere except production, where a GCS direct-to-blob adapter takes
 * over (M18c, {@code @Profile("prod")}) — guarded {@code !prod} rather than {@code dev} so it is present
 * in the default/test profile too (mirrors how {@code StubNotifier} and the other ACL stub adapters are
 * the default until a real vendor adapter is wired at the Production gate).
 */
@Component
@Profile("!prod")
public class DbTableDocumentStore implements DocumentStorePort {

    private final JdbcTemplate jdbc;

    public DbTableDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(UUID documentId, byte[] bytes) {
        // Upsert: M18a's storeGenerated always writes a fresh document_id (never re-put), but M18b's
        // upload step may be retried by the caller for the same document_id before finalize — a second
        // PUT should replace the bytes, not 500 on the PK.
        jdbc.update("INSERT INTO sys_document_blob (document_id, content_bytes) VALUES (?, ?) "
                        + "ON CONFLICT (document_id) DO UPDATE SET content_bytes = EXCLUDED.content_bytes",
                documentId, bytes);
    }

    @Override
    public byte[] get(UUID documentId) {
        byte[] bytes = jdbc.query("SELECT content_bytes FROM sys_document_blob WHERE document_id = ?",
                rs -> rs.next() ? rs.getBytes(1) : null, documentId);
        if (bytes == null) {
            throw new NotFoundException("no stored bytes for document " + documentId);
        }
        return bytes;
    }

    @Override
    public boolean exists(UUID documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_document_blob WHERE document_id = ?", Integer.class, documentId);
        return count != null && count > 0;
    }
}
