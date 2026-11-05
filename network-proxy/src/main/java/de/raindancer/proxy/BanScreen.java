package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;

/** What a banned player is shown when the proxy turns them away. */
public final class BanScreen {

    private BanScreen() {
    }

    public static Component component(BanCheck.Ban ban, long now) {
        return component(ban, now, LeaveFrame.from(new java.util.Properties()));
    }

    /** In the network's join/kick frame, worded like RainsCore's own ban screen. */
    public static Component component(BanCheck.Ban ban, long now, LeaveFrame frame) {
        String reason = MiniMessage.miniMessage().stripTags(ban.reason() == null ? "" : ban.reason()).strip();
        var body = Component.text()
                .append(Component.text("You are banned", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline());
        if (!reason.isEmpty()) {
            body.append(Component.text("Reason: ", NamedTextColor.GRAY)).append(Component.text(reason, NamedTextColor.WHITE))
                    .append(Component.newline());
        }
        body.append(ban.endsAt()
                .map(end -> Component.text("Ends in ", NamedTextColor.GRAY)
                        .append(Component.text(duration(end - now), NamedTextColor.WHITE)))
                .orElseGet(() -> Component.text("This ban does not expire.", NamedTextColor.GRAY)));
        return frame.around(body.build());
    }

    /** Plain text; a reason with markup in it is shown without it. */
    public static String text(BanCheck.Ban ban, long now) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component(ban, now));
    }

    static String duration(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 1) {
            return "less than a minute";
        }
        long days = minutes / 1_440;
        long hours = minutes % 1_440 / 60;
        long rest = minutes % 60;
        List<String> parts = new ArrayList<>();
        add(parts, days, "day");
        add(parts, hours, "hour");
        add(parts, rest, "minute");
        return String.join(", ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static void add(List<String> parts, long amount, String unit) {
        if (amount > 0) {
            parts.add(amount + " " + unit + (amount == 1 ? "" : "s"));
        }
    }
}
