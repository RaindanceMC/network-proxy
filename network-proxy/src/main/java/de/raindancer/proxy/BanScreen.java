package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;

/** What a banned player is shown when the proxy turns them away. */
public final class BanScreen {

    private BanScreen() {
    }

    public static Component component(BanCheck.Ban ban, long now) {
        return Component.text(text(ban, now), NamedTextColor.RED);
    }

    /** Plain text; a reason with markup in it is shown without it. */
    public static String text(BanCheck.Ban ban, long now) {
        String reason = MiniMessage.miniMessage().stripTags(ban.reason() == null ? "" : ban.reason()).strip();
        String lasts = ban.endsAt().map(end -> "Ends in " + duration(end - now) + ".")
                .orElse("This ban lasts until it is lifted.");
        return "You are banned from this network.\n\n" + (reason.isEmpty() ? "" : "Reason: " + reason + "\n") + lasts;
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
