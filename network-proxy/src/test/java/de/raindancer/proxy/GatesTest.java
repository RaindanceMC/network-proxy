package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Which servers are closed to all but a list, which hub names /hub knows, and what /tn asks for. */
class GatesTest {

    private static Properties config(String text) throws Exception {
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(text));
        return properties;
    }

    @Test
    @DisplayName("gate.<server>=<list>: several servers can share one list; its permission lets in without being on it")
    void gatesFromConfig() throws Exception {
        Gates gates = Gates.from(config("""
                gate.beta=beta
                gate.BetaHub=beta
                gate.broken=no list!
                list.beta.permission=thundernetwork.beta
                """));
        assertThat(gates.listOf("beta")).contains("beta");
        assertThat(gates.listOf("betahub")).as("server names any case").contains("beta");
        assertThat(gates.listOf("lillyxoxo")).isEmpty();
        assertThat(gates.listOf("broken")).isEmpty();
        assertThat(gates.problems()).singleElement().asString().contains("broken");
        assertThat(gates.lists()).containsExactly("beta");
        assertThat(gates.permission("beta")).isEqualTo("thundernetwork.beta");
        assertThat(gates.permission("other")).as("default").isEqualTo("thundernetwork.list.other");
        assertThat(gates.serversOf("beta")).containsExactlyInAnyOrder("beta", "betahub");
    }

    @Test
    @DisplayName("hubs.<name>=<server>: /hub <name> goes there, plain /hub to the main hub")
    void hubNames() throws Exception {
        Gates gates = Gates.from(config("hubs.beta=betahub\nhubs.Thunder=betahub\n"));
        assertThat(gates.hubs()).isEqualTo(Map.of("beta", "betahub", "thunder", "betahub"));
    }

    @Test
    @DisplayName("/hub <name>: on that hub already its spawn; elsewhere there; an unknown name is said so")
    void hubAnswers() {
        Map<String, String> hubs = Map.of("beta", "betahub");
        assertThat(HubCommand.target(List.of(), "hub", hubs)).contains("hub");
        assertThat(HubCommand.target(List.of("beta"), "hub", hubs)).contains("betahub");
        assertThat(HubCommand.target(List.of("BETA"), "hub", hubs)).contains("betahub");
        assertThat(HubCommand.target(List.of("nope"), "hub", hubs)).isEmpty();
        assertThat(HubCommand.answer(true, "betahub", "betahub", "spawn")).isEqualTo(HubCommand.Answer.TO_SPAWN);
        assertThat(HubCommand.answer(true, "betahub", "hub", "spawn")).as("back from the beta hub")
                .isEqualTo(HubCommand.Answer.GO);
    }

    @Test
    @DisplayName("/tn <list> add|remove <name>, /tn <list> list; anything else is the usage")
    void tnParsing() {
        List<String> lists = List.of("beta");
        assertThat(TnCommand.parse(new String[] {"beta", "add", "Ada"}, lists))
                .isEqualTo(new TnCommand.Action(TnCommand.Verb.ADD, "beta", "Ada"));
        assertThat(TnCommand.parse(new String[] {"BETA", "Remove", "Ada"}, lists))
                .isEqualTo(new TnCommand.Action(TnCommand.Verb.REMOVE, "beta", "Ada"));
        assertThat(TnCommand.parse(new String[] {"beta", "list"}, lists))
                .isEqualTo(new TnCommand.Action(TnCommand.Verb.LIST, "beta", null));
        assertThat(TnCommand.parse(new String[] {"beta", "add"}, lists).verb()).isEqualTo(TnCommand.Verb.USAGE);
        assertThat(TnCommand.parse(new String[] {"alpha", "add", "Ada"}, lists).verb()).isEqualTo(TnCommand.Verb.UNKNOWN_LIST);
        assertThat(TnCommand.parse(new String[] {"beta", "add", "not a name"}, lists).verb()).isEqualTo(TnCommand.Verb.USAGE);
        assertThat(TnCommand.parse(new String[] {}, lists).verb()).isEqualTo(TnCommand.Verb.USAGE);
        assertThat(TnCommand.parse(new String[] {"beta", "add", ".BedrockAda"}, lists).name())
                .as("Floodgate's prefix is part of a Bedrock name").isEqualTo(".BedrockAda");
    }

    @Test
    @DisplayName("tab completion: lists, then verbs, then names that start with what was typed")
    void tnSuggestions() {
        List<String> lists = List.of("beta");
        List<String> online = List.of("Ada", "Bo", "alex");
        assertThat(TnCommand.suggest(new String[] {""}, lists, online)).containsExactly("beta");
        assertThat(TnCommand.suggest(new String[] {"beta", "r"}, lists, online)).containsExactly("remove");
        assertThat(TnCommand.suggest(new String[] {"beta", "add", "a"}, lists, online)).containsExactly("Ada", "alex");
        assertThat(TnCommand.suggest(new String[] {"beta", "list", ""}, lists, online)).isEmpty();
    }
}
