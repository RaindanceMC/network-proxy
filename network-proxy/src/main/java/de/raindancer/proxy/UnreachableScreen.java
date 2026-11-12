package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.Optional;

/**
 * What somebody sees instead of Velocity's "Unable to connect you to smp. Please try again later." when the server they
 * were going to cannot be reached — nearly always a restart. In the network's frame, with what to do next.
 */
public final class UnreachableScreen {

    private static final TextColor WARM = TextColor.fromHexString("#F6B83D");
    private static final TextColor SKY = TextColor.fromHexString("#6EC1EA");

    private UnreachableScreen() {
    }

    /**
     * @param serverKicked what the server itself said, if it said anything — a real kick keeps its own words
     * @param leavesNetwork whether the player would end up off the network (not switching from another server)
     */
    public static boolean replaces(Optional<Component> serverKicked, boolean leavesNetwork) {
        return serverKicked.isEmpty() && leavesNetwork;
    }

    public static Component component(String server, LeaveFrame frame) {
        Component body = Component.text()
                .append(Component.text(named(server) + " is taking a short break", WARM, TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("It is restarting or briefly down — it is", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("usually back within a minute.", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Press ", NamedTextColor.GRAY))
                .append(Component.text("Reconnect", SKY, TextDecoration.BOLD))
                .append(Component.text(" in a moment.", NamedTextColor.GRAY))
                .build();
        return frame.around(body);
    }

    /** {@code smp} → "The SMP", {@code welcome} → "Welcome". */
    static String named(String server) {
        if (server == null || server.isBlank()) {
            return "The server";
        }
        String name = server.strip();
        if (name.length() <= 3) {
            return "The " + name.toUpperCase(Locale.ROOT);
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
