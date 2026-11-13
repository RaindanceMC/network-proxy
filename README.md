# RaindanceMC/network-proxy

- **`network-proxy`** — RainsNetworkProxy: The Velocity side of a RainsCore network: banned players are refused before any server sees them (read from the network's database), and with `whitelist=true` so is anybody not on the network's whitelist (RainsCore's `whitelist` table, which `/whitelist` on every server and Discord's `/link` write) — turned away with a Discord `/link` code written where Discord Link's bot redeems it, while that bot runs, /hub and /lobby from anywhere, players of a closing server wait on the hub, and servers behind another proxy (another data centre) are reached by handing the player over with Minecraft's transfer (`handover.<server>=host[:port]` in config.properties; the other proxy routes by that host name via forced-hosts and needs `accepts-transfers = true`).
- **`geyser-handover`** — RainsGeyserHandover: Geyser extension so Bedrock players follow such a transfer instead of being disconnected — to the same host on Bedrock's port (19132), or as `map.<host>=host:port` in its config.properties says. Turn `forward-hostname` on in that Geyser so the proxy's forced-hosts see the name.

Part of [RaindanceMC](https://github.com/RaindanceMC); built on [RainsCore](https://github.com/RaindanceMC/RainsCore) and the shared parent in [framework](https://github.com/RaindanceMC/framework). Split out of `rains-flexible-modules` on 16.10.2026, history kept.

**Queue for a full server** (1.11.0): `queue.server=smp`, `queue.max=100`, `queue.limbo=<waiting server>` in config.properties. Once the server holds `queue.max` players, newcomers wait on the limbo server with their place on the action bar and are moved in the moment a slot frees; `rains.prime` goes first (`queue.priority-permission`), `rainsnetworkproxy.queue.bypass` never waits (`queue.bypass-permission`). Without a limbo server they are turned away instead. Texts: `queue.message`, `queue.bar`. The backend's own `max-players` must be above `queue.max`. A player parked on the limbo server carries the note `rainswelcome:came-for=queue` (1.11.1), so RainsWelcome ≥ 0.5.0 there skips its newcomer intro.

## Build

```
mvn install
```

Needs Java 25 and read access to `packages.tstieh.de` (server ids `packages-releases` / `packages-snapshots` in `~/.m2/settings.xml`).

## Versions

A module's version is a property in this repository's root `pom.xml`, a plugin's is `plugin.version` in its own pom. CI publishes every version that is not in the registry yet on the next push to master.
