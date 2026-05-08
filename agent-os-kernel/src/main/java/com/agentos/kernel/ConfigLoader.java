package com.agentos.kernel;

public interface ConfigLoader {
    AgentOsConfig load();
    int priority();
}
