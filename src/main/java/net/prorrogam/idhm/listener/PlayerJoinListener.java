package net.prorrogam.idhm.listener;

import net.prorrogam.idhm.economy.EconomyService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final EconomyService economy;

    public PlayerJoinListener(EconomyService economy) {
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        economy.markOnline(player.getUniqueId(), player.getName());
        economy.loadPlayer(player.getUniqueId());
    }
}