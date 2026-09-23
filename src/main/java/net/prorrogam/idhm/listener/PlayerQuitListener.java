package net.prorrogam.idhm.listener;

import net.prorrogam.idhm.economy.EconomyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final EconomyService economy;

    public PlayerQuitListener(EconomyService economy) {
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        economy.markOffline(player.getUniqueId());
        economy.unloadPlayer(player.getUniqueId(), player.getName());
    }
}