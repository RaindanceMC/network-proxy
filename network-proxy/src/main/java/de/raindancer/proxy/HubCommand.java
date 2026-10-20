package de.raindancer.proxy;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hub} (and {@code /lobby}): back to the hub from anywhere on the network. On the proxy, so it answers before
 * any backend sees the command — RainsCore's own {@code /hub} panel stays reachable as {@code /smp}. On the hub
 * itself it is handed to the hub as its spawn command: a parkour course there is a world of its own, still "the hub".
 */
final class HubCommand implements SimpleCommand {

    enum Answer { GO, TO_SPAWN, ALREADY_THERE, PLAYERS_ONLY }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProxyServer proxy;
    private final String hub;
    private final String spawn;

    /** @param spawnCommand what takes somebody on the hub to its spawn, blank = nothing does */
    HubCommand(ProxyServer proxy, String hub, String spawnCommand) {
        this.proxy = proxy;
        this.hub = hub;
        this.spawn = chatInput(spawnCommand);
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
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
