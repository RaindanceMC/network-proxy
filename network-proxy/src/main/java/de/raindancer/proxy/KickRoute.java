package de.raindancer.proxy;

import java.util.List;
import java.util.Locale;

/**
 * Where a player goes when a server lets go of them. Velocity's own try-list would send <em>every</em> kick to the
 * hub: a /kick would become a short trip, and a ban a player stuck on the hub. Only a server going away — its
 * shutdown message, a crash, being unreachable — means "wait on the hub".
 *
 * <p>The shutdown messages are the one way in, so anything that can kick with one of them can send a player to the
 * hub instead of off the network: staff and plugins on that server, none of them a player.
 */
public final class KickRoute {

    public enum Route {
        /** To the hub, to wait. */
        HUB,
        /** Off the network, with the server's reason — what a kick or ban always was. */
        DISCONNECT,
        /** Not ours to decide: Velocity's default stands. */
        LEAVE
    }

    private final String hub;
    private final List<String> closing;

    public KickRoute(String hub, List<String> shutdownMessages) {
        this.hub = hub;
        this.closing = shutdownMessages.stream().map(KickRoute::normal).toList();
    }

    /**
     * @param server the server that let go of them
     * @param onHub  whether they are on the hub right now — a refused move, not a kick
     * @param reason the server's reason as plain text; {@code null} when the server never answered
     */
    public Route of(String server, boolean onHub, String reason) {
        if (hub.equalsIgnoreCase(server) || onHub) {
            return Route.LEAVE;
        }
        // null = no kick packet (a crash, or unreachable at join). A blank reason is still a kick.
        if (reason == null || closing.contains(normal(reason))) {
            return Route.HUB;
        }
        return Route.DISCONNECT;
    }

    private static String normal(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
