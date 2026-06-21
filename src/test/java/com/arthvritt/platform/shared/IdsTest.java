package com.arthvritt.platform.shared;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** M1b invariant tests for {@link Ids} (see docs/modules/M1b-ids-and-versioning.md §7). */
class IdsTest {

    @Test
    void generates_uuid_v7() { // INV-2 — version nibble = 7, IETF variant
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void ids_are_strictly_time_ordered() { // INV-2 — UUIDv7 sorts by creation time, even within a ms
        // java-uuid-generator's v7 generator is monotonic within a millisecond (RFC 9562 §6.2),
        // so consecutive ids must strictly increase with no sleep between them.
        UUID previous = Ids.newId();
        for (int i = 0; i < 1_000; i++) {
            UUID next = Ids.newId();
            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void ids_do_not_collide_in_a_tight_loop() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(10_000);
    }
}
