package de.raindancer.proxy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Where a player nobody on the network has seen yet goes first: {@code newcomers.server}, the welcome server with
 * the intro and the tutorial. Everybody else — and a newcomer while the database cannot say — gets Velocity's own
 * choice. The welcome server sends them on to the network when they are done or skip it.
 */
public final class NewcomerRoute {

    private final String server;

    private NewcomerRoute(String server) {
        this.server = server;
    }

    public static NewcomerRoute from(Properties config) {
        String name = config.getProperty("newcomers.server", "").strip().toLowerCase(Locale.ROOT);
        return new NewcomerRoute(name);
    }

    public boolean isOn() {
        return !server.isEmpty();
    }

    public String server() {
        return server;
    }

    /**
     * @param known      whether the network has seen them; empty when the database had no answer
     * @param registered whether Velocity knows the welcome server at all
     */
    public Optional<String> firstServer(Optional<Boolean> known, boolean registered) {
        if (!isOn() || !registered || known.isEmpty() || known.get()) {
            return Optional.empty();
        }
        return Optional.of(server);
    }

    /**
     * A newcomer whose very first connection — to the welcome server — failed would otherwise be off the network:
     * they go to the first other server Velocity would try instead.
     *
     * @param kickedFrom the server that let go of them
     * @param hadServer  whether they were on a server before (a later kick, not the first connection)
     * @param tryOrder   Velocity's try list
     */
    public Optional<String> rescue(String kickedFrom, boolean hadServer, List<String> tryOrder) {
        if (!isOn() || hadServer || !server.equalsIgnoreCase(kickedFrom)) {
            return Optional.empty();
        }
        return tryOrder.stream().filter(name -> !name.equalsIgnoreCase(server)).findFirst();
    }
}
