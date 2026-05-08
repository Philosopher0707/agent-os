package com.agentos.reasoning.llm;

import com.agentos.kernel.AgentContext;

public interface LlmAgentTool {
    String name();
    String description();
    String execute(String input, AgentContext ctx);
}
