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
 * any backend sees the command — RainsCore's own {@code /hub} panel stays reachable as {@code /smp}.
 */
final class HubCommand implements SimpleCommand {

    enum Answer { GO, ALREADY_THERE, PLAYERS_ONLY }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProxyServer proxy;
    private final String hub;

    HubCommand(ProxyServer proxy, String hub) {
        this.proxy = proxy;
        this.hub = hub;
    }

    /** @param current the server they are on, {@code null} while still connecting */
    static Answer answer(boolean isPlayer, String current, String hub) {
        if (!isPlayer) {
            return Answer.PLAYERS_ONLY;
        }
        return current != null && current.equalsIgnoreCase(hub) ? Answer.ALREADY_THERE : Answer.GO;
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
        switch (answer(player != null, current, hub)) {
            case PLAYERS_ONLY -> invocation.source().sendMessage(MM.deserialize("<red>Only players can go to the hub.</red>"));
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
