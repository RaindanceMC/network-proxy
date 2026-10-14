package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KickRouteTest {

    private final KickRoute route = new KickRoute("hub", List.of("Server closed", "Server is restarting"));

    @Test
    @DisplayName("any server closing sends its players to the hub to wait")
    void closingGoesToHub() {
        assertThat(route.of("smp", false, "Server closed")).isEqualTo(KickRoute.Route.HUB);
        assertThat(route.of("arena", false, "  server   IS restarting ")).isEqualTo(KickRoute.Route.HUB);
        assertThat(route.of("smp", false, null)).as("a crash sends no reason").isEqualTo(KickRoute.Route.HUB);
    }

    @Test
    @DisplayName("a kick or a ban stays a kick; the hub itself closing leaves them to Velocity")
    void otherwise() {
        assertThat(route.of("smp", false, "You were kicked: spam")).isEqualTo(KickRoute.Route.DISCONNECT);
        assertThat(route.of("smp", false, "")).isEqualTo(KickRoute.Route.DISCONNECT);
        assertThat(route.of("hub", false, "Server closed")).isEqualTo(KickRoute.Route.LEAVE);
        assertThat(route.of("smp", true, "Server closed")).isEqualTo(KickRoute.Route.LEAVE);
    }
}
