package io.github.moranyue.orepocserver.generation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OreCache {

    private final int maxSize;
    private final Map<Long, List<OrePosition>> cache;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public OreCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, List<OrePosition>> eldest) {
                return size() > OreCache.this.maxSize;
            }
        };
    }

    public List<OrePosition> get(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        lock.readLock().lock();
        try {
            return cache.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(int chunkX, int chunkZ, List<OrePosition> ores) {
        long key = pack(chunkX, chunkZ);
        lock.writeLock().lock();
        try {
            cache.put(key, ores);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static record OrePosition(int x, int y, int z, String block) {
        public String toJson() {
            return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"block\":\"" + block + "\"}";
        }
    }
}
