package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;

/**
 * Before a network opens: everybody without the bypass permission is turned away at the door with a countdown.
 * {@code launch.at} is German time unless it carries an offset; from that moment on the gate is simply open.
 */
public record LaunchGate(long at, String bypassPermission, String message) {

    static final ZoneId GERMANY = ZoneId.of("Europe/Berlin");
    static final String DEFAULT_PERMISSION = "rainsnetworkproxy.launch.bypass";
    static final String DEFAULT_MESSAGE = "<white><bold>We're not open yet</bold></white><newline><newline>"
            + "<gray>Opening in</gray> <gold><bold><remaining></bold></gold><newline>"
            + "<gray><when></gray><newline><newline>"
            + "<dark_gray>See you there!";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

    static Optional<LaunchGate> from(Properties config) {
        String raw = config.getProperty("launch.at", "").strip();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> at = parse(raw);
        if (at.isEmpty()) {
            return Optional.empty();
        }
        String permission = config.getProperty("launch.bypass-permission", "").strip();
        String message = config.getProperty("launch.message", "").strip();
        return Optional.of(new LaunchGate(at.get(), permission.isEmpty() ? DEFAULT_PERMISSION : permission,
                message.isEmpty() ? DEFAULT_MESSAGE : message.replace("\\n", "<newline>")));
    }

    static Optional<Long> parse(String raw) {
        try {
            return Optional.of(OffsetDateTime.parse(raw).toInstant().toEpochMilli());
        } catch (DateTimeParseException noOffset) {
            try {
                return Optional.of(LocalDateTime.parse(raw).atZone(GERMANY).toInstant().toEpochMilli());
            } catch (DateTimeParseException notATime) {
                return Optional.empty();
            }
        }
    }

    public boolean closed(long now) {
        return now < at;
    }

    /** "18d 3h 12m", "2h 1m", "1m" — rounded up, so it never says 0 while still closed. */
    static String remaining(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + mins + "m";
        }
        return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
    }

    public Component screen(long now, LeaveFrame frame) {
        String when = WHEN.format(Instant.ofEpochMilli(at).atZone(GERMANY)) + " (German time)";
        return frame.around(MiniMessage.miniMessage().deserialize(message,
                Placeholder.unparsed("remaining", remaining(at - now)), Placeholder.unparsed("when", when)));
    }
}
