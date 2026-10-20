package com.zqnt.sdk.edge.auth;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses every call into the adapter that does not carry a valid platform service token — only
 * the platform may command the device. Health probes ({@code grpc.health.v1.Health}) stay open.
 * Register it on the adapter's gRPC server (in Quarkus: a {@code @GlobalInterceptor} bean that
 * delegates to this, see edge-dji).
 */
@Slf4j
public class PlatformAuthServerInterceptor implements ServerInterceptor, AutoCloseable {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER = "Bearer ";

    private static final String NOT_CONFIGURED = "neither ZQNT_PLATFORM_KEYS_URL nor ZQNT_PLATFORM_PUBLIC_KEY is configured";

    private final boolean disabled;
    private final PlatformKeySet keys;
    private final PlatformTokenVerifier verifier;
    private final String misconfigured;

    public PlatformAuthServerInterceptor(EdgeAuthConfig config) {
        this(config, config.disabled() ? null : new PlatformKeySet(config.platformPublicKey(), config.platformKeysUrl()));
    }

    public PlatformAuthServerInterceptor(EdgeAuthConfig config, PlatformKeySet keys) {
        this.disabled = config.disabled();
        if (disabled) {
            log.warn("ZQNT_EDGE_AUTH_DISABLED is set: this adapter accepts commands from ANYONE who can reach "
                    + "its port. Local SITL/simulator use only.");
            this.keys = null;
            this.verifier = null;
            this.misconfigured = null;
        } else if (!keys.hasKeysUrl() && !keys.hasStaticKey()) {
            log.error("{}: every platform command will be refused. Set ZQNT_PLATFORM_KEYS_URL to the platform's "
                    + "/api/admin-console/auth/service-keys and/or ZQNT_PLATFORM_PUBLIC_KEY to its SERVICE_AUTH_PUBLIC_KEY "
                    + "(or ZQNT_EDGE_AUTH_DISABLED=true for local SITL).", NOT_CONFIGURED);
            this.keys = null;
            this.verifier = null;
            this.misconfigured = NOT_CONFIGURED;
        } else {
            this.keys = keys;
            this.verifier = new PlatformTokenVerifier(keys);
            this.misconfigured = null;
            keys.start();
        }
    }

    @Override
    public void close() {
        if (keys != null) {
            keys.close();
        }
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        Status refusal = check(call.getMethodDescriptor().getFullMethodName(), headers.get(AUTHORIZATION));
        if (refusal == null) {
            return next.startCall(call, headers);
        }
        call.close(refusal, new Metadata());
        return new ServerCall.Listener<>() { };
    }

    /** {@code null} when the call may proceed, else the status to refuse it with. */
    public Status check(String fullMethodName, String authorization) {
        if (disabled || fullMethodName.startsWith("grpc.health.v1.Health/")) {
            return null;
        }
        if (verifier == null) {
            return Status.UNAUTHENTICATED.withDescription(misconfigured);
        }
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return Status.UNAUTHENTICATED.withDescription("authentication required");
        }
        try {
            verifier.verify(authorization.substring(BEARER.length()));
            return null;
        } catch (PlatformTokenVerifier.EdgeAuthException e) {
            log.warn("Refused platform command {}: {}", fullMethodName, e.getMessage());
            return Status.UNAUTHENTICATED.withDescription(e.getMessage());
        }
    }
}
