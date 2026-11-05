package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The plugin starts on a proxy: config written, /hub registered, no database needed to come up. */
class NetworkProxyStartupTest {

    @TempDir
    Path folder;

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final CommandManager commands = mock(CommandManager.class);
    private final Logger log = mock(Logger.class);

    NetworkProxyStartupTest() {
        when(proxy.getCommandManager()).thenReturn(commands);
        CommandMeta.Builder builder = mock(CommandMeta.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(mock(CommandMeta.class));
        when(commands.metaBuilder(anyString())).thenReturn(builder);
    }

    @Test
    @DisplayName("first start: writes its config, registers /hub, and runs without a database")
    void startsWithoutADatabase() throws Exception {
        NetworkProxy plugin = new NetworkProxy(proxy, log, folder.resolve("rainsnetworkproxy"));
        plugin.onStart(mock(ProxyInitializeEvent.class));

        assertThat(folder.resolve("rainsnetworkproxy").resolve("config.properties")).exists();
        verify(commands).register(any(CommandMeta.class), any(HubCommand.class));
        verify(log).warn(org.mockito.ArgumentMatchers.contains("No database.host"));
    }

    @Test
    @DisplayName("with a database configured it starts too, and only checks bans when a player logs in")
    void startsWithADatabase() throws Exception {
        Path data = folder.resolve("rainsnetworkproxy");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.properties"), "hub=lobby\nhub-commands=\ndatabase.host=127.0.0.1\ndatabase.port=1\n");
        NetworkProxy plugin = new NetworkProxy(proxy, log, data);
        plugin.onStart(mock(ProxyInitializeEvent.class));

        verify(commands, never()).register(any(CommandMeta.class), any(HubCommand.class));
        verify(log).info(org.mockito.ArgumentMatchers.contains("Bans are checked at the door"), any(Object.class));
    }

    @Test
    @DisplayName("whitelist=true with a database: checked at the door, the list read again every half minute")
    void whitelistAtTheDoor() throws Exception {
        Path data = folder.resolve("rainsnetworkproxy");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.properties"), "hub-commands=\ndatabase.host=127.0.0.1\ndatabase.port=1\nwhitelist=true\n");
        com.velocitypowered.api.scheduler.Scheduler scheduler = mock(com.velocitypowered.api.scheduler.Scheduler.class);
        com.velocitypowered.api.scheduler.Scheduler.TaskBuilder task =
                mock(com.velocitypowered.api.scheduler.Scheduler.TaskBuilder.class, RETURNS_SELF);
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(task);
        new NetworkProxy(proxy, log, data).onStart(mock(ProxyInitializeEvent.class));

        verify(task).repeat(java.time.Duration.ofSeconds(30));
        verify(log).info(org.mockito.ArgumentMatchers.contains("whitelist is checked at the door"));
    }

    @Test
    @DisplayName("whitelist=true without a database says why it is left to the servers")
    void whitelistWithoutDatabase() throws Exception {
        Path data = folder.resolve("rainsnetworkproxy");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.properties"), "hub-commands=\nwhitelist=true\n");
        new NetworkProxy(proxy, log, data).onStart(mock(ProxyInitializeEvent.class));

        verify(log).warn(org.mockito.ArgumentMatchers.contains("whitelist=true needs database.host"));
    }
}
