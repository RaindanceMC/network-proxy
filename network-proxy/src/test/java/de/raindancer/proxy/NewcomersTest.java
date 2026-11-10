package de.raindancer.proxy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.testkit.TestPostgres;
import de.raindancer.core.data.sql.PostgresSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("who is new to the network: nobody has ever seen them on one of its servers")
class NewcomersTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private static String coreWith(String table, UUID player) {
        String namespace = TestPostgres.freshNamespace("newcomers");
        try (PostgresDatabase core = PostgresDatabase.open(TestPostgres.pool(), namespace, CoreSchema.CORE, () -> false, "smp")) {
            if (table != null) {
                core.write(connection -> {
                    String sql = table.equals("network_first_seen")
                            ? "INSERT INTO network_first_seen (player, server, at) VALUES (?, 'smp', 1000)"
                            : "INSERT INTO network_seen (player, name, name_lower, server, at, online) VALUES (?, 'Ada', 'ada', 'smp', 1000, 0)";
                    try (PreparedStatement insert = connection.prepareStatement(sql)) {
                        insert.setString(1, player.toString());
                        insert.executeUpdate();
                    }
                });
            }
        }
        return namespace;
    }

    private static Newcomers newcomers(String namespace) {
        PostgresSettings db = TestPostgres.require();
        return new Newcomers(new ProxyDatabase(db.host(), db.port(), db.database(), db.user(), db.password(),
                "prefer")::connect, namespace);
    }

    @Test
    @DisplayName("seen arriving once (Core's first-seen row): known")
    void firstSeen() {
        assertThat(newcomers(coreWith("network_first_seen", ADA)).known(ADA)).contains(true);
    }

    @Test
    @DisplayName("only in the last-seen list (a player from before first-seen was kept): known too")
    void lastSeen() {
        assertThat(newcomers(coreWith("network_seen", ADA)).known(ADA)).contains(true);
    }

    @Test
    @DisplayName("in neither: new")
    void newcomer() {
        assertThat(newcomers(coreWith("network_first_seen", ADA)).known(BO)).contains(false);
        assertThat(newcomers(coreWith(null, null)).known(BO)).contains(false);
    }

    @Test
    @DisplayName("a database that cannot be asked gives no answer, and says why")
    void unreachable() {
        Newcomers broken = new Newcomers(() -> {
            throw new java.sql.SQLException("connection refused");
        }, "core");
        assertThat(broken.known(ADA)).isEmpty();
        assertThat(broken.lastError()).hasValueSatisfying(error -> assertThat(error).contains("connection refused"));
    }

    @Test
    @DisplayName("a namespace that is no SQL name is refused")
    void namespace() {
        assertThatThrownBy(() -> new Newcomers(() -> null, "core; drop")).isInstanceOf(IllegalArgumentException.class);
    }
}
