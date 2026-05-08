package com.agentos.demo;

public class SimulatedService {
    private final String name;
    private volatile double cpuPercent = 30.0;
    private volatile double memoryPercent = 40.0;
    private volatile long responseTimeMs = 5L;
    private volatile int replicas = 3;
    private volatile String status = "HEALTHY";

    public SimulatedService(String name) { this.name = name; }

    public String name() { return name; }

    public ServiceHealth health() {
        return new ServiceHealth(name, status, cpuPercent, memoryPercent, responseTimeMs, replicas);
    }

    public void injectFault(String faultType) {
        switch (faultType) {
            case "high-cpu" -> {
                cpuPercent = 92.0;
                responseTimeMs = 500L;
                status = "DEGRADED";
            }
            case "timeout" -> {
                responseTimeMs = 5000L;
                status = "DEGRADED";
            }
            case "crash" -> {
                status = "DOWN";
                cpuPercent = 0;
                memoryPercent = 0;
                responseTimeMs = -1;
            }
        }
    }

    public void scale(int delta) {
        replicas += delta;
        if (replicas < 1) replicas = 1;
        cpuPercent = Math.max(30.0, cpuPercent / 2);
        responseTimeMs = 5L;
        status = "HEALTHY";
    }

    public void restart() {
        cpuPercent = 30.0;
        memoryPercent = 40.0;
        responseTimeMs = 5L;
        status = "HEALTHY";
    }
}
