package com.zqnt.sdk.edge.node;

import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.sdk.edge.gateway.EdgeGatewayClient;
import com.zqnt.sdk.edge.gateway.V3Availability;
import com.zqnt.sdk.edge.telemetry.TelemetryPublisher;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * An adapter attached to a local Edge Node ({@code zqnt-edge-node}) instead of the platform. The node
 * runs next to the adapters, owns the link to the platform, queues live data while the link is down
 * and runs the platform's commands on the adapters. The adapter talks to the node only, over two
 * Unix sockets:
 * <ul>
 *   <li>the node socket: {@link #telemetry()} ({@code TelemetryIngestService}), {@link #gateway()}
 *   ({@code EdgeGatewayService}: capabilities, command events) and {@link #queue()}
 *   ({@code NodeQueueService}), each call naming the adapter's socket in the
 *   {@code zqnt-adapter-endpoint} header;</li>
 *   <li>the adapter socket: {@link #serve} serves the adapter's {@code EdgeAdapterService} there with
 *   mode 0660, and the node calls it for every command.</li>
 * </ul>
 * Attached, the adapter has no access to core (no Redis, no v2 fallback) and does not verify the
 * platform's token on commands, because the node does not sign them yet (zqnt-api#40): the socket's
 * permissions are the access control. {@link com.zqnt.sdk.edge.auth.EdgeAuthConfig#fromEnv()} turns
 * the check off in this mode only.
 * <pre>{@code
 * try (EdgeNode node = EdgeNode.attach(EdgeNodeConfig.fromEnv().orElseThrow())) {
 *     node.serve(new EdgeAdapterGrpcServiceImpl(adapter), new EdgeAdapterV3GrpcServiceImpl(adapter));
 *     new CapabilityReporter(adapter, node.gateway()).track(sn);
 *     node.telemetry().publish(sample);
 * }
 * }</pre>
 * Serving needs grpc-netty-shaded (epoll, Linux) on the classpath; an adapter with its own gRPC server
 * (e.g. Quarkus on a domain socket) uses only the clients and {@link #warnUnverifiedCommands()}.
 */
@Slf4j
public class EdgeNode implements AutoCloseable {

	private final EdgeNodeConfig config;
	private final ManagedChannel channel;
	private final TelemetryPublisher telemetry;
	private final EdgeGatewayClient gateway;
	private final NodeQueueClient queue;
	private EdgeNodeServer server;

	private EdgeNode(EdgeNodeConfig config) {
		this.config = config;
		this.channel = Grpc.newChannelBuilder(config.nodeTarget(), InsecureChannelCredentials.create())
				.intercept(new AdapterEndpointClientInterceptor(config.adapterEndpoint()))
				.build();
		this.telemetry = new TelemetryPublisher(channel, null);
		this.gateway = new EdgeGatewayClient(EdgeGatewayServiceGrpc.newStub(channel), RemoteControlServiceGrpc.newStub(channel),
				new V3Availability("zqnt.edge.v3.EdgeGatewayService on the Edge Node"),
				(sn, current, revision) -> CompletableFuture.failedFuture(
						new IllegalStateException("The Edge Node serves v3 capability reports only")));
		this.queue = new NodeQueueClient(channel);
	}

	public static EdgeNode attach(EdgeNodeConfig config) {
		return new EdgeNode(config);
	}

	public EdgeNodeConfig config() {
		return config;
	}

	/** The channel to the node socket, naming this adapter's socket on every call. */
	public ManagedChannel channel() {
		return channel;
	}

	/** Live data into the node's queue (v3 only). */
	public TelemetryPublisher telemetry() {
		return telemetry;
	}

	/** Capability reports and command events to the node (v3 only). */
	public EdgeGatewayClient gateway() {
		return gateway;
	}

	public NodeQueueClient queue() {
		return queue;
	}

	/**
	 * Serves {@code services} (the adapter's v2 and v3 {@code EdgeAdapterService}) on the adapter socket
	 * with its mode (see {@link EdgeNodeServer}). Needs grpc-netty-shaded on the classpath.
	 */
	public synchronized Server serve(BindableService... services) throws IOException {
		if (server != null) {
			throw new IllegalStateException("Already serving on " + config.adapterSocket());
		}
		server = EdgeNodeServer.start(config, services);
		warnUnverifiedCommands();
		log.info("EdgeAdapterService listening on unix:{} for the Edge Node at {}", config.adapterSocket(), config.nodeSocket());
		return server.server();
	}

	/** Logs that commands on the adapter socket are not verified; {@link #serve} does it itself. */
	public void warnUnverifiedCommands() {
		log.warn("Attached to the Edge Node at {}: commands on {} are NOT verified -- the node does not sign them yet "
				+ "(zqnt-api#40). The socket's permissions ({}) are the only access control.", config.nodeSocket(),
				config.adapterSocket(), Integer.toOctalString(config.socketMode()));
	}

	@Override
	public synchronized void close() {
		telemetry.close();
		if (server != null) {
			server.close();
			server = null;
		}
		channel.shutdownNow();
	}
}
