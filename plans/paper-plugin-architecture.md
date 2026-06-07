# Paper Server Plugin + Fabric Mod Architecture

## Overview
The client mod connects to a local Paper server via WebSocket to request accurate ore positions. The Paper server generates chunks using vanilla world generation (same algorithm as the real server) and returns ore positions.

## Components

### 1. Paper Plugin (`orepoc-server`)
- **Location**: Standalone Paper plugin project
- **Runtime**: Paper 1.21.5+ (matching minecraft version 26.1.2)
- **Function**: WebSocket server, chunk generation, ore scanning

### 2. Fabric Mod (existing, modified)
- **Location**: `src/main/java/io/github/moranyue/orepoc/`
- **New package**: `orepoc/websocket/` - WebSocket client
- **New package**: `orepoc/remote/` - Remote generator integration

## WebSocket Protocol

### Connection
- Address: `ws://localhost:32567/orepoc`
- Format: JSON text messages

### Messages (Mod → Plugin)

**Set Seed** (sent once on connect):
```json
{
  "type": "set_seed",
  "seed": 1234567890
}
```

**Request Single Chunk**:
```json
{
  "type": "request_chunk",
  "chunkX": 24,
  "chunkZ": 22
}
```

**Request Batch** (for 3×3 area):
```json
{
  "type": "request_batch",
  "chunks": [
    {"chunkX": 23, "chunkZ": 21},
    {"chunkX": 24, "chunkZ": 21},
    ...
  ]
}
```

### Messages (Plugin → Mod)

**Chunk Data Response**:
```json
{
  "type": "chunk_data",
  "chunkX": 24,
  "chunkZ": 22,
  "ores": [
    {"x": 392, "y": 12, "z": 352, "block": "minecraft:coal_ore"},
    {"x": 393, "y": 45, "z": 355, "block": "minecraft:iron_ore"},
    ...
  ]
}
```

**Batch Complete**:
```json
{
  "type": "batch_complete",
  "count": 9
}
```

**Error**:
```json
{
  "type": "error",
  "message": "Chunk out of bounds"
}
```

## Paper Plugin Design

### Project Structure
```
orepoc-server/
├── build.gradle (Paper plugin build)
├── src/main/java/io/github/moranyue/orepocserver/
│   ├── OrePocServerPlugin.java  (extends JavaPlugin)
│   ├── websocket/
│   │   ├── WebSocketServer.java      (Netty/Jetty WS server)
│   │   └── MessageHandler.java       (message routing)
│   ├── generation/
│   │   ├── ChunkGenerator.java       (ore scanning logic)
│   │   └── OreCache.java             (LRU cache for generated chunks)
│   └── config/
│       └── PluginConfig.java         (port, cache size, etc.)
```

### Key Components

#### WebSocketServer
- Listen on port 32567 (configurable)
- Use Java's built-in `com.sun.net.httpserver` or a lightweight WebSocket library
- Each connection gets a session with associated seed
- Handle concurrent connections

#### ChunkGenerator
- On receiving chunk request:
  1. Check OreCache → if exists, return cached data
  2. Load/create the `World` for the seed (or use existing world)
  3. Get the chunk at `(chunkX, chunkZ)` → force generation if needed
  4. Scan all blocks in the chunk for ore blocks (using BlockTags)
  5. Cache the result
  6. Return ore positions

#### OreCache
- LRU cache with configurable size (default 1024 chunks)
- Key: `chunkX << 32 | (chunkZ & 0xffffffffL)`
- Value: List of ore positions
- Thread-safe (ConcurrentHashMap)

### World Management
- Paper plugin uses the existing world on the server
- The world must have the same seed as the target server
- Use `Bukkit.createWorld(WorldCreator.name("orepoc_temp").seed(seed))` to create temp world
- OR use the main world with `/seed` command verification
- Plugin config sets the seed

### Caching Strategy
- Chunks are cached in memory (not on disk)
- Cache is invalidated when seed changes
- Cache TTL: unlimited (until server restart or seed change)
- Cache can store results for millions of chunks (10 bytes per ore × 500 ores per chunk × 1024 chunks ≈ 5MB)

## Fabric Mod Changes

### New Dependencies
- Java WebSocket client (e.g., `org.java-websocket:Java-WebSocket` or use Java 11+ HttpClient with WebSocket support)

### New Classes

#### `io.github.moranyue.orepoc.websocket.OrePocWebSocketClient`
- Extends `java.net.http.WebSocket` or similar
- Connects to `ws://localhost:32567/orepoc`
- Sends `set_seed` on connect
- Sends `request_batch` for 3×3 area
- Receives `chunk_data` responses
- Thread-safe message queue

#### `io.github.moranyue.orepoc.remote.RemoteGenerator`
- Implements same interface as `LocalWorldGenerator`
- On `getChunkOres(chunkX, chunkZ)`:
  - Check local cache first
  - If miss, send WebSocket request
  - Wait for response (blocking)
  - Cache and return result
- Fallback to `LocalWorldGenerator` if WebSocket connection fails

### Integration Points
- `OrePocClient.onInitializeClient()` → start WebSocket connection
- `ChatScreenMixin` on `/orepoc set_seed` → send seed to WebSocket server
- If WebSocket fails (connection refused), fall back to local generation
- Config toggle: `use_local_generation = true/false`

## Message Flow

```
Mod starts:
  1. OrePocClient → try connect to local WS server
  2. If connected: set mode = REMOTE
  3. If failed: set mode = LOCAL (with warning)

Player sets seed:
  1. ChatScreenMixin sends set_seed to WS server
  2. WS server acknowledges

Player joins/moves:
  1. Chunk arrives → ChunkDataMixin calls RemoteGenerator
  2. RemoteGenerator checks local cache
  3. Cache miss → sends request_batch via WS
  4. WS server generates chunks (parallel)
  5. WS server returns chunk_data for each chunk
  6. RemoteGenerator caches and returns ore positions
  7. ChunkDataMixin applies replacements
```

## Implementation Order

1. **Paper Plugin**:
   - Set up build.gradle for Paper plugin
   - Implement basic WebSocket server
   - Implement chunk scanning logic
   - Add caching

2. **Fabric Mod**:
   - Add WebSocket client dependency
   - Create WebSocket client class
   - Create RemoteGenerator (interface compatible with LocalWorldGenerator)
   - Integration with existing replacement logic

3. **Testing**:
   - Start Paper server with correct seed
   - Join server with mod
   - Verify ore positions match exactly
   - Test fallback to local generation
