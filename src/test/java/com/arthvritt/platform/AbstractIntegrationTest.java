package com.arthvritt.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for integration tests that need a real Postgres (via Testcontainers) with the
 * Flyway migrations applied. Booting the Spring context runs Flyway against the container
 * and then Hibernate {@code ddl-auto=validate}, so every subclass exercises the real schema.
 *
 * <p>Production disables auto-migration ({@code spring.flyway.enabled=false} — migrations are
 * applied deliberately via {@code FlywayMigrationRunner}, then the app validates). Integration
 * tests re-enable it here so the fresh Testcontainers database gets the schema on context boot.
 *
 * <p>The container is shared and the context cached across all subclasses (identical config),
 * so the database spins up once per test run.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.flyway.enabled=true")
public abstract class AbstractIntegrationTest {
}
