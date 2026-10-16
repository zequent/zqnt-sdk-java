package com.zqnt.sdk.client.streams.application.impl;

import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.protos.stream.v3.ListLiveStreamsRequest;
import com.zqnt.protos.stream.v3.ListLiveStreamsResponse;
import com.zqnt.protos.stream.v3.LiveStream;
import com.zqnt.protos.stream.v3.LiveStreamServiceGrpc;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.streams.application.LiveStreams;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LiveStreamsImpl implements LiveStreams {

    private final LiveStreamServiceGrpc.LiveStreamServiceStub stub;
    private final int requestTimeoutSeconds;

    private LiveStreamsImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.stub = LiveStreamServiceGrpc.newStub(channel);
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
        CompletableFuture<List<LiveStream>> future = new CompletableFuture<>();
        try {
            stub.withDeadlineAfter(requestTimeoutSeconds, TimeUnit.SECONDS).listLiveStreams(request, new StreamObserver<>() {
                @Override
                public void onNext(ListLiveStreamsResponse response) {
                    future.complete(response.getStreamsList());
                }

                @Override
                public void onError(Throwable failure) {
                    future.completeExceptionally(CommandException.from(failure));
                }

                @Override
                public void onCompleted() {
                    if (!future.isDone()) future.completeExceptionally(CommandException.from(new IllegalStateException("no response")));
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(CommandException.from(failure));
        }
        return future;
    }
}
