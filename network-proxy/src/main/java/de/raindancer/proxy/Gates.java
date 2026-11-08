package de.raindancer.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * From config.properties: {@code gate.<server>=<list>} closes a server to all but that list (several servers may
 * share one), {@code list.<list>.permission} lets in without being on it (default {@code thundernetwork.list.<list>}),
 * {@code hubs.<name>=<server>} are the hubs {@code /hub <name>} goes to.
 */
public record Gates(Map<String, String> gates, Map<String, String> permissions, Map<String, String> hubs,
                    List<String> problems) {

    static final Pattern LIST_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    public static Gates from(Properties config) {
        Map<String, String> gates = new LinkedHashMap<>();
        Map<String, String> permissions = new LinkedHashMap<>();
        Map<String, String> hubs = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String key : new TreeSet<>(config.stringPropertyNames())) {
            String value = config.getProperty(key).strip();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("gate.") && lower.length() > 5) {
                String list = value.toLowerCase(Locale.ROOT);
                if (LIST_NAME.matcher(list).matches()) {
                    gates.put(lower.substring(5), list);
                } else {
                    problems.add(key + "=" + value + " (a list name is a-z, 0-9, _ and -)");
                }
            } else if (lower.startsWith("list.") && lower.endsWith(".permission") && !value.isEmpty()) {
                permissions.put(lower.substring(5, lower.length() - ".permission".length()), value);
            } else if (lower.startsWith("hubs.") && lower.length() > 5 && !value.isEmpty()) {
                hubs.put(lower.substring(5), value);
            }
        }
        return new Gates(Map.copyOf(gates), Map.copyOf(permissions), Map.copyOf(hubs), List.copyOf(problems));
    }

    public Optional<String> listOf(String server) {
        return Optional.ofNullable(gates.get(server.toLowerCase(Locale.ROOT)));
    }

    public List<String> lists() {
        return gates.values().stream().distinct().sorted().toList();
    }

    public List<String> serversOf(String list) {
        return gates.entrySet().stream().filter(gate -> gate.getValue().equals(list)).map(Map.Entry::getKey).toList();
    }

    public String permission(String list) {
        return permissions.getOrDefault(list, "thundernetwork.list." + list);
    }

    public boolean isEmpty() {
        return gates.isEmpty();
    }
}
