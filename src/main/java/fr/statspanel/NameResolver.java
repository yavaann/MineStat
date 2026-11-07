package fr.statspanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UUID → pseudo. Ordre : joueurs connectés, usercache.json du serveur, cache local,
 * puis API Mojang (en arrière-plan, UUID premium v4 seulement).
 */
public final class NameResolver {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long USERCACHE_CHECK_MS = 30_000;

    private final Path userCache;
    private final Path localCache;
    private final boolean remote;
    private final Map<UUID, String> known = new ConcurrentHashMap<>();
    private final Map<UUID, String> remoteNames = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService exec;
    private final HttpClient http;
    private volatile long userCacheMtime = -1;
    private volatile long lastUserCacheCheck;

    public NameResolver(Path userCache, Path localCache, boolean remote) {
        this.userCache = userCache;
        this.localCache = localCache;
        this.remote = remote;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StatsPanel-Names");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        loadLocalCache();
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    public String name(UUID uuid, Map<UUID, String> online) {
        String n = online.get(uuid);
        if (n != null) {
            known.put(uuid, n);
            return n;
        }
        refreshUserCache();
        n = known.get(uuid);
        if (n == null) n = remoteNames.get(uuid);
        if (n == null && remote && uuid.version() == 4 && pending.add(uuid)) {
            exec.submit(() -> fetchRemote(uuid));
        }
        return n;
    }

    private void refreshUserCache() {
        long now = System.currentTimeMillis();
        if (now - lastUserCacheCheck < USERCACHE_CHECK_MS) return;
        lastUserCacheCheck = now;
        try {
            if (!Files.exists(userCache)) return;
            long mtime = Files.getLastModifiedTime(userCache).toMillis();
            if (mtime == userCacheMtime) return;
            userCacheMtime = mtime;
            JsonElement root = JsonParser.parseString(Files.readString(userCache, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (!o.has("uuid") || !o.has("name")) continue;
                try {
                    known.put(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.debug("Lecture de usercache.json ratée", e);
        }
    }

    private void fetchRemote(UUID uuid) {
        try {
            Thread.sleep(250); // reste poli avec l'API Mojang
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "")))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "StatsPanel-FabricMod")
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                if (o.has("name")) {
                    remoteNames.put(uuid, o.get("name").getAsString());
                    saveLocalCache();
                }
            }
            // Autre code : on laisse l'UUID dans "pending" pour ne pas reboucher avant le prochain redémarrage
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.debug("Résolution Mojang ratée pour {}", uuid, e);
        }
    }

    private void loadLocalCache() {
        try {
            if (!Files.exists(localCache)) return;
            Map<String, String> m = GSON.fromJson(Files.readString(localCache, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
            if (m == null) return;
            m.forEach((k, v) -> {
                try {
                    remoteNames.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException ignored) {
                }
            });
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.debug("Cache de pseudos illisible", e);
        }
    }

    private synchronized void saveLocalCache() {
        try {
            Map<String, String> m = new TreeMap<>();
            remoteNames.forEach((k, v) -> m.put(k.toString(), v));
            Files.writeString(localCache, GSON.toJson(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StatsPanelMod.LOG.debug("Écriture du cache de pseudos ratée", e);
        }
    }
}
