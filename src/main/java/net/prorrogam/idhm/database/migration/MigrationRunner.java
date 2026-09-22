package net.prorrogam.idhm.database.migration;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies pending SQL migrations on startup.
 * <p>
 * Migrations are discovered from a hardcoded, ordered list of version
 * identifiers. Each identifier maps to a {@code .sql} file at
 * {@code db/migration/<version>.sql} inside the JAR.
 * <p>
 * <b>Migration file format:</b> a plain SQL script with statements
 * separated by {@code ;}. Each statement is executed individually, so
 * multi-statement scripts work on every JDBC driver (H2, MySQL,
 * MariaDB, PostgreSQL, SQLite) without special URL parameters.
 * <p>
 * <b>Limitations:</b> the splitter does not understand SQL string
 * literals or stored procedures. A {@code ;} inside a string will break
 * the script. This is acceptable for DDL migrations; if you ever need
 * procedures, upgrade the splitter.
 * <p>
 * <b>Idempotency contract:</b> if the process crashes between executing
 * a migration and recording it, the migration will be re-executed on
 * the next startup. All migrations must therefore be idempotent
 * ({@code CREATE TABLE IF NOT EXISTS}, {@code INSERT ... ON CONFLICT
 * DO NOTHING}, etc.).
 */
public final class MigrationRunner {

    /**
     * Ordered list of migration versions. Append new entries at the end.
     * Each identifier must match a file {@code db/migration/<id>.sql}.
     */
    private static final List<String> MIGRATIONS = List.of(
            "V1__init"
    );

    private MigrationRunner() {}

    /**
     * Applies all pending migrations. See class Javadoc for the
     * idempotency contract.
     *
     * @param dataSource  connection pool to use
     * @param tablePrefix validated prefix (see {@code StorageSettings})
     * @throws SQLException on database failure
     * @throws IOException  if a migration file is missing from the JAR
     */
    public static void run(DataSource dataSource, String tablePrefix)
            throws SQLException, IOException {

        SchemaHistory history = new SchemaHistory(dataSource, tablePrefix);
        history.createTableIfNotExists();

        Set<String> applied = history.appliedVersions();

        for (String version : MIGRATIONS) {
            if (applied.contains(version)) {
                continue;
            }
            String sql = loadMigration(version).replace("{prefix}", tablePrefix);
            executeScript(dataSource, sql);
            history.recordApplied(version);
        }
    }

    private static String loadMigration(String version) throws IOException {
        String path = "db/migration/" + version + ".sql";
        try (InputStream in = MigrationRunner.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Migration file not found in JAR: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeScript(DataSource dataSource, String script)
            throws SQLException {
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    /**
     * Splits a SQL script into individual statements.
     * <p>
     * <b>Not a real SQL parser.</b> Splits on every {@code ;}, then
     * trims and filters out blanks. Does not respect string literals
     * or comments. Acceptable for DDL scripts with one statement per
     * logical block.
     */
    static List<String> splitStatements(String script) {
        List<String> result = new ArrayList<>();
        for (String raw : script.split(";")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
