package com.zqnt.sdk.client.livestreams.application.impl;

import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.protos.stream.v3.ListLiveStreamsRequest;
import com.zqnt.protos.stream.v3.ListLiveStreamsResponse;
import com.zqnt.protos.stream.v3.LiveStream;
import com.zqnt.protos.stream.v3.LiveStreamServiceGrpc;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import com.zqnt.sdk.client.livestreams.application.LiveStreams;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LiveStreamsImpl implements LiveStreams {

    private final LiveStreamServiceGrpc.LiveStreamServiceStub stub;
    private final GrpcResilience resilience;
    private final int requestTimeoutSeconds;

    private LiveStreamsImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.stub = LiveStreamServiceGrpc.newStub(channel);
        this.resilience = new GrpcResilience(
                config.getMaxRetryAttempts(),
                config.getRetryDelayMillis(),
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerWaitDurationMillis());
        this.requestTimeoutSeconds = config.getRequestTimeoutSeconds();
    }

    public static LiveStreams create(GrpcClientConfig config, ManagedChannel channel) {
        return new LiveStreamsImpl(config, channel);
    }

    @Override
    public CompletableFuture<List<LiveStream>> listLiveStreams() {
        var request = ListLiveStreamsRequest.newBuilder()
                .setContext(RequestContext.newBuilder().setRequestId(UUID.randomUUID().toString()))
                .build();
        try {
            return resilience.<ListLiveStreamsResponse>executeWithResilienceAsync(() -> {
                        CompletableFuture<ListLiveStreamsResponse> future = new CompletableFuture<>();
                        stub.withDeadlineAfter(requestTimeoutSeconds, TimeUnit.SECONDS)
                                .listLiveStreams(request, completing(future));
                        return future;
                    })
                    .thenApply(ListLiveStreamsResponse::getStreamsList)
                    .exceptionallyCompose(failure -> CompletableFuture.failedFuture(CommandException.from(failure)));
        } catch (RuntimeException circuitOpen) {
            return CompletableFuture.failedFuture(CommandException.from(circuitOpen));
        }
    }

    private static <T> StreamObserver<T> completing(CompletableFuture<T> future) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable failure) {
                future.completeExceptionally(failure);
            }

            @Override
            public void onCompleted() {
                if (!future.isDone()) future.completeExceptionally(new IllegalStateException("no response"));
            }
        };
    }
}
