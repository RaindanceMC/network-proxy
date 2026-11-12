package de.raindancer.proxy;

import de.raindancer.proxy.LaunchGate.Door;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Creators in a day early, with a countdown of their own until then. */
class EarlyAccessTest {

    private static final long HOUR = 3_600_000L;
    private static final long LAUNCH = ZonedDateTime.of(2026, 11, 27, 16, 0, 0, 0, ZoneId.of("Europe/Berlin"))
            .toInstant().toEpochMilli();

    private static LaunchGate gate(String... pairs) {
        Properties p = new Properties();
        p.setProperty("launch.at", "2026-11-27T16:00");
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return LaunchGate.from(p).orElseThrow();
    }

    @Test
    void creatorsGetInADayEarlyAndEverybodyElseAtTheLaunch() {
        LaunchGate gate = gate();
        long early = LAUNCH - 24 * HOUR;
        assertThat(gate.earlyAt()).isEqualTo(early);
        assertThat(gate.earlyPermission()).isEqualTo("rainsnetworkproxy.launch.early");

        assertThat(gate.door(early - 1, false, true)).isEqualTo(Door.EARLY_COUNTDOWN);
        assertThat(gate.door(early, false, true)).isEqualTo(Door.OPEN);
        assertThat(gate.door(early, false, false)).isEqualTo(Door.COUNTDOWN);
        assertThat(gate.door(LAUNCH - 1, false, false)).isEqualTo(Door.COUNTDOWN);
        assertThat(gate.door(LAUNCH, false, false)).isEqualTo(Door.OPEN);
        // the team is in all along
        assertThat(gate.door(early - 10 * HOUR, true, false)).isEqualTo(Door.OPEN);
    }

    @Test
    void howEarlyIsTheOwnersChoice() {
        assertThat(gate("launch.early-hours", "48").earlyAt()).isEqualTo(LAUNCH - 48 * HOUR);
        LaunchGate none = gate("launch.early-hours", "0");
        assertThat(none.door(LAUNCH - 1, false, true)).isEqualTo(Door.COUNTDOWN);
    }

    @Test
    void theCreatorScreenCountsDownToTheirOwnOpening() {
        LaunchGate gate = gate();
        long now = LAUNCH - 24 * HOUR - 3 * HOUR;
        String text = PlainTextComponentSerializer.plainText().serialize(
                gate.earlyScreen(now, LeaveFrame.from(new Properties())));
        assertThat(text).contains("3h 0m").contains("26.11.2026").contains("16:00").contains("early");
    }

    @Test
    void theCreatorScreenCanBeWorded() {
        LaunchGate gate = gate("launch.early-message", "Creators: <remaining> until <when>");
        String text = PlainTextComponentSerializer.plainText().serialize(
                gate.earlyScreen(LAUNCH - 25 * HOUR, LeaveFrame.from(new Properties())));
        assertThat(text).startsWith("Creators: 1h 0m until 26.11.2026, 16:00");
    }
}
