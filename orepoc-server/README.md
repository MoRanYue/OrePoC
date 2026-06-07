# OrePoC Server Plugin

Server-side plugin for OrePoC client mod. Provides 100% accurate ore positions via HTTP API.

## Requirements

- Paper 1.21.5+ (minecraft 26.1.2)
- Java 25

## Installation

1. Build the plugin: `./gradlew jar`
2. Place `orepoc-server-1.0.0.jar` into your Paper server's `plugins/` folder
3. Set the world seed to match your target server's seed
4. Start the Paper server

## Configuration

Edit `plugins/OrePocServer/config.yml`:

```yaml
port: 32567          # HTTP API port (must match Fabric mod config)
cache-size: 1024     # Number of chunks to cache in memory
```

## API

HTTP POST to `http://localhost:32567/orepoc`

### Set Seed
```json
{"type":"set_seed","seed":1234567890}
```

### Request Chunk
```json
{"type":"request_chunk","chunkX":24,"chunkZ":22}
```
Response:
```json
{"type":"chunk_data","chunkX":24,"chunkZ":22,"ores":[...]}
```

### Request Batch
```json
{"type":"request_batch","chunks":[{"chunkX":24,"chunkZ":22},...]}
```
Response:
```json
{"type":"batch_complete","count":9,"chunks":[...]}
```

## Building

```bash
cd orepoc-server
./gradlew jar
# Output: build/libs/orepoc-server-1.0.0.jar
```
