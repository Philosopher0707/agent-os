package com.agentos.directory;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.directory.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class InMemoryServiceDirectory implements ServiceDirectory {

    private final ConcurrentHashMap<String, List<ServiceDescription>> services = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SubscriptionToken, Subscription> subscriptions = new ConcurrentHashMap<>();

    private record Subscription(String serviceType, Consumer<List<ServiceDescription>> callback) {}

    @Override
    public void register(ServiceDescription service) {
        services.compute(service.serviceType(), (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(service);
            return list;
        });
        notifySubscribers(service.serviceType());
    }

    @Override
    public void deregister(AgentId provider, String serviceType) {
        services.computeIfPresent(serviceType, (k, list) -> {
            list.removeIf(desc -> desc.provider().equals(provider));
            return list.isEmpty() ? null : list;
        });
        notifySubscribers(serviceType);
    }

    @Override
    public List<ServiceDescription> search(String serviceType) {
        return services.getOrDefault(serviceType, List.of());
    }

    @Override
    public List<ServiceDescription> search(String serviceType, Predicate<Map<String, String>> constraints) {
        return services.getOrDefault(serviceType, List.of()).stream()
            .filter(d -> constraints.test(d.properties()))
            .collect(Collectors.toList());
    }

    @Override
    public SubscriptionToken subscribe(String serviceType, Consumer<List<ServiceDescription>> callback) {
        SubscriptionToken token = SubscriptionToken.create();
        subscriptions.put(token, new Subscription(serviceType, callback));
        return token;
    }

    @Override
    public void unsubscribe(SubscriptionToken token) {
        subscriptions.remove(token);
    }

    private void notifySubscribers(String serviceType) {
        List<ServiceDescription> current = services.getOrDefault(serviceType, List.of());
        for (Subscription sub : subscriptions.values()) {
            if (sub.serviceType().equals(serviceType)) {
                sub.callback().accept(current);
            }
        }
    }
}
