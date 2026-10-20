package de.raindancer.geyser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockAddressTest {

    private static BedrockAddress rules(String... lines) {
        Properties config = new Properties();
        for (String line : lines) {
            String[] pair = line.split("=", 2);
            config.setProperty(pair[0], pair[1]);
        }
        return BedrockAddress.from(config);
    }

    @Test
    @DisplayName("by default a Bedrock player follows to the same host, on Bedrock's port")
    void sameHostBedrockPort() {
        BedrockAddress.Target target = rules().of("arena.lillyyxoxo.net", 25565).orElseThrow();
        assertThat(target.host()).isEqualTo("arena.lillyyxoxo.net");
        assertThat(target.port()).isEqualTo(19132);
    }

    @Test
    @DisplayName("the default Bedrock port can be changed")
    void otherDefaultPort() {
        assertThat(rules("bedrock-port=19133").of("arena.lillyyxoxo.net", 25565).orElseThrow().port()).isEqualTo(19133);
    }

    @Test
    @DisplayName("an exact host:port rule wins over a host rule, and both over the default")
    void exactRulesWin() {
        BedrockAddress mapped = rules(
                "map.lillyyxoxo.net:25567=lillyyxoxo.net:19132",
                "map.hub.lillyyxoxo.net=bedrock-hub.lillyyxoxo.net",
                "map.lillyyxoxo.net=wrong.example:1");
        assertThat(mapped.of("lillyyxoxo.net", 25567).orElseThrow())
                .isEqualTo(new BedrockAddress.Target("lillyyxoxo.net", 19132));
        assertThat(mapped.of("lillyyxoxo.net", 25565).orElseThrow())
                .isEqualTo(new BedrockAddress.Target("wrong.example", 1));
        assertThat(mapped.of("HUB.lillyyxoxo.net", 25567).orElseThrow())
                .isEqualTo(new BedrockAddress.Target("bedrock-hub.lillyyxoxo.net", 19132));
    }

    @Test
    @DisplayName("in the file a host:port rule escapes its colon, as properties files want")
    void fromAFile() throws Exception {
        Properties config = new Properties();
        config.load(new java.io.StringReader("map.lillyyxoxo.net\\:25567=lillyyxoxo.net:19132\n"));
        assertThat(BedrockAddress.from(config).of("lillyyxoxo.net", 25567).orElseThrow())
                .isEqualTo(new BedrockAddress.Target("lillyyxoxo.net", 19132));
    }

    @Test
    @DisplayName("follow=false: Geyser's own behaviour (the player is disconnected)")
    void canBeSwitchedOff() {
        assertThat(rules("follow=false").of("arena.lillyyxoxo.net", 25565)).isEmpty();
    }

    @Test
    @DisplayName("a transfer to nowhere is not followed")
    void blankHost() {
        assertThat(rules().of("", 25565)).isEmpty();
        assertThat(rules().of(null, 25565)).isEmpty();
    }

    @Test
    @DisplayName("broken lines are reported and skipped, the default still applies")
    void brokenLines() {
        BedrockAddress mapped = rules("map.arena.lillyyxoxo.net=arena.lillyyxoxo.net:nope", "bedrock-port=abc");
        assertThat(mapped.problems()).hasSize(2);
        assertThat(mapped.of("arena.lillyyxoxo.net", 25565).orElseThrow())
                .isEqualTo(new BedrockAddress.Target("arena.lillyyxoxo.net", 19132));
    }
}
