package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.Properties;

/**
 * RainsCore's join/kick frame (de.raindancer.core.ui.screen.LeaveScreen) for screens the proxy shows itself. A copy
 * because Velocity can't load Core; keep the two looking the same. Configured by screens.title, screens.footer and
 * screens.gradient in config.properties — set them like the servers' "Join and kick screens" settings.
 */
public record LeaveFrame(String title, String footer, TextColor from, TextColor to) {

    static final String MARK = "✦";
    static final String DIVIDER = "━━━━━━━━━━━━━━";
    /** Core's default preset tag gradient. */
    static final String DEFAULT_GRADIENT = "#C9A0FF,#7C5CBF";

    static LeaveFrame from(Properties config) {
        String[] ends = config.getProperty("screens.gradient", DEFAULT_GRADIENT).split(",");
        String[] fallback = DEFAULT_GRADIENT.split(",");
        TextColor from = colour(ends[0], TextColor.fromHexString(fallback[0]));
        TextColor to = colour(ends.length > 1 ? ends[1] : null, TextColor.fromHexString(fallback[1]));
        return new LeaveFrame(config.getProperty("screens.title", "").strip(), config.getProperty("screens.footer", "").strip(),
                from, to);
    }

    public Component around(Component body) {
        TextComponent.Builder screen = Component.text();
        if (!title.isBlank()) {
            screen.append(header()).append(Component.newline())
                    .append(Component.text(DIVIDER, NamedTextColor.DARK_GRAY))
                    .append(Component.newline()).append(Component.newline());
        }
        screen.append(body);
        if (!footer.isBlank()) {
            screen.append(Component.newline()).append(Component.newline())
                    .append(Component.text(footer, NamedTextColor.DARK_GRAY));
        }
        return screen.build();
    }

    private Component header() {
        String shown = MARK + " " + title.toUpperCase(Locale.ROOT) + " " + MARK;
        TextComponent.Builder header = Component.text().decoration(TextDecoration.BOLD, true);
        int[] points = shown.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            float progress = points.length == 1 ? 0 : (float) i / (points.length - 1);
            header.append(Component.text(Character.toString(points[i]), TextColor.lerp(progress, from, to)));
        }
        return header.build();
    }

    private static TextColor colour(String value, TextColor fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.strip().toLowerCase(Locale.ROOT);
        TextColor hex = TextColor.fromHexString(cleaned);
        if (hex != null) {
            return hex;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(cleaned);
        return named == null ? fallback : named;
    }
}
