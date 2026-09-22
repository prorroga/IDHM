package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.Storage;
import net.prorrogam.idhm.database.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class EconomyService {

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, PlayerBalances> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> currencyLocks = new ConcurrentHashMap<>();

    public EconomyService(CurrencyRegistry registry,
                          StorageManager storageManager,
                          Logger logger) {
        this.registry = registry;
        this.storageManager = storageManager;
        this.logger = logger;
    }

    public void markOnline(UUID uuid, String name) {
        onlinePlayers.add(uuid);
        playerNames.put(uuid, name);
    }

    public void markOffline(UUID uuid) {
        onlinePlayers.remove(uuid);
    }

    public void loadPlayer(UUID uuid) {
        storageManager.submit(s -> s.loadAllBalances(uuid))
                .whenComplete((balances, ex) -> {
                    if (ex != null) {
                        logger.warning("Failed to load balances for " + uuid
                                + ": " + ex.getMessage());
                        return;
                    }
                    if (!onlinePlayers.contains(uuid)) {
                        return;
                    }
                    PlayerBalances pb = cache.computeIfAbsent(uuid, k -> new PlayerBalances());
                    balances.forEach(pb::putIfAbsent);
                });
    }

    public void unloadPlayer(UUID uuid, String playerName) {
        PlayerBalances pb = cache.remove(uuid);
        dirty.remove(uuid);
        playerNames.remove(uuid);
        if (pb == null) {
            return;
        }

        Map<String, BigDecimal> snapshot = pb.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }

        List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                .map(e -> new Storage.BalanceUpdate(
                        uuid, playerName, e.getKey(), e.getValue()))
                .toList();

        storageManager.submit(s -> {
            s.saveBalances(updates);
            return null;
        }).whenComplete((v, ex) -> {
            if (ex != null) {
                logger.severe("Failed to flush balances for " + playerName
                        + " (" + uuid + ") on quit: " + ex.getMessage());
            }
        });
    }

    public void loadAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            markOnline(uuid, player.getName());
            loadPlayer(uuid);
        }
    }

    public void shutdown() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<UUID, PlayerBalances> entry : cache.entrySet()) {
            UUID uuid = entry.getKey();
            String name = playerNames.getOrDefault(uuid, uuid.toString());
            Map<String, BigDecimal> snapshot = entry.getValue().snapshot();
            if (snapshot.isEmpty()) {
                continue;
            }
            List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                    .map(e -> new Storage.BalanceUpdate(
                            uuid, name, e.getKey(), e.getValue()))
                    .toList();
            futures.add(storageManager.submit(s -> {
                s.saveBalances(updates);
                return null;
            }));
        }

        cache.clear();
        dirty.clear();
        onlinePlayers.clear();
        playerNames.clear();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("Shutdown flush failed: " + e.getMessage());
        }
    }
}
