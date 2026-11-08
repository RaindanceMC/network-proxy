package de.raindancer.proxy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Lists of players who may enter a gated server (a closed beta), kept in the network's database in the proxy's own
 * namespace, so every proxy with database access asks the same list. Only somebody on the network's whitelist (Core's
 * table) can be put on one: the name is looked up there, nobody new gets past the door by being on a server's list.
 *
 * <p>With the database out of reach the lists read last decide; with nothing read yet a gated server stays shut.
 */
public final class ServerAccess {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public record Entry(UUID player, String name, long addedAt, String addedBy) {
    }

    public record Known(UUID player, String name) {
    }

    private final WhitelistGate.Connector database;
    private final String own;
    private final String core;
    private final LongSupplier clock;
    private volatile Map<String, Set<UUID>> lastRead;
    private volatile Optional<String> lastError = Optional.empty();

    /**
     * @param own  the proxy's namespace, where the lists live
     * @param core RainsCore's, for the whitelist names are looked up in
     */
    public ServerAccess(WhitelistGate.Connector database, String own, String core, LongSupplier clock) {
        for (String namespace : new String[] {own, core}) {
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalArgumentException("not a namespace: " + namespace);
            }
        }
        this.database = database;
        this.own = own;
        this.core = core;
        this.clock = clock;
    }

    /** The namespace and table, if not there yet. */
    public void prepare() throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + own);
            statement.execute("CREATE TABLE IF NOT EXISTS " + own + ".server_access ("
                    + "list TEXT NOT NULL, player TEXT NOT NULL, name TEXT NOT NULL, added_at BIGINT NOT NULL, "
                    + "added_by TEXT, PRIMARY KEY (list, player))");
        }
    }

    /** Straight from the database; throws when it cannot be asked. */
    public boolean allowed(String list, UUID player) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT 1 FROM " + own + ".server_access WHERE list = ? AND player = ?")) {
            select.setString(1, list);
            select.setString(2, player.toString());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** For the door: the database, else the lists read last, else no. */
    public boolean check(String list, UUID player) {
        try {
            boolean in = allowed(list, player);
            lastError = Optional.empty();
            return in;
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            Map<String, Set<UUID>> known = lastRead;
            return known != null && known.getOrDefault(list, Set.of()).contains(player);
        }
    }

    /** Only what was read last, no database: for offering things (tab completion), never for the door. */
    public boolean remembered(String list, UUID player) {
        Map<String, Set<UUID>> known = lastRead;
        return known != null && known.getOrDefault(list, Set.of()).contains(player);
    }

    /** @return whether they were not on it before */
    public boolean add(String list, UUID player, String name, String by) throws SQLException {
        boolean added;
        try (Connection connection = database.connect();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO " + own
                     + ".server_access (list, player, name, added_at, added_by) VALUES (?, ?, ?, ?, ?) "
                     + "ON CONFLICT (list, player) DO NOTHING")) {
            insert.setString(1, list);
            insert.setString(2, player.toString());
            insert.setString(3, name);
            insert.setLong(4, clock.getAsLong());
            insert.setString(5, by);
            added = insert.executeUpdate() > 0;
        }
        remember(list, player, true);
        return added;
    }

    /** @return whether they were on it */
    public boolean remove(String list, UUID player) throws SQLException {
        boolean removed;
        try (Connection connection = database.connect();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + own + ".server_access WHERE list = ? AND player = ?")) {
            delete.setString(1, list);
            delete.setString(2, player.toString());
            removed = delete.executeUpdate() > 0;
        }
        remember(list, player, false);
        return removed;
    }

    /** Sorted by name. */
    public List<Entry> list(String list) throws SQLException {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT player, name, added_at, added_by FROM "
                     + own + ".server_access WHERE list = ? ORDER BY lower(name)")) {
            select.setString(1, list);
            List<Entry> entries = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    entries.add(new Entry(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getLong(3),
                            rows.getString(4)));
                }
            }
            return entries;
        }
    }

    /** Somebody on the network's whitelist by that name, any case. */
    public Optional<Known> whitelisted(String name) throws SQLException {
        return byName("SELECT player, name FROM " + core + ".whitelist WHERE lower(name) = lower(?)", name, null);
    }

    /** Somebody on this list by that name, any case. */
    public Optional<Known> onList(String list, String name) throws SQLException {
        return byName("SELECT player, name FROM " + own + ".server_access WHERE list = ? AND lower(name) = lower(?)",
                name, list);
    }

    private Optional<Known> byName(String sql, String name, String list) throws SQLException {
        try (Connection connection = database.connect(); PreparedStatement select = connection.prepareStatement(sql)) {
            int i = 1;
            if (list != null) {
                select.setString(i++, list);
            }
            select.setString(i, name);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(new Known(UUID.fromString(rows.getString(1)), rows.getString(2)))
                        : Optional.empty();
            }
        }
    }

    /** Reads every list, for when the database is out of reach later. @return whether it could be read */
    public boolean refresh() {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT list, player FROM " + own + ".server_access");
             ResultSet rows = select.executeQuery()) {
            Map<String, Set<UUID>> lists = new HashMap<>();
            while (rows.next()) {
                try {
                    lists.computeIfAbsent(rows.getString(1), list -> new HashSet<>()).add(UUID.fromString(rows.getString(2)));
                } catch (IllegalArgumentException notAnId) {
                    // nobody can join as it
                }
            }
            Map<String, Set<UUID>> copy = new HashMap<>();
            lists.forEach((list, players) -> copy.put(list, Set.copyOf(players)));
            lastRead = Map.copyOf(copy);
            lastError = Optional.empty();
            return true;
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            return false;
        }
    }

    public Optional<String> lastError() {
        return lastError;
    }

    private synchronized void remember(String list, UUID player, boolean in) {
        Map<String, Set<UUID>> known = lastRead;
        if (known == null) {
            return;
        }
        Map<String, Set<UUID>> changed = new HashMap<>(known);
        Set<UUID> players = new HashSet<>(changed.getOrDefault(list, Set.of()));
        if (in) {
            players.add(player);
        } else {
            players.remove(player);
        }
        changed.put(list, Set.copyOf(players));
        lastRead = Map.copyOf(changed);
    }
}
