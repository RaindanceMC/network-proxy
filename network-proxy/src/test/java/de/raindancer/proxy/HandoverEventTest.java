package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The proxy hands a player over when they switch to a server behind another proxy, and relays everything else. */
class HandoverEventTest {

    @TempDir
    Path folder;

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final Logger log = mock(Logger.class);
    private NetworkProxy plugin;

    private static final UUID JAVA = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f51b030beb");

    @BeforeEach
    void start() throws Exception {
        CommandManager commands = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(commands);
        CommandMeta.Builder builder = mock(CommandMeta.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(mock(CommandMeta.class));
        when(commands.metaBuilder(anyString())).thenReturn(builder);
        when(proxy.getPluginManager()).thenReturn(mock(PluginManager.class));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("config.properties"), """
                hub=hub
                handover.arena=arena.lillyyxoxo.net:25565
                handover.ctf=ctf.lillyyxoxo.net
                """);
        plugin = new NetworkProxy(proxy, log, folder);
        plugin.onStart(mock(ProxyInitializeEvent.class));
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("10.0.0.1", 1)));
        return server;
    }

    private static Player player(UUID id, ProtocolVersion version) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn("Tester");
        when(player.getProtocolVersion()).thenReturn(version);
        return player;
    }

    @Test
    @DisplayName("switching to a server behind another proxy: transferred there, the relay is cancelled")
    void handsOver() {
        Player player = player(JAVA, ProtocolVersion.MINECRAFT_1_21_4);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, server("arena"), server("hub"));
        plugin.onPreConnect(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        verify(player).transferToHost(InetSocketAddress.createUnresolved("arena.lillyyxoxo.net", 25565));
    }

    @Test
    @DisplayName("Bedrock players are handed over the same way (Geyser maps it with RainsGeyserHandover)")
    void bedrockToo() {
        Player player = player(BEDROCK, ProtocolVersion.MINECRAFT_1_21_4);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, server("ctf"), server("hub"));
        plugin.onPreConnect(event);

        assertThat(event.getResult().isAllowed()).isFalse();
        verify(player).transferToHost(InetSocketAddress.createUnresolved("ctf.lillyyxoxo.net", 25565));
    }

    @Test
    @DisplayName("a server on this proxy is joined as always")
    void localIsRelayed() {
        Player player = player(JAVA, ProtocolVersion.MINECRAFT_1_21_4);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, server("hub"), server("arena"));
        plugin.onPreConnect(event);

        assertThat(event.getResult().isAllowed()).isTrue();
        verify(player, never()).transferToHost(any());
    }

    @Test
    @DisplayName("the first server of a login and old clients are relayed through velocity.toml's address")
    void firstServerAndOldClients() {
        Player first = player(JAVA, ProtocolVersion.MINECRAFT_1_21_4);
        ServerPreConnectEvent login = new ServerPreConnectEvent(first, server("arena"));
        plugin.onPreConnect(login);
        assertThat(login.getResult().isAllowed()).isTrue();
        verify(first, never()).transferToHost(any());

        Player old = player(JAVA, ProtocolVersion.MINECRAFT_1_20_3);
        ServerPreConnectEvent switching = new ServerPreConnectEvent(old, server("arena"), server("hub"));
        plugin.onPreConnect(switching);
        assertThat(switching.getResult().isAllowed()).isTrue();
        verify(old, never()).transferToHost(any());
    }

    @Test
    @DisplayName("a connection another plugin already refused stays refused and is not handed over")
    void refusedStaysRefused() {
        Player player = player(JAVA, ProtocolVersion.MINECRAFT_1_21_4);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, server("arena"), server("hub"));
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        plugin.onPreConnect(event);

        verify(player, never()).transferToHost(any());
    }

    @Test
    @DisplayName("the start-up names the handovers")
    void startUpSaysSo() {
        verify(log).info(org.mockito.ArgumentMatchers.contains("handed over to their own proxy"), any(Object.class), any(Object.class));
    }
}
