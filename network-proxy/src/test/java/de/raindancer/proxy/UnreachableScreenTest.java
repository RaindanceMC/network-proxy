package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class UnreachableScreenTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static LeaveFrame hyades() {
        Properties config = new Properties();
        config.setProperty("screens.title", "Hyades");
        config.setProperty("screens.gradient", "#6EC1EA,#F6B83D");
        return LeaveFrame.from(config);
    }

    @Test
    void saysWhatIsGoingOnInTheNetworksFrame() {
        String text = plain(UnreachableScreen.component("smp", hyades()));
        assertThat(text).startsWith("✦ HYADES ✦\n━━━━━━━━━━━━━━\n\n");
        assertThat(text).contains("The SMP is taking a short break");
        assertThat(text).contains("Reconnect");
        assertThat(text).doesNotContain("Unable to connect");
    }

    @Test
    void namesTheServerReadably() {
        assertThat(UnreachableScreen.named("smp")).isEqualTo("The SMP");
        assertThat(UnreachableScreen.named("welcome")).isEqualTo("Welcome");
        assertThat(UnreachableScreen.named("")).isEqualTo("The server");
    }

    @Test
    void onlyAFailedConnectIsReplaced() {
        assertThat(UnreachableScreen.replaces(Optional.empty(), true)).isTrue();
        // a real kick from the server keeps its own words
        assertThat(UnreachableScreen.replaces(Optional.of(Component.text("Banned")), true)).isFalse();
        // somebody switching servers stays where they are and is told in chat, as Velocity does
        assertThat(UnreachableScreen.replaces(Optional.empty(), false)).isFalse();
    }
}
