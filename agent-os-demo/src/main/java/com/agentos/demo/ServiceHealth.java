package com.agentos.demo;

public record ServiceHealth(
    String name,
    String status,
    double cpuPercent,
    double memoryPercent,
    long responseTimeMs,
    int replicas
) {
    public static ServiceHealth healthy(String name) {
        return new ServiceHealth(name, "HEALTHY", 30.0, 40.0, 5L, 3);
    }
}
