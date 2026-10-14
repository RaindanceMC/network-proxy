package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkProxyConfigTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("the first start writes the defaults; the environment's RAINS_DB_* win over the file")
    void defaultsAndEnvironment() throws Exception {
        Path file = folder.resolve("config.properties");
        Properties first = NetworkProxy.config(file, Map.of());
        assertThat(file).exists();
        assertThat(first.getProperty("hub")).isEqualTo("hub");
        assertThat(first.getProperty("database.host")).isEmpty();
        assertThat(first.getProperty("database.namespace")).isEqualTo("core");

        Properties overridden = NetworkProxy.config(file, Map.of("RAINS_DB_HOST", "db.internal", "RAINS_DB_PASSWORD", "s3cret",
                "RAINS_DB_PORT", ""));
        assertThat(overridden.getProperty("database.host")).isEqualTo("db.internal");
        assertThat(overridden.getProperty("database.password")).isEqualTo("s3cret");
        assertThat(overridden.getProperty("database.port")).as("blank does not override").isEqualTo("5432");
    }
}
