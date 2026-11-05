package de.raindancer.proxy;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.moderation.whitelist.WhitelistTable;
import de.raindancer.core.platform.network.NetworkRoles;
import de.raindancer.core.testkit.TestPostgres;
import de.raindancer.modules.discordlink.model.PendingCode;
import de.raindancer.modules.discordlink.model.Purpose;
import de.raindancer.modules.discordlink.store.LinkStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The door: the network's whitelist read from Core's own table, and a join code written exactly as Discord Link
 * writes them — so the bot's {@code /link} redeems a code the proxy handed out.
 */
class WhitelistGateTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final long MINUTES = 30;

    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private final AtomicBoolean down = new AtomicBoolean();
    private String core;
    private String links;
    private PostgresDatabase coreDb;
    private PostgresDatabase linkDb;
    private WhitelistGate gate;

    @BeforeEach
    void setUp() {
        core = TestPostgres.freshNamespace("gate_core");
        links = TestPostgres.freshNamespace("gate_link");
        coreDb = PostgresDatabase.open(TestPostgres.pool(), core, CoreSchema.CORE, () -> false, "smp");
        linkDb = PostgresDatabase.open(TestPostgres.pool(), links, LinkStore.SCHEMA, () -> false, "smp");
        new WhitelistTable(coreDb, clock::get).add(ADA, "Ada", "test");
        var server = TestPostgres.require();
        ProxyDatabase database = new ProxyDatabase(server.host(), server.port(), server.database(), server.user(),
                server.password(), "prefer");
        gate = new WhitelistGate(() -> {
            if (down.get()) {
                throw new SQLException("down");
            }
            return database.connect();
        }, core, links, MINUTES * 60_000L, new Random(118), clock::get);
    }

    @AfterEach
    void close() {
        coreDb.close();
        linkDb.close();
    }

    private void botRunning() {
        new NetworkRoles(coreDb, "smp", clock::get).claim(WhitelistGate.BOT_ROLE);
    }

    @Test
    @DisplayName("on the list: in")
    void listed() {
        assertThat(gate.check(ADA, "Ada").kind()).isEqualTo(WhitelistGate.Kind.IN);
    }

    @Test
    @DisplayName("off the list while the bot runs: a code, the same one again, and Discord Link's store redeems it")
    void codeForUnlisted() {
        botRunning();
        WhitelistGate.Verdict first = gate.check(BO, "Bo");
        assertThat(first.kind()).isEqualTo(WhitelistGate.Kind.CODE);
        assertThat(first.code()).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}");
        clock.addAndGet(60_000);
        botRunning();
        assertThat(gate.check(BO, "Bo").code()).as("a second try shows the same code").isEqualTo(first.code());

        LinkStore store = new LinkStore(linkDb);
        assertThat(store.codeFor(BO, Purpose.JOIN, clock.get())).map(PendingCode::code).contains(first.code());
        Optional<PendingCode> taken = store.take(first.code(), clock.get());
        assertThat(taken).get().satisfies(code -> {
            assertThat(code.player()).isEqualTo(BO);
            assertThat(code.name()).isEqualTo("Bo");
            assertThat(code.purpose()).isEqualTo(Purpose.JOIN);
        });
    }

    @Test
    @DisplayName("a code that ran out is replaced by a new one")
    void expiredCode() {
        botRunning();
        String first = gate.check(BO, "Bo").code();
        clock.addAndGet(MINUTES * 60_000L + 1);
        botRunning();
        String second = gate.check(BO, "Bo").code();
        assertThat(second).isNotEqualTo(first);
        assertThat(new LinkStore(linkDb).take(first, clock.get())).isEmpty();
    }

    @Test
    @DisplayName("no bot answering anywhere: the plain refusal, no code nobody could redeem")
    void noBot() {
        assertThat(gate.check(BO, "Bo").kind()).isEqualTo(WhitelistGate.Kind.REFUSED);
        botRunning();
        clock.addAndGet(NetworkRoles.LEASE_MILLIS + 1);
        assertThat(gate.check(BO, "Bo").kind()).as("its lease ran out").isEqualTo(WhitelistGate.Kind.REFUSED);
    }

    @Test
    @DisplayName("database gone: the last list read decides; nothing read yet lets them on to the servers' own check")
    void databaseDown() {
        down.set(true);
        assertThat(gate.check(BO, "Bo").kind()).isEqualTo(WhitelistGate.Kind.UNKNOWN);
        assertThat(gate.lastError()).isPresent();

        down.set(false);
        assertThat(gate.refresh()).isTrue();
        down.set(true);
        assertThat(gate.check(ADA, "Ada").kind()).isEqualTo(WhitelistGate.Kind.IN);
        assertThat(gate.check(BO, "Bo").kind()).isEqualTo(WhitelistGate.Kind.REFUSED);
    }

    @Test
    @DisplayName("every code is one RainsCore's JoinCode reads back as itself, over the whole alphabet")
    void sameCodesAsCore() {
        Random random = new Random(7);
        java.util.Set<Character> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = WhitelistGate.newCode(random);
            assertThat(de.raindancer.core.moderation.whitelist.JoinCode.normalise(code)).contains(code);
            code.chars().filter(c -> c != '-').forEach(c -> seen.add((char) c));
        }
        assertThat(seen).hasSize(de.raindancer.core.moderation.whitelist.JoinCode.ALPHABET.length());
    }

    @Test
    @DisplayName("namespaces that are not plain identifiers are refused")
    void namespacesChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WhitelistGate(() -> null, "core; x", "discordlink",
                1, new Random(), clock::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
