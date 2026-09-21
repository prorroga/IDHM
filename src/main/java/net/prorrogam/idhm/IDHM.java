package net.prorrogam.idhm;

import net.prorrogam.idhm.util.FoliaDetector;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class IDHM extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String platform = FoliaDetector.IS_FOLIA ? "Folia" : "Paper/Spigot";
        @SuppressWarnings("deprecation")
        String version = getDescription().getVersion();
        getLogger().info("IDHM " + version + " enabling on " + platform);

        SchedulerUtil.runGlobal(this, () ->
                getLogger().info("SchedulerUtil is ready.")
        );
    }

    @Override
    public void onDisable() {
        getLogger().info("IDHM disabled.");
    }
}
