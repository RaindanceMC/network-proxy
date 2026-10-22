package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandoverTest {

    private static Handover handover(String... lines) {
        Properties config = new Properties();
        for (String line : lines) {
            String[] pair = line.split("=", 2);
            config.setProperty(pair[0], pair[1]);
        }
        return Handover.from(config);
    }

    private final Handover lilly = handover(
            "handover.arena=arena.lillyyxoxo.net:25565",
            "handover.CTF=ctf.lillyyxoxo.net");

    @Test
    @DisplayName("a server behind another proxy is handed over to that proxy's address")
    void handsOverToTheOtherProxy() {
        Handover.Decision decision = lilly.decide("arena", true, true, false);
        assertThat(decision.handOver()).isTrue();
        assertThat(decision.host()).isEqualTo("arena.lillyyxoxo.net");
        assertThat(decision.port()).isEqualTo(25565);
    }

    @Test
    @DisplayName("names match without case, and a missing port is Minecraft's default")
    void defaultPortAndCase() {
        Handover.Decision decision = lilly.decide("ctf", true, true, false);
        assertThat(decision.handOver()).isTrue();
        assertThat(decision.host()).isEqualTo("ctf.lillyyxoxo.net");
        assertThat(decision.port()).isEqualTo(25565);
    }

    @Test
    @DisplayName("servers on this proxy are relayed as always")
    void localServersAreRelayed() {
        assertThat(lilly.decide("hub", true, true, false).handOver()).isFalse();
    }

    @Test
    @DisplayName("the first server of a login is relayed: the client can only be transferred once it is in")
    void firstServerIsRelayed() {
        assertThat(lilly.decide("arena", false, true, false).handOver()).isFalse();
    }

    @Test
    @DisplayName("clients older than 1.20.5 know no transfer and are relayed")
    void oldClientsAreRelayed() {
        assertThat(lilly.decide("arena", true, false, false).handOver()).isFalse();
    }

    @Test
    @DisplayName("Bedrock players follow by default (their Geyser needs RainsGeyserHandover)")
    void bedrockFollowsByDefault() {
        assertThat(lilly.decide("arena", true, true, true).handOver()).isTrue();
    }

    @Test
    @DisplayName("handover.bedrock=false keeps Bedrock players relayed, for a Geyser without the extension")
    void bedrockCanBeKeptRelayed() {
        Handover javaOnly = handover("handover.arena=arena.lillyyxoxo.net", "handover.bedrock=false");
        assertThat(javaOnly.decide("arena", true, true, true).handOver()).isFalse();
        assertThat(javaOnly.decide("arena", true, true, false).handOver()).isTrue();
        assertThat(javaOnly.servers()).containsOnlyKeys("arena");
    }

    @Test
    @DisplayName("broken entries are skipped and reported, the rest still works")
    void brokenEntries() {
        Handover mixed = handover("handover.arena=arena.lillyyxoxo.net:abc", "handover.ctf=:25565",
                "handover.oneblock=oneblock.lillyyxoxo.net:25566", "handover.hub=hub.example:70000");
        assertThat(mixed.servers()).containsOnlyKeys("oneblock");
        assertThat(mixed.problems()).hasSize(3).anySatisfy(problem -> assertThat(problem).contains("arena"));
    }

    @Test
    @DisplayName("no handover lines: nothing is handed over")
    void none() {
        Handover empty = handover("hub=hub");
        assertThat(empty.servers()).isEmpty();
        assertThat(empty.decide("arena", true, true, false).handOver()).isFalse();
    }

    @Test
    @DisplayName("Floodgate gives Bedrock players ids whose upper half is zero")
    void bedrockIds() {
        assertThat(Handover.isBedrock(UUID.fromString("00000000-0000-0000-0009-01f51b030beb"))).isTrue();
        assertThat(Handover.isBedrock(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"))).isFalse();
    }

    @Test
    @DisplayName("handover.stay: who came in by one of those addresses is never handed over, the rest are")
    void stayHosts() {
        Handover fra = handover("handover.lillyxoxo=survival.lillyyxoxo.net:25567",
                "handover.stay=proxy-2.lillyyxoxo.net, Proxy-2.lillyyxoxo.de");
        assertThat(fra.decide("lillyxoxo", true, true, false, "proxy-2.lillyyxoxo.net").handOver()).isFalse();
        assertThat(fra.decide("lillyxoxo", true, true, false, "PROXY-2.lillyyxoxo.de.").handOver())
                .as("case and a trailing dot do not matter").isFalse();
        assertThat(fra.decide("lillyxoxo", true, true, false, "arena.lillyyxoxo.net").handOver()).isTrue();
        assertThat(fra.decide("lillyxoxo", true, true, false, null).handOver()).isTrue();
        assertThat(fra.servers()).containsOnlyKeys("lillyxoxo");
    }
}
