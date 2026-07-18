package com.arthvritt.platform.dev;

import com.arthvritt.platform.web.AbstractEdgeHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DL-BE-086 — prod-safety guard. Without the {@code dev} profile, neither {@link DevController} nor
 * {@link DevListingSeeder} is a bean, so {@code /dev/**} has no handler: {@code SecurityConfig} permits the
 * path but the request 404s. This is the "no bean, /dev/** 404" half of the DoD, proven in the default
 * (non-dev) profile the rest of the suite runs under.
 */
class DevEndpointsAbsentInProdTest extends AbstractEdgeHttpTest {

    @Test
    void seed_listing_is_404_without_the_dev_profile() throws Exception {
        mvc.perform(post("/dev/seed-listing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"live\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void seed_info_is_404_without_the_dev_profile() throws Exception {
        mvc.perform(get("/dev/seed-info")).andExpect(status().isNotFound());
    }
}
