package pgweb.audit;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Map;

/**
 * Applies the audit schema migrations as the migrator role (ADR-0005: the migrator owns the schema; the runtime role
 * only inserts). Flyway's {@code clean} is disabled so audit data can never be dropped by a mistaken command.
 * Migrations are expand-only within a release line (plan §7.6).
 */
public final class AuditMigrator {

    public static final String READER_ROLE = "pgweb_audit_reader";

    private AuditMigrator() {
    }

    public static MigrateResult migrate(String jdbcUrl, String migratorUser, String migratorPassword, String appRole) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, migratorUser, migratorPassword)
                .schemas("audit")
                .defaultSchema("audit")
                .createSchemas(true)
                .table("flyway_schema_history")
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", quoteIdent(appRole), "reader_role", quoteIdent(READER_ROLE)))
                .placeholderReplacement(true)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .baselineOnMigrate(false)
                .load();
        return flyway.migrate();
    }

    /** Double-quotes a role name for use in GRANT statements. */
    static String quoteIdent(String name) {
        if (name == null || name.isEmpty() || name.length() > 63 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid role name");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
