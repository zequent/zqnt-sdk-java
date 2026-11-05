package com.zqnt.sdk.client.grpc;

import com.zqnt.protos.control.v3.GetCapabilitiesRequest;
import com.zqnt.protos.control.v3.GetCapabilitiesResponse;
import com.zqnt.protos.control.v3.RemoteControlServiceGrpc;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.testing.TestPki;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static io.grpc.Status.Code.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A platform behind TLS from an installation's CA, dialled with {@code usePlaintext=false} and the CA file. */
class ChannelFactoryTlsTest {

    @TempDir
    static Path dir;
    static TestPki pki;
    static TestPki other;
    static Server server;

    @BeforeAll
    static void serveOverTls() throws Exception {
        pki = TestPki.generate(Files.createDirectories(dir.resolve("installation")));
        other = TestPki.generate(Files.createDirectories(dir.resolve("other")));
        server = Grpc.newServerBuilderForPort(0, TlsServerCredentials.create(pki.cert().toFile(), pki.key().toFile()))
                .addService(new RemoteControlServiceGrpc.RemoteControlServiceImplBase() {
                    @Override
                    public void getCapabilities(GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> observer) {
                        observer.onNext(GetCapabilitiesResponse.getDefaultInstance());
                        observer.onCompleted();
                    }
                })
                .build().start();
    }

    @AfterAll
    static void stop() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void tlsWithTheInstallationCaReachesTheService() throws Exception {
        ManagedChannel channel = ChannelFactory.createChannel(config(pki.ca()), "token");
        try {
            assertNotNull(stub(channel).getCapabilities(GetCapabilitiesRequest.getDefaultInstance()));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anotherCaIsNotTrusted() throws Exception {
        ManagedChannel channel = ChannelFactory.createChannel(config(other.ca()), "token");
        try {
            StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                    () -> stub(channel).getCapabilities(GetCapabilitiesRequest.getDefaultInstance()));
            assertEquals(UNAVAILABLE, failure.getStatus().getCode(), failure.getMessage());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void plaintextCannotTalkToTheTlsService() throws Exception {
        ManagedChannel channel = ChannelFactory.createChannel(config(null).toBuilder().usePlaintext(true).build(), "token");
        try {
            StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                    () -> stub(channel).getCapabilities(GetCapabilitiesRequest.getDefaultInstance()));
            assertEquals(UNAVAILABLE, failure.getStatus().getCode(), failure.getMessage());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aMissingCaFileIsAnErrorNotPlaintext() {
        ServiceConfig broken = config(dir.resolve("missing").resolve("ca.crt"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ChannelFactory.channelCredentials(broken));
        assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
    }

    private static ServiceConfig config(Path ca) {
        return ServiceConfig.builder()
                .serviceName("remote-control")
                .host("localhost")
                .port(server.getPort())
                .usePlaintext(false)
                .tlsCaFile(ca == null ? null : ca.toString())
                .build();
    }

    private static RemoteControlServiceGrpc.RemoteControlServiceBlockingStub stub(ManagedChannel channel) {
        return RemoteControlServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }
}
