package com.agentos.reasoning.bdi;

import java.util.List;

public record Plan(
    String triggeringEvent,
    String context,
    List<String> body,
    int priority
) {}
