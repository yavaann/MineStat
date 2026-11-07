package fr.statspanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Serveur HTTP embarqué : fichiers du site, API JSON, connexion et administration. */
public final class PanelWebServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String RESOURCE_ROOT = "/statspanel/web/";
    private static final String COOKIE = "sp_session";
    private static final int MAX_JSON = 64 * 1024;
    private static final int MAX_IMAGE = 25 * 1024 * 1024;
    private static final Map<String, String[]> STATIC = Map.of(
            "/", new String[]{"index.html", "text/html; charset=utf-8"},
            "/index.html", new String[]{"index.html", "text/html; charset=utf-8"},
            "/style.css", new String[]{"style.css", "text/css; charset=utf-8"},
            "/app.js", new String[]{"app.js", "text/javascript; charset=utf-8"},
            "/favicon.svg", new String[]{"favicon.svg", "image/svg+xml"});

    /** Contexte d'une requête : qui demande, avec quels droits. */
    private record Req(HttpExchange ex, String method, String path, AuthService.User user, String role, Set<String> perms,
                       boolean head) {
        boolean can(String perm) {
            return perms.contains(perm);
        }
    }

    private static final class HttpError extends Exception {
        final int status;

        HttpError(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    private final PanelConfig config;
    private final MetricsCollector collector;
    private final PlayerStatsRepository repository;
    private final AssetCache assets;
    private final SkinService skins;
    private final NameResolver names;
    private final AuthService auth;
    private final Path backgroundDir;
    private final Map<String, byte[]> staticCache = new ConcurrentHashMap<>();
    private HttpServer http;
    private ExecutorService pool;

    public PanelWebServer(PanelConfig config, MetricsCollector collector, PlayerStatsRepository repository,
                          AssetCache assets, SkinService skins, NameResolver names, AuthService auth, Path backgroundDir) {
        this.config = config;
        this.collector = collector;
        this.repository = repository;
        this.assets = assets;
        this.skins = skins;
        this.names = names;
        this.auth = auth;
        this.backgroundDir = backgroundDir;
    }

    public void start() {
        try {
            http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        } catch (BindException e) {
            StatsPanelMod.LOG.error("Port {} déjà utilisé : panel désactivé. Change \"port\" dans config/statspanel.json.",
                    config.port);
            return;
        } catch (IOException e) {
            StatsPanelMod.LOG.error("Démarrage du panel impossible sur {}:{}", config.bindAddress, config.port, e);
            return;
        }
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "StatsPanel-HTTP-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/", this::handle);
        http.start();
        StatsPanelMod.LOG.info("Panel de stats disponible sur http://{}:{}/", config.bindAddress, config.port);
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
        http = null;
        pool = null;
    }

    // ------------------------------------------------------------------ routage

    private void handle(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head && !"POST".equals(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD, POST");
                send(ex, 405, "text/plain; charset=utf-8", bytes("Méthode non autorisée"), head);
                return;
            }
            String path = ex.getRequestURI().getPath();
            AuthService.User user = auth.userFor(cookie(ex, COOKIE));
            String role = user == null ? Perms.PUBLIC : user.role;
            Req req = new Req(ex, method, path, user, role, config.permsFor(role), head);

            if (path.startsWith("/api/")) {
                try {
                    if ("POST".equals(method)) post(req, path.substring(5));
                    else get(req, path.substring(5));
                } catch (HttpError e) {
                    if ("POST".equals(method)) drain(ex);
                    JsonObject err = error(e.getMessage());
                    if (e.status == 403) err.addProperty("loginRequired", user == null);
                    sendJson(ex, e.status, err, head);
                }
                return;
            }
            String[] res = STATIC.get(path);
            if (res == null || "POST".equals(method)) {
                send(ex, 404, "text/plain; charset=utf-8", bytes("Introuvable"), head);
                return;
            }
            byte[] body = staticCache.computeIfAbsent(res[0], PanelWebServer::readResource);
            if (body.length == 0) {
                send(ex, 500, "text/plain; charset=utf-8", bytes("Ressource manquante : " + res[0]), head);
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, res[1], body, head);
        } catch (Throwable t) {
            StatsPanelMod.LOG.warn("Erreur du panel sur {}", ex.getRequestURI(), t);
            try {
                send(ex, 500, "text/plain; charset=utf-8", bytes("Erreur interne"), false);
            } catch (IOException | RuntimeException ignored) {
                // réponse déjà partiellement envoyée
            }
        } finally {
            ex.close();
        }
    }

    private void get(Req r, String route) throws IOException, HttpError {
        HttpExchange ex = r.ex();
        switch (route) {
            case "me" -> {
                sendJson(ex, 200, meJson(r), r.head());
                return;
            }
            case "ui" -> {
                sendJson(ex, 200, uiJson(), r.head());
                return;
            }
            case "lang" -> {
                ex.getResponseHeaders().set("Cache-Control", assets.ready() ? "public, max-age=3600" : "no-store");
                send(ex, 200, "application/json; charset=utf-8", assets.langJson(), r.head());
                return;
            }
            case "iconinfo" -> {
                sendJson(ex, 200, iconInfo(ex.getRequestURI()), r.head());
                return;
            }
            case "background" -> {
                background(r);
                return;
            }
            default -> {
            }
        }
        if (route.startsWith("tex/")) {
            texture(r, route.substring(4));
            return;
        }
        if (route.startsWith("admin/")) {
            requireAdmin(r);
            if (route.equals("admin/state")) {
                sendJson(ex, 200, adminState(), r.head());
                return;
            }
            throw new HttpError(404, "Route inconnue");
        }

        require(r, "panel.view");
        Map<UUID, String> online = collector.onlineNames();
        JsonElement body;
        switch (route) {
            case "server" -> body = collector.serverJson(r.perms());
            case "history" -> {
                require(r, "server.history");
                body = collector.historyJson(r.perms());
            }
            case "players" -> {
                require(r, "players.list");
                body = repository.playersJson(online, r.perms());
            }
            case "global" -> {
                require(r, "leaderboards");
                body = repository.globalJson(online);
            }
            case "stat" -> {
                require(r, "leaderboards");
                Map<String, String> q = query(ex.getRequestURI());
                String cat = q.getOrDefault("cat", "");
                String id = q.getOrDefault("id", "");
                if (!java.util.Arrays.asList(PlayerStatsRepository.CATEGORIES).contains(cat) || !id.matches("[a-z0-9_.:-]{1,100}"))
                    throw new HttpError(400, "Statistique inconnue");
                body = repository.statJson(cat, id, online);
            }
            default -> {
                if (route.startsWith("player/")) {
                    require(r, "players.list");
                    UUID uuid = parseUuid(route.substring(7));
                    if (uuid == null) throw new HttpError(400, "UUID invalide");
                    MetricsCollector.LivePlayer live = collector.live(uuid);
                    JsonObject p = repository.playerJson(uuid, online,
                            live == null ? null : collector.liveJson(live, r.perms()), r.perms());
                    if (p == null) throw new HttpError(404, "Joueur inconnu");
                    body = p;
                } else if (route.startsWith("skin/")) {
                    skin(r, route.substring(5), online);
                    return;
                } else {
                    throw new HttpError(404, "Route inconnue");
                }
            }
        }
        sendJson(ex, 200, body, r.head());
    }

    private void post(Req r, String route) throws IOException, HttpError {
        HttpExchange ex = r.ex();
        checkSameOrigin(ex);
        switch (route) {
            case "login" -> {
                JsonObject b = readJson(ex);
                try {
                    String token = auth.login(str(b, "username"), str(b, "password"), ex.getRemoteAddress().getAddress().getHostAddress());
                    setCookie(ex, token, auth.sessionMaxAgeSeconds());
                    AuthService.User u = auth.userFor(token);
                    sendJson(ex, 200, meJson(new Req(ex, "POST", "", u, u.role, config.permsFor(u.role), false)), false);
                } catch (AuthService.AuthException e) {
                    throw new HttpError(401, e.getMessage());
                }
            }
            case "logout" -> {
                auth.logout(cookie(ex, COOKIE));
                setCookie(ex, "", 0);
                sendJson(ex, 200, ok(), false);
            }
            case "account/password" -> {
                if (r.user() == null) throw new HttpError(401, "Connexion requise");
                JsonObject b = readJson(ex);
                try {
                    auth.changeOwnPassword(r.user(), str(b, "current"), str(b, "next"));
                } catch (AuthService.AuthException e) {
                    throw new HttpError(400, e.getMessage());
                }
                sendJson(ex, 200, ok(), false);
            }
            case "admin/settings" -> {
                requireAdmin(r);
                applySettings(readJson(ex));
                sendJson(ex, 200, adminState(), false);
            }
            case "admin/users" -> {
                requireAdmin(r);
                JsonObject b = readJson(ex);
                try {
                    auth.upsert(str(b, "username"), str(b, "role"), str(b, "password"));
                } catch (AuthService.AuthException e) {
                    throw new HttpError(400, e.getMessage());
                }
                sendJson(ex, 200, adminState(), false);
            }
            case "admin/users/delete" -> {
                requireAdmin(r);
                JsonObject b = readJson(ex);
                String name = str(b, "username");
                if (name != null && r.user().username.equalsIgnoreCase(name))
                    throw new HttpError(400, "Tu ne peux pas supprimer ton propre compte.");
                try {
                    auth.delete(name);
                } catch (AuthService.AuthException e) {
                    throw new HttpError(400, e.getMessage());
                }
                sendJson(ex, 200, adminState(), false);
            }
            case "admin/background" -> {
                if (r.user() == null || !Perms.ADMIN.equals(r.user().role)) {
                    try (InputStream in = ex.getRequestBody()) {
                        in.transferTo(OutputStream.nullOutputStream());
                    }
                }
                requireAdmin(r);
                uploadBackground(ex);
                sendJson(ex, 200, adminState(), false);
            }
            case "admin/background/delete" -> {
                requireAdmin(r);
                deleteBackground();
                sendJson(ex, 200, adminState(), false);
            }
            case "admin/assets/retry" -> {
                requireAdmin(r);
                JsonObject b = readJson(ex);
                assets.start(b.has("force") && b.get("force").getAsBoolean());
                sendJson(ex, 200, adminState(), false);
            }
            default -> throw new HttpError(404, "Route inconnue");
        }
    }

    // ------------------------------------------------------------------ droits

    private static void require(Req r, String perm) throws HttpError {
        if (!r.can(perm)) throw new HttpError(403, r.user() == null
                ? "Connecte-toi pour voir cette partie du panel."
                : "Ton compte n'a pas accès à cette partie du panel.");
    }

    private static void requireAdmin(Req r) throws HttpError {
        if (r.user() == null) throw new HttpError(401, "Connexion requise");
        if (!Perms.ADMIN.equals(r.user().role)) throw new HttpError(403, "Réservé aux administrateurs");
    }

    /** Bloque les requêtes POST venant d'un autre site (en plus du cookie SameSite=Strict). */
    private static void checkSameOrigin(HttpExchange ex) throws HttpError {
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-Panel"))) throw new HttpError(403, "Requête refusée");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && host != null) {
            try {
                URI o = URI.create(origin);
                String oh = o.getHost() + (o.getPort() > 0 ? ":" + o.getPort() : "");
                if (!oh.equalsIgnoreCase(host) && !o.getHost().equalsIgnoreCase(host.replaceFirst(":\\d+$", "")))
                    throw new HttpError(403, "Origine refusée");
            } catch (IllegalArgumentException e) {
                throw new HttpError(403, "Origine refusée");
            }
        }
    }

    // ------------------------------------------------------------------ JSON

    private JsonObject meJson(Req r) {
        JsonObject o = new JsonObject();
        if (r.user() != null) {
            JsonObject u = new JsonObject();
            u.addProperty("username", r.user().username);
            u.addProperty("role", r.user().role);
            o.add("user", u);
        } else {
            o.add("user", null);
        }
        JsonArray p = new JsonArray();
        r.perms().forEach(p::add);
        o.add("perms", p);
        // Ce qu'un visiteur gagnerait à se connecter (pour l'inviter à le faire)
        JsonArray locked = new JsonArray();
        Set<String> member = config.permsFor(Perms.MEMBER);
        for (Perms.Def d : Perms.ALL) {
            if (!r.perms().contains(d.key()) && member.contains(d.key())) locked.add(d.label());
        }
        o.add("lockedForVisitors", locked);
        return o;
    }

    private JsonObject uiJson() {
        JsonObject o = new JsonObject();
        o.addProperty("theme", config.theme);
        o.addProperty("headingFont", config.headingFont);
        o.addProperty("density", config.density);
        o.addProperty("showIcons", config.showIcons);
        o.addProperty("allowVisitorStyle", config.allowVisitorStyle);
        o.addProperty("styleRevision", config.styleRevision);
        boolean hasImage = backgroundFile() != null;
        String mode = config.backgroundMode;
        String bg = switch (mode) {
            case "image" -> hasImage ? "image" : "paysage";
            case "paysage" -> "paysage";
            case "aucun" -> "aucun";
            default -> hasImage ? "image" : "paysage";
        };
        o.addProperty("background", bg);
        o.addProperty("backgroundVersion", hasImage ? lastModified(backgroundFile()) : 0);
        o.addProperty("assetsReady", assets.ready());
        o.addProperty("title", config.panelTitle);
        return o;
    }

    private JsonObject adminState() {
        JsonObject o = new JsonObject();
        JsonArray catalog = new JsonArray();
        for (Perms.Def d : Perms.ALL) {
            JsonObject j = new JsonObject();
            j.addProperty("key", d.key());
            j.addProperty("label", d.label());
            j.addProperty("help", d.help());
            j.addProperty("icon", d.icon());
            catalog.add(j);
        }
        o.add("catalog", catalog);
        o.add("permissions", GSON.toJsonTree(config.permissions));

        JsonArray users = new JsonArray();
        for (AuthService.User u : auth.list()) {
            JsonObject j = new JsonObject();
            j.addProperty("username", u.username);
            j.addProperty("role", u.role);
            j.addProperty("createdAt", u.createdAt);
            users.add(j);
        }
        o.add("users", users);

        JsonObject s = new JsonObject();
        s.addProperty("panelTitle", config.panelTitle);
        s.add("hiddenPlayers", GSON.toJsonTree(config.hiddenPlayers));
        s.addProperty("theme", config.theme);
        s.addProperty("headingFont", config.headingFont);
        s.addProperty("density", config.density);
        s.addProperty("showIcons", config.showIcons);
        s.addProperty("allowVisitorStyle", config.allowVisitorStyle);
        s.addProperty("backgroundMode", config.backgroundMode);
        o.add("settings", s);
        o.addProperty("hasBackground", backgroundFile() != null);
        o.add("assets", assets.statusJson());
        return o;
    }

    private void applySettings(JsonObject b) {
        synchronized (config) {
            String before = config.theme + "|" + config.headingFont + "|" + config.density + "|" + config.showIcons;
            if (b.has("panelTitle")) config.panelTitle = str(b, "panelTitle");
            if (b.has("theme")) config.theme = str(b, "theme");
            if (b.has("headingFont")) config.headingFont = str(b, "headingFont");
            if (b.has("density")) config.density = str(b, "density");
            if (b.has("backgroundMode")) config.backgroundMode = str(b, "backgroundMode");
            if (b.has("showIcons")) config.showIcons = b.get("showIcons").getAsBoolean();
            if (b.has("allowVisitorStyle")) config.allowVisitorStyle = b.get("allowVisitorStyle").getAsBoolean();
            if (b.has("hiddenPlayers") && b.get("hiddenPlayers").isJsonArray()) {
                List<String> l = new ArrayList<>();
                b.getAsJsonArray("hiddenPlayers").forEach(e -> l.add(e.getAsString()));
                config.hiddenPlayers = l;
            }
            if (b.has("permissions") && b.get("permissions").isJsonObject()) {
                JsonObject p = b.getAsJsonObject("permissions");
                Map<String, List<String>> m = new java.util.LinkedHashMap<>();
                for (String role : List.of(Perms.PUBLIC, Perms.MEMBER)) {
                    List<String> l = new ArrayList<>();
                    if (p.has(role) && p.get(role).isJsonArray()) p.getAsJsonArray(role).forEach(e -> l.add(e.getAsString()));
                    m.put(role, l);
                }
                config.permissions = m;
            }
            config.sanitize();
            String after = config.theme + "|" + config.headingFont + "|" + config.density + "|" + config.showIcons;
            if (!before.equals(after)) config.styleRevision = System.currentTimeMillis();
            config.save();
        }
    }

    private JsonObject iconInfo(URI uri) {
        JsonObject out = new JsonObject();
        String q = uri.getRawQuery();
        if (q == null) return out;
        for (String part : q.split("&")) {
            if (!part.startsWith("k=")) continue;
            String list = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            String[] keys = list.split(",");
            for (int i = 0; i < Math.min(keys.length, 300); i++) {
                String k = keys[i];
                int c = k.indexOf(':');
                if (c <= 0) continue;
                String kind = k.substring(0, c);
                String id = k.substring(c + 1);
                if (!kind.equals("item") && !kind.equals("entity")) continue;
                out.add(k, assets.recipe(kind, id));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ fichiers binaires

    private void texture(Req r, String rest) throws IOException, HttpError {
        int slash = rest.indexOf('/');
        if (slash <= 0) throw new HttpError(404, "Texture inconnue");
        String folder = rest.substring(0, slash);
        String name = rest.substring(slash + 1);
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        byte[] png = assets.texture(folder, name);
        if (png == null) {
            r.ex().getResponseHeaders().set("Cache-Control", "no-store");
            throw new HttpError(404, "Texture inconnue");
        }
        r.ex().getResponseHeaders().set("Cache-Control", "public, max-age=604800");
        send(r.ex(), 200, "image/png", png, r.head());
    }

    private void skin(Req r, String id, Map<UUID, String> online) throws IOException, HttpError {
        UUID uuid = parseUuid(id);
        if (uuid == null) throw new HttpError(400, "UUID invalide");
        SkinService.Skin skin = skins.get(uuid, names.name(uuid, online));
        if (skin == null) {
            r.ex().getResponseHeaders().set("Cache-Control", "no-store");
            throw new HttpError(404, "Pas de skin");
        }
        Headers h = r.ex().getResponseHeaders();
        h.set("X-Skin-Model", skin.model());
        h.set("X-Skin-Default", String.valueOf(skin.isDefault()));
        h.set("Cache-Control", "private, max-age=600");
        send(r.ex(), 200, "image/png", skin.png(), r.head());
    }

    private Path backgroundFile() {
        for (String ext : List.of("jpg", "png", "webp")) {
            Path p = backgroundDir.resolve("background." + ext);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private void background(Req r) throws IOException, HttpError {
        Path p = backgroundFile();
        if (p == null) throw new HttpError(404, "Pas d'image de fond");
        String name = p.getFileName().toString();
        String type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        r.ex().getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        send(r.ex(), 200, type, Files.readAllBytes(p), r.head());
    }

    private void uploadBackground(HttpExchange ex) throws IOException, HttpError {
        byte[] data = readBody(ex, MAX_IMAGE);
        String ext;
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') ext = "png";
        else if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) ext = "jpg";
        else if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') ext = "webp";
        else throw new HttpError(400, "Format non reconnu : envoie un PNG, un JPEG ou un WebP.");
        deleteBackground();
        Files.createDirectories(backgroundDir);
        Files.write(backgroundDir.resolve("background." + ext), data);
    }

    private void deleteBackground() throws IOException {
        for (String ext : List.of("jpg", "png", "webp")) Files.deleteIfExists(backgroundDir.resolve("background." + ext));
    }

    // ------------------------------------------------------------------ utilitaires HTTP

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static void setCookie(HttpExchange ex, String value, long maxAge) {
        boolean https = "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + maxAge + (https ? "; Secure" : ""));
    }

    private static byte[] readBody(HttpExchange ex, int max) throws IOException, HttpError {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        try (InputStream in = ex.getRequestBody()) {
            long declared = -1;
            try {
                if (len != null) declared = Long.parseLong(len.trim());
            } catch (NumberFormatException ignored) {
            }
            // On lit tout le corps avant de répondre : sinon le navigateur voit la connexion
            // coupée en plein envoi et affiche « Failed to fetch » au lieu du message d'erreur.
            if (declared > max) {
                in.transferTo(OutputStream.nullOutputStream());
                throw new HttpError(413, "Fichier trop gros (max " + (max / 1024 / 1024) + " Mo)");
            }
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) {
                in.transferTo(OutputStream.nullOutputStream());
                throw new HttpError(413, "Fichier trop gros (max " + (max / 1024 / 1024) + " Mo)");
            }
            return data;
        }
    }

    /** Vide ce qui reste du corps de la requête, pour que le navigateur reçoive bien la réponse d'erreur. */
    private static void drain(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private static JsonObject readJson(HttpExchange ex) throws IOException, HttpError {
        byte[] data = readBody(ex, MAX_JSON);
        if (data.length == 0) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) throw new HttpError(400, "JSON attendu");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new HttpError(400, "JSON invalide");
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new java.util.HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private static void sendJson(HttpExchange ex, int status, JsonElement body, boolean head) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "application/json; charset=utf-8", bytes(GSON.toJson(body)), head);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean head) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "SAMEORIGIN");
        if (head) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static byte[] readResource(String name) {
        try (InputStream in = PanelWebServer.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
