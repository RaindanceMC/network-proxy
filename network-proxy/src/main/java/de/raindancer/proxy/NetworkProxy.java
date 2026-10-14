package de.raindancer.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The proxy's side of a RainsCore network: a banned player is turned away before any server sees them, /hub
 * works from anywhere, and the players of a server that closes wait on the hub instead of being dropped.
 */
public final class NetworkProxy {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path folder;
    private KickRoute route;
    private String hub;
    private BanCheck bans;
    private volatile String reportedError;

    @Inject
    public NetworkProxy(ProxyServer proxy, Logger log, @DataDirectory Path folder) {
        this.proxy = proxy;
        this.log = log;
        this.folder = folder;
    }

    @Subscribe
    public void onStart(ProxyInitializeEvent event) throws IOException {
        Properties config = config(folder.resolve("config.properties"), System.getenv());
        hub = config.getProperty("hub", "hub").strip();
        List<String> closing = new ArrayList<>();
        for (String message : config.getProperty("shutdown-messages", "Server closed").split("\\|")) {
            if (!message.isBlank()) {
                closing.add(message);
            }
        }
        route = new KickRoute(hub, closing);
        List<String> commands = HubCommand.names(config.getProperty("hub-commands"));
        if (!commands.isEmpty()) {
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(commands.getFirst())
                    .aliases(commands.subList(1, commands.size()).toArray(String[]::new)).plugin(this).build(),
                    new HubCommand(proxy, hub));
        }
        String host = config.getProperty("database.host", "").strip();
        if (host.isEmpty()) {
            log.warn("No database.host in config.properties (or RAINS_DB_HOST): bans are checked by each server only.");
        } else {
            ProxyDatabase database = new ProxyDatabase(host,
                    Integer.parseInt(config.getProperty("database.port", "5432").strip()),
                    config.getProperty("database.name", "rains").strip(),
                    config.getProperty("database.user", "rains").strip(),
                    config.getProperty("database.password", ""),
                    config.getProperty("database.sslmode", "prefer").strip());
            bans = new BanCheck(database, config.getProperty("database.namespace", "core").strip());
            log.info("Bans are checked at the door in {}.", database.describe());
        }
        log.info("RainsNetworkProxy: a closing server's players wait on '{}' (shutdown messages {}), hub commands {}.",
                hub, closing, commands);
    }

    /** The file (written with its defaults the first time), then RAINS_DB_* from the environment over it. */
    static Properties config(Path file, Map<String, String> environment) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # The hub, by its name in velocity.toml.
                    hub=hub
                    # Kick reasons that mean "this server is closing", separated by |: bukkit.yml shutdown-message
                    # and spigot.yml messages.restart. Its players wait on the hub; any other kick disconnects.
                    shutdown-messages=Server closed|Server is restarting
                    # Commands that send a player to the hub from anywhere, separated by |. Blank = none.
                    hub-commands=hub|lobby
                    # The network's database, as in each server's plugins/RainsCore/network.yml. Bans are refused here,
                    # before any server sees the player. Blank host = only the servers check. RAINS_DB_HOST,
                    # RAINS_DB_PORT, RAINS_DB_NAME, RAINS_DB_USER, RAINS_DB_PASSWORD, RAINS_DB_SSLMODE override these.
                    database.host=
                    database.port=5432
                    database.name=rains
                    database.user=rains
                    database.password=
                    database.sslmode=prefer
                    # Where RainsCore keeps its core data: the namespace "core" unless network.yml says otherwise.
                    database.namespace=core
                    """);
        }
        Properties config = new Properties();
        try (var in = Files.newBufferedReader(file)) {
            config.load(in);
        }
        Map.of("RAINS_DB_HOST", "database.host", "RAINS_DB_PORT", "database.port", "RAINS_DB_NAME", "database.name",
                "RAINS_DB_USER", "database.user", "RAINS_DB_PASSWORD", "database.password",
                "RAINS_DB_SSLMODE", "database.sslmode").forEach((variable, key) -> {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                config.setProperty(key, value);
            }
        });
        return config;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        BanCheck check = bans;
        if (check == null || !event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            long now = System.currentTimeMillis();
            Optional<BanCheck.Ban> ban = check.activeBan(event.getPlayer().getUniqueId(), now);
            ban.ifPresent(found -> event.setResult(ResultedEvent.ComponentResult.denied(BanScreen.component(found, now))));
            String error = check.lastError().orElse(null);
            if (error != null && !error.equals(reportedError)) {
                log.warn("Bans could not be checked at the door ({}); the servers still check them.", error);
            }
            reportedError = error;
        });
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        if (route == null) {
            return;
        }
        boolean onHub = event.getPlayer().getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(hub)).orElse(false);
        String reason = event.getServerKickReason().map(PlainTextComponentSerializer.plainText()::serialize).orElse(null);
        Optional<RegisteredServer> target = proxy.getServer(hub);
        switch (route.of(event.getServer().getServerInfo().getName(), onHub, reason)) {
            case HUB -> target.ifPresent(server -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(server,
                    MiniMessage.miniMessage().deserialize("<light_purple>That server is restarting</light_purple> "
                            + "<gray>— wait on the hub.</gray>"))));
            case DISCONNECT -> event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    event.getServerKickReason().orElse(Component.text("Disconnected"))));
            case LEAVE -> {
            }
        }
    }
}
