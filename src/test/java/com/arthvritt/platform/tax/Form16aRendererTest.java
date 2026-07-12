package com.arthvritt.platform.tax;

import com.arthvritt.platform.tax.Form16aRenderer.Form16aData;
import com.arthvritt.platform.tax.Form16aRenderer.Form16aLine;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Form16aRenderer} is pure and clock-free: the same {@link Form16aData} always renders to the same
 * bytes (and therefore the same hash), so the download endpoint can re-render on demand and verify it
 * against the hash stamped at issuance (M16, DL-BE-067).
 */
class Form16aRendererTest {

    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.of(2026, 7, 12, 10, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void identical_inputs_render_identical_bytes_and_hash() {
        Form16aData data = data();

        byte[] first = Form16aRenderer.render(data);
        byte[] second = Form16aRenderer.render(data);

        assertThat(first).isEqualTo(second);
        assertThat(Form16aRenderer.sha256(first)).isEqualTo(Form16aRenderer.sha256(second));
    }

    @Test
    void different_inputs_render_different_bytes_and_hash() {
        Form16aData original = data();
        Form16aData changed = new Form16aData(original.investorId(), original.pan(), original.fyCode(),
                original.panVerified(), original.tdsRateBps(), original.cumulativeIncomePaise() + 1,
                original.cumulativeTdsPaise(), original.issuedAt(), original.lines());

        byte[] a = Form16aRenderer.render(original);
        byte[] b = Form16aRenderer.render(changed);

        assertThat(a).isNotEqualTo(b);
        assertThat(Form16aRenderer.sha256(a)).isNotEqualTo(Form16aRenderer.sha256(b));
    }

    @Test
    void render_never_reads_the_wall_clock() throws InterruptedException {
        Form16aData data = data();
        byte[] before = Form16aRenderer.render(data);
        Thread.sleep(5); // if issuedAt were re-read from a clock, this would move the bytes
        byte[] after = Form16aRenderer.render(data);
        assertThat(before).isEqualTo(after);
    }

    private static Form16aData data() {
        UUID investorId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        List<Form16aLine> lines = List.of(new Form16aLine(listingId, 6_00_000L, 60_000L, 5_40_000L, "CHLN-1"));
        return new Form16aData(investorId, "ABCDE1234F", "FY2026-27", true, 1000, 6_00_000L, 60_000L,
                ISSUED_AT, lines);
    }
}
