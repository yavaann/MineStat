package fr.statspanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Niveaux d'XP dépensés par joueur (table d'enchantement + enclume).
 * Minecraft n'a pas de statistique pour ça : on la stocke dans world/statspanel_xp_used.json
 * plutôt que d'ajouter une stat vanilla, qui gênerait les clients sans le mod.
 */
public final class XpTracker {
    private static volatile XpTracker instance;

    private final Path file;
    private final Map<UUID, Long> levels = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    private XpTracker(Path file) {
        this.file = file;
        load();
    }

    public static void start(Path file) {
        instance = new XpTracker(file);
    }

    public static void stop() {
        XpTracker t = instance;
        instance = null;
        if (t != null) t.save();
    }

    /** Appelé depuis les mixins, sur le thread serveur. */
    public static void record(UUID uuid, int spent) {
        XpTracker t = instance;
        if (t == null || spent <= 0) return;
        t.levels.merge(uuid, (long) spent, Long::sum);
        t.dirty = true;
    }

    public static long levelsUsed(UUID uuid) {
        XpTracker t = instance;
        return t == null ? 0 : t.levels.getOrDefault(uuid, 0L);
    }

    public static void saveIfDirty() {
        XpTracker t = instance;
        if (t != null && t.dirty) t.save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                try {
                    levels.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
                } catch (RuntimeException ignored) {
                    // entrée invalide
                }
            }
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.warn("Lecture de {} impossible : {}", file, e.getMessage());
        }
    }

    private synchronized void save() {
        dirty = false;
        JsonObject o = new JsonObject();
        levels.forEach((u, v) -> o.addProperty(u.toString(), v));
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true;
            StatsPanelMod.LOG.warn("Écriture de {} impossible : {}", file, e.getMessage());
        }
    }
}
