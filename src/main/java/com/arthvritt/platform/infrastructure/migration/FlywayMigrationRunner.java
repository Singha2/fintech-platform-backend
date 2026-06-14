package com.arthvritt.platform.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Standalone migration runner — no Spring context required.
 *
 * Run before starting the main application when the DB schema needs to be
 * created or updated. The main app uses ddl-auto=validate and will refuse
 * to start against an out-of-date schema.
 *
 * How to run:
 *   IntelliJ  → right-click main() → Run
 *   Maven     → ./mvnw exec:java -Dexec.mainClass="com.arthvritt.platform.infrastructure.migration.FlywayMigrationRunner"
 *
 * Override defaults via env vars (same names Spring Boot uses):
 *   SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD
 */
public class FlywayMigrationRunner {

    public static void main(String[] args) {
        String url      = env("SPRING_DATASOURCE_URL",      "jdbc:postgresql://localhost:5432/platform");
        String user     = env("SPRING_DATASOURCE_USERNAME", "platform");
        String password = env("SPRING_DATASOURCE_PASSWORD", "avc@2026");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();

        System.out.println("=== Flyway migration info (before) ===");
        for (MigrationInfo info : flyway.info().all()) {
            System.out.printf("  %-6s  %-40s  %s%n",
                    info.getVersion() != null ? info.getVersion() : "repeat",
                    info.getDescription(),
                    info.getState().name());
        }

        System.out.println("\nRunning migrations...");
        MigrateResult result = flyway.migrate();

        System.out.printf("%nDone. Applied: %d  |  Target version: %s  |  Success: %b%n",
                result.migrationsExecuted,
                result.targetSchemaVersion != null ? result.targetSchemaVersion : "none",
                result.success);

        if (!result.success) {
            System.err.println("Migration failed — check the output above.");
            System.exit(1);
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
