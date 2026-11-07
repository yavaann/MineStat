package fr.statspanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comptes du panel (config/statspanel/users.json, mots de passe hachés en PBKDF2)
 * et sessions par cookie, gardées en mémoire.
 */
public final class AuthService {
    public static final class User {
        public String username;
        public String role;
        public String salt;
        public String hash;
        public int iterations;
        public long createdAt;
    }

    public record Session(String username, long expires) {}

    public static final class AuthException extends Exception {
        public AuthException(String msg) {
            super(msg);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int ITERATIONS = 210_000;
    private static final int MAX_FAILS = 8;
    private static final long FAIL_WINDOW_MS = 10 * 60_000L;

    private final Path file;
    private final PanelConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> fails = new ConcurrentHashMap<>();

    public AuthService(Path file, PanelConfig config) {
        this.file = file;
        this.config = config;
        load();
    }

    // ------------------------------------------------------------------ fichier

    private void load() {
        try {
            if (Files.exists(file)) {
                List<User> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<User>>() {}.getType());
                if (list != null) {
                    for (User u : list) {
                        if (u == null || u.username == null || u.hash == null || u.salt == null) continue;
                        u.role = Perms.ADMIN.equals(u.role) ? Perms.ADMIN : Perms.MEMBER;
                        users.put(key(u.username), u);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            StatsPanelMod.LOG.error("Comptes illisibles ({}) : {}", file, e.getMessage());
            return; // on n'écrase pas un fichier qu'on n'a pas su lire
        }
        if (users.values().stream().noneMatch(u -> Perms.ADMIN.equals(u.role))) {
            String pw = randomPassword();
            String name = users.containsKey("admin") ? "admin" + (100 + random.nextInt(900)) : "admin";
            users.put(key(name), newUser(name, Perms.ADMIN, pw));
            save();
            StatsPanelMod.LOG.warn("==================================================================");
            StatsPanelMod.LOG.warn(" Stats Panel : compte administrateur créé");
            StatsPanelMod.LOG.warn("   identifiant  : {}", name);
            StatsPanelMod.LOG.warn("   mot de passe : {}", pw);
            StatsPanelMod.LOG.warn(" Change-le dans l'onglet Administration du panel.");
            StatsPanelMod.LOG.warn("==================================================================");
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            List<User> list = new ArrayList<>(users.values());
            list.sort(Comparator.comparing(u -> u.username.toLowerCase(Locale.ROOT)));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(list), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            StatsPanelMod.LOG.error("Impossible d'enregistrer les comptes : {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ connexion

    /** Renvoie un jeton de session. */
    public String login(String username, String password, String ip) throws AuthException {
        long now = System.currentTimeMillis();
        Deque<Long> f = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (f) {
            while (!f.isEmpty() && now - f.peekFirst() > FAIL_WINDOW_MS) f.pollFirst();
            if (f.size() >= MAX_FAILS) throw new AuthException("Trop d'essais. Réessaie dans quelques minutes.");
        }
        User u = username == null ? null : users.get(key(username));
        boolean ok = u != null && password != null && verify(u, password);
        if (!ok) {
            if (u == null && password != null) hash(password, new byte[16], 1000); // temps de réponse similaire
            synchronized (f) {
                f.addLast(now);
            }
            throw new AuthException("Identifiant ou mot de passe incorrect.");
        }
        fails.remove(ip);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, new Session(u.username, now + config.sessionDays * 86_400_000L));
        return token;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Utilisateur de la session, ou null. */
    public User userFor(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return users.get(key(s.username()));
    }

    public long sessionMaxAgeSeconds() {
        return config.sessionDays * 86_400L;
    }

    // ------------------------------------------------------------------ gestion des comptes

    public List<User> list() {
        List<User> l = new ArrayList<>(users.values());
        l.sort(Comparator.comparing(u -> u.username.toLowerCase(Locale.ROOT)));
        return l;
    }

    /** Crée un compte, ou change le rôle et/ou le mot de passe d'un compte existant. */
    public synchronized void upsert(String username, String role, String password) throws AuthException {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{2,32}"))
            throw new AuthException("Identifiant invalide : 2 à 32 caractères, lettres, chiffres, _ . -");
        String r = Perms.ADMIN.equals(role) ? Perms.ADMIN : Perms.MEMBER;
        User existing = users.get(key(username));
        if (existing == null) {
            checkPassword(password);
            users.put(key(username), newUser(username, r, password));
        } else {
            if (Perms.ADMIN.equals(existing.role) && !Perms.ADMIN.equals(r) && adminCount() <= 1)
                throw new AuthException("Il doit rester au moins un administrateur.");
            boolean changed = !existing.role.equals(r);
            existing.role = r;
            if (password != null && !password.isEmpty()) {
                checkPassword(password);
                setPassword(existing, password);
                changed = true;
            }
            if (changed) dropSessions(existing.username);
        }
        save();
    }

    public synchronized void delete(String username) throws AuthException {
        User u = users.get(key(username));
        if (u == null) throw new AuthException("Compte inconnu.");
        if (Perms.ADMIN.equals(u.role) && adminCount() <= 1)
            throw new AuthException("Impossible de supprimer le dernier administrateur.");
        users.remove(key(username));
        dropSessions(u.username);
        save();
    }

    public synchronized void changeOwnPassword(User u, String current, String next) throws AuthException {
        if (!verify(u, current == null ? "" : current)) throw new AuthException("Mot de passe actuel incorrect.");
        checkPassword(next);
        setPassword(u, next);
        save();
    }

    private long adminCount() {
        return users.values().stream().filter(u -> Perms.ADMIN.equals(u.role)).count();
    }

    private void dropSessions(String username) {
        sessions.values().removeIf(s -> s.username().equalsIgnoreCase(username));
    }

    private static void checkPassword(String pw) throws AuthException {
        if (pw == null || pw.length() < 8) throw new AuthException("Le mot de passe doit faire au moins 8 caractères.");
        if (pw.length() > 200) throw new AuthException("Mot de passe trop long.");
    }

    // ------------------------------------------------------------------ hachage

    private User newUser(String name, String role, String password) {
        User u = new User();
        u.username = name;
        u.role = role;
        u.createdAt = System.currentTimeMillis();
        setPassword(u, password);
        return u;
    }

    private void setPassword(User u, String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        u.salt = Base64.getEncoder().encodeToString(salt);
        u.iterations = ITERATIONS;
        u.hash = Base64.getEncoder().encodeToString(hash(password, salt, ITERATIONS));
    }

    private static boolean verify(User u, String password) {
        byte[] expected = Base64.getDecoder().decode(u.hash);
        byte[] actual = hash(password, Base64.getDecoder().decode(u.salt), u.iterations > 0 ? u.iterations : ITERATIONS);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] hash(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomPassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
