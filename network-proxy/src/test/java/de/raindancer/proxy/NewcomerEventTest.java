package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.PostgresDatabase;
import de.raindancer.core.testkit.TestPostgres;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Newcomers on the real plugin: the welcome server first for somebody the network's database has never seen. */
class NewcomerEventTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @TempDir
    Path folder;

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final RegisteredServer smp = server("smp");
    private final RegisteredServer welcome = server("welcome");
    private PostgresDatabase coreDb;
    private NetworkProxy plugin;

    @BeforeEach
    void start() throws Exception {
        String core = TestPostgres.freshNamespace("newcomer_ev");
        coreDb = PostgresDatabase.open(TestPostgres.pool(), core, CoreSchema.CORE, () -> false, "smp");
        coreDb.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO network_first_seen (player, server, at) VALUES (?, 'smp', 1000)")) {
                insert.setString(1, ADA.toString());
                insert.executeUpdate();
            }
        });
        CommandManager commands = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(commands);
        CommandMeta.Builder builder = mock(CommandMeta.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(mock(CommandMeta.class));
        when(commands.metaBuilder(anyString())).thenReturn(builder);
        when(proxy.getPluginManager()).thenReturn(mock(PluginManager.class));
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(mock(Scheduler.TaskBuilder.class, RETURNS_SELF));
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(proxy.getServer("smp")).thenReturn(Optional.of(smp));
        when(proxy.getServer("welcome")).thenReturn(Optional.of(welcome));
        ProxyConfig velocity = mock(ProxyConfig.class);
        when(velocity.getAttemptConnectionOrder()).thenReturn(List.of("smp"));
        when(proxy.getConfiguration()).thenReturn(velocity);
        var db = TestPostgres.require();
        Files.writeString(folder.resolve("config.properties"), """
                hub=hub
                database.host=%s
                database.port=%d
                database.name=%s
                database.user=%s
                database.password=%s
                database.namespace=%s
                newcomers.server=welcome
                """.formatted(db.host(), db.port(), db.database(), db.user(), db.password() == null ? "" : db.password(),
                core));
        plugin = new NetworkProxy(proxy, mock(Logger.class), folder);
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

    private static Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        return player;
    }

    private PlayerChooseInitialServerEvent arrive(Player player) {
        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player, smp);
        EventTask task = plugin.onChooseInitialServer(event);
        if (task != null) {
            task.execute(mock(Continuation.class));
        }
        return event;
    }

    @Test
    @DisplayName("never seen on the network: the welcome server first")
    void newcomer() {
        assertThat(arrive(player(BO, "Bo")).getInitialServer()).contains(welcome);
    }

    @Test
    @DisplayName("seen before: where Velocity would send them anyway")
    void known() {
        assertThat(arrive(player(ADA, "Ada")).getInitialServer()).contains(smp);
    }

    @Test
    @DisplayName("the welcome server down at their first connect: on to the SMP instead of off the network")
    void welcomeDown() {
        Player bo = player(BO, "Bo");
        KickedFromServerEvent event = new KickedFromServerEvent(bo, welcome, null, true,
                KickedFromServerEvent.DisconnectPlayer.create(Component.text("Unable to connect")));
        plugin.onKicked(event);
        assertThat(event.getResult()).isInstanceOfSatisfying(KickedFromServerEvent.RedirectPlayer.class,
                redirect -> assertThat(redirect.getServer()).isEqualTo(smp));
    }
}
