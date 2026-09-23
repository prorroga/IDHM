package net.prorrogam.idhm.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.database.migration.MigrationRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SqlStorage implements Storage {

    private static final String COLUMNS = "player_uuid, player_name, currency_id, balance";

    private final SqlDialect dialect;
    private final String tableName;
    private final HikariDataSource dataSource;

    public SqlStorage(StorageSettings settings) throws SQLException, IOException {
        this.dialect = settings.dialect();
        this.tableName = settings.tablePrefix() + "accounts";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.url());
        config.setDriverClassName(dialect.driverClassName());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(1);
        config.setPoolName("idhm-storage-" + dialect.id());

        this.dataSource = new HikariDataSource(config);

        try {
            MigrationRunner.run(dataSource, settings.tablePrefix());
        } catch (Exception e) {
            dataSource.close();
            throw e;
        }
    }

    @Override
    public BigDecimal loadBalance(UUID playerId, String currencyId) throws SQLException {
        String sql = "SELECT balance FROM " + tableName
                + " WHERE player_uuid = ? AND currency_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            stmt.setString(2, currencyId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal balance = rs.getBigDecimal("balance");
                    return balance != null ? balance : BigDecimal.ZERO;
                }
                return BigDecimal.ZERO;
            }
        }
    }

    @Override
    public Map<String, BigDecimal> loadAllBalances(UUID playerId) throws SQLException {
        String sql = "SELECT currency_id, balance FROM " + tableName
                + " WHERE player_uuid = ?";

        Map<String, BigDecimal> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BigDecimal balance = rs.getBigDecimal("balance");
                    result.put(rs.getString("currency_id"),
                            balance != null ? balance : BigDecimal.ZERO);
                }
            }
        }
        return result;
    }

    @Override
    public void saveBalances(List<BalanceUpdate> updates) throws SQLException {
        if (updates.isEmpty()) {
            return;
        }

        String sql = buildUpsertSql();

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (BalanceUpdate update : updates) {
                    bindUpsert(stmt, update);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public List<BalanceEntry> topBalances(String currencyId, int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, got " + limit);
        }

        String sql = "SELECT player_uuid, player_name, balance FROM " + tableName
                + " WHERE currency_id = ? AND balance > 0"
                + " ORDER BY balance DESC LIMIT ?";

        List<BalanceEntry> result = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, currencyId);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new BalanceEntry(
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getBigDecimal("balance")));
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String buildUpsertSql() {
        return switch (dialect.upsertStyle()) {
            case MERGE -> "MERGE INTO " + tableName
                    + " (" + COLUMNS + ") KEY (player_uuid, currency_id)"
                    + " VALUES (?, ?, ?, ?)";
            case ON_DUPLICATE_KEY -> "INSERT INTO " + tableName
                    + " (" + COLUMNS + ") VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE"
                    + " player_name = VALUES(player_name),"
                    + " balance = VALUES(balance)";
            case ON_CONFLICT -> "INSERT INTO " + tableName
                    + " (" + COLUMNS + ") VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT (player_uuid, currency_id) DO UPDATE SET"
                    + " player_name = excluded.player_name,"
                    + " balance = excluded.balance";
        };
    }

    private void bindUpsert(PreparedStatement stmt, BalanceUpdate update)
            throws SQLException {
        stmt.setString(1, update.playerId().toString());
        stmt.setString(2, update.playerName());
        stmt.setString(3, update.currencyId());
        stmt.setBigDecimal(4, update.balance());
    }
}
