package fr.statspanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * config/statspanel.json. Relu au démarrage ; les réglages modifiés depuis le panel admin
 * sont appliqués tout de suite et réécrits dans le fichier.
 */
public final class PanelConfig {
    // ---------------------------------------------------------------- réseau (redémarrage nécessaire)
    public int port = 8765;
    public String bindAddress = "0.0.0.0";
    /** Durée de connexion avant de devoir se reconnecter, en jours. */
    public int sessionDays = 14;

    // ---------------------------------------------------------------- collecte
    public int clientRefreshSeconds = 5;
    public int historyIntervalSeconds = 10;
    public int historyPoints = 360;
    public int statsFlushSeconds = 120;
    public boolean resolveNamesWithMojang = true;

    // ---------------------------------------------------------------- modifiables depuis le panel admin
    /** Titre affiché en haut du panel. Vide = MOTD du serveur. */
    public String panelTitle = "";
    /** Joueurs masqués (pseudos ou UUID). */
    public List<String> hiddenPlayers = new ArrayList<>();
    /** Droits des visiteurs ("public") et des comptes membres ("membre"). Les admins voient tout. */
    public Map<String, List<String>> permissions = new LinkedHashMap<>();
    /** auto | calcite | deepslate | nether | end | inventaire */
    public String theme = "auto";
    /** pixel | classique */
    public String headingFont = "pixel";
    /** confortable | compacte */
    public String density = "confortable";
    public boolean showIcons = true;
    public boolean allowVisitorStyle = true;
    /** Change quand l'admin modifie le style par défaut : les réglages perso des visiteurs plus anciens sont oubliés. */
    public long styleRevision = 0;
    /** auto (image envoyée sinon paysage généré) | paysage | image | aucun */
    public String backgroundMode = "auto";

    // ---------------------------------------------------------------- ressources du jeu
    public boolean downloadGameAssets = true;
    /** Version à télécharger si la détection automatique échoue (ex. "26.3"). Vide = automatique. */
    public String gameVersionOverride = "";
    public String language = "fr_fr";
    public int skinCacheHours = 6;
    public boolean skinsByNameForOfflinePlayers = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private transient Path file;

    public static PanelConfig load(Path file) {
        PanelConfig cfg = new PanelConfig();
        boolean readable = true;
        if (Files.exists(file)) {
            try {
                PanelConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PanelConfig.class);
                if (read != null) cfg = read;
            } catch (IOException | JsonParseException e) {
                StatsPanelMod.LOG.error("Config {} illisible ({}), valeurs par défaut utilisées sans écraser le fichier",
                        file, e.getMessage());
                readable = false;
            }
        }
        cfg.file = readable ? file : null;
        cfg.sanitize();
        cfg.save();
        return cfg;
    }

    /** Réécrit le fichier (écriture atomique). */
    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            StatsPanelMod.LOG.warn("Impossible d'écrire {} : {}", file, e.getMessage());
        }
    }

    synchronized void sanitize() {
        if (port < 1 || port > 65535) port = 8765;
        if (bindAddress == null || bindAddress.isBlank()) bindAddress = "0.0.0.0";
        sessionDays = clamp(sessionDays, 1, 365);
        if (panelTitle == null) panelTitle = "";
        panelTitle = panelTitle.length() > 80 ? panelTitle.substring(0, 80) : panelTitle;
        clientRefreshSeconds = clamp(clientRefreshSeconds, 1, 300);
        historyIntervalSeconds = clamp(historyIntervalSeconds, 1, 3600);
        historyPoints = clamp(historyPoints, 10, 10_000);
        statsFlushSeconds = clamp(statsFlushSeconds, 0, 3600);
        if (hiddenPlayers == null) hiddenPlayers = new ArrayList<>();
        hiddenPlayers = new ArrayList<>(hiddenPlayers.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList());

        Map<String, List<String>> perms = new LinkedHashMap<>();
        for (String role : List.of(Perms.PUBLIC, Perms.MEMBER)) {
            List<String> given = permissions == null ? null : permissions.get(role);
            List<String> clean = given == null ? Perms.defaults(role)
                    : given.stream().filter(Perms.KEYS::contains).distinct().toList();
            perms.put(role, new ArrayList<>(clean));
        }
        permissions = perms;

        theme = oneOf(theme, "auto", "auto", "calcite", "deepslate", "nether", "end", "inventaire");
        headingFont = oneOf(headingFont, "pixel", "pixel", "classique");
        density = oneOf(density, "confortable", "confortable", "compacte");
        backgroundMode = oneOf(backgroundMode, "auto", "auto", "paysage", "image", "aucun");
        if (gameVersionOverride == null) gameVersionOverride = "";
        language = language == null || !language.matches("[a-z]{2,3}_[a-z]{2,3}") ? "fr_fr" : language;
        skinCacheHours = clamp(skinCacheHours, 1, 24 * 30);
    }

    /** Droits effectifs d'un rôle. Un membre voit au moins ce que voit un visiteur. */
    public Set<String> permsFor(String role) {
        if (Perms.ADMIN.equals(role)) return Perms.KEYS;
        Set<String> s = new LinkedHashSet<>(permissions.getOrDefault(Perms.PUBLIC, List.of()));
        if (Perms.MEMBER.equals(role)) s.addAll(permissions.getOrDefault(Perms.MEMBER, List.of()));
        return Set.copyOf(s);
    }

    public boolean isHidden(UUID uuid, String name) {
        List<String> list = hiddenPlayers;
        if (list.isEmpty()) return false;
        String id = uuid.toString();
        String idNoDash = id.replace("-", "");
        for (String h : list) {
            String v = h.trim().toLowerCase(Locale.ROOT);
            if (v.equals(id) || v.equals(idNoDash)) return true;
            if (name != null && v.equals(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static String oneOf(String value, String fallback, String... allowed) {
        if (value != null) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (String a : allowed) if (a.equals(v)) return a;
        }
        return fallback;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
