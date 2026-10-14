package de.raindancer.proxy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Whether a player is banned on the network, read from RainsCore's own punishment table — before they reach any
 * server. Fails open: a database the proxy cannot reach lets them through, and every server checks again at its
 * own login (RainsCore's PunishmentGuard), so a ban still holds.
 */
public final class BanCheck {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public record Ban(String reason, Optional<Long> endsAt) {
    }

    private final ProxyDatabase database;
    private final String query;
    private volatile Optional<String> lastError = Optional.empty();

    /** @param namespace the schema RainsCore keeps its core data in on this network */
    public BanCheck(ProxyDatabase database, String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("not a namespace: " + namespace);
        }
        this.database = database;
        // Permanent first, then the one ending last: the screen shows the ban that keeps them out longest.
        this.query = "SELECT reason, ends_at FROM " + namespace + ".punishment"
                + " WHERE target = ? AND kind = 'BAN' AND lifted_at IS NULL AND (ends_at IS NULL OR ends_at > ?)"
                + " ORDER BY (ends_at IS NULL) DESC, ends_at DESC LIMIT 1";
    }

    public Optional<Ban> activeBan(UUID player, long now) {
        try (Connection connection = database.connect();
             PreparedStatement select = connection.prepareStatement(query)) {
            select.setString(1, player.toString());
            select.setLong(2, now);
            try (ResultSet rows = select.executeQuery()) {
                lastError = Optional.empty();
                if (!rows.next()) {
                    return Optional.empty();
                }
                long endsAt = rows.getLong(2);
                Optional<Long> end = rows.wasNull() ? Optional.empty() : Optional.of(endsAt);
                return Optional.of(new Ban(rows.getString(1), end));
            }
        } catch (SQLException | RuntimeException failed) {
            lastError = Optional.of(String.valueOf(failed.getMessage()));
            return Optional.empty();
        }
    }

    /** Why the last check could not be made; empty once one could. */
    public Optional<String> lastError() {
        return lastError;
    }
}
