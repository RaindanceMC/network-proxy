package de.raindancer.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
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
    private volatile Optional<LaunchGate> launch = Optional.empty();
    private BanCheck bans;
    private volatile NewcomerRoute newcomerRoute;
    private volatile Newcomers newcomers;
    private WhitelistGate whitelist;
    private String whitelistMessage;
    private String codeMessage;
    private volatile Handover handover;
    private Gates gates = Gates.from(new Properties());
    private volatile Optional<ServerQueue.Settings> queueSettings = Optional.empty();
    private final ServerQueue queue = new ServerQueue();
    /** Who the queue is moving in right now: let through once, past the line. */
    private final java.util.Set<java.util.UUID> moving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String queueMessage = DEFAULT_QUEUE;
    private String queueBar = DEFAULT_QUEUE_BAR;
    private String queueTitle = DEFAULT_QUEUE_TITLE;
    private String queueSubtitle = DEFAULT_QUEUE_SUBTITLE;
    private ServerAccess access;
    private String gateMessage;
    private Predicate<UUID> bedrock = Handover::isBedrock;
    private WhitelistGate.Connector databaseConnector;
    private final java.util.concurrent.atomic.AtomicBoolean prepared = new java.util.concurrent.atomic.AtomicBoolean();
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
        launch = LaunchGate.from(config);
        launch.ifPresent(gate -> log.info("Closed until launch ({}); bypass: {}", java.time.Instant.ofEpochMilli(gate.at()), gate.bypassPermission()));
        List<String> closing = new ArrayList<>();
        for (String message : config.getProperty("shutdown-messages", "Server closed").split("\\|")) {
            if (!message.isBlank()) {
                closing.add(message);
            }
        }
        route = new KickRoute(hub, closing);
        newcomerRoute = NewcomerRoute.from(config);
        gates = Gates.from(config);
        gates.problems().forEach(problem -> log.warn("Not a gate, the line is wrong: {}", problem));
        gateMessage = config.getProperty("gates.message", DEFAULT_GATE);
        List<String> commands = HubCommand.names(config.getProperty("hub-commands"));
        if (!commands.isEmpty()) {
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(commands.getFirst())
                    .aliases(commands.subList(1, commands.size()).toArray(String[]::new)).plugin(this).build(),
                    new HubCommand(proxy, hub, config.getProperty("hub-spawn-command", "spawn"), gates.hubs())
                            .offering(this::mayOffer));
        }
        String host = config.getProperty("database.host", "").strip();
        if (host.isEmpty()) {
            log.warn("No database.host in config.properties (or RAINS_DB_HOST): bans are checked by each server only.");
            if (newcomerRoute.isOn()) {
                log.warn("newcomers.server needs database.host to know who is new: nobody is sent there.");
            }
            if (Boolean.parseBoolean(config.getProperty("whitelist", "false").strip())) {
                log.warn("whitelist=true needs database.host: the whitelist is left to each server.");
            }
            if (!gates.isEmpty()) {
                log.warn("gate.* needs database.host for its lists: only players with a list's permission get in.");
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
            databaseConnector = database::connect;
            if (newcomerRoute.isOn()) {
                newcomers = new Newcomers(database::connect, core);
                log.info("Players new to the network go to {} first.", newcomerRoute.server());
            }
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
        if (!gates.isEmpty() && bans != null) {
            ServerAccess lists = new ServerAccess(databaseConnector, config.getProperty("access.namespace", "network_proxy").strip(),
                    config.getProperty("database.namespace", "core").strip(), System::currentTimeMillis);
            access = lists;
            proxy.getScheduler().buildTask(this, () -> {
                if (prepared.compareAndSet(false, true)) {
                    try {
                        lists.prepare();
                    } catch (java.sql.SQLException | RuntimeException failed) {
                        prepared.set(false);
                        report(failed.getMessage(), "The gates' lists");
                        return;
                    }
                }
                report(lists.refresh() ? null : lists.lastError().orElse(null), "The gates' lists");
            }).repeat(java.time.Duration.ofSeconds(30)).schedule();
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("tn").plugin(this).build(),
                    new TnCommand(proxy, this, log, lists, gates, hub));
        }
        if (!gates.isEmpty()) {
            for (String list : gates.lists()) {
                log.info("Gate '{}': servers {} only for players on its list or with {}.", list, gates.serversOf(list),
                        gates.permission(list));
            }
        }
        queueSettings = ServerQueue.Settings.from(config);
        String gradient = "<gradient:" + frame.from().asHexString() + ":" + frame.to().asHexString() + ">";
        queueMessage = config.getProperty("queue.message", DEFAULT_QUEUE);
        queueBar = config.getProperty("queue.bar", DEFAULT_QUEUE_BAR.replace("<frame>", gradient));
        queueTitle = config.getProperty("queue.title", DEFAULT_QUEUE_TITLE.replace("<frame>", gradient));
        queueSubtitle = config.getProperty("queue.subtitle", DEFAULT_QUEUE_SUBTITLE);
        queueSettings.ifPresent(settings -> {
            proxy.getScheduler().buildTask(this, this::moveQueue).repeat(java.time.Duration.ofSeconds(2)).schedule();
            log.info("Queue: {} takes {} players; then they wait on '{}', {} first, {} never waits.", settings.server(),
                    settings.max(), settings.limbo().isEmpty() ? "(no waiting server: turned away)" : settings.limbo(),
                    settings.priorityPermission(), settings.bypassPermission());
        });
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
                    # Players the network has never seen (no first-seen or last-seen row in database.namespace) start
                    # on this server — a welcome server with the intro and tutorial, on a database of its own, which
                    # sends them on when they are done. Needs database.host; unreachable at their first connect, they
                    # go to Velocity's try list instead. Blank = off.
                    newcomers.server=
                    whitelist.code-minutes=30
                    # Where Discord Link keeps its codes: the namespace "discordlink" unless its database says otherwise.
                    whitelist.codes-namespace=discordlink
                    #whitelist.message=<red>You are not whitelisted on this network.</red>
                    #whitelist.message-code=<white>Not on the whitelist yet.</white> <gray>Type</gray> /link <code> <gray>in Discord.</gray>
                    # Servers only some may enter (a closed beta): gate.<server>=<list>, several servers may share a
                    # list. On it: /tn <list> add|remove|list (permission thundernetwork.admin, or the console); only
                    # names on the network's whitelist can be added. list.<list>.permission lets in without being on it
                    # (default thundernetwork.list.<list>). Needs database.host; the lists live in access.namespace.
                    #gate.beta=beta
                    #list.beta.permission=thundernetwork.beta
                    access.namespace=network_proxy
                    #gates.message=<light_purple>The <list> is invite-only.</light_purple> <gray>Ask the staff if you want in.</gray>
                    # More hubs for /hub <name>: hubs.<name>=<server>. Plain /hub goes back to hub=.
                    #hubs.beta=betahub
                    # Screens the proxy shows (a ban, the whitelist): the same frame as the servers' "Join and kick screens" -
                    # the name on top in capitals, its tag gradient (two colours), a grey footer. Blank title = no frame.
                    screens.title=
                    screens.footer=
                    screens.gradient=#C9A0FF,#7C5CBF
                    # Before a launch: who lacks launch.bypass-permission gets a countdown screen. German time unless an offset is given.
                    #launch.at=2026-11-27T16:00
                    #launch.bypass-permission=rainsnetworkproxy.launch.bypass
                    #launch.message=<white><bold>We're not open yet</bold></white>\\n\\n<gray>Opening in</gray> <gold><remaining></gold>\\n<gray><when></gray>
                    # Early access (creators): who has launch.early-permission gets in launch.early-hours before the launch,
                    # and until then sees launch.early-message, counting down to their own opening. 0 hours = none.
                    #launch.early-permission=rainsnetworkproxy.launch.early
                    #launch.early-hours=24
                    #launch.early-message=<white><bold>Your early access is almost here</bold></white>\\n\\n<gray>You get in</gray> <gold><remaining></gold>\\n<gray><when></gray>
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
        if (!event.getResult().isAllowed()) {
            return null;
        }
        Optional<LaunchGate> opening = launch;
        long at = System.currentTimeMillis();
        if (opening.isPresent() && opening.get().closed(at)) {
            LaunchGate gate = opening.get();
            Player player = event.getPlayer();
            switch (gate.door(at, player.hasPermission(gate.bypassPermission()), player.hasPermission(gate.earlyPermission()))) {
                case OPEN -> {
                }
                case EARLY_COUNTDOWN -> {
                    log.info("{} ({}) has early access, not yet: turned away with their own countdown.",
                            player.getUsername(), player.getUniqueId());
                    event.setResult(ResultedEvent.ComponentResult.denied(gate.earlyScreen(at, frame)));
                    return null;
                }
                case COUNTDOWN -> {
                    log.info("{} ({}) tried to join before the launch: turned away with the countdown.",
                            player.getUsername(), player.getUniqueId());
                    event.setResult(ResultedEvent.ComponentResult.denied(gate.screen(at, frame)));
                    return null;
                }
            }
        }
        BanCheck check = bans;
        WhitelistGate gate = whitelist;
        if (check == null) {
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

    /**
     * A gated server: only who is on its list or has the list's permission. Refused on the way in (no server yet):
     * the hub instead. Before the handover (late), which must not hand somebody over to a server they may not enter.
     */
    @Subscribe(order = PostOrder.NORMAL)
    public EventTask onGate(ServerPreConnectEvent event) {
        if (gates.isEmpty() || !event.getResult().isAllowed()) {
            return null;
        }
        RegisteredServer target = event.getResult().getServer().orElse(null);
        Optional<String> list = target == null ? Optional.empty() : gates.listOf(target.getServerInfo().getName());
        if (list.isEmpty()) {
            return null;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(gates.permission(list.get()))) {
            return null;
        }
        ServerAccess lists = access;
        return EventTask.async(() -> {
            boolean in = lists != null && lists.check(list.get(), player.getUniqueId());
            if (lists != null) {
                report(lists.lastError().orElse(null), "The gates' lists");
            }
            if (in) {
                return;
            }
            log.info("{} may not enter {} (not on the {} list).", player.getUsername(), target.getServerInfo().getName(),
                    list.get());
            player.sendMessage(MiniMessage.miniMessage().deserialize(gateMessage, Placeholder.unparsed("list", list.get())));
            Optional<RegisteredServer> main = proxy.getServer(hub);
            boolean arriving = event.getPreviousServer() == null && player.getCurrentServer().isEmpty();
            if (arriving && main.isPresent() && !main.get().equals(target)) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(main.get()));
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        });
    }

    /** For tab completion only: permission or the list read last, no database question. */
    private boolean mayOffer(Player player, String server) {
        Optional<String> list = gates.listOf(server);
        if (list.isEmpty() || player.hasPermission(gates.permission(list.get()))) {
            return true;
        }
        ServerAccess lists = access;
        return lists != null && lists.remembered(list.get(), player.getUniqueId());
    }

    static final String DEFAULT_GATE = "<light_purple>The <list> is invite-only.</light_purple> "
            + "<gray>Ask the staff if you want in.</gray>";

    static final String DEFAULT_QUEUE = "<hyades> <gray>is full (<online>/<max>). You're</gray> <white><bold>#<position></bold></white>"
            + " <gray>in line and go in by yourself as soon as a place frees up — Prime members go first.</gray>";
    /*
     * The waiting screen, in the frame of the network's other screens (screens.title / screens.gradient): <frame> is
     * its gradient, <hyades> its "✦ HYADES ✦" mark. Over the welcome server's midnight sky (RainsWelcome 0.6.0).
     */
    static final String DEFAULT_QUEUE_TITLE = "<white>You're</white> <frame><bold>#<position></bold></gradient> <white>in line</white>";
    static final String DEFAULT_QUEUE_SUBTITLE = "<hyades> <gray>is full · you're moved in by yourself</gray>";
    static final String DEFAULT_QUEUE_BAR = "<dark_gray>━━━━━</dark_gray>  <gray><size> waiting · <frame>Prime</gradient> goes first</gray>"
            + "  <dark_gray>━━━━━</dark_gray>";
    /** Refreshed every two seconds, so it never fades between two refreshes. */
    private static final net.kyori.adventure.title.Title.Times QUEUE_TITLE_TIMES = net.kyori.adventure.title.Title.Times.times(
            java.time.Duration.ZERO, java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(500));
    static final String DEFAULT_QUEUE_FULL = "<white>We're full right now</white> <gray>(<online>/<max>).</gray>\n\n<gray>Try again in a moment.</gray>";

    /**
     * The line in front of a full server ({@link ServerQueue}). Late, after the gates: somebody who may not enter at
     * all never queues for it.
     */
    @Subscribe(order = PostOrder.LATE)
    public void onQueue(ServerPreConnectEvent event) {
        Optional<ServerQueue.Settings> settings = queueSettings;
        if (settings.isEmpty() || !event.getResult().isAllowed()) {
            return;
        }
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null || !target.getServerInfo().getName().equalsIgnoreCase(settings.get().server())) {
            return;
        }
        Player player = event.getPlayer();
        java.util.UUID id = player.getUniqueId();
        boolean priority = player.hasPermission(settings.get().priorityPermission());
        boolean wasMoving = moving.remove(id);
        int online = target.getPlayersConnected().size();
        if (ServerQueue.admit(online, settings.get().max(), player.hasPermission(settings.get().bypassPermission()),
                wasMoving, queue.isEmpty())) {
            queue.leave(id);
            return;
        }
        // Moved in but the slot was taken in between: back to the very front.
        queue.join(id, priority, wasMoving ? Long.MIN_VALUE : System.currentTimeMillis());
        boolean arriving = event.getPreviousServer() == null && player.getCurrentServer().isEmpty();
        Optional<RegisteredServer> limbo = settings.get().limbo().isEmpty() ? Optional.empty()
                : proxy.getServer(settings.get().limbo());
        if (arriving && limbo.isEmpty()) {
            queue.leave(id);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(frame.around(MiniMessage.miniMessage().deserialize(DEFAULT_QUEUE_FULL,
                    Placeholder.unparsed("online", String.valueOf(online)),
                    Placeholder.unparsed("max", String.valueOf(settings.get().max())))));
            return;
        }
        event.setResult(arriving ? ServerPreConnectEvent.ServerResult.allowed(limbo.get())
                : ServerPreConnectEvent.ServerResult.denied());
        if (arriving) {
            leaveQueueNote(player);
        }
        player.sendMessage(MiniMessage.miniMessage().deserialize(queueMessage,
                Placeholder.component("hyades", frame.mark()),
                Placeholder.unparsed("online", String.valueOf(online)),
                Placeholder.unparsed("max", String.valueOf(settings.get().max())),
                Placeholder.unparsed("position", String.valueOf(queue.position(id)))));
        log.info("{} queued for {} (#{} of {}{}).", player.getUsername(), target.getServerInfo().getName(),
                queue.position(id), queue.size(), priority ? ", Prime" : "");
    }

    /** Read by RainsWelcome (its {@code came-for} note): a parked player skips the newcomer intro there. */
    static final net.kyori.adventure.key.Key QUEUE_NOTE = net.kyori.adventure.key.Key.key("rainswelcome", "came-for");

    private void leaveQueueNote(Player player) {
        // Cookies exist from 1.20.5 on; older clients (and Bedrock through Geyser) simply get the welcome as before.
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
            return;
        }
        try {
            player.storeCookie(QUEUE_NOTE, "queue".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalStateException | IllegalArgumentException notNow) {
            log.debug("No queue note for {}: {}", player.getUsername(), notNow.getMessage());
        }
    }

    /** Every two seconds: free places to the front of the line, everybody else their place on the action bar. */
    private void moveQueue() {
        Optional<ServerQueue.Settings> settings = queueSettings;
        Optional<RegisteredServer> target = settings.flatMap(s -> proxy.getServer(s.server()));
        if (settings.isEmpty() || target.isEmpty()) {
            return;
        }
        int free = settings.get().max() - target.get().getPlayersConnected().size() - moving.size();
        while (free > 0) {
            Optional<java.util.UUID> next = queue.next();
            if (next.isEmpty()) {
                break;
            }
            Optional<Player> player = proxy.getPlayer(next.get());
            if (player.isEmpty()) {
                continue;
            }
            moving.add(next.get());
            player.get().createConnectionRequest(target.get()).connect().whenComplete((result, failure) -> {
                moving.remove(next.get());
                if (failure != null || result == null || !result.isSuccessful()) {
                    queue.join(next.get(), player.get().hasPermission(settings.get().priorityPermission()), Long.MIN_VALUE);
                } else {
                    player.get().clearTitle();
                }
            });
            free--;
        }
        int size = queue.size();
        for (java.util.UUID waiting : queue.waiting()) {
            proxy.getPlayer(waiting).ifPresent(player -> showPlace(player, queue.position(waiting), size));
        }
    }

    private void showPlace(Player player, int position, int size) {
        net.kyori.adventure.text.minimessage.tag.resolver.TagResolver places =
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                        Placeholder.unparsed("position", String.valueOf(position)),
                        Placeholder.unparsed("size", String.valueOf(size)),
                        Placeholder.component("hyades", frame.mark()));
        MiniMessage mini = MiniMessage.miniMessage();
        player.showTitle(net.kyori.adventure.title.Title.title(mini.deserialize(queueTitle, places),
                mini.deserialize(queueSubtitle, places), QUEUE_TITLE_TIMES));
        if (!queueBar.isBlank()) {
            player.sendActionBar(mini.deserialize(queueBar, places));
        }
    }

    @Subscribe
    public void onQueueLeave(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        queue.leave(event.getPlayer().getUniqueId());
        moving.remove(event.getPlayer().getUniqueId());
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

    /** A player the network has never seen starts on the welcome server (newcomers.server). */
    @Subscribe
    public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        NewcomerRoute rule = newcomerRoute;
        Newcomers check = newcomers;
        if (rule == null || !rule.isOn() || check == null) {
            return null;
        }
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            Optional<Boolean> known = check.known(player.getUniqueId());
            report(check.lastError().orElse(null), "Newcomers");
            Optional<RegisteredServer> welcome = proxy.getServer(rule.server());
            rule.firstServer(known, welcome.isPresent()).flatMap(proxy::getServer).ifPresent(server -> {
                event.setInitialServer(server);
                log.info("{} is new to the network: first to {}.", player.getUsername(),
                        server.getServerInfo().getName());
            });
        });
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        NewcomerRoute rule = newcomerRoute;
        if (rule != null) {
            Optional<RegisteredServer> instead = rule.rescue(event.getServer().getServerInfo().getName(),
                    event.getPlayer().getCurrentServer().isPresent(),
                    proxy.getConfiguration().getAttemptConnectionOrder()).flatMap(proxy::getServer);
            if (instead.isPresent()) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(instead.get()));
                log.info("{}: the welcome server could not be reached, on to {}.", event.getPlayer().getUsername(),
                        instead.get().getServerInfo().getName());
                return;
            }
        }
        // The server could not be reached at all (a restart, nearly always): our own screen instead of Velocity's
        // "Unable to connect you to smp", unless something below sends them somewhere instead.
        boolean nowhereElse = event.getPlayer().getCurrentServer().isEmpty()
                || event.getPlayer().getCurrentServer().get().getServer().equals(event.getServer());
        if (UnreachableScreen.replaces(event.getServerKickReason(), nowhereElse)
                && event.getResult() instanceof KickedFromServerEvent.DisconnectPlayer) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    UnreachableScreen.component(event.getServer().getServerInfo().getName(), frame)));
        }
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
