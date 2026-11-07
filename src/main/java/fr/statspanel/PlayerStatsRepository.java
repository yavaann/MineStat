package fr.statspanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lit les statistiques vanilla de world/stats/&lt;uuid&gt;.json (joueurs hors ligne compris).
 * Les fichiers ne sont reparsés que si leur date de modification change.
 */
public final class PlayerStatsRepository {
    public static final String[] CATEGORIES = {
            "minecraft:custom", "minecraft:mined", "minecraft:used", "minecraft:crafted", "minecraft:broken",
            "minecraft:picked_up", "minecraft:dropped", "minecraft:killed", "minecraft:killed_by"
    };
    private static final String[] SUMMARY_KEYS = {
            "playTime", "deaths", "mobKills", "playerKills", "distanceCm", "mined", "crafted", "used",
            "jumps", "damageDealt", "damageTaken", "fishCaught", "trades", "animalsBred"
    };

    public record Entry(UUID uuid, long lastModified, JsonObject stats) {}

    private record Cached(long mtime, JsonObject stats) {}

    private final Path statsDir;
    private final NameResolver names;
    private final PanelConfig config;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public PlayerStatsRepository(Path statsDir, NameResolver names, PanelConfig config) {
        this.statsDir = statsDir;
        this.names = names;
        this.config = config;
    }

    /** Tous les joueurs ayant un fichier de stats, plus les joueurs connectés qui n'en ont pas encore. */
    public synchronized Map<UUID, Entry> loadAll(Set<UUID> onlineUuids) {
        Map<UUID, Entry> out = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        if (Files.isDirectory(statsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(statsDir, "*.json")) {
                for (Path file : ds) {
                    String fn = file.getFileName().toString();
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(fn.substring(0, fn.length() - 5));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    seen.add(uuid);
                    try {
                        long mtime = Files.getLastModifiedTime(file).toMillis();
                        Cached c = cache.get(uuid);
                        if (c == null || c.mtime() != mtime) {
                            c = new Cached(mtime, parse(file));
                            cache.put(uuid, c);
                        }
                        out.put(uuid, new Entry(uuid, mtime, c.stats()));
                    } catch (IOException | RuntimeException e) {
                        StatsPanelMod.LOG.debug("Stats illisibles : {}", file, e);
                    }
                }
            } catch (IOException e) {
                StatsPanelMod.LOG.warn("Lecture de {} impossible : {}", statsDir, e.getMessage());
            }
        }
        cache.keySet().retainAll(seen);
        long now = System.currentTimeMillis();
        for (UUID u : onlineUuids) out.putIfAbsent(u, new Entry(u, now, new JsonObject()));
        return out;
    }

    private static JsonObject parse(Path file) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (root.isJsonObject() && root.getAsJsonObject().has("stats")
                && root.getAsJsonObject().get("stats").isJsonObject()) {
            return root.getAsJsonObject().getAsJsonObject("stats");
        }
        return new JsonObject();
    }

    // ------------------------------------------------------------------ JSON pour l'API

    public JsonObject playersJson(Map<UUID, String> online, Set<String> perms) {
        JsonArray arr = new JsonArray();
        for (Entry e : loadAll(online.keySet()).values()) {
            String name = names.name(e.uuid(), online);
            if (config.isHidden(e.uuid(), name)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("name", name);
            o.addProperty("online", perms.contains("players.online") && online.containsKey(e.uuid()));
            o.addProperty("lastSeen", e.lastModified());
            o.add("summary", summary(e.stats()));
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("players", arr);
        return root;
    }

    /** null si le joueur est inconnu ou masqué. */
    public JsonObject playerJson(UUID uuid, Map<UUID, String> online, JsonObject live, Set<String> perms) {
        Entry e = loadAll(online.keySet()).get(uuid);
        if (e == null) return null;
        String name = names.name(uuid, online);
        if (config.isHidden(uuid, name)) return null;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        boolean isOnline = perms.contains("players.online") && online.containsKey(uuid);
        o.addProperty("online", isOnline);
        o.addProperty("lastSeen", e.lastModified());
        o.add("summary", summary(e.stats()));
        o.add("live", isOnline && live != null && !live.isEmpty() ? live : null);
        if (perms.contains("players.details")) {
            JsonObject stats = new JsonObject();
            for (String cat : CATEGORIES) {
                JsonObject c = e.stats().has(cat) && e.stats().get(cat).isJsonObject()
                        ? e.stats().getAsJsonObject(cat) : new JsonObject();
                stats.add(cat, c);
            }
            o.add("stats", stats);
        }
        return o;
    }

    /**
     * Totaux de tout le serveur : résumé + chaque statistique de chaque catégorie,
     * avec le nombre de joueurs qui l'ont. Format compact : {"stats": {cat: {id: [total, joueurs]}}}.
     */
    public JsonObject globalJson(Map<UUID, String> online) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String k : SUMMARY_KEYS) totals.put(k, 0L);
        Map<String, Map<String, long[]>> agg = new LinkedHashMap<>();
        for (String c : CATEGORIES) agg.put(c, new HashMap<>());

        int count = 0;
        for (Entry e : loadAll(online.keySet()).values()) {
            String name = names.name(e.uuid(), online);
            if (config.isHidden(e.uuid(), name)) continue;
            count++;
            JsonObject s = summary(e.stats());
            for (String k : SUMMARY_KEYS) totals.merge(k, s.get(k).getAsLong(), Long::sum);
            for (String c : CATEGORIES) {
                Map<String, long[]> m = agg.get(c);
                for (Map.Entry<String, JsonElement> it : category(e.stats(), c).entrySet()) {
                    long v = asLong(it.getValue());
                    if (v <= 0) continue;
                    long[] a = m.computeIfAbsent(it.getKey(), k -> new long[2]);
                    a[0] += v;
                    a[1]++;
                }
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("playerCount", count);
        JsonObject t = new JsonObject();
        totals.forEach(t::addProperty);
        root.add("totals", t);
        JsonObject stats = new JsonObject();
        for (String c : CATEGORIES) {
            JsonObject cat = new JsonObject();
            agg.get(c).forEach((id, a) -> {
                JsonArray pair = new JsonArray();
                pair.add(a[0]);
                pair.add(a[1]);
                cat.add(id, pair);
            });
            stats.add(c, cat);
        }
        root.add("stats", stats);
        return root;
    }

    /** Classement complet des joueurs pour une statistique précise (ex. ancien débris miné). */
    public JsonObject statJson(String cat, String id, Map<UUID, String> online) {
        List<Object[]> rows = new ArrayList<>();
        long total = 0;
        for (Entry e : loadAll(online.keySet()).values()) {
            String name = names.name(e.uuid(), online);
            if (config.isHidden(e.uuid(), name)) continue;
            JsonElement v = category(e.stats(), cat).get(id);
            long val = v == null ? 0 : asLong(v);
            if (val <= 0) continue;
            total += val;
            rows.add(new Object[]{e.uuid(), name, val});
        }
        rows.sort((a, b) -> Long.compare((long) b[2], (long) a[2]));
        JsonObject root = new JsonObject();
        root.addProperty("cat", cat);
        root.addProperty("id", id);
        root.addProperty("total", total);
        JsonArray arr = new JsonArray();
        for (Object[] r : rows) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", r[0].toString());
            j.addProperty("name", (String) r[1]);
            j.addProperty("value", (long) r[2]);
            arr.add(j);
        }
        root.add("players", arr);
        return root;
    }

    // ------------------------------------------------------------------ résumé

    static JsonObject summary(JsonObject stats) {
        JsonObject custom = category(stats, "minecraft:custom");
        long playTime = get(custom, "minecraft:play_time");
        if (playTime == 0) playTime = get(custom, "minecraft:play_one_minute"); // anciens mondes

        long distance = 0;
        for (Map.Entry<String, JsonElement> e : custom.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_one_cm") && !k.equals("minecraft:fall_one_cm")) distance += asLong(e.getValue());
        }

        JsonObject o = new JsonObject();
        o.addProperty("playTime", playTime);
        o.addProperty("deaths", get(custom, "minecraft:deaths"));
        o.addProperty("mobKills", get(custom, "minecraft:mob_kills"));
        o.addProperty("playerKills", get(custom, "minecraft:player_kills"));
        o.addProperty("distanceCm", distance);
        o.addProperty("mined", sum(category(stats, "minecraft:mined")));
        o.addProperty("crafted", sum(category(stats, "minecraft:crafted")));
        o.addProperty("used", sum(category(stats, "minecraft:used")));
        o.addProperty("jumps", get(custom, "minecraft:jump"));
        o.addProperty("damageDealt", get(custom, "minecraft:damage_dealt"));
        o.addProperty("damageTaken", get(custom, "minecraft:damage_taken"));
        o.addProperty("fishCaught", get(custom, "minecraft:fish_caught"));
        o.addProperty("trades", get(custom, "minecraft:traded_with_villager"));
        o.addProperty("animalsBred", get(custom, "minecraft:animals_bred"));
        return o;
    }

    private static JsonObject category(JsonObject stats, String cat) {
        JsonElement e = stats.get(cat);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static long get(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null ? 0 : asLong(e);
    }

    private static long sum(JsonObject o) {
        long s = 0;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) s += asLong(e.getValue());
        return s;
    }

    private static long asLong(JsonElement e) {
        try {
            return e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
