package de.raindancer.proxy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The network's whitelist at the door: RainsCore's {@code whitelist} table, read before any server sees the player.
 * Somebody not on it gets a join code, written into Discord Link's code table exactly as its own join gate writes
 * them, so Discord's {@code /link} whitelists them — but only while the bot holds its network role somewhere: a code
 * nobody can redeem is worse than the plain refusal.
 *
 * <p>With the database out of reach the list read last decides (refreshed every half minute); with nothing read yet
 * the player is let on, and every server's own whitelist checks again.
 */
public final class WhitelistGate {

    /** Discord Link's bot, as RainsCore's network roles call it. */
    static final String BOT_ROLE = "discordlink-bot";
    /** RainsCore's {@code NetworkRoles.LEASE_MILLIS}: a lease not renewed for this long has nobody behind it. */
    static final long LEASE_MILLIS = 15_000;

    private static final Pattern NAMESPACE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public enum Kind {
        /** On the list. */
        IN,
        /** Not on it; here is their code. */
        CODE,
        /** Not on it, and no code to give. */
        REFUSED,
        /** Nobody could ask: the servers decide. */
        UNKNOWN
    }

    public record Verdict(Kind kind, String code) {
        static final Verdict IN = new Verdict(Kind.IN, null);
        static final Verdict REFUSED = new Verdict(Kind.REFUSED, null);
        static final Verdict UNKNOWN = new Verdict(Kind.UNKNOWN, null);
    }

    @FunctionalInterface
    public interface Connector {
        Connection connect() throws SQLException;
    }

    private final Connector database;
    private final String core;
    private final String links;
    private final long codeMillis;
    private final RandomGenerator random;
    private final LongSupplier clock;
    private volatile Set<UUID> lastRead;
    private volatile Optional<String> lastError = Optional.empty();

    /**
     * @param core  the schema RainsCore keeps its core data in
     * @param links the schema Discord Link keeps its codes in
     */
    public WhitelistGate(Connector database, String core, String links, long codeMillis, RandomGenerator random,
                         LongSupplier clock) {
        for (String namespace : new String[] {core, links}) {
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalArgumentException("not a namespace: " + namespace);
            }
        }
        this.database = database;
        this.core = core;
        this.links = links;
        this.codeMillis = codeMillis;
        this.random = random;
        this.clock = clock;
    }

    public long codeMinutes() {
        return codeMillis / 60_000L;
    }

    public Verdict check(UUID player, String name) {
        long now = clock.getAsLong();
        try (Connection connection = database.connect()) {
            Verdict verdict;
            if (listed(connection, player)) {
                verdict = Verdict.IN;
            } else if (!botRunning(connection, now)) {
                verdict = Verdict.REFUSED;
            } else {
                verdict = new Verdict(Kind.CODE, code(connection, player, name, now));
            }
            lastError = Optional.empty();
            return verdict;
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            Set<UUID> known = lastRead;
            if (known == null) {
                return Verdict.UNKNOWN;
            }
            return known.contains(player) ? Verdict.IN : Verdict.REFUSED;
        }
    }

    /** Reads the whole list, for when the database is out of reach later. @return whether it could be read */
    public boolean refresh() {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement("SELECT player FROM " + core + ".whitelist");
             ResultSet rows = select.executeQuery()) {
            Set<UUID> players = new java.util.HashSet<>();
            while (rows.next()) {
                try {
                    players.add(UUID.fromString(rows.getString(1)));
                } catch (IllegalArgumentException notAnId) {
                    // nobody can join as it
                }
            }
            lastRead = Set.copyOf(players);
            lastError = Optional.empty();
            return true;
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            return false;
        }
    }

    /** Why the last question could not be asked; empty once one could. */
    public Optional<String> lastError() {
        return lastError;
    }

    /**
     * A code as RainsCore's {@code JoinCode} makes them (two blocks of five, no look-alikes). Its own copy: Core is
     * built for Java 25, Velocity's floor is 21. {@code WhitelistGateTest} holds the two together.
     */
    static String newCode(RandomGenerator random) {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private boolean listed(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM " + core + ".whitelist WHERE player = ?")) {
            select.setString(1, player.toString());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean botRunning(Connection connection, long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM " + core + ".network_role WHERE role = ? AND heartbeat_at >= ?")) {
            select.setString(1, BOT_ROLE);
            select.setLong(2, now - LEASE_MILLIS);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Their join code that still lasts, or a new one: every proxy, server and retry shows the same code. */
    private String code(Connection connection, UUID player, String name, long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT code FROM " + links
                + ".discord_link_code WHERE player = ? AND purpose = 'JOIN' AND expires_at > ?")) {
            select.setString(1, player.toString());
            select.setLong(2, now);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
            }
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement clean = connection.prepareStatement("DELETE FROM " + links
                + ".discord_link_code WHERE expires_at <= ? OR (player = ? AND purpose = 'JOIN')");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO " + links
                     + ".discord_link_code (code, player, name, purpose, expires_at) VALUES (?, ?, ?, 'JOIN', ?)")) {
            clean.setLong(1, now);
            clean.setString(2, player.toString());
            clean.executeUpdate();
            String code = newCode(random);
            insert.setString(1, code);
            insert.setString(2, player.toString());
            insert.setString(3, name == null ? "" : name);
            insert.setLong(4, now + codeMillis);
            insert.executeUpdate();
            connection.commit();
            return code;
        } catch (SQLException failed) {
            connection.rollback();
            throw failed;
        } finally {
            connection.setAutoCommit(auto);
        }
    }
}
