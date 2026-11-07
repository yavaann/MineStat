package fr.statspanel;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class StatsPanelMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "statspanel";
    public static final Logger LOG = LoggerFactory.getLogger("StatsPanel");

    private PanelConfig config;
    private MetricsCollector collector;
    private PlayerStatsRepository repository;
    private NameResolver names;
    private AssetCache assets;
    private PanelWebServer web;

    @Override
    public void onInitializeServer() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = PanelConfig.load(configDir.resolve("statspanel.json"));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            names = new NameResolver(
                    FabricLoader.getInstance().getGameDir().resolve("usercache.json"),
                    configDir.resolve("statspanel-names.json"),
                    config.resolveNamesWithMojang);
            collector = new MetricsCollector(server, config);
            repository = new PlayerStatsRepository(
                    server.getWorldPath(LevelResource.PLAYER_STATS_DIR), names, config);
            Path cache = configDir.resolve("statspanel/cache");
            String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
            assets = new AssetCache(cache, mcVersion, config);
            SkinService skins = new SkinService(cache.resolve("skins"), assets, config);
            AuthService auth = new AuthService(configDir.resolve("statspanel/users.json"), config);
            web = new PanelWebServer(config, collector, repository, assets, skins, names, auth,
                    configDir.resolve("statspanel"));
            web.start();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (web != null) web.stop();
            if (collector != null) collector.shutdown();
            if (names != null) names.shutdown();
            if (assets != null) assets.shutdown();
            assets = null;
            web = null;
            collector = null;
            repository = null;
            names = null;
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            MetricsCollector c = collector;
            if (c != null) c.onTickStart();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MetricsCollector c = collector;
            if (c != null) c.onTickEnd();
        });
    }
}
