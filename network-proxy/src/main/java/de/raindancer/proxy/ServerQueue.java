package de.raindancer.proxy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The line in front of a full server: whoever arrives when it holds {@code queue.max} players waits on
 * {@code queue.limbo} and is moved in as soon as a slot frees. Prime members ({@code rains.prime}) go first, then
 * everybody in the order they came; staff ({@code rainsnetworkproxy.queue.bypass}) never wait.
 */
public final class ServerQueue {

    public record Settings(String server, int max, String limbo, String priorityPermission, String bypassPermission) {

        public static final String DEFAULT_PRIORITY = "rains.prime";
        public static final String DEFAULT_BYPASS = "rainsnetworkproxy.queue.bypass";

        public static Optional<Settings> from(Properties config) {
            String server = config.getProperty("queue.server", "").strip().toLowerCase(Locale.ROOT);
            int max;
            try {
                max = Integer.parseInt(config.getProperty("queue.max", "0").strip());
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
            if (server.isEmpty() || max <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Settings(server, max, config.getProperty("queue.limbo", "").strip(),
                    config.getProperty("queue.priority-permission", DEFAULT_PRIORITY).strip(),
                    config.getProperty("queue.bypass-permission", DEFAULT_BYPASS).strip()));
        }
    }

    private record Place(boolean priority, long since) {
    }

    private final Map<UUID, Place> waiting = new ConcurrentHashMap<>();

    /**
     * Whether a player may go in now.
     *
     * @param moving  they are the one the queue is moving in
     * @param nobodyWaits nobody is in the line
     */
    public static boolean admit(int online, int max, boolean bypass, boolean moving, boolean nobodyWaits) {
        if (bypass) {
            return true;
        }
        return online < max && (moving || nobodyWaits);
    }

    /** Into the line, or kept where they are; gaining priority moves them up. */
    public synchronized void join(UUID player, boolean priority, long now) {
        Place before = waiting.get(player);
        if (before == null) {
            waiting.put(player, new Place(priority, now));
        } else if (priority && !before.priority()) {
            waiting.put(player, new Place(true, before.since()));
        }
    }

    public synchronized void leave(UUID player) {
        waiting.remove(player);
    }

    /** 1 for the front of the line, 0 for not in it. */
    public synchronized int position(UUID player) {
        Place mine = waiting.get(player);
        if (mine == null) {
            return 0;
        }
        int ahead = 0;
        for (Place other : waiting.values()) {
            if (before(other, mine)) {
                ahead++;
            }
        }
        return ahead + 1;
    }

    public synchronized Optional<UUID> next() {
        Optional<Map.Entry<UUID, Place>> first = waiting.entrySet().stream()
                .min((a, b) -> before(a.getValue(), b.getValue()) ? -1 : before(b.getValue(), a.getValue()) ? 1 : 0);
        first.ifPresent(entry -> waiting.remove(entry.getKey()));
        return first.map(Map.Entry::getKey);
    }

    public int size() {
        return waiting.size();
    }

    public boolean isEmpty() {
        return waiting.isEmpty();
    }

    public java.util.Set<UUID> waiting() {
        return java.util.Set.copyOf(waiting.keySet());
    }

    private static boolean before(Place a, Place b) {
        if (a.priority() != b.priority()) {
            return a.priority();
        }
        return a.since() < b.since();
    }
}
