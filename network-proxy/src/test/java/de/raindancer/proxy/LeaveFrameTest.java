package de.raindancer.proxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class LeaveFrameTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Properties props(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("the same frame as RainsCore's join/kick screens: name, divider, body, footer")
    void framed() {
        LeaveFrame frame = LeaveFrame.from(props("screens.title", "Lilly's SMP", "screens.footer", "lillyyxoxo.net"));
        assertThat(plain(frame.around(Component.text("Bye"))))
                .isEqualTo("✦ LILLY'S SMP ✦\n━━━━━━━━━━━━━━\n\nBye\n\nlillyyxoxo.net");
    }

    @Test
    @DisplayName("not configured: the body alone, as before")
    void unconfigured() {
        assertThat(plain(LeaveFrame.from(new Properties()).around(Component.text("Bye")))).isEqualTo("Bye");
    }

    @Test
    @DisplayName("a broken gradient setting falls back to Core's default colours instead of failing the proxy")
    void brokenGradient() {
        LeaveFrame frame = LeaveFrame.from(props("screens.title", "X", "screens.gradient", "pink-ish"));
        assertThat(plain(frame.around(Component.text("Bye")))).startsWith("✦ X ✦");
    }

    @Test
    @DisplayName("the ban screen in the frame reads like Core's")
    void banScreen() {
        LeaveFrame frame = LeaveFrame.from(props("screens.title", "Lilly's SMP"));
        Component screen = BanScreen.component(new BanCheck.Ban("griefing", Optional.of(1_000L + 3 * 86_400_000L + 3_600_000L)),
                1_000L, frame);
        assertThat(plain(screen)).isEqualTo(
                "✦ LILLY'S SMP ✦\n━━━━━━━━━━━━━━\n\nYou are banned\n\nReason: griefing\nEnds in 3 days, 1 hour");
    }

    @Test
    @DisplayName("the proxy's /link code screen reads like Discord Link's own, in the same frame")
    void codeScreen() {
        LeaveFrame frame = LeaveFrame.from(props("screens.title", "Lilly's SMP"));
        Component screen = frame.around(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                NetworkProxy.DEFAULT_CODE,
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("code", "ABCDE-FGHJK"),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("minutes", "30")));
        assertThat(plain(screen)).isEqualTo("""
                ✦ LILLY'S SMP ✦
                ━━━━━━━━━━━━━━

                You're not on the whitelist yet

                Type this in Lilly's Discord:
                /link ABCDE-FGHJK

                The bot whitelists you. Then join again.
                The code lasts 30 minutes.""");
    }
}
