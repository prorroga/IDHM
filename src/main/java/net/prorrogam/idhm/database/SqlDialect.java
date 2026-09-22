package net.prorrogam.idhm.database;

import java.util.Locale;

/**
 * Supported SQL backends.
 * <p>
 * Each dialect knows four things that vary between databases:
 * <ul>
 *   <li>A short id used in {@code config.yml}.</li>
 *   <li>The JDBC driver class name (for Hikari).</li>
 *   <li>Whether the pool must be limited to a single connection.</li>
 *   <li>The upsert syntax family.</li>
 * </ul>
 * Adding a new backend is a single line here, no changes elsewhere.
 */
public enum SqlDialect {

    H2("h2", "org.h2.Driver", false, UpsertStyle.MERGE),
    SQLITE("sqlite", "org.sqlite.JDBC", true, UpsertStyle.ON_CONFLICT),
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", false, UpsertStyle.ON_DUPLICATE_KEY),
    MARIADB("mariadb", "org.mariadb.jdbc.Driver", false, UpsertStyle.ON_DUPLICATE_KEY),
    POSTGRESQL("postgresql", "org.postgresql.Driver", false, UpsertStyle.ON_CONFLICT);

    /**
     * Family of upsert syntax used by the backend.
     */
    public enum UpsertStyle {
        /**
         * H2: replaces the whole INSERT with
         * {@code MERGE INTO ... KEY (...) VALUES (...)}.
         */
        MERGE,
        /**
         * MySQL/MariaDB: appends {@code ON DUPLICATE KEY UPDATE ...}
         * to the INSERT.
         */
        ON_DUPLICATE_KEY,
        /**
         * PostgreSQL/SQLite: appends
         * {@code ON CONFLICT (...) DO UPDATE SET ...}.
         */
        ON_CONFLICT
    }

    private final String id;
    private final String driverClassName;
    private final boolean singleConnection;
    private final UpsertStyle upsertStyle;

    SqlDialect(String id, String driverClassName,
               boolean singleConnection, UpsertStyle upsertStyle) {
        this.id = id;
        this.driverClassName = driverClassName;
        this.singleConnection = singleConnection;
        this.upsertStyle = upsertStyle;
    }

    /**
     * @return a lowercase identifier used in config.yml (e.g. "h2", "mysql").
     */
    public String id() {
        return id;
    }

    /**
     * @return the fully qualified JDBC driver class name.
     */
    public String driverClassName() {
        return driverClassName;
    }

    /**
     * @return true if the backend cannot handle concurrent writers and
     *         must be used with a pool of size 1 (SQLite).
     */
    public boolean singleConnection() {
        return singleConnection;
    }

    /**
     * @return the family of upsert syntax used by this backend.
     */
    public UpsertStyle upsertStyle() {
        return upsertStyle;
    }

    /**
     * Resolves a dialect from its config id, case-insensitively.
     *
     * @param id value from config.yml; may be null or blank
     * @return the matching dialect, or {@code null} if none matches
     */
    public static SqlDialect fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (SqlDialect dialect : values()) {
            if (dialect.id.equals(normalized)) {
                return dialect;
            }
        }
        return null;
    }
}
