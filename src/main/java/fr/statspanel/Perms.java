package fr.statspanel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ce que chaque rôle a le droit de voir. Les admins voient tout. */
public final class Perms {
    public static final String PUBLIC = "public";
    public static final String MEMBER = "membre";
    public static final String ADMIN = "admin";

    public record Def(String key, String label, String help, String icon, boolean publicDefault, boolean memberDefault) {}

    public static final List<Def> ALL = List.of(
            new Def("panel.view", "Ouvrir le panel", "Sans ce droit, il faut se connecter pour voir quoi que ce soit.", "spyglass", true, true),
            new Def("server.performance", "Performances", "TPS, durée d'un tick, nombre de joueurs connectés.", "clock", true, true),
            new Def("server.history", "Graphiques", "Historique des performances sur la dernière heure.", "map", true, true),
            new Def("server.cpu", "Processeur", "Utilisation du processeur par le serveur et la machine.", "redstone", false, true),
            new Def("server.memory", "Mémoire", "Mémoire allouée et utilisée par le serveur.", "chest", false, true),
            new Def("server.worlds", "Dimensions", "Chunks chargés et entités par dimension.", "grass_block", false, true),
            new Def("server.machine", "Machine", "Système, Java, disque, taille du monde, mods installés.", "command_block", false, true),
            new Def("players.online", "Joueurs connectés", "Qui est en ligne en ce moment.", "armor_stand", true, true),
            new Def("players.coordinates", "Positions", "Coordonnées et dimension des joueurs connectés.", "compass", false, true),
            new Def("players.live", "État en direct", "Vie, faim, niveau et ping des joueurs connectés.", "golden_apple", false, true),
            new Def("players.list", "Liste des joueurs", "L'onglet Joueurs et le résumé de chaque joueur.", "name_tag", true, true),
            new Def("players.details", "Statistiques détaillées", "Tout ce qu'un joueur a miné, fabriqué, tué…", "writable_book", true, true),
            new Def("leaderboards", "Classements", "L'onglet Classements et les totaux du serveur.", "nether_star", true, true)
    );

    public static final Set<String> KEYS;

    static {
        Set<String> k = new LinkedHashSet<>();
        for (Def d : ALL) k.add(d.key());
        KEYS = Set.copyOf(k);
    }

    public static List<String> defaults(String role) {
        return ALL.stream()
                .filter(d -> PUBLIC.equals(role) ? d.publicDefault() : d.memberDefault())
                .map(Def::key).toList();
    }

    private Perms() {
    }
}
