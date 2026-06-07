# Registry Access Fix Plan

## Problem
`Registries.NOISE` (`minecraft:worldgen/noise`) is not available in `ClientLevel.registryAccess()`.

## Analysis
The client's RegistryAccess wraps the registries differently than the server's. 
In Fabric client-side, the `ClientLevel` may not have access to worldgen registries.

## Options

### Option A: Use `DynamicRegistryManager` / Bootstrap
Minecraft has built-in `BuiltInRegistries` and `RegistryFriendlyByteBuf` that may
bootstrap these registries. Check if there's a static bootstrap method.

### Option B: Get registry from server
In singleplayer, `Minecraft.getInstance().getSingleplayerServer()` returns the
integrated server which has full registry access.

### Option C: Build minimal registry
Create a simple `HolderGetter<NoiseParameters>` that returns empty optionals.
May work if `NoiseGeneratorSettings.dummy()` doesn't actually use the noise params.

### Option D: Use HolderGetter.Provider.lookupOrThrow
`RegistryAccess` extends `HolderLookup.Provider`. The `lookupOrThrow` on `Provider`
uses a different lookup path. Maybe we need `registryAccess().lookup(Registries.NOISE)`
instead of `lookupOrThrow`.

## Recommended: Options B + D
1. Try `level.registryAccess().lookupOrThrow(Registries.NOISE)` with server registry
2. If that fails, try `BuiltInRegistries.NOISE` or similar static registries
3. If both fail, fall back to building a minimal HolderGetter

## Implementation
In `generateChunk()`:
1. Try `mc.getSingleplayerServer().registryAccess().lookupOrThrow(Registries.NOISE)` (singleplayer)
2. Fallback: check if `level.registryAccess().lookup(Registries.NOISE).isPresent()` returns false
3. Last resort: create anonymous `HolderGetter` returning empty optionals
