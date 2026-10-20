package de.raindancer.geyser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Where a Bedrock player goes when the Java server transfers them to {@code host:port}. By default the same host on
 * Bedrock's port — a network that hands over to {@code arena.example.net:25565} runs Geyser for that proxy on
 * {@code arena.example.net:19132}, and Geyser's forward-hostname passes the name on to the proxy's forced-hosts.
 *
 * <p>config.properties: {@code bedrock-port=19132}, {@code follow=true}, and rules {@code map.<host>=host[:port]}
 * or {@code map.<host>\:<port>=host[:port]} (the colon escaped, it is a properties file); host:port wins over host.
 */
public final class BedrockAddress {

    static final int BEDROCK_PORT = 19132;
    private static final String MAP = "map.";

    public record Target(String host, int port) {
    }

    private final boolean follow;
    private final int defaultPort;
    private final Map<String, Target> rules;
    private final List<String> problems;

    private BedrockAddress(boolean follow, int defaultPort, Map<String, Target> rules, List<String> problems) {
        this.follow = follow;
        this.defaultPort = defaultPort;
        this.rules = rules;
        this.problems = problems;
    }

    public static BedrockAddress from(Properties config) {
        List<String> problems = new ArrayList<>();
        int defaultPort = BEDROCK_PORT;
        String port = config.getProperty("bedrock-port");
        if (port != null) {
            try {
                defaultPort = checkedPort(Integer.parseInt(port.strip()));
            } catch (IllegalArgumentException wrong) {
                problems.add("bedrock-port=" + port + ": not a port, " + BEDROCK_PORT + " is used");
            }
        }
        Map<String, Target> rules = new HashMap<>();
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith(MAP)) {
                continue;
            }
            String value = config.getProperty(key).strip();
            try {
                rules.put(key.substring(MAP.length()).strip().toLowerCase(Locale.ROOT), parse(value, defaultPort));
            } catch (IllegalArgumentException wrong) {
                problems.add(key + "=" + value + ": " + wrong.getMessage());
            }
        }
        boolean follow = !config.getProperty("follow", "true").strip().equalsIgnoreCase("false");
        return new BedrockAddress(follow, defaultPort, Map.copyOf(rules), List.copyOf(problems));
    }

    private static Target parse(String address, int defaultPort) {
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon).strip();
        int port = defaultPort;
        if (colon >= 0) {
            try {
                port = checkedPort(Integer.parseInt(address.substring(colon + 1).strip()));
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("the port is not a number");
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("no host");
        }
        return new Target(host, port);
    }

    private static int checkedPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range");
        }
        return port;
    }

    /** @return where to send the Bedrock client; empty = leave it to Geyser (which disconnects them) */
    public Optional<Target> of(String javaHost, int javaPort) {
        if (!follow || javaHost == null || javaHost.isBlank()) {
            return Optional.empty();
        }
        String host = javaHost.strip().toLowerCase(Locale.ROOT);
        Target rule = rules.get(host + ":" + javaPort);
        if (rule == null) {
            rule = rules.get(host);
        }
        return Optional.of(rule != null ? rule : new Target(javaHost.strip(), defaultPort));
    }

    public List<String> problems() {
        return problems;
    }

    public boolean follows() {
        return follow;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public int rules() {
        return rules.size();
    }
}
