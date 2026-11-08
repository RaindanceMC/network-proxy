package de.raindancer.proxy;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /hub} (and {@code /lobby}): back to the hub from anywhere on the network. On the proxy, so it answers before
 * any backend sees the command — RainsCore's own {@code /hub} panel stays reachable as {@code /smp}. On the hub
 * itself it is handed to the hub as its spawn command: a parkour course there is a world of its own, still "the hub".
 * {@code /hub <name>} goes to another hub ({@code hubs.<name>} in the config, e.g. the beta's); whether they may enter
 * it is the gate's business ({@link Gates}), plain {@code /hub} brings them back.
 */
final class HubCommand implements SimpleCommand {

    enum Answer { GO, TO_SPAWN, ALREADY_THERE, PLAYERS_ONLY }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProxyServer proxy;
    private final String hub;
    private final String spawn;
    private final Map<String, String> hubs;

    /** @param spawnCommand what takes somebody on the hub to its spawn, blank = nothing does */
    HubCommand(ProxyServer proxy, String hub, String spawnCommand) {
        this(proxy, hub, spawnCommand, Map.of());
    }

    /** @param hubs more hubs by name: {@code /hub <name>} */
    HubCommand(ProxyServer proxy, String hub, String spawnCommand, Map<String, String> hubs) {
        this.proxy = proxy;
        this.hub = hub;
        this.spawn = chatInput(spawnCommand);
        this.hubs = hubs;
    }

    /** No argument = the main hub; a known name = that hub; anything else = none. */
    static Optional<String> target(List<String> args, String hub, Map<String, String> hubs) {
        if (args.isEmpty()) {
            return Optional.of(hub);
        }
        return Optional.ofNullable(hubs.get(args.getFirst().toLowerCase(Locale.ROOT)));
    }

    /**
     * @param current the server they are on, {@code null} while still connecting
     * @param spawn   the hub's spawn command, blank = none
     */
    static Answer answer(boolean isPlayer, String current, String hub, String spawn) {
        if (!isPlayer) {
            return Answer.PLAYERS_ONLY;
        }
        if (current == null || !current.equalsIgnoreCase(hub)) {
            return Answer.GO;
        }
        return spawn.isBlank() ? Answer.ALREADY_THERE : Answer.TO_SPAWN;
    }

    /** {@code spawn} or {@code /spawn} → {@code /spawn}; {@code null} or blank → blank. */
    static String chatInput(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String stripped = command.strip();
        return stripped.startsWith("/") ? stripped : "/" + stripped;
    }

    /** {@code hub|lobby} → [hub, lobby]; {@code null} = the defaults, blank = none. */
    static List<String> names(String configured) {
        if (configured == null) {
            return List.of("hub", "lobby");
        }
        return Arrays.stream(configured.split("\\|")).map(name -> name.strip().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty()).toList();
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = invocation.source() instanceof Player p ? p : null;
        Optional<String> wanted = target(List.of(invocation.arguments()), hub, hubs);
        if (wanted.isEmpty()) {
            invocation.source().sendMessage(MM.deserialize("<red>There's no hub called <white><name></white>.</red>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", invocation.arguments()[0])));
            return;
        }
        String hub = wanted.get();
        String current = player == null ? null
                : player.getCurrentServer().map(ServerConnection::getServerInfo).map(info -> info.getName()).orElse(null);
        switch (answer(player != null, current, hub, spawn)) {
            case PLAYERS_ONLY -> invocation.source().sendMessage(MM.deserialize("<red>Only players can go to the hub.</red>"));
            // straight to the hub server, not through the proxy's commands again
            case TO_SPAWN -> player.spoofChatInput(spawn);
            case ALREADY_THERE -> player.sendMessage(MM.deserialize("<gray>You're on the hub already.</gray>"));
            case GO -> proxy.getServer(hub).ifPresentOrElse(
                    server -> player.createConnectionRequest(server).fireAndForget(),
                    () -> player.sendMessage(MM.deserialize("<red>The hub isn't set up on this network.</red>")));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1 || hubs.isEmpty()) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Player player = invocation.source() instanceof Player p ? p : null;
        return hubs.keySet().stream().filter(name -> name.startsWith(typed))
                .filter(name -> player == null || mayEnter.test(player, hubs.get(name))).sorted().toList();
    }

    /** Who sees which hub names offered; the gate still decides at the door. */
    private java.util.function.BiPredicate<Player, String> mayEnter = (player, server) -> true;

    HubCommand offering(java.util.function.BiPredicate<Player, String> mayEnter) {
        this.mayEnter = mayEnter;
        return this;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
