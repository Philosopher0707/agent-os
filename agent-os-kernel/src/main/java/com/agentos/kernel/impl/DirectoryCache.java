package com.agentos.kernel.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DirectoryCache {
    private static final int MAX_ENTRIES = 1000;

    private final Map<String, CacheEntry> cache;
    private final Duration ttl;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public record CacheEntry(String containerId, Instant cachedAt) {}

    public DirectoryCache(Duration ttl) {
        this.ttl = ttl;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }

    public Optional<String> get(String agentName) {
        lock.readLock().lock();
        try {
            CacheEntry entry = cache.get(agentName);
            if (entry == null) return Optional.empty();
            if (Duration.between(entry.cachedAt(), Instant.now()).compareTo(ttl) > 0) {
                return Optional.empty();
            }
            return Optional.of(entry.containerId());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(String agentName, String containerId) {
        lock.writeLock().lock();
        try {
            cache.put(agentName, new CacheEntry(containerId, Instant.now()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void invalidate(String agentName) {
        lock.writeLock().lock();
        try {
            cache.remove(agentName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try { return cache.size(); }
        finally { lock.readLock().unlock(); }
    }
}
