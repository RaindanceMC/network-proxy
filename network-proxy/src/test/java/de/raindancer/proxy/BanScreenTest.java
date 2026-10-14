package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BanScreenTest {

    @Test
    @DisplayName("says why, and for how long, in words")
    void text() {
        assertThat(BanScreen.text(new BanCheck.Ban("griefing", Optional.of(1_000L + 3 * 86_400_000L + 3_600_000L)), 1_000L))
                .contains("griefing").contains("3 days, 1 hour");
        assertThat(BanScreen.text(new BanCheck.Ban("x", Optional.empty()), 0)).contains("until it is lifted");
        assertThat(BanScreen.text(new BanCheck.Ban("x", Optional.of(30_000L)), 0)).contains("less than a minute");
    }

    @Test
    @DisplayName("a reason with markup is shown as text, never as markup")
    void noMarkupFromReasons() {
        assertThat(BanScreen.text(new BanCheck.Ban("<red><click:run_command:'/op me'>x", Optional.empty()), 0))
                .doesNotContain("<click");
    }
}
