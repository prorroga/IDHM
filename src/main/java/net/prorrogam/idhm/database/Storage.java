package net.prorrogam.idhm.database;

import net.prorrogam.idhm.api.BalanceEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence layer for balance data.
 * <p>
 * Synchronous by design: defines what to persist, not when. The async
 * wrapping happens in {@link StorageManager}.
 * <p>
 * Implementations must be thread-safe.
 */
public interface Storage extends AutoCloseable {

    default void saveBalance(UUID playerId, String playerName, String currencyId,
                             BigDecimal balance) throws Exception {
        saveBalances(List.of(new BalanceUpdate(playerId, playerName, currencyId, balance)));
    }

    void saveBalances(List<BalanceUpdate> updates) throws Exception;

    BigDecimal loadBalance(UUID playerId, String currencyId) throws Exception;

    List<BalanceEntry> topBalances(String currencyId, int limit) throws Exception;

    @Override
    void close();

    record BalanceUpdate(
            UUID playerId,
            String playerName,
            String currencyId,
            BigDecimal balance
    ) {
        public BalanceUpdate {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerName, "playerName");
            Objects.requireNonNull(currencyId, "currencyId");
            Objects.requireNonNull(balance, "balance");
        }
    }
}
