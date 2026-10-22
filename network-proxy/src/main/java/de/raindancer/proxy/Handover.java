package de.raindancer.proxy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Servers that live behind another proxy, e.g. in another data centre: a player going there is handed over with
 * Minecraft's transfer (1.20.5+) and connects to that proxy directly, instead of this proxy relaying every packet
 * there and back. Configured as {@code handover.<server>=host[:port]}; the other proxy picks the server by the
 * host name (its forced-hosts), so nothing has to be passed along.
 *
 * <p>Relayed as before, through the address in velocity.toml: the first server of a login (the client cannot be
 * transferred before it is in), clients older than 1.20.5, and Bedrock players with {@code handover.bedrock=false}
 * — Geyser drops a transferred Bedrock player unless RainsGeyserHandover runs on it.
 *
 * <p>{@code handover.stay=host[,host…]}: players who came in by one of these addresses are never handed over, so a
 * second way into the network (a proxy closer to some players) keeps them on it for every server.
 */
public final class Handover {

    static final int MINECRAFT_PORT = 25565;
    private static final String PREFIX = "handover.";
    private static final String BEDROCK = "bedrock";
    private static final String STAY = "stay";

    public record Decision(boolean handOver, String host, int port) {
        static final Decision RELAY = new Decision(false, null, 0);
    }

    record Target(String host, int port) {
    }

    private final Map<String, Target> servers;
    private final boolean bedrock;
    private final Set<String> stay;
    private final List<String> problems;

    private Handover(Map<String, Target> servers, boolean bedrock, Set<String> stay, List<String> problems) {
        this.servers = servers;
        this.bedrock = bedrock;
        this.stay = stay;
        this.problems = problems;
    }

    static Handover from(Properties config) {
        Map<String, Target> servers = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith(PREFIX) || key.equals(PREFIX + BEDROCK) || key.equals(PREFIX + STAY)) {
                continue;
            }
            String server = key.substring(PREFIX.length()).strip().toLowerCase(Locale.ROOT);
            String value = config.getProperty(key).strip();
            try {
                servers.put(server, parse(value));
            } catch (IllegalArgumentException wrong) {
                problems.add(key + "=" + value + ": " + wrong.getMessage());
            }
        }
        boolean bedrock = !config.getProperty(PREFIX + BEDROCK, "true").strip().equalsIgnoreCase("false");
        Set<String> stay = new HashSet<>();
        for (String host : config.getProperty(PREFIX + STAY, "").split(",")) {
            if (!host.isBlank()) {
                stay.add(hostKey(host));
            }
        }
        return new Handover(Map.copyOf(servers), bedrock, Set.copyOf(stay), List.copyOf(problems));
    }

    private static Target parse(String address) {
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon).strip();
        int port = MINECRAFT_PORT;
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1).strip());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("the port is not a number");
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("no host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        return new Target(host, port);
    }

    /**
     * @param server      the server the player is about to join, by its velocity.toml name
     * @param switching   whether they come from another server (false = the first server of the login)
     * @param canTransfer whether their client is 1.20.5 or newer
     * @param bedrockPlayer whether they play through Geyser/Floodgate
     */
    public Decision decide(String server, boolean switching, boolean canTransfer, boolean bedrockPlayer) {
        return decide(server, switching, canTransfer, bedrockPlayer, null);
    }

    /** @param joinedBy the address the player typed to come in, if known — a {@code handover.stay} one keeps them here */
    public Decision decide(String server, boolean switching, boolean canTransfer, boolean bedrockPlayer, String joinedBy) {
        if (joinedBy != null && stay.contains(hostKey(joinedBy))) {
            return Decision.RELAY;
        }
        Target target = server == null ? null : servers.get(server.toLowerCase(Locale.ROOT));
        if (target == null || !switching || !canTransfer || (bedrockPlayer && !bedrock)) {
            return Decision.RELAY;
        }
        return new Decision(true, target.host(), target.port());
    }

    private static String hostKey(String host) {
        String key = host.strip().toLowerCase(Locale.ROOT);
        return key.endsWith(".") ? key.substring(0, key.length() - 1) : key;
    }

    Set<String> stay() {
        return stay;
    }

    /** Floodgate's ids for Bedrock players carry the XUID in the lower half; the upper half is zero. */
    static boolean isBedrock(UUID player) {
        return player.getMostSignificantBits() == 0;
    }

    Map<String, Target> servers() {
        return servers;
    }

    boolean bedrock() {
        return bedrock;
    }

    List<String> problems() {
        return problems;
    }
}
