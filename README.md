# RaindanceMC/network-proxy

- **`network-proxy`** — RainsNetworkProxy: The Velocity side of a RainsCore network: banned players are refused before any server sees them (read from the network's database), /hub and /lobby from anywhere, players of a closing server wait on the hub, and servers behind another proxy (another data centre) are reached by handing the player over with Minecraft's transfer (`handover.<server>=host[:port]` in config.properties; the other proxy routes by that host name via forced-hosts and needs `accepts-transfers = true`).
- **`geyser-handover`** — RainsGeyserHandover: Geyser extension so Bedrock players follow such a transfer instead of being disconnected — to the same host on Bedrock's port (19132), or as `map.<host>=host:port` in its config.properties says. Turn `forward-hostname` on in that Geyser so the proxy's forced-hosts see the name.

Part of [RaindanceMC](https://github.com/RaindanceMC); built on [RainsCore](https://github.com/RaindanceMC/RainsCore) and the shared parent in [framework](https://github.com/RaindanceMC/framework). Split out of `rains-flexible-modules` on 16.10.2026, history kept.

## Build

```
mvn install
```

Needs Java 25 and read access to `packages.tstieh.de` (server ids `packages-releases` / `packages-snapshots` in `~/.m2/settings.xml`).

## Versions

A module's version is a property in this repository's root `pom.xml`, a plugin's is `plugin.version` in its own pom. CI publishes every version that is not in the registry yet on the next push to master.
