package com.agentos.kernel.persistence;

import com.agentos.kernel.messaging.ACLMessage;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface MessageStore {
    CompletionStage<Void> append(ACLMessage msg);
    CompletionStage<List<ACLMessage>> replay(String agentId, Instant since);
    CompletionStage<Void> archiveBefore(Instant cutoff);
}
