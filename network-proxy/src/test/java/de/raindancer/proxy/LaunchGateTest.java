package de.raindancer.proxy;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LaunchGateTest {

    private static final long LAUNCH = ZonedDateTime.of(2026, 11, 27, 16, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli();

    private static Properties config(String at) {
        Properties p = new Properties();
        if (at != null) p.setProperty("launch.at", at);
        return p;
    }

    @Test
    @DisplayName("without launch.at there is no gate")
    void off() {
        assertThat(LaunchGate.from(config(null))).isEmpty();
        assertThat(LaunchGate.from(config("  "))).isEmpty();
    }

    @Test
    @DisplayName("a wall-clock time is German time; an ISO time with offset is taken as it is")
    void parses() {
        assertThat(LaunchGate.from(config("2026-11-27T16:00")).orElseThrow().at()).isEqualTo(LAUNCH);
        assertThat(LaunchGate.from(config("2026-11-27T15:00Z")).orElseThrow().at()).isEqualTo(LAUNCH);
        assertThat(LaunchGate.from(config("next friday"))).isEmpty();
    }

    @Test
    @DisplayName("closed until the moment, then open; the default bypass is the MC team's permission")
    void closedUntilLaunch() {
        LaunchGate gate = LaunchGate.from(config("2026-11-27T16:00")).orElseThrow();
        assertThat(gate.closed(LAUNCH - 1)).isTrue();
        assertThat(gate.closed(LAUNCH)).isFalse();
        assertThat(gate.bypassPermission()).isEqualTo("rainsnetworkproxy.launch.bypass");
    }

    @Test
    @DisplayName("the screen counts down in words and says when, in German time")
    void screen() {
        LaunchGate gate = LaunchGate.from(config("2026-11-27T16:00")).orElseThrow();
        String text = PlainTextComponentSerializer.plainText().serialize(
                gate.screen(LAUNCH - (18 * 86_400_000L + 3 * 3_600_000L + 12 * 60_000L + 5_000L), LeaveFrame.from(new Properties())));
        assertThat(text).contains("18d 3h 13m").contains("27.11.2026").contains("16:00");
        assertThat(LaunchGate.remaining(30_000)).isEqualTo("1m");
        assertThat(LaunchGate.remaining(2 * 3_600_000L + 60_000)).isEqualTo("2h 1m");
    }
}
