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
 *
 * <p>Early access: whoever has {@code launch.early-permission} (creators) gets in {@code launch.early-hours} before the
 * launch, and until then is shown a countdown of their own, to their opening rather than everybody's.
 */
public record LaunchGate(long at, String bypassPermission, String message, String earlyPermission, long earlyMillis,
                         String earlyMessage) {

    /** What the door does for one player at one moment. */
    public enum Door { OPEN, COUNTDOWN, EARLY_COUNTDOWN }

    static final String DEFAULT_EARLY_PERMISSION = "rainsnetworkproxy.launch.early";
    static final int DEFAULT_EARLY_HOURS = 24;
    static final String DEFAULT_EARLY_MESSAGE = "<white><bold>Your early access is almost here</bold></white>"
            + "<newline><newline><gray>You get in</gray> <gold><bold><remaining></bold></gold> <gray>from now</gray>"
            + "<newline><gray><when></gray><newline><newline>"
            + "<dark_gray>A day before everybody else — get your bearings and your cameras ready!";

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
        String early = config.getProperty("launch.early-permission", "").strip();
        String earlyMessage = config.getProperty("launch.early-message", "").strip();
        long hours;
        try {
            hours = Math.max(0, Long.parseLong(config.getProperty("launch.early-hours", "").strip()));
        } catch (NumberFormatException unset) {
            hours = DEFAULT_EARLY_HOURS;
        }
        return Optional.of(new LaunchGate(at.get(), permission.isEmpty() ? DEFAULT_PERMISSION : permission,
                message.isEmpty() ? DEFAULT_MESSAGE : message.replace("\\n", "<newline>"),
                early.isEmpty() ? DEFAULT_EARLY_PERMISSION : early, hours * 3_600_000L,
                earlyMessage.isEmpty() ? DEFAULT_EARLY_MESSAGE : earlyMessage.replace("\\n", "<newline>")));
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

    /** When those with early access get in. */
    public long earlyAt() {
        return at - earlyMillis;
    }

    /**
     * @param bypass whether they are in all along (the team)
     * @param early  whether they have early access (creators)
     */
    public Door door(long now, boolean bypass, boolean early) {
        if (!closed(now) || bypass) {
            return Door.OPEN;
        }
        if (early && earlyMillis > 0) {
            return now >= earlyAt() ? Door.OPEN : Door.EARLY_COUNTDOWN;
        }
        return Door.COUNTDOWN;
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
        return countdown(message, at, now, frame);
    }

    /** The countdown for those with early access, to their own opening. */
    public Component earlyScreen(long now, LeaveFrame frame) {
        return countdown(earlyMessage, earlyAt(), now, frame);
    }

    private static Component countdown(String text, long until, long now, LeaveFrame frame) {
        String when = WHEN.format(Instant.ofEpochMilli(until).atZone(GERMANY)) + " (German time)";
        return frame.around(MiniMessage.miniMessage().deserialize(text,
                Placeholder.unparsed("remaining", remaining(until - now)), Placeholder.unparsed("when", when)));
    }
}
