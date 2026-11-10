package de.raindancer.proxy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Whether the network has ever seen a player: a row in RainsCore's first-seen table (written the first time they
 * reach a server) or in its last-seen list (older players from before first-seen was kept). A server with a
 * database of its own — the welcome server — writes neither here, so a newcomer stays new until a server of this
 * network has had them.
 */
public final class Newcomers {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final WhitelistGate.Connector database;
    private final String query;
    private volatile Optional<String> lastError = Optional.empty();

    /** @param namespace the schema RainsCore keeps its core data in on this network */
    public Newcomers(WhitelistGate.Connector database, String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("not a namespace: " + namespace);
        }
        this.database = database;
        this.query = "SELECT 1 FROM " + namespace + ".network_first_seen WHERE player = ?"
                + " UNION ALL SELECT 1 FROM " + namespace + ".network_seen WHERE player = ? LIMIT 1";
    }

    /** @return empty when the database could not be asked */
    public Optional<Boolean> known(UUID player) {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement(query)) {
            select.setString(1, player.toString());
            select.setString(2, player.toString());
            try (ResultSet rows = select.executeQuery()) {
                lastError = Optional.empty();
                return Optional.of(rows.next());
            }
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            return Optional.empty();
        }
    }

    public Optional<String> lastError() {
        return lastError;
    }
}
