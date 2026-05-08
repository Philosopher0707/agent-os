package com.agentos.kernel.impl;

import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dead-letter queue for messages that could not be delivered after all retries.
 *
 * Features:
 *   - Stores failed messages with failure reason and timestamp
 *   - Supports replay (re-deliver to original receivers)
 *   - Configurable max size with eviction of oldest entries
 *   - Exposes metrics for monitoring
 */
public final class DeadLetterQueue {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    public record DeadLetterEntry(
        ACLMessage message,
        String reason,
        Instant failedAt,
        int retryCount
    ) {}

    private final ConcurrentLinkedQueue<DeadLetterEntry> entries = new ConcurrentLinkedQueue<>();
    private final int maxEntries;
    private final AtomicLong totalDeadLettered = new AtomicLong(0);
    private final AtomicLong totalReplayed = new AtomicLong(0);
    private final AtomicLong totalExpired = new AtomicLong(0);

    public DeadLetterQueue(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public DeadLetterQueue() {
        this(10_000);
    }

    /**
     * Enqueue a failed message.
     */
    public void enqueue(ACLMessage msg, String reason, int retryCount) {
        while (entries.size() >= maxEntries) {
            entries.poll();
            totalExpired.incrementAndGet();
        }
        DeadLetterEntry entry = new DeadLetterEntry(msg, reason, Instant.now(), retryCount);
        entries.offer(entry);
        totalDeadLettered.incrementAndGet();
        log.debug("DLQ: enqueued message convId={} reason={} retries={}",
            msg.conversationId(), reason, retryCount);
    }

    /**
     * Replay all dead-lettered messages through a replay handler.
     * Messages remain in the queue; they are not removed unless replay succeeds.
     *
     * @param replayer function that attempts re-delivery; returns true on success
     * @return count of successfully replayed messages
     */
    public int replayAll(java.util.function.Predicate<ACLMessage> replayer) {
        int replayed = 0;
        List<DeadLetterEntry> toRemove = new ArrayList<>();

        for (DeadLetterEntry entry : entries) {
            try {
                if (replayer.test(entry.message)) {
                    toRemove.add(entry);
                    replayed++;
                }
            } catch (Exception e) {
                log.warn("DLQ replay failed for convId={}: {}", entry.message.conversationId(), e.getMessage());
            }
        }

        entries.removeAll(toRemove);
        totalReplayed.addAndGet(replayed);
        log.info("DLQ: replayed {} messages, {} remaining", replayed, entries.size());
        return replayed;
    }

    /**
     * Replay messages for a specific conversation.
     */
    public int replayConversation(String conversationId, java.util.function.Predicate<ACLMessage> replayer) {
        int replayed = 0;
        List<DeadLetterEntry> toRemove = new ArrayList<>();

        for (DeadLetterEntry entry : entries) {
            if (entry.message.conversationId().equals(conversationId)) {
                try {
                    if (replayer.test(entry.message)) {
                        toRemove.add(entry);
                        replayed++;
                    }
                } catch (Exception e) {
                    log.warn("DLQ replay failed for convId={}: {}", conversationId, e.getMessage());
                }
            }
        }

        entries.removeAll(toRemove);
        totalReplayed.addAndGet(replayed);
        return replayed;
    }

    /**
     * Replay all dead-lettered messages by sending them through a Consumer.
     * Messages are removed on successful send (no exception).
     */
    public int replayAllWithSender(java.util.function.Consumer<ACLMessage> sender) {
        int replayed = 0;
        List<DeadLetterEntry> toRemove = new ArrayList<>();
        for (DeadLetterEntry entry : entries) {
            try {
                sender.accept(entry.message);
                toRemove.add(entry);
                replayed++;
            } catch (Exception e) {
                log.warn("DLQ replay send failed for convId={}: {}", entry.message.conversationId(), e.getMessage());
            }
        }
        entries.removeAll(toRemove);
        totalReplayed.addAndGet(replayed);
        log.info("DLQ: replayed {} messages via sender, {} remaining", replayed, entries.size());
        return replayed;
    }

    /**
     * Purge all entries.
     */
    public int purge() {
        int count = entries.size();
        entries.clear();
        log.info("DLQ: purged {} messages", count);
        return count;
    }

    /**
     * Purge entries older than the given instant.
     */
    public int purgeOlderThan(Instant cutoff) {
        int removed = 0;
        Iterator<DeadLetterEntry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().failedAt.isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    // --- Inspection ---

    public List<DeadLetterEntry> peek(int limit) {
        return entries.stream().limit(limit).toList();
    }

    public List<DeadLetterEntry> all() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    // --- Metrics ---

    public long totalDeadLettered() { return totalDeadLettered.get(); }
    public long totalReplayed() { return totalReplayed.get(); }
    public long totalExpired() { return totalExpired.get(); }
    public int currentSize() { return entries.size(); }

    public Map<String, Object> metrics() {
        return Map.of(
            "dlq.total_dead_lettered", totalDeadLettered.get(),
            "dlq.total_replayed", totalReplayed.get(),
            "dlq.total_expired", totalExpired.get(),
            "dlq.current_size", entries.size(),
            "dlq.max_size", maxEntries
        );
    }
}
