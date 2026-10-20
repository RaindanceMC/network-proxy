package de.raindancer.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubCommandTest {

    @Test
    void aPlayerElsewhereGoesToTheHub() {
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, "lillyxoxo", "hub", "spawn"));
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, "arena", "hub", "spawn"));
    }

    @Test
    void onTheHubTheHubItselfBringsThemToItsSpawn() {
        // a parkour course on the hub is a world of its own: "already there" left them stuck on it
        assertEquals(HubCommand.Answer.TO_SPAWN, HubCommand.answer(true, "hub", "hub", "spawn"));
        assertEquals(HubCommand.Answer.TO_SPAWN, HubCommand.answer(true, "HUB", "hub", "spawn"));
    }

    @Test
    void withoutASpawnCommandTheHubOnlySaysTheyAreThere() {
        assertEquals(HubCommand.Answer.ALREADY_THERE, HubCommand.answer(true, "hub", "hub", ""));
    }

    @Test
    void theSpawnCommandIsSentWithItsSlash() {
        assertEquals("/spawn", HubCommand.chatInput("spawn"));
        assertEquals("/spawn", HubCommand.chatInput(" /spawn "));
        assertEquals("", HubCommand.chatInput(null));
        assertEquals("", HubCommand.chatInput("  "));
    }

    @Test
    void stillConnectingIsSentAsWell() {
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, null, "hub", "spawn"));
    }

    @Test
    void theConsoleIsNoPlayer() {
        assertEquals(HubCommand.Answer.PLAYERS_ONLY, HubCommand.answer(false, null, "hub", "spawn"));
    }

    @Test
    void theNamesComeFromTheConfigSeparatedByBars() {
        assertEquals(List.of("hub", "lobby", "l"), HubCommand.names(" hub | lobby|L "));
        assertEquals(List.of("hub", "lobby"), HubCommand.names(null));
        assertEquals(List.of(), HubCommand.names("  "), "blank = no command at all");
    }
}
