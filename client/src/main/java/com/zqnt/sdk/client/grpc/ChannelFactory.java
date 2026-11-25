package com.zqnt.sdk.client.grpc;

import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import io.grpc.ChannelCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating gRPC channels with load balancing and service discovery support.
 */
@Slf4j
public class ChannelFactory {

    /**
     * Create a managed channel for a service with the given configuration, without a client
     * credential. Core refuses such calls; kept for callers that add their own interceptors.
     */
    public static ManagedChannel createChannel(ServiceConfig config) {
        return createChannel(config, (String) null);
    }

    /**
     * Create a managed channel for a service that sends {@code clientToken} (see
     * {@link ClientCredentials}) on every call. A null token sends none, and refusals still come
     * back with an explanation.
     */
    public static ManagedChannel createChannel(ServiceConfig config, String clientToken) {
        return createChannel(config, clientToken, List.of());
    }

    /**
     * Create a managed channel from the client-wide configuration: its client token and the host
     * application's {@link GrpcClientConfig#getInterceptors() interceptors}.
     */
    public static ManagedChannel createChannel(ServiceConfig config, GrpcClientConfig clientConfig) {
        return createChannel(config, clientConfig.getClientToken(),
                clientConfig.getInterceptors() == null ? List.of() : clientConfig.getInterceptors());
    }

    /**
     * Create a managed channel whose calls pass through {@code interceptors} (in the order given,
     * the first one outermost) and then the SDK's credential interceptor, which adds
     * {@code clientToken} only when none of {@code interceptors} has set {@code authorization}.
     */
    public static ManagedChannel createChannel(ServiceConfig config, String clientToken,
                                               List<? extends ClientInterceptor> interceptors) {
        String target;
        if (config.isUseStork() && config.getStorkServiceName() != null) {
            // Use Stork service discovery
            log.info("Creating channel with Stork service discovery: {}", config.getStorkServiceName());
            target = "stork://" + config.getStorkServiceName();
        } else {
            // Direct connection
            log.info("Creating direct channel: {}:{}", config.getHost(), config.getPort());
            target = config.getHost() + ":" + config.getPort();
        }
        ManagedChannelBuilder<?> channelBuilder = Grpc.newChannelBuilder(target, channelCredentials(config));

        // Configure load balancing
        String loadBalancerPolicy = getLoadBalancerPolicy(config.getLoadBalancerType());
        channelBuilder.defaultLoadBalancingPolicy(loadBalancerPolicy);
        log.info("Load balancer policy: {}", loadBalancerPolicy);

        // Configure keep-alive
        channelBuilder
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(30, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(5, TimeUnit.MINUTES)
                .enableRetry()
                .maxRetryAttempts(5);

        // Configure max inbound message size
        if (config.getMaxInboundMessageSize() > 0) {
            channelBuilder.maxInboundMessageSize(config.getMaxInboundMessageSize());
            log.info("Max inbound message size set to {} bytes for service: {}",
                    config.getMaxInboundMessageSize(), config.getServiceName());
        }

        // gRPC calls the interceptor registered LAST first, so the credential interceptor goes in
        // first (innermost) and the host application's interceptors after it, reversed so that the
        // first one given is the outermost: whatever they put in the headers is there by the time
        // the credential interceptor decides whether to add the client token.
        channelBuilder.intercept(ClientCredentials.interceptor(clientToken));
        List<ClientInterceptor> outermostLast = new ArrayList<>(interceptors);
        Collections.reverse(outermostLast);
        if (!outermostLast.isEmpty()) {
            channelBuilder.intercept(outermostLast);
            log.info("{} host interceptor(s) on the channel for service: {}", outermostLast.size(), config.getServiceName());
        }

        ManagedChannel channel = channelBuilder.build();
        log.info("Channel created successfully for service: {}", config.getServiceName());

        return channel;
    }

    /**
     * The channel's transport: plaintext, TLS against the system trust store, or TLS against the CA
     * in {@link ServiceConfig#getTlsCaFile()} (an installation's internal CA).
     */
    static ChannelCredentials channelCredentials(ServiceConfig config) {
        if (config.isUsePlaintext()) {
            return InsecureChannelCredentials.create();
        }
        if (config.getTlsCaFile() == null || config.getTlsCaFile().isBlank()) {
            return TlsChannelCredentials.create();
        }
        File ca = new File(config.getTlsCaFile().trim());
        try {
            return TlsChannelCredentials.newBuilder().trustManager(ca).build();
        } catch (IOException e) {
            throw new IllegalStateException("TLS for service " + config.getServiceName() + ": the CA file " + ca
                    + " cannot be read: " + e.getMessage(), e);
        }
    }

    private static String getLoadBalancerPolicy(ServiceConfig.LoadBalancerType type) {
        return switch (type) {
            case ROUND_ROBIN -> "round_robin";
            case RANDOM -> "random";
            case LEAST_REQUESTS -> "least_request";
            case POWER_OF_TWO_CHOICES -> "pick_first"; // Fallback
        };
    }
}
