package fr.statspanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Textures et traductions officielles de la version du serveur.
 * Premier lancement : téléchargement du client (vérifié par SHA-1) et extraction des textures de blocs,
 * d'objets et des skins par défaut, puis du fichier de langue. Tout reste dans config/statspanel/cache/.
 * Le serveur ne fait que servir les fichiers et indiquer quelles textures composent chaque icône :
 * le dessin (cubes isométriques, teintes) est fait par le navigateur.
 */
public final class AssetCache {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String[] LANG_PREFIXES = {
            "block.minecraft.", "item.minecraft.", "entity.minecraft.", "stat.minecraft.", "stat_type.minecraft."
    };
    private static final String[] DEFAULT_SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
    private static final JsonObject MISS = new JsonObject();

    private final Path root;
    private final String detectedVersion;
    private final PanelConfig config;
    private final HttpClient http;
    private final ExecutorService exec;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, JsonObject> recipes = new ConcurrentHashMap<>();

    private volatile Path dir;
    private volatile boolean ready;
    private volatile String status = "en préparation";
    private volatile String lastError = "";
    private volatile String version = "";
    private volatile byte[] langJson = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile Set<String> blockTex = Set.of();
    private volatile Set<String> itemTex = Set.of();

    public AssetCache(Path cacheRoot, String detectedVersion, PanelConfig config) {
        // Chemin absolu : avec un chemin relatif ("./config/…"), le contrôle anti zip-slip rejetait tous les fichiers
        this.root = cacheRoot.toAbsolutePath().normalize();
        this.detectedVersion = detectedVersion;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StatsPanel-Assets");
            t.setDaemon(true);
            return t;
        });
        start(false);
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    /** Relance la préparation ; force = supprime le cache et retélécharge. */
    public boolean start(boolean force) {
        if (!running.compareAndSet(false, true)) return false;
        exec.submit(() -> {
            try {
                prepare(force);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean ready() {
        return ready;
    }

    public byte[] langJson() {
        return langJson;
    }

    public JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ready", ready);
        o.addProperty("running", running.get());
        o.addProperty("status", status);
        o.addProperty("error", lastError);
        o.addProperty("version", version.isEmpty() ? detectedVersion : version);
        o.addProperty("blocks", blockTex.size());
        o.addProperty("items", itemTex.size());
        o.addProperty("folder", dir == null ? root.toString() : dir.toString());
        return o;
    }

    // ------------------------------------------------------------------ préparation

    private void prepare(boolean force) {
        ready = false;
        recipes.clear();
        lastError = "";
        try {
            List<String> candidates = versionCandidates();
            Path chosen = null;
            for (String v : candidates) {
                Path d = root.resolve(safe(v));
                if (force) deleteTree(d);
                if (Files.exists(d.resolve(".complete"))) {
                    if (listPng(d.resolve("textures/block")).isEmpty() && listPng(d.resolve("textures/item")).isEmpty()) {
                        // cache vide laissé par une ancienne version du mod : on le refait
                        StatsPanelMod.LOG.warn("Cache de textures vide dans {}, nouveau téléchargement", d);
                        deleteTree(d);
                        continue;
                    }
                    chosen = d;
                    version = v;
                    break;
                }
            }
            if (chosen == null) {
                if (!config.downloadGameAssets) {
                    status = "téléchargement désactivé (downloadGameAssets)";
                    return;
                }
                status = "téléchargement des textures";
                StatsPanelMod.LOG.info("Téléchargement des textures de Minecraft (essais : {})…", candidates);
                chosen = downloadClientAssets(candidates);
            }
            dir = chosen;
            String lang = config.language;
            if (!"en_us".equals(lang) && !Files.exists(chosen.resolve("lang/" + lang + ".json")) && config.downloadGameAssets) {
                status = "téléchargement de la langue";
                downloadLanguage(chosen, lang);
            }
            blockTex = listPng(chosen.resolve("textures/block"));
            itemTex = listPng(chosen.resolve("textures/item"));
            if (blockTex.isEmpty() && itemTex.isEmpty()) {
                Files.deleteIfExists(chosen.resolve(".complete"));
                throw new IOException("aucune texture trouvée dans " + chosen);
            }
            loadLanguage(chosen, lang);
            ready = true;
            status = "prêt";
            StatsPanelMod.LOG.info("Textures prêtes ({}) : {} blocs, {} objets", version, blockTex.size(), itemTex.size());
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + " : " + e.getMessage();
            status = "échec";
            StatsPanelMod.LOG.warn("Textures indisponibles : {}. Le panel fonctionne sans icônes.", lastError);
        }
    }

    private List<String> versionCandidates() {
        Set<String> c = new LinkedHashSet<>();
        if (!config.gameVersionOverride.isBlank()) c.add(config.gameVersionOverride.trim());
        String v = detectedVersion.trim();
        c.add(v);
        int plus = v.indexOf('+');
        if (plus > 0) c.add(v.substring(0, plus));
        if (v.endsWith(".0")) c.add(v.substring(0, v.length() - 2));
        else if (v.chars().filter(ch -> ch == '.').count() == 1) c.add(v + ".0");
        return new ArrayList<>(c);
    }

    private Path downloadClientAssets(List<String> candidates) throws Exception {
        JsonObject manifest = getJson(MANIFEST);
        String versionUrl = null;
        String id = null;
        outer:
        for (String want : candidates) {
            for (JsonElement el : manifest.getAsJsonArray("versions")) {
                JsonObject o = el.getAsJsonObject();
                if (want.equals(o.get("id").getAsString())) {
                    versionUrl = o.get("url").getAsString();
                    id = want;
                    break outer;
                }
            }
        }
        if (versionUrl == null) {
            throw new IOException("aucune de ces versions n'existe chez Mojang : " + candidates
                    + ". Renseigne gameVersionOverride dans la config.");
        }

        JsonObject ver = getJson(versionUrl);
        JsonObject client = ver.getAsJsonObject("downloads").getAsJsonObject("client");
        String jarUrl = client.get("url").getAsString();
        String sha1 = client.get("sha1").getAsString();

        Path d = root.resolve(safe(id)).toAbsolutePath().normalize();
        Files.createDirectories(d);
        Files.writeString(d.resolve("assetindex.url"),
                ver.getAsJsonObject("assetIndex").get("url").getAsString(), StandardCharsets.UTF_8);

        Path tmp = d.resolve("client.jar.part");
        HttpResponse<Path> res = http.send(request(jarUrl, Duration.ofMinutes(10)), HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() != 200) throw new IOException("client.jar → HTTP " + res.statusCode());
        if (!sha1.equalsIgnoreCase(sha1(tmp))) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA-1 du client invalide (téléchargement corrompu)");
        }

        int extracted = 0;
        try (ZipFile zip = new ZipFile(tmp.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String rel = null;
                if (name.startsWith("assets/minecraft/") && name.endsWith(".png")) {
                    String r = name.substring("assets/minecraft/".length());
                    if (r.startsWith("textures/block/") || r.startsWith("textures/item/")
                            || r.startsWith("textures/entity/player/")) rel = r;
                } else if (name.equals("assets/minecraft/lang/en_us.json")) {
                    rel = "lang/en_us.json";
                }
                if (rel == null) continue;
                Path out = d.resolve(rel).normalize();
                if (!out.startsWith(d)) continue;
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (extracted < 50) throw new IOException("seulement " + extracted + " fichiers trouvés dans le client");
        Files.writeString(d.resolve(".complete"), id, StandardCharsets.UTF_8);
        version = id;
        return d;
    }

    private void downloadLanguage(Path d, String lang) {
        try {
            String indexUrl = Files.readString(d.resolve("assetindex.url"), StandardCharsets.UTF_8).trim();
            JsonObject objects = getJson(indexUrl).getAsJsonObject("objects");
            JsonElement obj = objects.get("minecraft/lang/" + lang + ".json");
            if (obj == null) {
                StatsPanelMod.LOG.warn("Langue {} inconnue, noms en anglais", lang);
                return;
            }
            String hash = obj.getAsJsonObject().get("hash").getAsString();
            HttpResponse<byte[]> res = http.send(request(
                            "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash,
                            Duration.ofSeconds(30)),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            Path out = d.resolve("lang/" + lang + ".json");
            Files.createDirectories(out.getParent());
            Files.write(out, res.body());
        } catch (Exception e) {
            StatsPanelMod.LOG.warn("Fichier de langue {} indisponible : {}", lang, e.toString());
        }
    }

    private static Set<String> listPng(Path folder) throws IOException {
        Set<String> set = new HashSet<>();
        if (!Files.isDirectory(folder)) return Set.of();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "*.png")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                set.add(n.substring(0, n.length() - 4));
            }
        }
        return Set.copyOf(set);
    }

    private void loadLanguage(Path d, String lang) {
        JsonObject merged = new JsonObject();
        mergeLang(merged, d.resolve("lang/en_us.json"));
        if (!"en_us".equals(lang)) mergeLang(merged, d.resolve("lang/" + lang + ".json"));
        langJson = merged.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void mergeLang(JsonObject into, Path file) {
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                for (String p : LANG_PREFIXES) {
                    if (e.getKey().startsWith(p)) {
                        into.add(e.getKey(), e.getValue());
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.debug("Langue illisible : {}", file, e);
        }
    }

    // ------------------------------------------------------------------ fichiers servis

    /** Octets d'une texture (folder = block ou item), ou null. */
    public byte[] texture(String folder, String name) {
        Path d = dir;
        if (!ready || d == null || !name.matches("[a-z0-9_]{1,80}")) return null;
        Set<String> set = "block".equals(folder) ? blockTex : "item".equals(folder) ? itemTex : Set.of();
        if (!set.contains(name)) return null;
        try {
            return Files.readAllBytes(d.resolve("textures/" + folder + "/" + name + ".png"));
        } catch (IOException e) {
            return null;
        }
    }

    /** Skin par défaut attribué par le jeu à cet UUID, ou null. */
    public SkinService.Skin defaultSkin(UUID uuid) {
        Path d = dir;
        if (!ready || d == null) return null;
        int idx = Math.floorMod(uuid.hashCode(), DEFAULT_SKINS.length * 2);
        boolean slim = idx < DEFAULT_SKINS.length;
        Path p = d.resolve("textures/entity/player/" + (slim ? "slim/" : "wide/") + DEFAULT_SKINS[idx % DEFAULT_SKINS.length] + ".png");
        String model = slim ? "slim" : "classic";
        try {
            if (!Files.exists(p)) {
                p = d.resolve("textures/entity/player/wide/steve.png");
                model = "classic";
            }
            if (!Files.exists(p)) return null;
            return new SkinService.Skin(Files.readAllBytes(p), model, true);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ recettes d'icônes

    /**
     * Comment dessiner une icône. kind = "item" (bloc ou objet) ou "entity" (créature).
     * Réponse : {"t":"flat","src":"item/diamond"} ou {"t":"cube","top":..,"left":..,"right":..,"tint":{..},"overlay":..}
     * ou null si rien ne correspond.
     */
    public JsonObject recipe(String kind, String id) {
        if (!ready || !id.matches("[a-z0-9_]{1,64}")) return null;
        JsonObject r = recipes.computeIfAbsent(kind + ":" + id, k -> {
            JsonObject o = "entity".equals(kind) ? entityRecipe(id) : itemRecipe(id);
            return o == null ? MISS : o;
        });
        return r == MISS ? null : r;
    }

    private JsonObject entityRecipe(String id) {
        String egg = switch (id) {
            case "ender_dragon" -> "dragon_head";
            case "wither" -> "nether_star";
            case "player" -> "player_head";
            case "giant" -> "zombie_spawn_egg";
            case "illusioner" -> "pillager_spawn_egg";
            case "snow_golem" -> "carved_pumpkin";
            case "iron_golem" -> "iron_golem_spawn_egg";
            default -> id + "_spawn_egg";
        };
        if (itemTex.contains(egg)) return flat("item/" + egg);
        JsonObject r = itemRecipe(egg);
        return r != null ? r : itemRecipe(id);
    }

    private JsonObject itemRecipe(String id) {
        for (String cand : candidates(id)) {
            if (itemTex.contains(cand)) return flat("item/" + cand);
            // Objets animés ou à états : montre (clock_00), boussoles (compass_00), arbalète (crossbow_standby)…
            for (String v : new String[]{cand + "_00", cand + "_standby", cand + "_0"}) {
                if (itemTex.contains(v)) return flat("item/" + v);
            }
            JsonObject block = blockRecipe(cand);
            if (block != null) return block;
        }
        return null;
    }

    private JsonObject blockRecipe(String n) {
        String top = first(n + "_top", n, n + "_end", n + "_side", n + "_front");
        String side = first(n + "_side", n, n + "_front", n + "_top");
        String front = first(n + "_front", n + "_side", n, n + "_top");
        if (top == null && side == null) {
            String still = first(n + "_still", n + "_0", n + "_stage0", n + "_stage_0", n + "_bottom");
            if (still == null) return null;
            JsonObject f = flat("block/" + still);
            tint(f, "src", still);
            return f;
        }
        if (top == null) top = side;
        if (side == null) side = top;
        if (front == null) front = side;

        JsonObject o = new JsonObject();
        o.addProperty("t", "cube");
        o.addProperty("top", "block/" + top);
        o.addProperty("left", "block/" + front);
        o.addProperty("right", "block/" + side);
        JsonObject tint = new JsonObject();
        addTint(tint, "top", top);
        addTint(tint, "left", front);
        addTint(tint, "right", side);
        if (!tint.isEmpty()) o.add("tint", tint);
        if (blockTex.contains(n + "_side_overlay")) { // herbe, mycélium enneigé…
            o.addProperty("overlay", "block/" + n + "_side_overlay");
            String c = tintColor(n + "_side_overlay");
            o.addProperty("overlayTint", c != null ? c : "#91bd59");
        }
        return o;
    }

    private static JsonObject flat(String src) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "flat");
        o.addProperty("src", src);
        return o;
    }

    private static void tint(JsonObject o, String key, String tex) {
        String c = tintColor(tex);
        if (c == null) return;
        JsonObject t = new JsonObject();
        t.addProperty(key, c);
        o.add("tint", t);
    }

    private static void addTint(JsonObject t, String face, String tex) {
        String c = tintColor(tex);
        if (c != null) t.addProperty(face, c);
    }

    /** Teinte appliquée par le jeu (herbe, feuilles, eau…), ou null. */
    static String tintColor(String tex) {
        if (tex == null) return null;
        if (tex.contains("cherry") || tex.contains("azalea") || tex.contains("pale_oak")) return null;
        if (tex.startsWith("spruce_leaves")) return "#619961";
        if (tex.startsWith("birch_leaves")) return "#80a755";
        if (tex.contains("leaves")) return "#48b518";
        if (tex.equals("grass_block_top") || tex.equals("grass_block_side_overlay") || tex.startsWith("short_grass")
                || tex.startsWith("tall_grass") || tex.equals("fern") || tex.startsWith("large_fern")
                || tex.equals("vine") || tex.equals("lily_pad") || tex.equals("sugar_cane") || tex.equals("bush"))
            return "#91bd59";
        if (tex.startsWith("water")) return "#3f76e4";
        return null;
    }

    private static List<String> candidates(String id) {
        Set<String> c = new LinkedHashSet<>();
        c.add(id);
        String n = id;
        if (n.startsWith("potted_")) c.add(n = n.substring(7));
        if (n.startsWith("wall_")) c.add(n.substring(5));
        if (n.contains("_wall_")) c.add(n.replace("_wall_", "_"));
        if (n.endsWith("_carpet")) c.add(n.replace("_carpet", "_wool"));
        if (n.startsWith("infested_")) c.add(n.substring(9));
        if (n.startsWith("waxed_")) c.add(n = n.substring(6));
        if (n.endsWith("_cauldron")) c.add("cauldron");
        if (n.equals("water") || n.equals("lava")) c.add(n + "_bucket");
        if (n.startsWith("attached_")) c.add(n.substring(9));
        if (n.equals("redstone_wire")) c.add("redstone");
        if (n.equals("tripwire")) c.add("string");
        if (n.endsWith("_bed")) c.add(n.replace("_bed", "_wool"));
        if (n.endsWith("_log") || n.endsWith("_stem")) c.add(n.replace("_log", "_planks").replace("_stem", "_planks"));
        String[] suffixes = {"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_pressure_plate", "_button",
                "_hanging_sign", "_sign", "_trapdoor"};
        for (String s : suffixes) {
            if (n.endsWith(s)) {
                String base = n.substring(0, n.length() - s.length());
                c.add(base);
                c.add(base + "s");
                c.add(base + "_planks");
                c.add(base + "_block");
                if (base.startsWith("smooth_")) c.add(base.substring(7));
                break;
            }
        }
        return new ArrayList<>(c);
    }

    private String first(String... names) {
        for (String n : names) if (blockTex.contains(n)) return n;
        return null;
    }

    // ------------------------------------------------------------------ utilitaires

    private HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", "StatsPanel-FabricMod").GET().build();
    }

    private JsonObject getJson(String url) throws Exception {
        HttpResponse<String> res = http.send(request(url, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException(url + " → HTTP " + res.statusCode());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String safe(String v) {
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
