package com.arthvritt.platform.credit.port;

import java.util.UUID;

/**
 * A read-only snapshot of a BC3 pricing band (M9-A, DL-BE-039): the band identity plus the rate window and
 * the flat fee, all the listing snapshot needs to validate the rate (L.10) and compute {@code funding_target}
 * (L.7). Carries no mutable state — the consuming BC freezes these values into {@code pricing_snapshot}.
 */
public record PricingBand(UUID bandId, int minBps, int maxBps, int feeBps) {

    public boolean covers(int rateBps) {
        return rateBps >= minBps && rateBps <= maxBps;
    }
}
