package com.agentos.kernel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple token-based authentication for gRPC and HTTP management endpoints.
 *
 * Token format: base64(agentId:timestamp:signature)
 * Signature: SHA-256(agentId:timestamp:sharedSecret)
 *
 * Usage:
 *   1. Generate a token via {@link #issueToken(String)}
 *   2. Pass it as "Authorization: Bearer <token>" header
 *   3. Validate via {@link #validate(String)}
 */
public final class TokenAuth {
    private final String sharedSecret;
    private final long tokenTtlSeconds;
    private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();

    public TokenAuth(String sharedSecret, long tokenTtlSeconds) {
        this.sharedSecret = sharedSecret;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public TokenAuth(String sharedSecret) {
        this(sharedSecret, 3600); // 1 hour default
    }

    /** Returns the shared secret (for management endpoint verification). */
    public String sharedSecret() { return sharedSecret; }

    /**
     * Issue a new token for the given agent/principal.
     */
    public String issueToken(String principal) {
        long now = Instant.now().getEpochSecond();
        String payload = principal + ":" + now;
        String signature = hmacSha256(payload, sharedSecret);
        String token = payload + ":" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate a token. Returns the principal if valid, null otherwise.
     */
    public String validate(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 3);
            if (parts.length != 3) return null;

            String principal = parts[0];
            long issuedAt;
            try {
                issuedAt = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            String providedSig = parts[2];

            // Check expiry
            long now = Instant.now().getEpochSecond();
            if (now - issuedAt > tokenTtlSeconds) return null;

            // Check revocation
            if (revokedTokens.containsKey(token)) return null;

            // Verify signature
            String payload = principal + ":" + issuedAt;
            String expectedSig = hmacSha256(payload, sharedSecret);
            if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                providedSig.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            return principal;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Revoke a token so it can no longer be used.
     */
    public void revoke(String token) {
        revokedTokens.put(token, Instant.now());
    }

    /**
     * Clean up expired revocations.
     */
    public void cleanupRevocations() {
        Instant cutoff = Instant.now().minusSeconds(tokenTtlSeconds * 2);
        revokedTokens.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /**
     * Extract the Bearer token from an Authorization header value.
     */
    public static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        if (authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7).trim();
        }
        return null;
    }

    private static String hmacSha256(String data, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = data + ":" + key;
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
