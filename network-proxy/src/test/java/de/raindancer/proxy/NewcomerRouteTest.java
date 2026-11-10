package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("newcomers: their first server is the welcome server, everybody else's what Velocity picks")
class NewcomerRouteTest {

    private static NewcomerRoute route(String server) {
        Properties config = new Properties();
        if (server != null) {
            config.setProperty("newcomers.server", server);
        }
        return NewcomerRoute.from(config);
    }

    @Test
    @DisplayName("off unless newcomers.server names a server")
    void off() {
        assertThat(route(null).isOn()).isFalse();
        assertThat(route("  ").isOn()).isFalse();
        assertThat(route("welcome").isOn()).isTrue();
        assertThat(route(" Welcome ").server()).isEqualTo("welcome");
    }

    @Test
    @DisplayName("a newcomer goes to the welcome server")
    void newcomer() {
        assertThat(route("welcome").firstServer(Optional.of(false), true)).contains("welcome");
    }

    @Test
    @DisplayName("somebody known, a database without an answer, or no such server: Velocity's own choice stands")
    void others() {
        assertThat(route("welcome").firstServer(Optional.of(true), true)).isEmpty();
        assertThat(route("welcome").firstServer(Optional.empty(), true)).isEmpty();
        assertThat(route("welcome").firstServer(Optional.of(false), false)).isEmpty();
        assertThat(route(null).firstServer(Optional.of(false), true)).isEmpty();
    }

    @Test
    @DisplayName("the welcome server unreachable at the very first connect: on to the first other server Velocity "
            + "would try, never off the network")
    void rescue() {
        NewcomerRoute welcome = route("welcome");
        assertThat(welcome.rescue("welcome", false, List.of("welcome", "smp"))).contains("smp");
        assertThat(welcome.rescue("Welcome", false, List.of("smp", "hub"))).contains("smp");
    }

    @Test
    @DisplayName("a kick from the welcome server later on, or from any other server: not this rule's business")
    void noRescue() {
        NewcomerRoute welcome = route("welcome");
        assertThat(welcome.rescue("welcome", true, List.of("smp"))).isEmpty();
        assertThat(welcome.rescue("smp", false, List.of("smp"))).isEmpty();
        assertThat(welcome.rescue("welcome", false, List.of("welcome"))).isEmpty();
        assertThat(route(null).rescue("welcome", false, List.of("smp"))).isEmpty();
    }
}
