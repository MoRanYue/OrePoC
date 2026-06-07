package io.github.moranyue.orepoc.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stores and retrieves the user-configured world seed.
 * <p>
 * The seed is persisted as plain text in {@code <game_dir>/orepoc/seed.txt}.
 */
public final class OrePocConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("orepoc/config");
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getGameDir().resolve("orepoc");
    private static final Path SEED_FILE = CONFIG_DIR.resolve("seed.txt");

    private static Long cachedSeed = null;

    private OrePocConfig() {
    }

    /**
     * Load the persisted seed. Returns 0 if no seed is set or on error.
     */
    public static long loadSeed() {
        if (cachedSeed != null) {
            return cachedSeed;
        }
        if (!Files.exists(SEED_FILE)) {
            return 0L;
        }
        try {
            String content = Files.readString(SEED_FILE, StandardCharsets.UTF_8).trim();
            long seed = Long.parseLong(content);
            cachedSeed = seed;
            return seed;
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("Could not read seed file {}: {}", SEED_FILE, e.getMessage());
            return 0L;
        }
    }

    /**
     * Persist the given seed to disk.
     */
    public static void saveSeed(long seed) {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(SEED_FILE, Long.toString(seed), StandardCharsets.UTF_8);
            cachedSeed = seed;
            LOGGER.info("Seed saved: {}", seed);
        } catch (IOException e) {
            LOGGER.error("Failed to save seed to {}: {}", SEED_FILE, e.getMessage());
        }
    }

    /**
     * Returns true if a seed has been persisted.
     */
    public static boolean isSeedSet() {
        return Files.exists(SEED_FILE);
    }

    /**
     * Returns a short hex fingerprint of the given seed (for display without revealing the seed).
     */
    public static String seedFingerprint(long seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "????";
        }
    }
}
