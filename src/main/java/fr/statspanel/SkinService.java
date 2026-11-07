package fr.statspanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Récupère les skins directement chez Mojang (sessionserver → textures.minecraft.net),
 * sans site tiers, avec cache mémoire + disque. Sans skin perso : skin par défaut du jeu.
 */
public final class SkinService {
    public record Skin(byte[] png, String model, boolean isDefault) {}

    private record Cached(Skin skin, long at) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final Path dir;
    private final AssetCache assets;
    private final PanelConfig config;
    private final HttpClient http;
    private final Map<UUID, Cached> memory = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public SkinService(Path dir, AssetCache assets, PanelConfig config) {
        this.dir = dir;
        this.assets = assets;
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Skin du joueur, ou null si rien n'est disponible (le site dessine alors un visage générique). */
    public Skin get(UUID uuid, String name) {
        long ttl = config.skinCacheHours * 3_600_000L;
        long now = System.currentTimeMillis();
        Cached c = memory.get(uuid);
        if (c != null && now - c.at() < ttl) return c.skin();

        synchronized (locks.computeIfAbsent(uuid, k -> new Object())) {
            c = memory.get(uuid);
            if (c != null && now - c.at() < ttl) return c.skin();

            Path png = dir.resolve(uuid + ".png");
            Path meta = dir.resolve(uuid + ".model");
            Skin disk = readDisk(png, meta);
            try {
                if (disk != null && now - Files.getLastModifiedTime(png).toMillis() < ttl) {
                    memory.put(uuid, new Cached(disk, Files.getLastModifiedTime(png).toMillis()));
                    return disk;
                }
            } catch (IOException ignored) {
            }

            Skin fresh = null;
            boolean answered = false;  // Mojang a répondu (avec ou sans skin perso)
            try {
                UUID profile = profileId(uuid, name);
                if (profile != null) fresh = fetch(profile);
                answered = true;
            } catch (Exception e) {
                StatsPanelMod.LOG.debug("Skin de {} indisponible : {}", uuid, e.toString());
            }

            Skin result;
            if (fresh != null) {
                result = fresh;
                writeDisk(png, meta, fresh);
            } else if (!answered && disk != null) {
                result = disk;                      // Mojang injoignable : on garde l'ancien
            } else {
                result = assets.defaultSkin(uuid);  // pas de skin perso (ou rien en cache)
            }
            // En cas d'échec réseau, on réessaie dans 10 minutes au lieu d'attendre tout le cache
            long stamp = answered ? now : now - ttl + 600_000L;
            if (result != null) memory.put(uuid, new Cached(result, stamp));
            return result;
        }
    }

    /** UUID du compte premium à interroger. */
    private UUID profileId(UUID uuid, String name) throws Exception {
        if (uuid.version() == 4) return uuid;
        if (!config.skinsByNameForOfflinePlayers || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        HttpResponse<String> res = http.send(req("https://api.mojang.com/users/profiles/minecraft/" + name),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        String id = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
        return UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /** null si le compte n'a pas de skin perso. Exception si Mojang ne répond pas. */
    private Skin fetch(UUID profile) throws Exception {
        HttpResponse<String> res = http.send(req("https://sessionserver.mojang.com/session/minecraft/profile/"
                + profile.toString().replace("-", "")), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 204 || res.statusCode() == 404) return null;
        if (res.statusCode() != 200) throw new IOException("sessionserver HTTP " + res.statusCode());

        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        String texturesB64 = null;
        if (root.has("properties")) {
            for (JsonElement p : root.getAsJsonArray("properties")) {
                JsonObject o = p.getAsJsonObject();
                if ("textures".equals(o.get("name").getAsString())) texturesB64 = o.get("value").getAsString();
            }
        }
        if (texturesB64 == null) return null;

        JsonObject textures = JsonParser.parseString(
                        new String(Base64.getDecoder().decode(texturesB64), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures");
        if (textures == null || !textures.has("SKIN")) return null;
        JsonObject skin = textures.getAsJsonObject("SKIN");
        String url = skin.get("url").getAsString().replace("http://", "https://");
        URI uri = URI.create(url);
        if (uri.getHost() == null || !uri.getHost().endsWith("textures.minecraft.net")) return null;

        String model = "classic";
        if (skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString())) model = "slim";

        HttpResponse<byte[]> img = http.send(req(url), HttpResponse.BodyHandlers.ofByteArray());
        if (img.statusCode() != 200 || !isPng(img.body())) throw new IOException("texture HTTP " + img.statusCode());
        return new Skin(img.body(), model, false);
    }

    private HttpRequest req(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", "StatsPanel-FabricMod").GET().build();
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static Skin readDisk(Path png, Path meta) {
        try {
            if (!Files.exists(png)) return null;
            String model = Files.exists(meta) ? Files.readString(meta, StandardCharsets.UTF_8).trim() : "classic";
            return new Skin(Files.readAllBytes(png), "slim".equals(model) ? "slim" : "classic", false);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeDisk(Path png, Path meta, Skin skin) {
        try {
            Files.createDirectories(png.getParent());
            Files.write(png, skin.png());
            Files.writeString(meta, skin.model(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StatsPanelMod.LOG.debug("Cache de skin non écrit", e);
        }
    }
}
