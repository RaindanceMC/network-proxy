package de.raindancer.geyser;

import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Against Geyser's own event: what Geyser reads back after the listeners decides where the Bedrock client goes. */
class GeyserHandoverTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a transfer is answered with the Bedrock address Geyser then sends the client to")
    void setsTheBedrockAddress() {
        ServerTransferEvent event = new ServerTransferEvent(mock(GeyserConnection.class), "arena.lillyyxoxo.net", 25565,
                Map.of());
        GeyserHandover.follow(event, BedrockAddress.from(new Properties()));
        assertThat(event.bedrockHost()).isEqualTo("arena.lillyyxoxo.net");
        assertThat(event.bedrockPort()).isEqualTo(19132);
    }

    @Test
    @DisplayName("follow=false leaves Geyser's event untouched, so Geyser disconnects as it always did")
    void leavesItAlone() {
        ServerTransferEvent event = new ServerTransferEvent(mock(GeyserConnection.class), "arena.lillyyxoxo.net", 25565,
                Map.of());
        Properties off = new Properties();
        off.setProperty("follow", "false");
        GeyserHandover.follow(event, BedrockAddress.from(off));
        assertThat(event.bedrockHost()).isNull();
    }

    @Test
    @DisplayName("the first start writes a config that reads back as the defaults")
    void writesItsConfig() throws Exception {
        Properties config = GeyserHandover.config(folder.resolve("rainsgeyserhandover").resolve("config.properties"));
        assertThat(Files.readString(folder.resolve("rainsgeyserhandover").resolve("config.properties"))).contains("bedrock-port=19132");
        BedrockAddress rules = BedrockAddress.from(config);
        assertThat(rules.problems()).isEmpty();
        assertThat(rules.follows()).isTrue();
        assertThat(rules.defaultPort()).isEqualTo(19132);
    }
}
