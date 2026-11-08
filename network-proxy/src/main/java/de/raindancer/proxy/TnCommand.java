package de.raindancer.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code /tn <list> add|remove|list [name]} (Thunder Network): who may enter the servers behind a gate. Only names
 * already on the network's whitelist can be added. Taken off while on such a server without the list's permission:
 * sent to the hub.
 */
final class TnCommand implements SimpleCommand {

    static final String ADMIN_PERMISSION = "thundernetwork.admin";

    enum Verb { ADD, REMOVE, LIST, USAGE, UNKNOWN_LIST }

    record Action(Verb verb, String list, String name) {
    }

    /** Java names, plus Floodgate's leading dot for Bedrock players. */
    private static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");
    private static final List<String> VERBS = List.of("add", "remove", "list");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger log;
    private final ServerAccess access;
    private final Gates gates;
    private final String hub;

    TnCommand(ProxyServer proxy, Object plugin, Logger log, ServerAccess access, Gates gates, String hub) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.log = log;
        this.access = access;
        this.gates = gates;
        this.hub = hub;
    }

    static Action parse(String[] args, List<String> lists) {
        if (args.length < 2) {
            return new Action(Verb.USAGE, null, null);
        }
        String list = args[0].toLowerCase(Locale.ROOT);
        if (!lists.contains(list)) {
            return new Action(Verb.UNKNOWN_LIST, list, null);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (verb.equals("list") && args.length == 2) {
            return new Action(Verb.LIST, list, null);
        }
        if ((verb.equals("add") || verb.equals("remove")) && args.length == 3 && NAME.matcher(args[2]).matches()) {
            return new Action(verb.equals("add") ? Verb.ADD : Verb.REMOVE, list, args[2]);
        }
        return new Action(Verb.USAGE, list, null);
    }

    static List<String> suggest(String[] args, List<String> lists, List<String> online) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Stream<String> options = switch (args.length) {
            case 0, 1 -> lists.stream();
            case 2 -> VERBS.stream();
            case 3 -> args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove") ? online.stream() : Stream.empty();
            default -> Stream.empty();
        };
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        Action action = parse(invocation.arguments(), gates.lists());
        switch (action.verb()) {
            case USAGE -> {
                source.sendMessage(MM.deserialize("<gray>Usage:</gray> <white>/tn <list> add|remove <name></white> "
                        + "<gray>or</gray> <white>/tn <list> list</white>", Placeholder.unparsed("list",
                        String.join("|", gates.lists()))));
                return;
            }
            case UNKNOWN_LIST -> {
                source.sendMessage(MM.deserialize("<red>No list called <list>.</red> <gray>Known: <known></gray>",
                        Placeholder.unparsed("list", action.list()),
                        Placeholder.unparsed("known", String.join(", ", gates.lists()))));
                return;
            }
            default -> {
            }
        }
        String by = source instanceof Player player ? player.getUsername() : "console";
        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                run(source, action, by);
            } catch (SQLException failed) {
                log.warn("/tn {} {} failed: {}", action.list(), action.verb(), failed.getMessage());
                source.sendMessage(MM.deserialize("<red>The database didn't answer - try again in a moment.</red>"));
            }
        }).schedule();
    }

    private void run(CommandSource source, Action action, String by) throws SQLException {
        String list = action.list();
        switch (action.verb()) {
            case ADD -> {
                Optional<ServerAccess.Known> known = access.whitelisted(action.name());
                if (known.isEmpty()) {
                    source.sendMessage(MM.deserialize("<red><name> isn't on the network's whitelist.</red> "
                            + "<gray>Whitelist them first.</gray>", Placeholder.unparsed("name", action.name())));
                    return;
                }
                ServerAccess.Known player = known.get();
                if (access.add(list, player.player(), player.name(), by)) {
                    log.info("{} put {} ({}) on the {} list.", by, player.name(), player.player(), list);
                    source.sendMessage(MM.deserialize("<green><name> is on the <list> list now.</green>",
                            Placeholder.unparsed("name", player.name()), Placeholder.unparsed("list", list)));
                    proxy.getPlayer(player.player()).ifPresent(online -> online.sendMessage(MM.deserialize(
                            "<light_purple>You're in the <list>!</light_purple> <gray>Try</gray> <white>/hub <list></white>",
                            Placeholder.unparsed("list", list))));
                } else {
                    source.sendMessage(MM.deserialize("<gray><name> is on the <list> list already.</gray>",
                            Placeholder.unparsed("name", player.name()), Placeholder.unparsed("list", list)));
                }
            }
            case REMOVE -> {
                Optional<ServerAccess.Known> known = access.onList(list, action.name());
                if (known.isEmpty() || !access.remove(list, known.get().player())) {
                    source.sendMessage(MM.deserialize("<gray><name> isn't on the <list> list.</gray>",
                            Placeholder.unparsed("name", action.name()), Placeholder.unparsed("list", list)));
                    return;
                }
                ServerAccess.Known player = known.get();
                log.info("{} took {} ({}) off the {} list.", by, player.name(), player.player(), list);
                source.sendMessage(MM.deserialize("<yellow><name> is off the <list> list.</yellow>",
                        Placeholder.unparsed("name", player.name()), Placeholder.unparsed("list", list)));
                proxy.getPlayer(player.player()).ifPresent(online -> sendOff(online, list));
            }
            case LIST -> {
                List<ServerAccess.Entry> entries = access.list(list);
                source.sendMessage(MM.deserialize(entries.isEmpty() ? "<gray>Nobody is on the <list> list.</gray>"
                                : "<gray>On the <list> list (<count>):</gray> <white><names></white>",
                        Placeholder.unparsed("list", list), Placeholder.unparsed("count", String.valueOf(entries.size())),
                        Placeholder.unparsed("names", String.join(", ", entries.stream().map(ServerAccess.Entry::name).toList()))));
            }
            default -> {
            }
        }
    }

    /** Off a server of that list unless their permission still lets them stay. */
    private void sendOff(Player player, String list) {
        if (player.hasPermission(gates.permission(list))) {
            return;
        }
        boolean there = player.getCurrentServer()
                .map(connection -> gates.listOf(connection.getServerInfo().getName()).filter(list::equals).isPresent())
                .orElse(false);
        if (!there) {
            return;
        }
        Optional<RegisteredServer> target = proxy.getServer(hub);
        player.sendMessage(MM.deserialize("<gray>You're no longer in the <list> - back to the hub.</gray>",
                Placeholder.unparsed("list", list)));
        target.ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return suggest(invocation.arguments(), gates.lists(),
                proxy.getAllPlayers().stream().map(Player::getUsername).sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof ConsoleCommandSource || invocation.source().hasPermission(ADMIN_PERMISSION);
    }
}
