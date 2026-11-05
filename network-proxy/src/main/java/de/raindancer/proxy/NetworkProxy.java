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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
    private LeaveFrame frame = LeaveFrame.from(new Properties());
    private BanCheck bans;
    private WhitelistGate whitelist;
    private String whitelistMessage;
    private String codeMessage;
    private volatile Handover handover;
    private Predicate<UUID> bedrock = Handover::isBedrock;
    private final Map<String, String> reportedErrors = new java.util.concurrent.ConcurrentHashMap<>();

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
        frame = LeaveFrame.from(config);
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
            if (Boolean.parseBoolean(config.getProperty("whitelist", "false").strip())) {
                log.warn("whitelist=true needs database.host: the whitelist is left to each server.");
            }
        } else {
            ProxyDatabase database = new ProxyDatabase(host,
                    Integer.parseInt(config.getProperty("database.port", "5432").strip()),
                    config.getProperty("database.name", "rains").strip(),
                    config.getProperty("database.user", "rains").strip(),
                    config.getProperty("database.password", ""),
                    config.getProperty("database.sslmode", "prefer").strip());
            String core = config.getProperty("database.namespace", "core").strip();
            bans = new BanCheck(database, core);
            log.info("Bans are checked at the door in {}.", database.describe());
            if (Boolean.parseBoolean(config.getProperty("whitelist", "false").strip())) {
                whitelist = new WhitelistGate(database::connect, core,
                        config.getProperty("whitelist.codes-namespace", "discordlink").strip(),
                        Long.parseLong(config.getProperty("whitelist.code-minutes", "30").strip()) * 60_000L,
                        new java.security.SecureRandom(), System::currentTimeMillis);
                whitelistMessage = config.getProperty("whitelist.message", DEFAULT_NOT_WHITELISTED);
                codeMessage = config.getProperty("whitelist.message-code", DEFAULT_CODE);
                WhitelistGate gate = whitelist;
                proxy.getScheduler().buildTask(this, gate::refresh).repeat(java.time.Duration.ofSeconds(30)).schedule();
                log.info("The network's whitelist is checked at the door; who is not on it gets a Discord /link code.");
            }
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
                    # The network's whitelist (RainsCore's, one list for every server), checked here before any server
                    # sees the player; needs database.host. Who is not on it is turned away with a Discord /link code
                    # while Discord Link's bot runs somewhere, else with whitelist.message. MiniMessage; <code> and
                    # <minutes> in whitelist.message-code. false = every server checks its own.
                    whitelist=false
                    whitelist.code-minutes=30
                    # Where Discord Link keeps its codes: the namespace "discordlink" unless its database says otherwise.
                    whitelist.codes-namespace=discordlink
                    #whitelist.message=<red>You are not whitelisted on this network.</red>
                    #whitelist.message-code=<white>Not on the whitelist yet.</white> <gray>Type</gray> /link <code> <gray>in Discord.</gray>
                    # Screens the proxy shows (a ban, the whitelist): the same frame as the servers' "Join and kick screens" -
                    # the name on top in capitals, its tag gradient (two colours), a grey footer. Blank title = no frame.
                    screens.title=
                    screens.footer=
                    screens.gradient=#C9A0FF,#7C5CBF
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

    /** Early: who may not come in at all is refused before another plugin acts on the login (wakes a server). */
    @Subscribe(order = PostOrder.EARLY)
    public EventTask onLogin(LoginEvent event) {
        BanCheck check = bans;
        WhitelistGate gate = whitelist;
        if (check == null || !event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            long now = System.currentTimeMillis();
            Player player = event.getPlayer();
            Optional<BanCheck.Ban> ban = check.activeBan(player.getUniqueId(), now);
            ban.ifPresent(found -> event.setResult(ResultedEvent.ComponentResult.denied(BanScreen.component(found, now, frame))));
            report(check.lastError().orElse(null), "Bans");
            if (ban.isPresent() || gate == null) {
                return;
            }
            WhitelistGate.Verdict verdict = gate.check(player.getUniqueId(), player.getUsername());
            report(gate.lastError().orElse(null), "The whitelist");
            switch (verdict.kind()) {
                case CODE -> {
                    log.info("{} ({}) is not on the network's whitelist: turned away with a /link code.",
                            player.getUsername(), player.getUniqueId());
                    event.setResult(ResultedEvent.ComponentResult.denied(frame.around(MiniMessage.miniMessage().deserialize(
                            codeMessage, Placeholder.unparsed("code", verdict.code()),
                            Placeholder.unparsed("minutes", String.valueOf(gate.codeMinutes()))))));
                }
                case REFUSED -> {
                    log.info("{} ({}) is not on the network's whitelist: turned away.", player.getUsername(),
                            player.getUniqueId());
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            frame.around(MiniMessage.miniMessage().deserialize(whitelistMessage))));
                }
                case IN, UNKNOWN -> {
                }
            }
        });
    }

    /** Once per new error, not once per login. */
    private void report(String error, String what) {
        String before = error == null ? reportedErrors.remove(what) : reportedErrors.put(what, error);
        if (error != null && !error.equals(before)) {
            log.warn("{} could not be checked at the door ({}); the servers still check.", what, error);
        }
    }

    static final String DEFAULT_NOT_WHITELISTED = "<white><bold>You're not on the whitelist</bold></white><newline><newline>"
            + "<gray>Ask the staff to add you.";
    /** Worded like Discord Link's own code screen (discordlink.join.kick), so both doors look the same. */
    static final String DEFAULT_CODE = "<white><bold>You're not on the whitelist yet</bold></white><newline><newline>"
            + "<gray>Type this in <#5865F2>Lilly's Discord</#5865F2>:</gray><newline>"
            + "<light_purple><bold>/link <code></bold></light_purple><newline><newline>"
            + "<gray>The bot whitelists you. Then join again.<newline>"
            + "<dark_gray>The code lasts <minutes> minutes.";

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
