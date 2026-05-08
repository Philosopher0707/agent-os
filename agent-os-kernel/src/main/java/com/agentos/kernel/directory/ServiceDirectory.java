package com.agentos.kernel.directory;

import com.agentos.kernel.AgentId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface ServiceDirectory {
    void register(ServiceDescription service);
    void deregister(AgentId provider, String serviceType);
    List<ServiceDescription> search(String serviceType);
    List<ServiceDescription> search(String serviceType, Predicate<Map<String, String>> constraints);
    SubscriptionToken subscribe(String serviceType, Consumer<List<ServiceDescription>> callback);
    void unsubscribe(SubscriptionToken token);
}
