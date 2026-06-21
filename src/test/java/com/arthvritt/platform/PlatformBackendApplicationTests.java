package com.arthvritt.platform;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context boots. Now that the app maps JPA entities,
 * {@code ddl-auto=validate} runs against the schema, so this needs the migrated Testcontainers
 * database — hence it extends {@link AbstractIntegrationTest} (Testcontainers + Flyway) and shares
 * the cached context with the other integration tests.
 */
class PlatformBackendApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
