package de.raindancer.proxy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.moderation.whitelist.WhitelistTable;
import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A server only some may enter (the beta): its own list in the network's database, next to the whitelist. */
class ServerAccessTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private final AtomicLong clock = new AtomicLong(5_000L);
    private final AtomicBoolean down = new AtomicBoolean();
    private String core;
    private String own;
    private PostgresDatabase coreDb;
    private ServerAccess access;

    @BeforeEach
    void setUp() {
        core = TestPostgres.freshNamespace("access_core");
        own = TestPostgres.freshNamespace("access_proxy");
        coreDb = PostgresDatabase.open(TestPostgres.pool(), core, CoreSchema.CORE, () -> false, "smp");
        WhitelistTable whitelist = new WhitelistTable(coreDb, clock::get);
        whitelist.add(ADA, "Ada", "test");
        whitelist.add(BO, "Bo", "test");
        var server = TestPostgres.require();
        ProxyDatabase database = new ProxyDatabase(server.host(), server.port(), server.database(), server.user(),
                server.password(), "prefer");
        access = new ServerAccess(() -> {
            if (down.get()) {
                throw new SQLException("down");
            }
            return database.connect();
        }, own, core, clock::get);
    }

    @AfterEach
    void close() {
        coreDb.close();
    }

    @Test
    @DisplayName("nobody is on a fresh list; added: allowed, the same add again changes nothing; removed: out again")
    void addAndRemove() throws SQLException {
        access.prepare();
        assertThat(access.allowed("beta", ADA)).isFalse();

        assertThat(access.add("beta", ADA, "Ada", "Tom")).isTrue();
        assertThat(access.add("beta", ADA, "Ada", "Tom")).as("already on it").isFalse();
        assertThat(access.allowed("beta", ADA)).isTrue();
        assertThat(access.allowed("beta", BO)).isFalse();
        assertThat(access.allowed("other", ADA)).as("one list per gate").isFalse();

        assertThat(access.remove("beta", ADA)).isTrue();
        assertThat(access.remove("beta", ADA)).as("not on it any more").isFalse();
        assertThat(access.allowed("beta", ADA)).isFalse();
    }

    @Test
    @DisplayName("the list shows who is on it, by name, sorted, with who added them")
    void listing() throws SQLException {
        access.prepare();
        access.add("beta", BO, "Bo", "Tom");
        clock.addAndGet(1);
        access.add("beta", ADA, "Ada", "console");
        assertThat(access.list("beta")).extracting(ServerAccess.Entry::name).containsExactly("Ada", "Bo");
        assertThat(access.list("beta").getFirst().addedBy()).isEqualTo("console");
    }

    @Test
    @DisplayName("a name is found on the network's whitelist, any case — somebody not on it cannot be added")
    void nameFromTheWhitelist() throws SQLException {
        access.prepare();
        assertThat(access.whitelisted("ada")).contains(new ServerAccess.Known(ADA, "Ada"));
        assertThat(access.whitelisted("Nobody")).isEmpty();
    }

    @Test
    @DisplayName("someone already on the list is found by name too, for taking them off")
    void nameOnTheList() throws SQLException {
        access.prepare();
        access.add("beta", ADA, "Ada", "Tom");
        assertThat(access.onList("beta", "ADA")).contains(new ServerAccess.Known(ADA, "Ada"));
        assertThat(access.onList("beta", "Bo")).isEmpty();
    }

    @Test
    @DisplayName("database gone: the list read last decides; nothing read yet keeps everybody out (a closed server)")
    void databaseDown() throws SQLException {
        access.prepare();
        access.add("beta", ADA, "Ada", "Tom");
        down.set(true);
        assertThat(access.check("beta", ADA)).as("nothing read yet").isFalse();

        down.set(false);
        assertThat(access.refresh()).isTrue();
        down.set(true);
        assertThat(access.check("beta", ADA)).isTrue();
        assertThat(access.check("beta", BO)).isFalse();
        assertThat(access.lastError()).isPresent();

        down.set(false);
        assertThat(access.check("beta", BO)).isFalse();
        assertThat(access.lastError()).isEmpty();
    }

    @Test
    @DisplayName("a change is in the remembered list at once, not only after the next refresh")
    void changesRemembered() throws SQLException {
        access.prepare();
        access.refresh();
        access.add("beta", BO, "Bo", "Tom");
        down.set(true);
        assertThat(access.check("beta", BO)).isTrue();
        down.set(false);
        access.remove("beta", BO);
        down.set(true);
        assertThat(access.check("beta", BO)).isFalse();
    }

    @Test
    @DisplayName("namespaces that are not plain identifiers are refused")
    void namespacesChecked() {
        assertThatThrownBy(() -> new ServerAccess(() -> null, "x; drop", "core", clock::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
