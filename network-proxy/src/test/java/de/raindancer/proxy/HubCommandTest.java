package de.raindancer.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HubCommandTest {

    @Test
    void aPlayerElsewhereGoesToTheHub() {
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, "lillyxoxo", "hub"));
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, "arena", "hub"));
    }

    @Test
    void onTheHubAlreadyThereIsNowhereToGo() {
        assertEquals(HubCommand.Answer.ALREADY_THERE, HubCommand.answer(true, "hub", "hub"));
        assertEquals(HubCommand.Answer.ALREADY_THERE, HubCommand.answer(true, "HUB", "hub"));
    }

    @Test
    void stillConnectingIsSentAsWell() {
        assertEquals(HubCommand.Answer.GO, HubCommand.answer(true, null, "hub"));
    }

    @Test
    void theConsoleIsNoPlayer() {
        assertEquals(HubCommand.Answer.PLAYERS_ONLY, HubCommand.answer(false, null, "hub"));
    }

    @Test
    void theNamesComeFromTheConfigSeparatedByBars() {
        assertEquals(List.of("hub", "lobby", "l"), HubCommand.names(" hub | lobby|L "));
        assertEquals(List.of("hub", "lobby"), HubCommand.names(null));
        assertEquals(List.of(), HubCommand.names("  "), "blank = no command at all");
    }
}
