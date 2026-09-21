package net.prorrogam.idhm.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Fachada unificada de schedulers para Folia, Paper y Spigot.
 * <p>
 * Reglas de diseño:
 * <ul>
 *   <li>Toda tarea se agenda; ninguna ejecuta inline. Esto garantiza
 *       semántica idéntica en las tres plataformas.</li>
 *   <li>Las referencias a clases de Folia se resuelven de forma lazy.
 *       En Spigot, {@code FoliaTaskHandle} nunca se instancia porque
 *       {@code IS_FOLIA} es {@code false}, así que la JVM no intenta
 *       cargar esas clases.</li>
 * <b>Métodos pendientes</b> (se añadirán cuando aparezca un caso real,
 * no antes):
 * <ul>
 *   <li>{@code runLaterForEntity} — para diferir tareas en el contexto
 *       de una entidad.</li>
 *   <li>{@code runLaterForRegion} — para diferir tareas en el contexto
 *       de una región.</li>
 */
public final class SchedulerUtil {

    private SchedulerUtil() {}

    public interface Task {
        void cancel();
        boolean cancelled();
    }

    private record FoliaTaskHandle(
            io.papermc.paper.threadedregions.scheduler.ScheduledTask task
    ) implements Task {
        public void cancel() { task.cancel(); }
        public boolean cancelled() { return task.isCancelled(); }
    }

    private record BukkitTaskHandle(
            org.bukkit.scheduler.BukkitTask task
    ) implements Task {
        public void cancel() { task.cancel(); }
        public boolean cancelled() { return task.isCancelled(); }
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        if (FoliaDetector.IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runForEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        if (FoliaDetector.IS_FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), retired);
        } else {
            runGlobal(plugin, task);
        }
    }

    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        runForEntity(plugin, entity, task, null);
    }

    public static void runForRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (FoliaDetector.IS_FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
        } else {
            runGlobal(plugin, task);
        }
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (FoliaDetector.IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static Task runAsyncRepeating(Plugin plugin, Runnable task,
                                         long delaySeconds, long periodSeconds) {
        long safeDelay = Math.max(1, delaySeconds);
        long safePeriod = Math.max(1, periodSeconds);

        if (FoliaDetector.IS_FOLIA) {
            return new FoliaTaskHandle(Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, t -> task.run(), safeDelay, safePeriod, TimeUnit.SECONDS));
        } else {
            return new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, task, safeDelay * 20L, safePeriod * 20L));
        }
    }
}