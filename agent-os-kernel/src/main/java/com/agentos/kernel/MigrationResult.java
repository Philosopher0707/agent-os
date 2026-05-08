package com.agentos.kernel;

public record MigrationResult(boolean success, String reason) {
    public static MigrationResult ok() { return new MigrationResult(true, "migrated"); }
    public static MigrationResult vetoed() { return new MigrationResult(false, "agent vetoed"); }
    public static MigrationResult failed(String reason) { return new MigrationResult(false, reason); }
}
