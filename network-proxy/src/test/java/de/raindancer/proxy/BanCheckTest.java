package de.raindancer.proxy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.data.sql.PostgresSettings;
import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BanCheckTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** Core's own schema in a namespace of its own, a punishment row written as Core writes them. */
    private static String coreWith(String kind, Long endsAt, Long liftedAt) {
        String namespace = TestPostgres.freshNamespace("proxy");
        try (PostgresDatabase core = PostgresDatabase.open(TestPostgres.pool(), namespace, CoreSchema.CORE, () -> false, "smp")) {
            core.write(connection -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO punishment (id, target, kind, moderator, reason, given_at, ends_at, lifted_at)
                        VALUES (?, ?, ?, NULL, 'griefing the spawn', 1000, ?, ?)""")) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, ALEX.toString());
                    insert.setString(3, kind);
                    if (endsAt == null) {
                        insert.setNull(4, java.sql.Types.BIGINT);
                    } else {
                        insert.setLong(4, endsAt);
                    }
                    if (liftedAt == null) {
                        insert.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        insert.setLong(5, liftedAt);
                    }
                    insert.executeUpdate();
                }
            });
        }
        return namespace;
    }

    private static BanCheck check(String namespace) {
        PostgresSettings server = TestPostgres.require();
        return new BanCheck(new ProxyDatabase(server.host(), server.port(), server.database(), server.user(),
                server.password(), "prefer"), namespace);
    }

    @Test
    @DisplayName("a ban in force is found, with its reason and end")
    void banInForce() {
        Optional<BanCheck.Ban> ban = check(coreWith("BAN", 9_000_000_000_000L, null)).activeBan(ALEX, 2_000);
        assertThat(ban).get().satisfies(found -> {
            assertThat(found.reason()).isEqualTo("griefing the spawn");
            assertThat(found.endsAt()).contains(9_000_000_000_000L);
        });
    }

    @Test
    @DisplayName("a ban until lifted has no end")
    void permanent() {
        assertThat(check(coreWith("BAN", null, null)).activeBan(ALEX, 2_000)).get()
                .extracting(BanCheck.Ban::endsAt).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("lifted, expired, or only a mute: the door is open")
    void notBanned() {
        assertThat(check(coreWith("BAN", null, 1_500L)).activeBan(ALEX, 2_000)).isEmpty();
        assertThat(check(coreWith("BAN", 1_500L, null)).activeBan(ALEX, 2_000)).isEmpty();
        assertThat(check(coreWith("MUTE", null, null)).activeBan(ALEX, 2_000)).isEmpty();
        assertThat(check(coreWith("BAN", null, null)).activeBan(UUID.randomUUID(), 2_000)).isEmpty();
    }

    @Test
    @DisplayName("a database nobody can reach says 'unknown', and the door stays open — every server checks again")
    void unreachable() {
        BanCheck nowhere = new BanCheck(new ProxyDatabase("127.0.0.1", 1, "x", "y", "", "disable"), "core");
        assertThat(nowhere.activeBan(ALEX, 2_000)).isEmpty();
        assertThat(nowhere.lastError()).isPresent();
    }

    @Test
    @DisplayName("a namespace name that is not a plain identifier is refused")
    void namespaceChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BanCheck(
                new ProxyDatabase("127.0.0.1", 1, "x", "y", "", "disable"), "core; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
