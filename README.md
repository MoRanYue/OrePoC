# Ore PoC — Seed-Based Client-Side Ore Rendering Override

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-blue)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-orange)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**Ore PoC** is a Fabric client-side mod for Minecraft 26.1.2 that overrides client-side ore rendering using seed-based local world generation. It allows players to see real ore distributions even on servers with anti-xray protection, by locally generating ore positions from a known seed and replacing server-sent ore blocks accordingly.

This project consists of **two components**:

| Component | Description | Stack |
|-----------|-------------|-------|
| [`orepoc`](src) (Fabric Mod) | Client-side mod handling local ore generation and render override | Java 25, Fabric Loom |
| [`orepoc-server`](orepoc-server) (Paper Plugin) | Server-side plugin providing 100% accurate ore positions via HTTP API | Java 25, Paper API |

---

## Features

- **Three operation modes**: [`LOCAL`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:42) (approximate local generation), [`REMOTE`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:44) (accurate via Paper plugin), [`NONE`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:45) (disabled)
- **Anti-Xray compatible**: Ores hidden as air by the server are never revealed (see [`OreRenderOverride.getOverride()`](src/main/java/io/github/moranyue/orepoc/render/OreRenderOverride.java:33) line 53)
- **Local generation**: Uses vanilla [`Feature.ORE`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:625) to independently generate ore on the client — no server cooperation required
- **Remote mode**: Fetches 100% accurate ore coordinates from a local Paper server via HTTP API ([`RemoteGenerator`](src/main/java/io/github/moranyue/orepoc/generator/RemoteGenerator.java))
- **Seed persistence**: Seed is saved to `orepoc/seed.txt` in the game directory ([`OrePocConfig`](src/main/java/io/github/moranyue/orepoc/config/OrePocConfig.java))
- **Mode switching**: Automatically restores original server block states when toggling between `LOCAL`/`REMOTE`/`NONE`
- **LRU caching**: Up to 64 chunks cached with async multi-threaded generation, never blocking the render thread

---

## How It Works

### Core Algorithm

For each rendered block position `(x, y, z)`:

```
1. Query LocalWorldGenerator for the predicted ore at (x, y, z)
2. If no ore is predicted → render the server's original block
3. If ore IS predicted but server shows AIR → render AIR (don't reveal hidden ores)
4. If ore IS predicted and server shows a non-air block → render the predicted ore
```

See [`OreRenderOverride.getOverride()`](src/main/java/io/github/moranyue/orepoc/render/OreRenderOverride.java:33) for implementation details.

### Mode Details

| Mode | Description | Use Case |
|------|-------------|----------|
| `LOCAL` | **Buggy!** Generates ore locally on the client using vanilla [`Feature.ORE`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:625) with the configured seed | Single-player testing, anti-xray servers |
| `REMOTE` | Fetches 100% accurate ore data from a local Paper server running [`orepoc-server`](orepoc-server) via HTTP API | Environments needing exact ore positions |
| `NONE` | Disables all ore override, renders original server blocks | Debugging, performance testing |

### Local Generation Flow

1. After setting a seed, [`LocalWorldGenerator.triggerGenerationForCurrentPosition()`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:121) is invoked
2. A `3×3` chunk area around the player is generated using [`XoroshiroRandomSource`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:580) to replicate vanilla ore random logic
3. [`OreGenLevel`](src/main/java/io/github/moranyue/orepoc/generator/OreGenLevel.java) (implementing `WorldGenLevel`) serves as the simulated world container
4. [`Feature.ORE.place()`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:625) is called to place ores into the simulated world
5. Ore positions are read from the simulated world, cached, and then applied to the real client world

### Remote Fetch Flow

1. Client sends HTTP POST requests to [`OrePocServer`](orepoc-server/src/main/java/io/github/moranyue/orepocserver/websocket/OreApiHandler.java) for chunk ore data
2. The server plugin uses the Bukkit API to scan already-generated chunks and returns accurate ore coordinates ([`OreScanner`](orepoc-server/src/main/java/io/github/moranyue/orepocserver/generation/OreScanner.java))
3. Client caches the results and applies them to world rendering

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/orepoc about` | None | Show mod info, current mode, seed fingerprint, and server connection status |
| `/orepoc set_seed <seed>` | None (client) | Set the world seed and trigger local generation |
| `/orepoc set_server <host> [port]` | None (client) | Set the remote Paper server address (default port: 32567) |
| `/orepoc set_mode local` | None (client) | Switch to local generation mode (it's buggy so do not use!) |
| `/orepoc set_mode remote` | None (client) | Switch to remote fetch mode |
| `/orepoc set_mode none` | None (client) | Disable ore override |

---

## Building

### Prerequisites

- **Java 25** or later
- **Git**

### Build the Fabric Mod

```bash
# Build the main mod
./gradlew build
# Output: build/libs/ore-poc-<version>.jar

# Build with sources
./gradlew build sourcesJar
# Output: build/libs/ore-poc-<version>-sources.jar
```

### Build the Paper Plugin

```bash
cd orepoc-server
./gradlew jar
# Output: build/libs/orepoc-server-<version>.jar
```

---

## Installation

### Client (Fabric Mod)

1. Ensure [Fabric Loader](https://fabricmc.net/) ≥0.19.2 is installed
2. Place the built JAR into `minecraft/mods/`
3. Launch the game and use `/orepoc set_seed <seed>` to configure the world seed

### Server (Paper Plugin)

1. Server must be running [Paper](https://papermc.io/).
2. Place the built `orepoc-server-<version>.jar` into the `plugins/` folder
3. Delete `world` directory if it exists, then change `level-seed` field in `server.properties` to create a new world.
4. Start the server; the plugin starts an HTTP API on port `32567`
4. On the client, use `/orepoc set_server <server-ip> 32567` to connect
5. Use `/orepoc set_mode remote` to switch to remote mode

---

## Configuration

### Fabric Mod

The seed is persisted as plain text in `<game_dir>/orepoc/seed.txt`.

### Paper Plugin

Edit `plugins/OrePocServer/config.yml`:

```yaml
port: 32567          # HTTP API port (must match Fabric mod config)
cache-size: 1024     # Number of chunks to cache in memory
```

---

## Dependencies

### Fabric Mod

| Dependency | Version |
|------------|---------|
| Minecraft | 26.1.2 |
| Fabric Loader | ≥0.19.2 |
| Fabric API | ≥0.150.0+26.1.2 |

### Paper Plugin

| Dependency | Version |
|------------|---------|
| Paper API | 26.1.2 build.69 |
| Gson | 2.11.0 |

---

## Technical Details

- **Random number generation**: Uses `XoroshiroRandomSource` to replicate vanilla ore random logic
- **Ore configurations**: Hard-coded generation parameters for 8 overworld ores and 3 nether ores ([`LocalWorldGenerator.initOreConfigs()`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:131))
- **Thread safety**: `ReadWriteLock` guards cache access ([`LocalWorldGenerator`](src/main/java/io/github/moranyue/orepoc/generator/LocalWorldGenerator.java:57))
- **Cache strategy**: LRU eviction; Fabric mod caches up to 64 chunks, Paper plugin up to 1024 chunks
- **Anti-xray cleanup**: Fake ores sent by the server (anti-xray randomized replacements) are replaced with stone/deepslate

---

## License

MIT License — see [LICENSE](LICENSE)