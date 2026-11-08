package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.moderation.whitelist.WhitelistTable;
import de.raindancer.core.testkit.TestPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The beta's gate on the real plugin: a list in the network's database, a permission, the hub for who may not. */
class GateEventTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @TempDir
    Path folder;

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final CommandManager commands = mock(CommandManager.class);
    private final Logger log = mock(Logger.class);
    private final RegisteredServer hub = server("hub");
    private final RegisteredServer beta = server("beta");
    private final RegisteredServer betaHub = server("betahub");
    private PostgresDatabase coreDb;
    private NetworkProxy plugin;

    @BeforeEach
    void start() throws Exception {
        String core = TestPostgres.freshNamespace("gate_ev_core");
        String own = TestPostgres.freshNamespace("gate_ev_proxy");
        coreDb = PostgresDatabase.open(TestPostgres.pool(), core, CoreSchema.CORE, () -> false, "smp");
        new WhitelistTable(coreDb, System::currentTimeMillis).add(ADA, "Ada", "test");
        var db = TestPostgres.require();
        ServerAccess lists = new ServerAccess(new ProxyDatabase(db.host(), db.port(), db.database(), db.user(),
                db.password(), "prefer")::connect, own, core, System::currentTimeMillis);
        lists.prepare();
        lists.add("beta", ADA, "Ada", "test");

        when(proxy.getCommandManager()).thenReturn(commands);
        CommandMeta.Builder builder = mock(CommandMeta.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(mock(CommandMeta.class));
        when(commands.metaBuilder(anyString())).thenReturn(builder);
        when(proxy.getPluginManager()).thenReturn(mock(PluginManager.class));
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(mock(Scheduler.TaskBuilder.class, RETURNS_SELF));
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(proxy.getServer("hub")).thenReturn(Optional.of(hub));
        Files.writeString(folder.resolve("config.properties"), """
                hub=hub
                database.host=%s
                database.port=%d
                database.name=%s
                database.user=%s
                database.password=%s
                database.namespace=%s
                access.namespace=%s
                gate.beta=beta
                gate.betahub=beta
                list.beta.permission=thundernetwork.beta
                hubs.beta=betahub
                """.formatted(db.host(), db.port(), db.database(), db.user(), db.password() == null ? "" : db.password(),
                core, own));
        plugin = new NetworkProxy(proxy, log, folder);
        plugin.onStart(mock(ProxyInitializeEvent.class));
    }

    @AfterEach
    void close() {
        coreDb.close();
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("10.0.0.1", 1)));
        return server;
    }

    private Player player(UUID id, String name, RegisteredServer on, boolean permission) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        when(player.hasPermission("thundernetwork.beta")).thenReturn(permission);
        if (on == null) {
            when(player.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerInfo info = on.getServerInfo();
            ServerConnection connection = mock(ServerConnection.class);
            when(connection.getServerInfo()).thenReturn(info);
            when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        }
        return player;
    }

    private ServerPreConnectEvent move(Player player, RegisteredServer to, RegisteredServer from) {
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, to, from);
        EventTask task = plugin.onGate(event);
        if (task != null) {
            task.execute(mock(Continuation.class));
        }
        return event;
    }

    @Test
    @DisplayName("on the list: into the beta hub and the beta SMP")
    void listed() {
        Player ada = player(ADA, "Ada", hub, false);
        assertThat(move(ada, betaHub, hub).getResult().getServer()).contains(betaHub);
        assertThat(move(ada, beta, betaHub).getResult().getServer()).contains(beta);
    }

    @Test
    @DisplayName("not on it, switching: stays where they are and is told why")
    void unlistedSwitching() {
        Player bo = player(BO, "Bo", hub, false);
        ServerPreConnectEvent event = move(bo, betaHub, hub);
        assertThat(event.getResult().isAllowed()).isFalse();
        verify(bo).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    @DisplayName("not on it, arriving by the beta's address: the main hub instead of a refused login")
    void unlistedArriving() {
        Player bo = player(BO, "Bo", null, false);
        assertThat(move(bo, beta, null).getResult().getServer()).contains(hub);
    }

    @Test
    @DisplayName("the permission (or *, an admin) lets in without being on the list, no database asked")
    void permission() {
        Player bo = player(BO, "Bo", hub, true);
        ServerPreConnectEvent event = new ServerPreConnectEvent(bo, betaHub, hub);
        assertThat(plugin.onGate(event)).isNull();
        assertThat(event.getResult().getServer()).contains(betaHub);
    }

    @Test
    @DisplayName("ungated servers are not asked about at all")
    void ungated() {
        Player bo = player(BO, "Bo", hub, false);
        assertThat(plugin.onGate(new ServerPreConnectEvent(bo, server("lillyxoxo"), hub))).isNull();
    }

    @Test
    @DisplayName("with gates and a database, /tn is registered")
    void tnRegistered() {
        verify(commands).register(any(CommandMeta.class), any(TnCommand.class));
        verify(commands).metaBuilder(eq("tn"));
    }
}
