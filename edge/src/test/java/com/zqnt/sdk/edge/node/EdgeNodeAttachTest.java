package com.zqnt.sdk.edge.node;

import com.google.protobuf.Empty;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.edge.v3.EdgeAdapterServiceGrpc;
import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.protos.edge.v3.PublishCommandEventRequest;
import com.zqnt.protos.edge.v3.PublishCommandEventResponse;
import com.zqnt.protos.edge.v3.ReportCapabilitiesRequest;
import com.zqnt.protos.edge.v3.ReportCapabilitiesResponse;
import com.zqnt.protos.telemetry.v3.PublishTelemetryRequest;
import com.zqnt.protos.telemetry.v3.PublishTelemetryResponse;
import com.zqnt.protos.telemetry.v3.TelemetryIngestServiceGrpc;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.sdk.edge.adapter.domains.NotificationRequestData;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterV3GrpcServiceImpl;
import com.zqnt.sdk.edge.adapter.application.RegistryEdgeAdapter;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.registry.CommandRequest;
import com.zqnt.utils.common.proto.AssetTypeEnum;
import com.zqnt.sdk.edge.auth.EdgeAuthConfig;
import com.zqnt.sdk.edge.auth.PlatformAuthServerInterceptor;
import com.zqnt.sdk.edge.gateway.CapabilityReporter;
import com.zqnt.sdk.edge.livedata.application.NotificationMapper;
import com.zqnt.sdk.edge.livedata.application.impl.LiveDataServiceImpl;
import com.zqnt.utils.events.proto.CommandExecutionStatus;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.events.proto.NotificationSeverity;
import com.zqnt.utils.livedata.proto.LiveDataServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.zqnt.sdk.edge.testing.TestGrpc.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An adapter attached to a local Edge Node, against an in-process fake node on a Unix socket: live
 * data and capabilities reach the node with the adapter's socket in the header, a command the node
 * sends over that socket reaches the adapter's handler, and the adapter's events come back.
 */
class EdgeNodeAttachTest {

	private static final String SN = "SN-1";

	@TempDir
	Path dir;

	private final List<TelemetrySample> samples = new CopyOnWriteArrayList<>();
	private final List<CapabilitySet> capabilities = new CopyOnWriteArrayList<>();
	private final List<CommandEvent> events = new CopyOnWriteArrayList<>();
	private final List<String> endpoints = new CopyOnWriteArrayList<>();

	private static final class Drone extends RegistryEdgeAdapter {
		final List<CommandRequest> takeoffs = new CopyOnWriteArrayList<>();

		Drone() {
			super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
			registerCommand("flight.takeoff", Map.of("type", "object"), Map.of(), request -> {
				takeoffs.add(request);
				return CompletableFuture.completedFuture(CommandResult.accepted("climbing", "flight-7", request.sn()));
			});
		}
	}

	private Server node;
	private EventLoopGroup nodeLoop;
	private EdgeNodeConfig config;

	@BeforeEach
	void startNode() throws Exception {
		Path nodeSocket = dir.resolve("node/node.sock");
		Files.createDirectories(nodeSocket.getParent());
		config = new EdgeNodeConfig(nodeSocket, dir.resolve("adapters/acme.sock"));
		nodeLoop = new EpollEventLoopGroup(1);
		node = NettyServerBuilder.forAddress(new DomainSocketAddress(nodeSocket.toString()))
				.channelType(EpollServerDomainSocketChannel.class)
				.bossEventLoopGroup(nodeLoop)
				.workerEventLoopGroup(nodeLoop)
				.addService(ServerInterceptors.intercept(new Ingest(), new RecordEndpoint()))
				.addService(ServerInterceptors.intercept(new Gateway(), new RecordEndpoint()))
				.addService(queueService())
				.build()
				.start();
	}

	@AfterEach
	void stopNode() throws Exception {
		node.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		nodeLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
	}

	@Test
	void anAdapterTalksToTheNodeOnlyAndTheNodeCommandsItOverTheAdaptersSocket() throws Exception {
		Drone drone = new Drone();
		try (EdgeNode attached = EdgeNode.attach(config);
				CapabilityReporter reporter = new CapabilityReporter(drone, attached.gateway())) {
			attached.serve(new EdgeAdapterV3GrpcServiceImpl(drone));
			assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(config.adapterSocket())));

			reporter.track(SN).get(5, TimeUnit.SECONDS);
			assertEquals(SN, capabilities.get(0).getAssetSn());
			assertTrue(capabilities.get(0).getCapabilitiesList().stream().anyMatch(c -> c.getCommandId().equals("flight.takeoff")));
			assertEquals(List.of("unix://" + config.adapterSocket()), endpoints);

			attached.telemetry().publish(TelemetrySample.newBuilder().setAsset(AssetRef.newBuilder().setSn(SN))
					.setBatteryPercent(80).build()).get();
			await(() -> samples.size() == 1);
			assertEquals(80, samples.get(0).getBatteryPercent());
			assertEquals(2, endpoints.size());

			ExecuteCommandResponse response;
			ManagedChannel toAdapter = Grpc.newChannelBuilder(endpoints.get(0), InsecureChannelCredentials.create()).build();
			try {
				response = EdgeAdapterServiceGrpc.newBlockingStub(toAdapter).executeCommand(ExecuteCommandRequest.newBuilder()
						.setCommand(Command.newBuilder().setCommandId("flight.takeoff").setAsset(AssetRef.newBuilder().setSn(SN)))
						.setCommandExecutionId("capexec:run-1:takeoff")
						.build());
			} finally {
				toAdapter.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
			}
			assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
			assertEquals("capexec:run-1:takeoff", drone.takeoffs.get(0).commandExecutionId());

			LiveDataServiceImpl liveData = new LiveDataServiceImpl(null, null, new NotificationMapper(),
					LiveDataServiceGrpc.newStub(attached.channel()), attached.gateway());
			try {
				liveData.produceNotificationData(completed("flight-7")).get(5, TimeUnit.SECONDS);
			} finally {
				liveData.shutdown();
			}
			await(() -> events.size() == 1);
			assertEquals("capexec:run-1:takeoff", events.get(0).getCommandExecutionId());
			assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, events.get(0).getState());

			QueueStats stats = attached.queue().get().get(5, TimeUnit.SECONDS);
			assertFalse(stats.connected());
			assertEquals(0.3, stats.throttle("LANE_TELEMETRY"));
			assertEquals(1.0, stats.throttle("LANE_ALARM"));
			QueueStats.Lane lane = stats.lane("LANE_TELEMETRY").orElseThrow();
			assertEquals(900, lane.items());
			assertEquals(3, lane.dropped());
			assertNotNull(lane.oldest());

			List<Double> watched = new CopyOnWriteArrayList<>();
			try (AutoCloseable ignored = attached.queue().watch(s -> watched.add(s.throttle("LANE_TELEMETRY")), e -> { })) {
				await(() -> watched.size() == 2);
			}
			assertEquals(List.of(0.3, 1.0), watched);
			assertEquals(1.0, attached.queue().latest().throttle("LANE_TELEMETRY"));
		}
		assertFalse(Files.exists(config.adapterSocket()), "the adapter socket is removed on close");
	}

	@Test
	void aFileInPlaceOfTheAdapterSocketIsNotReplaced() throws Exception {
		Files.createDirectories(config.adapterSocket().getParent());
		Files.writeString(config.adapterSocket(), "keep");
		try (EdgeNode attached = EdgeNode.attach(config)) {
			assertThrows(java.io.IOException.class, attached::serve);
		}
		assertEquals("keep", Files.readString(config.adapterSocket()));
	}

	@Test
	void theConfigurationComesFromTheEnvironment() {
		assertTrue(EdgeNodeConfig.fromEnv(Map.of()).isEmpty());
		EdgeNodeConfig fromEnv = EdgeNodeConfig.fromEnv(Map.of(
				EdgeNodeConfig.NODE_SOCKET_ENV, "/run/zqnt-edge-node/node.sock",
				EdgeNodeConfig.ADAPTER_SOCKET_ENV, "/run/zqnt-adapter/acme.sock")).orElseThrow();
		assertEquals("unix:///run/zqnt-adapter/acme.sock", fromEnv.adapterEndpoint());
		assertEquals(0660, fromEnv.socketMode());
		assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.fromEnv(Map.of(
				EdgeNodeConfig.NODE_SOCKET_ENV, "/run/zqnt-edge-node/node.sock")));
		assertThrows(IllegalArgumentException.class, () -> new EdgeNodeConfig(Path.of("/n.sock"), Path.of("/a.sock"), 0666));
	}

	@Test
	void onlyAnAttachedAdapterSkipsThePlatformTokenCheck() {
		String method = "zqnt.edge.v3.EdgeAdapterService/ExecuteCommand";
		try (var direct = new PlatformAuthServerInterceptor(new EdgeAuthConfig(null, null, null, false));
				var attached = new PlatformAuthServerInterceptor(new EdgeAuthConfig(null, null, null, false, true))) {
			assertEquals(Status.Code.UNAUTHENTICATED, direct.check(method, null).getCode());
			assertNull(attached.check(method, null));
		}
	}

	private static NotificationRequestData completed(String externalId) {
		return NotificationRequestData.builder()
				.sn(SN)
				.severity(NotificationSeverity.NOTIFICATION_SEVERITY_INFO)
				.eventType(NotificationEventType.NOTIFICATION_EVENT_COMMAND_EXECUTION)
				.commandExecutionEvent(NotificationRequestData.CommandExecutionEventData.builder()
						.externalExecutionId(externalId)
						.commandId("flight.takeoff")
						.status(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_SUCCEEDED)
						.assetSn(SN)
						.build())
				.build();
	}

	private final class RecordEndpoint implements ServerInterceptor {
		@Override
		public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
				ServerCallHandler<ReqT, RespT> next) {
			String endpoint = headers.get(AdapterEndpointClientInterceptor.ADAPTER_ENDPOINT);
			if (!call.getMethodDescriptor().getFullMethodName().endsWith("PublishCommandEvent")) endpoints.add(endpoint);
			return next.startCall(call, headers);
		}
	}

	private final class Ingest extends TelemetryIngestServiceGrpc.TelemetryIngestServiceImplBase {
		@Override
		public StreamObserver<PublishTelemetryRequest> publishTelemetry(StreamObserver<PublishTelemetryResponse> observer) {
			return new StreamObserver<>() {
				@Override
				public void onNext(PublishTelemetryRequest request) {
					samples.add(request.getSample());
				}

				@Override
				public void onError(Throwable t) {
				}

				@Override
				public void onCompleted() {
					observer.onNext(PublishTelemetryResponse.newBuilder().setAccepted(samples.size()).build());
					observer.onCompleted();
				}
			};
		}
	}

	private final class Gateway extends EdgeGatewayServiceGrpc.EdgeGatewayServiceImplBase {
		@Override
		public void reportCapabilities(ReportCapabilitiesRequest request, StreamObserver<ReportCapabilitiesResponse> observer) {
			capabilities.add(request.getCapabilities());
			observer.onNext(ReportCapabilitiesResponse.newBuilder().setAcceptedRevision(request.getCapabilities().getRevision()).build());
			observer.onCompleted();
		}

		@Override
		public void publishCommandEvent(PublishCommandEventRequest request, StreamObserver<PublishCommandEventResponse> observer) {
			events.add(request.getEvent());
			observer.onNext(PublishCommandEventResponse.getDefaultInstance());
			observer.onCompleted();
		}
	}

	private static ServerServiceDefinition queueService() {
		return ServerServiceDefinition.builder(NodeQueueClient.SERVICE)
				.addMethod(method("GetQueueStats", MethodDescriptor.MethodType.UNARY),
						ServerCalls.asyncUnaryCall((Empty request, StreamObserver<Struct> observer) -> {
							observer.onNext(stats(0.3));
							observer.onCompleted();
						}))
				.addMethod(method("WatchQueueStats", MethodDescriptor.MethodType.SERVER_STREAMING),
						ServerCalls.asyncServerStreamingCall((Empty request, StreamObserver<Struct> observer) -> {
							observer.onNext(stats(0.3));
							observer.onNext(stats(1.0));
						}))
				.build();
	}

	private static MethodDescriptor<Empty, Struct> method(String name, MethodDescriptor.MethodType type) {
		return MethodDescriptor.<Empty, Struct>newBuilder()
				.setType(type)
				.setFullMethodName(MethodDescriptor.generateFullMethodName(NodeQueueClient.SERVICE, name))
				.setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
				.setResponseMarshaller(ProtoUtils.marshaller(Struct.getDefaultInstance()))
				.build();
	}

	private static Struct stats(double throttle) {
		List<Value> lanes = new ArrayList<>();
		lanes.add(Value.newBuilder().setStructValue(Struct.newBuilder()
				.putFields("lane", Value.newBuilder().setStringValue("LANE_TELEMETRY").build())
				.putFields("items", number(900))
				.putFields("bytes", number(4096))
				.putFields("max_items", number(1000))
				.putFields("max_bytes", number(0))
				.putFields("fill", number(0.9))
				.putFields("dropped", number(3))
				.putFields("oldest", Value.newBuilder().setStringValue("2026-11-02T10:00:00.123456789Z").build())
				.putFields("throttle", number(throttle))).build());
		return Struct.newBuilder()
				.putFields("connected", Value.newBuilder().setBoolValue(false).build())
				.putFields("lanes", Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(lanes)).build())
				.build();
	}

	private static Value number(double value) {
		return Value.newBuilder().setNumberValue(value).build();
	}
}
