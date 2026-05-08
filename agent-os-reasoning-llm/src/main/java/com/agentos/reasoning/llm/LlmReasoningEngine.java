package com.agentos.reasoning.llm;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.reasoning.ReasoningEngine;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

public final class LlmReasoningEngine implements ReasoningEngine {
    private static final Logger log = LoggerFactory.getLogger(LlmReasoningEngine.class);

    private final Function<String, String> llm;
    private final ChatLanguageModel chatModel;
    private final Map<Agent, AgentContext> contexts = new ConcurrentHashMap<>();
    private final Map<Agent, List<LlmAgentTool>> tools = new ConcurrentHashMap<>();
    private final Map<Agent, List<Map<String, String>>> conversationMemories = new ConcurrentHashMap<>();
    private final Set<Agent> llmAgents = ConcurrentHashMap.newKeySet();
    private final int maxMemoryEntries;

    // --- Constructors ---

    /** Create with a raw Function<String,String> (e.g., for testing or custom LLM calls) */
    public LlmReasoningEngine(Function<String, String> llm) {
        this(llm, null, 20);
    }

    public LlmReasoningEngine(Function<String, String> llm, int maxMemoryEntries) {
        this(llm, null, maxMemoryEntries);
    }

    /** Create with a LangChain4j ChatLanguageModel */
    public LlmReasoningEngine(ChatLanguageModel chatModel) {
        this(null, chatModel, 20);
    }

    public LlmReasoningEngine(ChatLanguageModel chatModel, int maxMemoryEntries) {
        this(null, chatModel, maxMemoryEntries);
    }

    private LlmReasoningEngine(Function<String, String> llm, ChatLanguageModel chatModel, int maxMemoryEntries) {
        this.llm = llm;
        this.chatModel = chatModel;
        this.maxMemoryEntries = maxMemoryEntries;
    }

    // --- Tool management ---

    public void addTool(Agent agent, LlmAgentTool tool) {
        tools.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(tool);
    }

    // --- ReasoningEngine implementation ---

    @Override public String name() { return "llm"; }
    @Override public boolean supports(Agent agent) { return llmAgents.contains(agent); }
    @Override public void install(Agent agent) { llmAgents.add(agent); }

    @Override
    public void start(Agent agent, AgentContext ctx) {
        contexts.put(agent, ctx);
        conversationMemories.put(agent, new ArrayList<>());
    }

    @Override
    public void onMessage(Agent agent, ACLMessage msg) {
        var ctx = contexts.get(agent);
        var agentTools = tools.getOrDefault(agent, List.of());
        if (ctx == null) { agent.onMessage(msg); return; }

        var memory = conversationMemories.get(agent);
        String userMessage = "From: " + msg.sender().name() +
            ", Type: " + msg.performative() +
            ", Content: " + msg.content();

        if (memory != null) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", userMessage);
            memory.add(entry);
            while (memory.size() > maxMemoryEntries) {
                memory.remove(0);
            }
        }

        String prompt = buildPrompt(agentTools, memory, msg);
        String response;

        try {
            if (chatModel != null) {
                response = chatModel.generate(prompt);
            } else if (llm != null) {
                response = llm.apply(prompt);
            } else {
                log.warn("LLM: no model configured");
                return;
            }

            log.info("LLM response: {}", response);

            if (memory != null) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("role", "assistant");
                entry.put("content", response);
                memory.add(entry);
            }

            dispatchTool(response, agentTools, ctx);
        } catch (Exception e) {
            log.warn("LLM processing failed: {}", e.getMessage());
        }
    }

    private String buildPrompt(List<LlmAgentTool> agentTools,
                                List<Map<String, String>> memory, ACLMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent in a multi-agent system overseeing microservice orchestration.\n");
        sb.append("Respond with ONLY the name of the tool to call, followed by its JSON input.\n\n");
        sb.append("Available tools:\n");
        for (var tool : agentTools) {
            sb.append("  - ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        sb.append("\n");

        if (memory != null && memory.size() > 1) {
            sb.append("Recent conversation:\n");
            for (var entry : memory) {
                sb.append("  ").append(entry.get("role")).append(": ")
                    .append(entry.get("content")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Current message:\n");
        sb.append("  From: ").append(msg.sender().name()).append("\n");
        sb.append("  Type: ").append(msg.performative()).append("\n");
        sb.append("  Content: ").append(msg.content()).append("\n");

        return sb.toString();
    }

    private void dispatchTool(String response, List<LlmAgentTool> agentTools, AgentContext ctx) {
        boolean toolFound = false;
        for (var tool : agentTools) {
            if (response.toLowerCase().contains(tool.name().toLowerCase())) {
                try {
                    String result = tool.execute(response, ctx);
                    log.info("Tool {} executed: {}", tool.name(), result);
                    toolFound = true;
                } catch (Exception e) {
                    log.warn("Tool {} execution failed: {}", tool.name(), e.getMessage());
                }
                break;
            }
        }
        if (!toolFound) {
            log.info("LLM: no matching tool found for response '{}'", response);
        }
    }

    @Override public void step(Agent agent) {}

    @Override public void stop(Agent agent) {
        llmAgents.remove(agent);
        contexts.remove(agent);
        tools.remove(agent);
        conversationMemories.remove(agent);
    }
}
