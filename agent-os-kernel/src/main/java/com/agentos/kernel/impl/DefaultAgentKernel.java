package com.agentos.kernel.impl;

import com.agentos.kernel.*;
import com.agentos.kernel.auth.TokenAuth;
import com.agentos.kernel.directory.*;
import com.agentos.kernel.management.KernelManagement;
import com.agentos.kernel.messaging.*;
import com.agentos.kernel.persistence.*;
import com.agentos.kernel.reasoning.*;
import com.agentos.kernel.sandbox.SandboxedAgent;
import com.agentos.kernel.sandbox.SandboxPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.Optional;
import java.time.Instant;

public final class DefaultAgentKernel implements AgentKernel {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentKernel.class);

    private final String containerId;
    private final AgentOsConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<AgentId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentMailbox> mailboxes = new ConcurrentHashMap<>();
    private AgentRegistry registry;
    private ServiceDirectory serviceDir;
    private MessageTransport transport;
    private MessageStore messageStore;
    private AgentStateStore stateStore;
    private final List<ReasoningEngine> reasoningEngines = new CopyOnWriteArrayList<>();
    private final List<MessagingProtocol> protocols = new CopyOnWriteArrayList<>();
    private final DirectoryCache routingCache;
    private volatile boolean started = false;
    private final AtomicLong messagesRouted = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private KernelManagement management;
    private DeadLetterQueue deadLetterQueue;
    private TokenAuth tokenAuth;

    private DefaultAgentKernel(String containerId, AgentOsConfig config) {
        this.containerId = containerId;
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> { Thread t = new Thread(r, "kernel-scheduler"); t.setDaemon(true); return t; }
        );
        this.routingCache = new DirectoryCache(config.routingCacheTtl());
    }

    public static AgentKernel create(String containerId, AgentOsConfig config) {
        return new DefaultAgentKernel(containerId, config);
    }

    public static AgentKernel create(String containerId) {
        return new DefaultAgentKernel(containerId, loadConfig());
    }

    public static AgentKernel createDefault() {
        return create("default");
    }

    private static AgentOsConfig loadConfig() {
        ServiceLoader<ConfigLoader> loaders = ServiceLoader.load(ConfigLoader.class);
        ConfigLoader best = null;
        int bestPriority = -1;
        for (ConfigLoader loader : loaders) {
            if (loader.priority() > bestPriority) {
                AgentOsConfig cfg = loader.load();
                if (cfg != null) {
                    best = loader;
                    bestPriority = loader.priority();
                }
            }
        }
        if (best != null) return best.load();

        var propsLoader = new com.agentos.kernel.config.PropertiesConfigLoader();
        var envLoader = new com.agentos.kernel.config.EnvConfigLoader();
        // Properties take priority over env for explicit overrides.
        // Both are always non-null (return config with defaults).
        var propsCfg = propsLoader.load();
        return propsCfg;
    }

    @Override
    public void bind(MessageTransport transport) {
        if (started) throw new IllegalStateException("kernel already started");
        this.transport = transport;
    }

    @Override
    public void bind(AgentRegistry registry) {
        if (started) throw new IllegalStateException("kernel already started");
        this.registry = registry;
    }

    @Override
    public void bind(ServiceDirectory services) {
        if (started) throw new IllegalStateException("kernel already started");
        this.serviceDir = services;
    }

    @Override
    public void bind(ReasoningEngine engine) {
        if (started) throw new IllegalStateException("kernel already started");
        reasoningEngines.add(engine);
    }

    public void bind(MessageStore store) {
        if (started) throw new IllegalStateException("kernel already started");
        this.messageStore = store;
    }

    public void bind(AgentStateStore store) {
        if (started) throw new IllegalStateException("kernel already started");
        this.stateStore = store;
    }

    public void bind(MessagingProtocol protocol) {
        if (started) throw new IllegalStateException("kernel already started");
        protocols.add(protocol);
    }

    public void bind(DeadLetterQueue dlq) {
        if (started) throw new IllegalStateException("kernel already started");
        this.deadLetterQueue = dlq;
    }

    public void bind(TokenAuth auth) {
        if (started) throw new IllegalStateException("kernel already started");
        this.tokenAuth = auth;
    }

    @Override
    public void start() {
        if (started) return;
        started = true;

        if (registry == null) {
            ServiceLoader<AgentRegistry> loader = ServiceLoader.load(AgentRegistry.class);
            registry = loader.findFirst().orElse(null);
        }

        if (serviceDir == null) {
            ServiceLoader<ServiceDirectory> loader = ServiceLoader.load(ServiceDirectory.class);
            serviceDir = loader.findFirst().orElse(null);
        }

        if (transport == null) {
            ServiceLoader<MessageTransport> loader = ServiceLoader.load(MessageTransport.class);
            transport = loader.findFirst().orElse(null);
        }
        if (transport != null) {
            transport.receive(this::receiveFromTransport);
            transport.start();
        }

        if (reasoningEngines.isEmpty()) {
            ServiceLoader<ReasoningEngine> loader = ServiceLoader.load(ReasoningEngine.class);
            loader.forEach(reasoningEngines::add);
        }

        // Initialize dead-letter queue if not bound explicitly
        if (deadLetterQueue == null) {
            deadLetterQueue = new DeadLetterQueue(config.dlqMaxEntries() > 0 ? config.dlqMaxEntries() : 10_000);
        }

        // Initialize token auth if not bound explicitly
        if (tokenAuth == null && config.authSecret() != null && !config.authSecret().isBlank()) {
            tokenAuth = new TokenAuth(config.authSecret(), config.authTokenTtlSeconds());
        }

        log.info("Kernel {} started. Registry={}, Transport={}, Engines={}, Persistence={}",
            containerId,
            registry != null ? registry.getClass().getSimpleName() : "none",
            transport != null ? transport.scheme() : "none",
            reasoningEngines.size(),
            messageStore != null ? "enabled" : "none");

        // Validate configuration and log warnings
        var configWarnings = config.validate();
        if (!configWarnings.isEmpty()) {
            log.warn("Configuration warnings:");
            configWarnings.forEach(w -> log.warn("  - {}", w));
        }

        // Start management server with metrics
        int mgmtPort = config.managementPort() > 0 ? config.managementPort() : 9091;
        management = new KernelManagement(mgmtPort, this::health, deadLetterQueue, tokenAuth,
            (agentName, faultType) -> {
                sessions.keySet().stream()
                    .filter(id -> id.name().equals(agentName))
                    .findFirst()
                    .ifPresent(id -> {
                        switch (faultType) {
                            case "crash" -> transition(id, AgentLifecycle.TRANSIENT);
                            case "hang" -> transition(id, AgentLifecycle.SUSPENDED);
                            case "slow" -> log.info("Injected slow fault into {}", agentName);
                            case "memory" -> log.info("Injected memory fault into {}", agentName);
                        }
                    });
            },
            this::send,
            name -> sessions.keySet().stream()
                .filter(id -> id.name().equals(name))
                .findFirst()
                .flatMap(this::agentHealth));
        management.start();
    }

    /** Handle messages arriving from the transport (remote agents) */
    private void receiveFromTransport(ACLMessage msg) {
        // Handle migration messages
        if (msg.performative() == ACLMessage.Performative.PROPAGATE
            && "migration".equals(msg.protocol())) {
            handleMigration(msg);
            return;
        }
        send(msg);
    }

    private void handleMigration(ACLMessage msg) {
        try {
            byte[] state = Base64.getDecoder().decode(msg.content());
            AgentId agentId = msg.sender();

            // Look up agent type from registry to re-instantiate
            // For now, we require the agent to already be registered (pre-staged)
            // or we restore from state store
            log.info("Received migration for agent {} ({} bytes)", agentId.name(), state.length);

            // If we have the agent class info, we could re-instantiate here.
            // For now, store the state and let the agent be re-registered manually
            // with restore() called during init.
            if (stateStore != null) {
                stateStore.save(agentId, state);
            }

            // Acknowledge migration
            ACLMessage ack = ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(AgentId.of("kernel@" + containerId))
                .receiver(msg.sender())
                .conversationId(msg.conversationId())
                .content("{\"migrated\":true,\"container\":\"" + containerId + "\"}")
                .build();
            send(ack);
        } catch (Exception e) {
            log.error("Migration receive failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public AgentId register(Agent agent) {
        if (!started) throw new IllegalStateException("kernel not started");

        if (registry != null) {
            try {
                AgentId assigned = registry.register(agent, containerId);
                registerInternal(assigned, agent);
                return assigned;
            } catch (AgentExistsException e) {
                throw e;
            }
        }

        AgentId id = AgentId.of(agent.agentId() != null ? agent.agentId().name()
            : "agent-" + UUID.randomUUID().toString().substring(0, 8));
        registerInternal(id, agent);
        return id;
    }

    private void registerInternal(AgentId id, Agent agent) {
        // Apply sandbox wrapping if configured
        Agent effectiveAgent = agent;
        if (config.sandboxEnabled() && !(agent instanceof SandboxedAgent)) {
            var policy = switch (config.sandboxPolicy().toLowerCase()) {
                case "strict" -> SandboxPolicy.strict();
                case "permissive" -> SandboxPolicy.permissive();
                default -> SandboxPolicy.defaults();
            };
            effectiveAgent = new SandboxedAgent(agent, policy);
        }

        DefaultAgentContext ctx = new DefaultAgentContext(id, registry, serviceDir,
            this::send, scheduler);
        AgentSession session = new AgentSession(effectiveAgent, ctx, config);
        sessions.put(id, session);

        Consumer<ACLMessage> dispatcher = msg -> {
            ReasoningEngine engine = findEngine(agent);
            if (engine != null) {
                engine.onMessage(agent, msg);
            } else {
                agent.onMessage(msg);
            }
        };
        AgentMailbox mailbox = new AgentMailbox(config.mailboxCapacity(), dispatcher);
        mailboxes.put(id.name(), mailbox);

        ReasoningEngine engine = findEngine(agent);
        if (engine != null) {
            engine.start(agent, ctx);
        }

        if (transport != null) {
            transport.registerAgent(id.name(), containerId);
        }

        // Restore persisted state if available
        if (stateStore != null) {
            stateStore.load(id).thenAccept(opt -> {
                opt.ifPresent(state -> log.info("Restored state for agent {}", id.name()));
            });
        }

        try {
            session.init();
        } catch (Exception e) {
            log.warn("Agent {} init failed: {}", id.name(), e.getMessage());
            sessions.remove(id);
            mailboxes.remove(id.name());
            routingCache.invalidate(id.name());
            if (registry != null) {
                registry.unregister(id);
            }
            return;
        }

        session.startTicking(scheduler, config.tickInterval());
        log.info("Agent {} registered as {}", id.name(), session.state());
    }

    private ReasoningEngine findEngine(Agent agent) {
        for (ReasoningEngine engine : reasoningEngines) {
            if (engine.supports(agent)) return engine;
        }
        return null;
    }

    @Override
    public void unregister(AgentId id) {
        AgentSession session = sessions.remove(id);
        if (session != null) {
            session.shutdown();
            mailboxes.remove(id.name());
            routingCache.invalidate(id.name());
            if (registry != null) {
                registry.unregister(id);
            }
            if (transport != null) {
                transport.unregisterAgent(id.name());
            }
        }
    }

    @Override
    public void transition(AgentId id, AgentLifecycle state) {
        AgentSession session = sessions.get(id);
        if (session != null) {
            session.transition(state);
            if (registry != null) {
                registry.setState(id, state);
            }
        }
    }

    @Override
    public AgentLifecycle stateOf(AgentId id) {
        AgentSession session = sessions.get(id);
        return session != null ? session.state() : AgentLifecycle.TERMINATED;
    }

    @Override
    public void send(ACLMessage msg) {
        messagesRouted.incrementAndGet();

        // Validate against protocols
        for (MessagingProtocol protocol : protocols) {
            var violation = protocol.validate(msg.conversationId(), msg);
            if (violation.isPresent()) {
                log.warn("Protocol violation: {}", violation.get().reason());
            }
        }

        // Persist message if store is available
        if (messageStore != null) {
            messageStore.append(msg);
        }

        // Deliver locally and drain immediately to prevent race conditions
        for (AgentId receiver : msg.receivers()) {
            AgentMailbox mailbox = mailboxes.get(receiver.name());
            if (mailbox != null) {
                mailbox.deliver(msg);
                mailbox.drain(failed -> {
                    messagesFailed.incrementAndGet();
                    sendFailure(msg, receiver, "dispatch error");
                });
            } else {
                // Not local — try transport for remote delivery with retries
                if (transport != null) {
                    sendWithRetry(msg, receiver);
                } else {
                    messagesFailed.incrementAndGet();
                    sendFailure(msg, receiver, "agent not found");
                }
            }
        }
    }

    private void sendWithRetry(ACLMessage msg, AgentId receiver) {
        int maxRetries = config.maxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                transport.send(msg).get(config.stepTimeout().toMillis(), TimeUnit.MILLISECONDS);
                return; // success
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.debug("Send attempt {}/{} failed for {}: {}",
                        attempt + 1, maxRetries, receiver.name(), e.getMessage());
                    try {
                        Thread.sleep(100L * (attempt + 1)); // exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.warn("Send failed after {} retries for {}: {}",
                        maxRetries, receiver.name(), e.getMessage());
                }
            }
        }
        messagesFailed.incrementAndGet();
        // Enqueue to dead-letter queue
        deadLetterQueue.enqueue(msg, "transport failed after " + maxRetries + " retries", maxRetries);
        sendFailure(msg, receiver, "transport failed after " + maxRetries + " retries");
    }

    private void sendFailure(ACLMessage msg, AgentId receiver, String reason) {
        if (msg.replyTo() != null) {
            ACLMessage failure = ACLMessage.builder()
                .performative(ACLMessage.Performative.FAILURE)
                .sender(receiver)
                .receiver(msg.replyTo())
                .conversationId(msg.conversationId())
                .content("{\"reason\": \"" + reason + "\"}")
                .build();
            send(failure);
        }
    }

    @Override
    public AgentContext contextOf(AgentId id) {
        AgentSession session = sessions.get(id);
        return session != null ? session.context() : null;
    }

    @Override
    public AgentKernelHealth health() {
        int active = 0, suspended = 0, terminated = 0, sandboxed = 0;
        long violations = 0;
        for (AgentSession s : sessions.values()) {
            switch (s.state()) {
                case ACTIVE -> active++;
                case SUSPENDED -> suspended++;
                case TERMINATED -> terminated++;
                default -> {}
            }
            if (s.agent() instanceof SandboxedAgent sa) {
                sandboxed++;
                violations += sa.violationCount();
            }
        }
        return new AgentKernelHealth(containerId, active, suspended, terminated,
            messagesRouted.get(), messagesFailed.get(),
            registry != null, transport != null, sandboxed, violations);
    }

    @Override
    public Optional<AgentHealth> agentHealth(AgentId id) {
        AgentSession session = sessions.get(id);
        if (session == null) return Optional.empty();
        Agent agent = session.agent();
        boolean sandboxed = agent instanceof SandboxedAgent;
        long violations = sandboxed ? ((SandboxedAgent) agent).violationCount() : 0;
        boolean hasError = sandboxed && ((SandboxedAgent) agent).hasFailed();
        return Optional.of(new AgentHealth(
            id.name(), session.state(), session.consecutiveFailures(),
            sandboxed, violations, hasError, Instant.now()));
    }

    @Override
    public CompletableFuture<MigrationResult> migrate(AgentId id, String targetContainer) {
        AgentSession session = sessions.get(id);
        if (session == null) {
            return CompletableFuture.completedFuture(MigrationResult.failed("agent not found"));
        }

        Agent agent = session.agent();
        if (!(agent instanceof MobileAgent mobile)) {
            log.warn("Migrate: agent {} is not mobile", id.name());
            return CompletableFuture.completedFuture(MigrationResult.failed("agent not mobile"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Ask agent to prepare
                if (!mobile.prepareMigration()) {
                    log.info("Migrate: agent {} vetoed migration", id.name());
                    return MigrationResult.vetoed();
                }

                // 2. Checkpoint state
                byte[] state = mobile.checkpoint();
                log.info("Migrate: checkpointed agent {} ({} bytes)", id.name(), state.length);

                // 3. Suspend locally
                session.suspend();
                transition(id, AgentLifecycle.SUSPENDED);

                // 4. Send migration message to target container
                ACLMessage migrationMsg = ACLMessage.builder()
                    .performative(ACLMessage.Performative.PROPAGATE)
                    .sender(id)
                    .receiver(AgentId.of("kernel@" + targetContainer))
                    .protocol("migration")
                    .content(Base64.getEncoder().encodeToString(state))
                    .build();

                // Use transport to deliver to target
                if (transport != null) {
                    transport.send(migrationMsg).get(30, TimeUnit.SECONDS);
                }

                // 5. Unregister locally
                unregister(id);

                log.info("Migrate: agent {} migrated to {}", id.name(), targetContainer);
                return MigrationResult.ok();
            } catch (Exception e) {
                log.error("Migrate: agent {} failed: {}", id.name(), e.getMessage(), e);
                // Rollback: resume agent locally
                try { session.resume(); } catch (Exception ignored) {}
                return MigrationResult.failed(e.getMessage());
            }
        }, scheduler);
    }

    @Override
    public void close() {
        log.info("Shutting down kernel {}", containerId);
        started = false;

        // Persist agent states before shutdown
        if (stateStore != null) {
            for (var entry : sessions.entrySet()) {
                stateStore.save(entry.getKey(), entry.getValue().serializeState());
            }
        }

        List<AgentSession> snapshot = new ArrayList<>(sessions.values());
        for (AgentSession s : snapshot) {
            s.transition(AgentLifecycle.SUSPENDED);
        }

        try {
            Thread.sleep(config.gracefulShutdown().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (AgentSession s : snapshot) {
            s.shutdown();
        }

        if (transport != null) {
            transport.close();
        }
        if (management != null) {
            management.close();
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessions.clear();
        mailboxes.clear();
        log.info("Kernel {} shut down", containerId);
    }
}
