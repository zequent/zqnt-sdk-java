package com.zqnt.sdk.client.livestreams.application.impl;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.stream.v3.ListLiveStreamsRequest;
import com.zqnt.protos.stream.v3.ListLiveStreamsResponse;
import com.zqnt.protos.stream.v3.LiveStream;
import com.zqnt.protos.stream.v3.LiveStreamServiceGrpc;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.livestreams.application.LiveStreams;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveStreamsImplTest {

    private final AtomicReference<ListLiveStreamsRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<Status> refuseWith = new AtomicReference<>();
    private final List<LiveStream> live = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;
    private LiveStreams liveStreams;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FakeLiveStreamService())
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        liveStreams = LiveStreamsImpl.create(GrpcClientConfig.builder()
                .liveDataConfig(ServiceConfig.builder().serviceName("live-data").host("in-process").port(0).build())
                .maxRetryAttempts(0)
                .requestTimeoutSeconds(5)
                .build(), channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void listsTheLiveStreamsOfTheOrganization() {
        LiveStream stream = LiveStream.newBuilder()
                .setAsset(AssetRef.newBuilder().setSn("DRONE-1"))
                .setPath("live/DOCK-1/drone")
                .setSince(Timestamp.newBuilder().setSeconds(1_792_152_000L))
                .setViewers(2)
                .build();
        live.add(stream);

        List<LiveStream> streams = liveStreams.listLiveStreams().join();

        assertEquals(List.of(stream), streams);
        assertFalse(lastRequest.get().getContext().getRequestId().isBlank());
    }

    @Test
    void nothingLiveIsAnEmptyList() {
        assertEquals(List.of(), liveStreams.listLiveStreams().join());
    }

    @Test
    void aMissingLicenseFeatureFailsWithPermissionDenied() {
        refuseWith.set(Status.PERMISSION_DENIED.withDescription("license has no live-stream feature"));

        CommandException failure = failureOf(liveStreams);

        assertEquals(Status.Code.PERMISSION_DENIED, failure.getStatus());
        assertFalse(failure.isRetryable());
    }

    @Test
    void noFreshAnswerFromTheMediaServerFailsWithUnavailable() {
        refuseWith.set(Status.UNAVAILABLE.withDescription("media server not answering"));

        CommandException failure = failureOf(liveStreams);

        assertEquals(Status.Code.UNAVAILABLE, failure.getStatus());
        assertTrue(failure.isRetryable());
    }

    private static CommandException failureOf(LiveStreams liveStreams) {
        CompletionException thrown = assertThrows(CompletionException.class, () -> liveStreams.listLiveStreams().join());
        return assertInstanceOf(CommandException.class, thrown.getCause());
    }

    private final class FakeLiveStreamService extends LiveStreamServiceGrpc.LiveStreamServiceImplBase {
        @Override
        public void listLiveStreams(ListLiveStreamsRequest request, StreamObserver<ListLiveStreamsResponse> responses) {
            lastRequest.set(request);
            Status status = refuseWith.get();
            if (status != null) {
                responses.onError(status.asRuntimeException());
                return;
            }
            responses.onNext(ListLiveStreamsResponse.newBuilder().addAllStreams(live).build());
            responses.onCompleted();
        }
    }
}
