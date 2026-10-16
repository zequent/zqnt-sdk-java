package com.zqnt.sdk.client.streams.application.impl;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.stream.v3.ListLiveStreamsRequest;
import com.zqnt.protos.stream.v3.ListLiveStreamsResponse;
import com.zqnt.protos.stream.v3.LiveStream;
import com.zqnt.protos.stream.v3.LiveStreamServiceGrpc;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.streams.application.LiveStreams;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveStreamsImplTest {

    private final AtomicReference<ListLiveStreamsRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<Status> refuseWith = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FakeLiveStreamService())
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void listsTheLiveStreamsOfTheOrganization() {
        List<LiveStream> streams = liveStreams().listLiveStreams().join();

        assertEquals(1, streams.size());
        assertEquals("DRONE-1", streams.get(0).getAsset().getSn());
        assertEquals("live/DRONE-1/drone", streams.get(0).getPath());
        assertEquals(1_700_000_000L, streams.get(0).getSince().getSeconds());
        assertEquals(2, streams.get(0).getViewers());
        assertFalse(lastRequest.get().getContext().getRequestId().isBlank());
    }

    @Test
    void noAnswerFromTheMediaServerFailsWithARetryableServiceError() {
        refuseWith.set(Status.UNAVAILABLE.withDescription("media server did not answer"));

        CompletionException failure = assertThrows(CompletionException.class, () -> liveStreams().listLiveStreams().join());

        CommandException unavailable = assertInstanceOf(CommandException.class, failure.getCause());
        assertEquals(ErrorCategory.ERROR_CATEGORY_SERVICE, unavailable.getCategory());
        assertEquals(Status.Code.UNAVAILABLE, unavailable.getStatus());
        assertTrue(unavailable.isRetryable());
        assertEquals("media server did not answer", unavailable.getMessage());
    }

    @Test
    void aRefusedCallFailsWithItsCategory() {
        refuseWith.set(Status.PERMISSION_DENIED.withDescription("not licensed"));

        CompletionException failure = assertThrows(CompletionException.class, () -> liveStreams().listLiveStreams().join());

        CommandException refused = assertInstanceOf(CommandException.class, failure.getCause());
        assertEquals(ErrorCategory.ERROR_CATEGORY_PERMISSION_DENIED, refused.getCategory());
        assertFalse(refused.isRetryable());
    }

    private LiveStreams liveStreams() {
        return LiveStreamsImpl.create(GrpcClientConfig.builder()
                .liveDataConfig(ServiceConfig.builder().serviceName("live-data").host("in-process").port(0).build())
                .requestTimeoutSeconds(5)
                .build(), channel);
    }

    private final class FakeLiveStreamService extends LiveStreamServiceGrpc.LiveStreamServiceImplBase {

        @Override
        public void listLiveStreams(ListLiveStreamsRequest request, StreamObserver<ListLiveStreamsResponse> observer) {
            lastRequest.set(request);
            Status status = refuseWith.get();
            if (status != null) {
                observer.onError(status.asRuntimeException());
                return;
            }
            observer.onNext(ListLiveStreamsResponse.newBuilder().addStreams(LiveStream.newBuilder()
                    .setAsset(AssetRef.newBuilder().setSn("DRONE-1"))
                    .setPath("live/DRONE-1/drone")
                    .setSince(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setViewers(2)).build());
            observer.onCompleted();
        }
    }
}
