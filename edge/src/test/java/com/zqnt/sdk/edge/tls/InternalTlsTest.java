package com.zqnt.sdk.edge.tls;

import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.protos.edge.v3.PublishCommandEventRequest;
import com.zqnt.protos.edge.v3.PublishCommandEventResponse;
import com.zqnt.sdk.edge.auth.PlatformKeySet;
import com.zqnt.sdk.edge.testing.TestPki;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.grpc.Status.Code.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform behind TLS from the installation's CA, as an adapter dials it with {@link InternalTls}. */
class InternalTlsTest {

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
				.addService(new EdgeGatewayServiceGrpc.EdgeGatewayServiceImplBase() {
					@Override
					public void publishCommandEvent(PublishCommandEventRequest request,
							StreamObserver<PublishCommandEventResponse> observer) {
						observer.onNext(PublishCommandEventResponse.getDefaultInstance());
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
	void aChannelWithTheInstallationCaReachesTheService() throws Exception {
		ManagedChannel channel = InternalTls.withCa(pki.ca()).channelBuilder("localhost", server.getPort()).build();
		try {
			assertNotNull(stub(channel).publishCommandEvent(PublishCommandEventRequest.getDefaultInstance()));
		} finally {
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void aPlaintextChannelCannotTalkToTheTlsService() throws Exception {
		ManagedChannel channel = InternalTls.off().channelBuilder("localhost", server.getPort()).build();
		try {
			StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
					() -> stub(channel).publishCommandEvent(PublishCommandEventRequest.getDefaultInstance()));
			assertEquals(UNAVAILABLE, failure.getStatus().getCode(), failure.getMessage());
		} finally {
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void anotherCaIsNotTrusted() throws Exception {
		ManagedChannel channel = InternalTls.withCa(other.ca()).channelBuilder("localhost", server.getPort()).build();
		try {
			StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
					() -> stub(channel).publishCommandEvent(PublishCommandEventRequest.getDefaultInstance()));
			assertEquals(UNAVAILABLE, failure.getStatus().getCode(), failure.getMessage());
		} finally {
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void theEnvironmentSwitchesItOn() {
		assertFalse(InternalTls.fromEnv(name -> null).enabled(), "plaintext by default");
		assertEquals(InternalTls.DEFAULT_CA, InternalTls.fromEnv(name -> null).caFile());
		InternalTls on = InternalTls.fromEnv(Map.of("ZQNT_INTERNAL_TLS", " True ", "ZQNT_INTERNAL_TLS_CA", "/tmp/ca.crt")::get);
		assertTrue(on.enabled());
		assertEquals(Path.of("/tmp/ca.crt"), on.caFile());
		assertTrue(InternalTls.fromEnv(Map.of("ZQNT_INTERNAL_TLS", "1")::get).enabled());
		assertFalse(InternalTls.fromEnv(Map.of("ZQNT_INTERNAL_TLS", "maybe")::get).enabled());
	}

	@Test
	void aMissingCaFileIsAnErrorNotPlaintext() {
		InternalTls broken = InternalTls.withCa(dir.resolve("missing").resolve("ca.crt"));
		IllegalStateException failure = assertThrows(IllegalStateException.class, broken::channelCredentials);
		assertTrue(failure.getMessage().contains("ZQNT_INTERNAL_TLS_CA"), failure.getMessage());
		assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
		assertThrows(IllegalStateException.class, broken::sslContext);
	}

	@Test
	void anHttpsKeySetIsVerifiedAgainstTheCa() throws Exception {
		assertNotNull(InternalTls.withCa(pki.ca()).sslContext().getSocketFactory());
		try (PlatformKeySet keys = new PlatformKeySet(null, "https://localhost:1/keys", InternalTls.withCa(pki.ca()))) {
			assertTrue(keys.hasKeysUrl());
		}
	}

	private static EdgeGatewayServiceGrpc.EdgeGatewayServiceBlockingStub stub(ManagedChannel channel) {
		return EdgeGatewayServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
	}
}
