package fr.statspanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collecte les métriques serveur. Tout ce qui touche au jeu est lu sur le thread serveur
 * (événements de tick) et publié dans des champs volatiles lus par le serveur HTTP.
 */
public final class MetricsCollector {
    private static final int WINDOW = 100;              // 100 ticks = 5 s à 20 TPS
    private static final long PAUSED_AFTER_NANOS = 3_000_000_000L;

    public record LivePlayer(UUID uuid, String name, float health, float maxHealth, int food, int xpLevel,
                             int ping, String dimension, double x, double y, double z) {}

    public record WorldInfo(String dimension, int loadedChunks, int entities, int players) {}

    public record HistoryPoint(long time, double tps, double mspt, long heapUsed, double cpuProcess, int players) {}

    private final MinecraftServer server;
    private final PanelConfig config;
    private final long startedAt = System.currentTimeMillis();
    private final ScheduledExecutorService scheduler;
    private final Path worldPath;
    private final String mcVersion;
    private final String loaderVersion;
    private final String fabricApiVersion;
    private final int modCount;

    // État de tick : thread serveur uniquement
    private final long[] tickEnds = new long[WINDOW];
    private final double[] tickDurationsMs = new double[WINDOW];
    private int tickIndex;
    private int tickCount;
    private long tickStart;
    private long tickCounter;

    // Publié vers le thread HTTP
    private volatile double tps = 20.0;
    private volatile double msptAvg;
    private volatile double msptMax;
    private volatile long lastTickEnd = System.nanoTime();
    private volatile List<LivePlayer> online = List.of();
    private volatile List<WorldInfo> worlds = List.of();
    private final AtomicLong worldSizeBytes = new AtomicLong(-1);
    private final ArrayDeque<HistoryPoint> history = new ArrayDeque<>();

    public MetricsCollector(MinecraftServer server, PanelConfig config) {
        this.server = server;
        this.config = config;
        this.worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        this.mcVersion = modVersion("minecraft");
        this.loaderVersion = modVersion("fabricloader");
        this.fabricApiVersion = modVersion("fabric-api");
        this.modCount = FabricLoader.getInstance().getAllMods().size();

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "StatsPanel-Collector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sampleHistory, config.historyIntervalSeconds,
                config.historyIntervalSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::computeWorldSize, 5, 600, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ ticks (thread serveur)

    public void onTickStart() {
        tickStart = System.nanoTime();
    }

    public void onTickEnd() {
        long now = System.nanoTime();
        tickDurationsMs[tickIndex] = (now - tickStart) / 1_000_000.0;
        tickEnds[tickIndex] = now;
        tickIndex = (tickIndex + 1) % WINDOW;
        if (tickCount < WINDOW) tickCount++;
        lastTickEnd = now;
        tickCounter++;

        if (tickCounter % 20 == 0) {
            computeTickStats();
            captureOnline();
        }
        if (tickCounter % 100 == 0) {
            captureWorlds();
        }
        if (config.statsFlushSeconds > 0 && tickCounter % (config.statsFlushSeconds * 20L) == 0
                && !server.getPlayerList().getPlayers().isEmpty()) {
            // Écrit world/stats/<uuid>.json pour que le panel voie les stats à jour
            server.getPlayerList().saveAll();
        }
    }

    private void computeTickStats() {
        if (tickCount < 2) return;
        int newest = (tickIndex - 1 + WINDOW) % WINDOW;
        int oldest = (tickIndex - tickCount + WINDOW) % WINDOW;
        long span = tickEnds[newest] - tickEnds[oldest];
        if (span > 0) tps = (tickCount - 1) * 1_000_000_000.0 / span;

        double sum = 0;
        double max = 0;
        for (int i = 0; i < tickCount; i++) {
            double d = tickDurationsMs[(oldest + i) % WINDOW];
            sum += d;
            if (d > max) max = d;
        }
        msptAvg = sum / tickCount;
        msptMax = max;
    }

    private void captureOnline() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<LivePlayer> list = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            try {
                int ping = p.connection != null ? p.connection.latency() : -1;
                list.add(new LivePlayer(
                        p.getUUID(),
                        p.getName().getString(),
                        p.getHealth(),
                        p.getMaxHealth(),
                        p.getFoodData().getFoodLevel(),
                        p.experienceLevel,
                        ping,
                        dimensionId(p.level()),
                        p.getX(), p.getY(), p.getZ()));
            } catch (RuntimeException e) {
                StatsPanelMod.LOG.debug("Lecture du joueur {} impossible", p.getUUID(), e);
            }
        }
        online = List.copyOf(list);
    }

    private void captureWorlds() {
        List<WorldInfo> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            int entities = 0;
            for (Entity ignored : level.getAllEntities()) entities++;
            list.add(new WorldInfo(
                    dimensionId(level),
                    level.getChunkSource().getLoadedChunksCount(),
                    entities,
                    level.players().size()));
        }
        worlds = List.copyOf(list);
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" → "minecraft:overworld" (indépendant des renommages Identifier/ResourceLocation). */
    static String dimensionId(Level level) {
        String s = String.valueOf(level.dimension());
        int i = s.lastIndexOf(" / ");
        int j = s.lastIndexOf(']');
        return (i >= 0 && j > i) ? s.substring(i + 3, j) : s;
    }

    // ------------------------------------------------------------------ tâches de fond

    private void sampleHistory() {
        try {
            Runtime rt = Runtime.getRuntime();
            HistoryPoint point = new HistoryPoint(
                    System.currentTimeMillis(),
                    round2(isPaused() ? 0 : tps),
                    round2(isPaused() ? 0 : msptAvg),
                    rt.totalMemory() - rt.freeMemory(),
                    processCpuLoad(),
                    online.size());
            synchronized (history) {
                history.addLast(point);
                while (history.size() > config.historyPoints) history.removeFirst();
            }
        } catch (Throwable t) {
            StatsPanelMod.LOG.debug("Échantillon d'historique raté", t);
        }
    }

    private void computeWorldSize() {
        AtomicLong total = new AtomicLong();
        try {
            Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            worldSizeBytes.set(total.get());
        } catch (IOException e) {
            StatsPanelMod.LOG.debug("Calcul de la taille du monde raté", e);
        }
    }

    // ------------------------------------------------------------------ lecture (thread HTTP)

    public boolean isPaused() {
        return System.nanoTime() - lastTickEnd > PAUSED_AFTER_NANOS;
    }

    public List<LivePlayer> online() {
        return online;
    }

    public Map<UUID, String> onlineNames() {
        Map<UUID, String> map = new HashMap<>();
        for (LivePlayer p : online) map.put(p.uuid(), p.name());
        return map;
    }

    public LivePlayer live(UUID uuid) {
        for (LivePlayer p : online) if (p.uuid().equals(uuid)) return p;
        return null;
    }

    /** Données en direct d'un joueur connecté, filtrées selon les droits. */
    public JsonObject liveJson(LivePlayer p, java.util.Set<String> perms) {
        JsonObject o = new JsonObject();
        if (perms.contains("players.live")) {
            o.addProperty("health", round2(p.health()));
            o.addProperty("maxHealth", round2(p.maxHealth()));
            o.addProperty("food", p.food());
            o.addProperty("xpLevel", p.xpLevel());
            o.addProperty("ping", p.ping());
        }
        if (perms.contains("players.coordinates")) {
            o.addProperty("dimension", p.dimension());
            o.addProperty("x", Math.floor(p.x()));
            o.addProperty("y", Math.floor(p.y()));
            o.addProperty("z", Math.floor(p.z()));
        }
        return o;
    }

    public JsonObject serverJson(java.util.Set<String> perms) {
        JsonObject o = new JsonObject();
        String motd = safeMotd();
        o.addProperty("title", config.panelTitle.isBlank() ? motd : config.panelTitle);
        o.addProperty("motd", motd);
        o.addProperty("refreshSeconds", config.clientRefreshSeconds);
        o.addProperty("now", System.currentTimeMillis());
        o.addProperty("startedAt", startedAt);
        o.addProperty("uptimeMs", System.currentTimeMillis() - startedAt);

        JsonObject versions = new JsonObject();
        versions.addProperty("minecraft", mcVersion);
        if (perms.contains("server.machine")) {
            versions.addProperty("fabricLoader", loaderVersion);
            versions.addProperty("fabricApi", fabricApiVersion);
            versions.addProperty("mods", modCount);
        }
        o.add("versions", versions);

        List<LivePlayer> on = online;
        if (perms.contains("server.performance")) {
            boolean paused = isPaused();
            JsonObject ticks = new JsonObject();
            ticks.addProperty("paused", paused);
            ticks.addProperty("tps", round2(paused ? 0 : tps));
            ticks.addProperty("msptAvg", round2(msptAvg));
            ticks.addProperty("msptMax", round2(msptMax));
            o.add("ticks", ticks);

            JsonObject players = new JsonObject();
            players.addProperty("online", on.size());
            players.addProperty("max", server.getMaxPlayers());
            o.add("players", players);
        }

        if (perms.contains("players.online")) {
            JsonArray list = new JsonArray();
            for (LivePlayer p : on) {
                if (config.isHidden(p.uuid(), p.name())) continue;
                JsonObject j = liveJson(p, perms);
                j.addProperty("uuid", p.uuid().toString());
                j.addProperty("name", p.name());
                list.add(j);
            }
            o.add("online", list);
        }

        if (perms.contains("server.memory")) {
            Runtime rt = Runtime.getRuntime();
            JsonObject mem = new JsonObject();
            mem.addProperty("heapUsed", rt.totalMemory() - rt.freeMemory());
            mem.addProperty("heapCommitted", rt.totalMemory());
            mem.addProperty("heapMax", rt.maxMemory());
            o.add("memory", mem);
        }

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (perms.contains("server.cpu")) {
            JsonObject cpu = new JsonObject();
            cpu.addProperty("process", processCpuLoad());
            cpu.addProperty("system", systemCpuLoad());
            cpu.addProperty("cores", os.getAvailableProcessors());
            o.add("cpu", cpu);
        }

        if (perms.contains("server.machine")) {
            JsonObject sys = new JsonObject();
            sys.addProperty("os", os.getName() + " " + os.getVersion());
            sys.addProperty("arch", os.getArch());
            sys.addProperty("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                sys.addProperty("ramTotal", sun.getTotalMemorySize());
                sys.addProperty("ramFree", sun.getFreeMemorySize());
            }
            sys.addProperty("loadAverage", round2(os.getSystemLoadAverage()));
            sys.addProperty("nonHeapUsed", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
            sys.addProperty("threads", ManagementFactory.getThreadMXBean().getThreadCount());
            long gcCount = 0;
            long gcTime = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0, gc.getCollectionCount());
                gcTime += Math.max(0, gc.getCollectionTime());
            }
            sys.addProperty("gcCount", gcCount);
            sys.addProperty("gcTimeMs", gcTime);
            try {
                FileStore store = Files.getFileStore(worldPath);
                sys.addProperty("diskTotal", store.getTotalSpace());
                sys.addProperty("diskFree", store.getUsableSpace());
            } catch (IOException ignored) {
            }
            sys.addProperty("worldSize", worldSizeBytes.get());
            o.add("system", sys);
        }

        if (perms.contains("server.worlds")) {
            JsonArray w = new JsonArray();
            for (WorldInfo wi : worlds) {
                JsonObject j = new JsonObject();
                j.addProperty("dimension", wi.dimension());
                j.addProperty("loadedChunks", wi.loadedChunks());
                j.addProperty("entities", wi.entities());
                j.addProperty("players", wi.players());
                w.add(j);
            }
            o.add("worlds", w);
        }
        return o;
    }

    public JsonObject historyJson(java.util.Set<String> perms) {
        boolean mem = perms.contains("server.memory");
        boolean cpu = perms.contains("server.cpu");
        JsonObject o = new JsonObject();
        o.addProperty("intervalSeconds", config.historyIntervalSeconds);
        if (mem) o.addProperty("heapMax", Runtime.getRuntime().maxMemory());
        JsonArray arr = new JsonArray();
        synchronized (history) {
            for (HistoryPoint p : history) {
                JsonObject j = new JsonObject();
                j.addProperty("t", p.time());
                j.addProperty("tps", p.tps());
                j.addProperty("mspt", p.mspt());
                if (mem) j.addProperty("heap", p.heapUsed());
                if (cpu) j.addProperty("cpu", p.cpuProcess());
                j.addProperty("players", p.players());
                arr.add(j);
            }
        }
        o.add("points", arr);
        return o;
    }

    // ------------------------------------------------------------------ utilitaires

    private String safeMotd() {
        try {
            String m = server.getMotd();
            return m == null ? "" : m;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static double processCpuLoad() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
            double v = sun.getProcessCpuLoad();
            return v < 0 ? 0 : round4(v);
        }
        return 0;
    }

    private static double systemCpuLoad() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
            double v = sun.getCpuLoad();
            return v < 0 ? 0 : round4(v);
        }
        return 0;
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
