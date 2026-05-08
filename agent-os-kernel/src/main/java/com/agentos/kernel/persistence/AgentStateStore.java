package com.agentos.kernel.persistence;

import com.agentos.kernel.AgentId;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface AgentStateStore {
    CompletionStage<Void> save(AgentId id, byte[] state);
    CompletionStage<Optional<byte[]>> load(AgentId id);
    CompletionStage<Void> delete(AgentId id);
}
