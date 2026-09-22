package net.prorrogam.idhm.database.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which migrations have been applied to the database.
 * <p>
 * Backed by a single table ({@code <prefix>schema_history}) with one row
 * per applied migration. The table is created on first call to
 * {@link #createTableIfNotExists()}.
 * <p>
 * <b>Contrato con el runner:</b>
 * <ul>
 *   <li>El runner debe aplicar primero la migración y después llamar a
 *       {@link #recordApplied(String)}.</li>
 *   <li>Aplicar y registrar no son transaccionales: si el proceso
 *       crashea entre ambas, la migración se re-aplicará en el próximo
 *       arranque.</li>
 *   <li>Por tanto, todas las migraciones de Fase 3A deben ser
 *       idempotentes ({@code CREATE TABLE IF NOT EXISTS}, etc.).</li>
 * </ul>
 * <p>
 * El prefijo de tabla viene de {@code StorageSettings.tablePrefix()},
 * que ya validó el patrón {@code ^[a-z0-9_]*$}. La interpolación directa
 * del nombre de tabla en el SQL es segura por ese motivo.
 */
public final class SchemaHistory {

    private final DataSource dataSource;
    private final String tableName;

    public SchemaHistory(DataSource dataSource, String tablePrefix) {
        this.dataSource = dataSource;
        this.tableName = tablePrefix + "schema_history";
    }

    /**
     * Creates the schema history table if it does not exist.
     * Safe to call multiple times.
     */
    public void createTableIfNotExists() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "  version VARCHAR(64) NOT NULL PRIMARY KEY,"
                + "  applied_at TIMESTAMP NOT NULL"
                + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }

    /**
     * @return the set of migration versions already applied.
     */
    public Set<String> appliedVersions() throws SQLException {
        String sql = "SELECT version FROM " + tableName;
        Set<String> versions = new HashSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        return versions;
    }

    /**
     * Records a migration as applied.
     *
     * @param version migration identifier (e.g. "V1__init")
     * @throws SQLException if the version was already recorded
     *         (primary key violation), or on I/O failure
     */
    public void recordApplied(String version) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (version, applied_at) VALUES (?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, version);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        }
    }
}
