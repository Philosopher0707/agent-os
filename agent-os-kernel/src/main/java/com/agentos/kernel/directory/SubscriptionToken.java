package com.agentos.kernel.directory;

import java.util.UUID;

public record SubscriptionToken(UUID id) {
    public static SubscriptionToken create() {
        return new SubscriptionToken(UUID.randomUUID());
    }
}
