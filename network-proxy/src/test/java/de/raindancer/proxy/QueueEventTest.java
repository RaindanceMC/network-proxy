package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The queue on the real plugin: a full SMP, the waiting server, Prime first, staff past it. */
class QueueEventTest {

    @TempDir
    Path folder;

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final RegisteredServer smp = server("smp");
    private final RegisteredServer welcome = server("welcome");
    private NetworkProxy plugin;

    @BeforeEach
    void start() throws Exception {
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
        Files.writeString(folder.resolve("config.properties"), """
                queue.server=smp
                queue.max=2
                queue.limbo=welcome
                """);
        plugin = new NetworkProxy(proxy, mock(Logger.class), folder);
        plugin.onStart(mock(ProxyInitializeEvent.class));
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("10.0.0.1", 1)));
        return server;
    }

    private void online(int players) {
        when(smp.getPlayersConnected()).thenReturn(Collections.nCopies(players, mock(Player.class)));
    }

    private Player player(String name, boolean prime, boolean staff, RegisteredServer on) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUsername()).thenReturn(name);
        when(player.hasPermission("rains.prime")).thenReturn(prime);
        when(player.hasPermission("rainsnetworkproxy.queue.bypass")).thenReturn(staff);
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

    private ServerPreConnectEvent join(Player player, RegisteredServer from) {
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, smp, from);
        plugin.onQueue(event);
        return event;
    }

    @Test
    @DisplayName("room on the SMP: straight in")
    void room() {
        online(1);
        assertThat(join(player("Ada", false, false, null), null).getResult().getServer()).contains(smp);
    }

    @Test
    @DisplayName("full, arriving: onto the waiting server, told their place")
    void fullArriving() {
        online(2);
        Player ada = player("Ada", false, false, null);
        assertThat(join(ada, null).getResult().getServer()).contains(welcome);
        verify(ada).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    @DisplayName("full, switching from another server: stays where they are, in the line")
    void fullSwitching() {
        online(2);
        assertThat(join(player("Ada", false, false, welcome), welcome).getResult().isAllowed()).isFalse();
    }

    @Test
    @DisplayName("staff never wait, however full")
    void staff() {
        online(50);
        assertThat(join(player("Mod", false, true, null), null).getResult().getServer()).contains(smp);
    }

    @Test
    @DisplayName("a place frees up while others wait: a newcomer still queues behind them")
    void noJumping() {
        online(2);
        join(player("Ada", false, false, null), null);
        online(1);
        assertThat(join(player("Bo", true, false, null), null).getResult().getServer()).contains(welcome);
    }

    @Test
    @DisplayName("another server is not the queue's business")
    void otherServer() {
        online(2);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player("Ada", false, false, null), welcome, null);
        plugin.onQueue(event);
        assertThat(event.getResult().getServer()).contains(welcome);
    }
}
