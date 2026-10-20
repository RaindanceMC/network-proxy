package de.raindancer.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.java.ServerTransferEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Without this, Geyser drops a Bedrock player whom the Java server transfers (Minecraft 1.20.5's transfer packet,
 * e.g. RainsNetworkProxy's handover to a proxy in another data centre). With it they follow, see {@link BedrockAddress}.
 * Geyser registers the extension itself as a listener.
 */
public final class GeyserHandover implements Extension {

    private volatile BedrockAddress addresses = BedrockAddress.from(new Properties());

    @Subscribe
    public void onStart(GeyserPostInitializeEvent event) {
        try {
            addresses = BedrockAddress.from(config(dataFolder().resolve("config.properties")));
        } catch (IOException unreadable) {
            logger().error("config.properties could not be read; Bedrock players follow to the same host on port "
                    + BedrockAddress.BEDROCK_PORT, unreadable);
        }
        addresses.problems().forEach(problem -> logger().warning("Line skipped: " + problem));
        logger().info(addresses.follows()
                ? "Transferred Bedrock players follow: same host on port " + addresses.defaultPort() + ", "
                        + addresses.rules() + " own rule(s)."
                : "follow=false: transferred Bedrock players are disconnected, as Geyser does by itself.");
    }

    static Properties config(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # When the Java server transfers a player to host:port, the Bedrock player goes to the same host
                    # on this port. Turn Geyser's forward-hostname on there, so the proxy's forced-hosts see the name.
                    bedrock-port=19132
                    # Own rules where a host's Bedrock side lives elsewhere (colons in the key escaped):
                    #map.arena.example.net=bedrock.example.net:19133
                    #map.example.net\\:25567=example.net:19132
                    # false = Geyser's own behaviour: a transferred Bedrock player is disconnected.
                    follow=true
                    """);
        }
        Properties config = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            config.load(in);
        }
        return config;
    }

    @Subscribe
    public void onTransfer(ServerTransferEvent event) {
        follow(event, addresses).ifPresent(target -> logger().info(event.connection().bedrockUsername()
                + " follows the transfer to " + event.host() + ":" + event.port() + " as Bedrock " + target.host() + ":"
                + target.port()));
    }

    /** Geyser sends the client to the Bedrock address set here; none set = it disconnects them. */
    static Optional<BedrockAddress.Target> follow(ServerTransferEvent event, BedrockAddress addresses) {
        Optional<BedrockAddress.Target> target = addresses.of(event.host(), event.port());
        target.ifPresent(where -> {
            event.bedrockHost(where.host());
            event.bedrockPort(where.port());
        });
        return target;
    }
}
