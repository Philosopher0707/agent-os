package com.agentos.kernel;

import java.util.Map;
import java.util.Optional;

/**
 * Interface for agents that support mobility — the ability to checkpoint,
 * migrate to another container, and restore state.
 *
 * Implementing agents must be serializable (state captured as byte[]).
 */
public interface MobileAgent extends Agent {

    /**
     * Capture the agent's current state as a byte array for migration.
     * Called before migration or during periodic checkpointing.
     */
    byte[] checkpoint();

    /**
     * Restore the agent's state from a previously captured checkpoint.
     * Called after migration to a new container.
     */
    void restore(byte[] state);

    /**
     * Called when migration is about to begin.
     * The agent should finish any in-progress work and prepare for suspension.
     * Return false to veto the migration.
     */
    default boolean prepareMigration() { return true; }

    /**
     * Called after successful migration to a new container.
     * The agent receives the new context and can resume work.
     */
    default void afterMigration(AgentContext newContext) {}

    /**
     * Optional metadata about the agent's current location/state
     * for migration planning.
     */
    default Map<String, String> migrationMetadata() { return Map.of(); }
}
