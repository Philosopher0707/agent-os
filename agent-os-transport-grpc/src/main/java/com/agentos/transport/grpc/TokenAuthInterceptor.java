package com.agentos.transport.grpc;

import com.agentos.kernel.auth.TokenAuth;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server interceptor that validates Bearer tokens on every RPC.
 *
 * Tokens are passed via the "authorization" metadata header:
 *   authorization: Bearer <token>
 *
 * Unauthenticated calls receive UNAUTHENTICATED status.
 */
public final class TokenAuthInterceptor implements ServerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TokenAuthInterceptor.class);

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final TokenAuth tokenAuth;

    public TokenAuthInterceptor(TokenAuth tokenAuth) {
        this.tokenAuth = tokenAuth;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {

        String authHeader = headers.get(AUTHORIZATION_KEY);
        String token = TokenAuth.extractBearer(authHeader);

        if (token == null) {
            log.warn("gRPC call rejected: missing authorization header from {}",
                call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
            call.close(Status.UNAUTHENTICATED.withDescription("Missing Bearer token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String principal = tokenAuth.validate(token);
        if (principal == null) {
            log.warn("gRPC call rejected: invalid token from {}",
                call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or expired token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        // Inject principal into context for downstream handlers
        Context ctx = Context.current().withValue(Constants.PRINCIPAL_KEY, principal);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    /** Constants for context keys shared between interceptor and service impls. */
    public static final class Constants {
        public static final Context.Key<String> PRINCIPAL_KEY =
            Context.key("principal");
    }
}
