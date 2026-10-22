package de.raindancer.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The proxy's side of a RainsCore network: a banned player is turned away before any server sees them, /hub
 * works from anywhere, the players of a server that closes wait on the hub instead of being dropped, and servers
 * behind another proxy are reached by handing the player over to it ({@link Handover}).
 */
public final class NetworkProxy {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path folder;
    private KickRoute route;
    private String hub;
    private BanCheck bans;
    private volatile Handover handover;
    private Predicate<UUID> bedrock = Handover::isBedrock;
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
                    new HubCommand(proxy, hub, config.getProperty("hub-spawn-command", "spawn")));
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
        handover = Handover.from(config);
        handover.problems().forEach(problem -> log.warn("Not handed over, the line is wrong: {}", problem));
        if (!handover.servers().isEmpty()) {
            bedrock = floodgate().orElse(Handover::isBedrock);
            log.info("Players going to these servers are handed over to their own proxy (Bedrock players too: {}): {}",
                    handover.bedrock(), handover.servers());
            if (!handover.stay().isEmpty()) {
                log.info("...except who came in by {}: they stay on this proxy.", handover.stay());
            }
        }
        log.info("RainsNetworkProxy: a closing server's players wait on '{}' (shutdown messages {}), hub commands {}.",
                hub, closing, commands);
    }

    /** Floodgate's own answer when it runs here — it also knows Bedrock players linked to a Java account. */
    private Optional<Predicate<UUID>> floodgate() {
        if (!proxy.getPluginManager().isLoaded("floodgate")) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = type.getMethod("getInstance").invoke(null);
            Method check = type.getMethod("isFloodgatePlayer", UUID.class);
            return Optional.of(player -> {
                try {
                    return (Boolean) check.invoke(api, player);
                } catch (ReflectiveOperationException broken) {
                    return Handover.isBedrock(player);
                }
            });
        } catch (ReflectiveOperationException | LinkageError missing) {
            log.warn("Floodgate is loaded but its API could not be reached ({}); Bedrock players are told apart by id.",
                    missing.toString());
            return Optional.empty();
        }
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
                    # On the hub those commands run this one there instead, e.g. off a parkour course. Blank = only
                    # "you're on the hub already".
                    hub-spawn-command=spawn
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
                    # Servers behind another proxy (another data centre): a player switching there is handed over
                    # with Minecraft's transfer and connects to that proxy directly. handover.<server>=host[:port],
                    # the other proxy picks the server by that host name (forced-hosts) and needs accepts-transfers.
                    # Relayed through velocity.toml as before: the first server of a login and clients before 1.20.5.
                    #handover.arena=arena.example.net:25565
                    # Bedrock players follow too; their Geyser needs the RainsGeyserHandover extension, else it
                    # drops them. false = Bedrock players stay relayed.
                    handover.bedrock=true
                    # Addresses whose players are never handed over from here, e.g. a second way in closer to some
                    # players: handover.stay=proxy-2.example.net
                    #handover.stay=
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

    /** Late, so a plugin that refuses the move has had its say first. */
    @Subscribe(order = PostOrder.LATE)
    public void onPreConnect(ServerPreConnectEvent event) {
        Handover rules = handover;
        if (rules == null || !event.getResult().isAllowed()) {
            return;
        }
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        Player player = event.getPlayer();
        Handover.Decision decision = rules.decide(target.getServerInfo().getName(),
                event.getPreviousServer() != null || player.getCurrentServer().isPresent(),
                player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_5),
                bedrock.test(player.getUniqueId()),
                player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null));
        if (!decision.handOver()) {
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.transferToHost(InetSocketAddress.createUnresolved(decision.host(), decision.port()));
        log.info("{} handed over to {} ({}:{}).", player.getUsername(), target.getServerInfo().getName(),
                decision.host(), decision.port());
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
