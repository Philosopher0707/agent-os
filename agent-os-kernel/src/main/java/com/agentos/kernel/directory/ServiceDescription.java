package com.agentos.kernel.directory;

import com.agentos.kernel.AgentId;

import java.util.Map;

public record ServiceDescription(
    AgentId provider,
    String serviceType,
    Map<String, String> properties
) {}
