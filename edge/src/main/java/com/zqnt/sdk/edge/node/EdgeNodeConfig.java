package com.zqnt.sdk.edge.node;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where a local Edge Node and this adapter listen, when the adapter attaches to the node instead of
 * the platform (see {@link EdgeNode}).
 *
 * @param nodeSocket    the node's socket ({@code ZQNT_EDGE_NODE_SOCKET}), e.g. {@code /run/zqnt-edge-node/node.sock}
 * @param adapterSocket this adapter's socket ({@code ZQNT_ADAPTER_SOCKET}), e.g. {@code /run/zqnt-adapter/dji.sock}
 * @param socketMode    permission bits of the adapter socket, owner and group only ({@link #DEFAULT_SOCKET_MODE})
 */
public record EdgeNodeConfig(Path nodeSocket, Path adapterSocket, int socketMode) {

	public static final String NODE_SOCKET_ENV = "ZQNT_EDGE_NODE_SOCKET";
	public static final String ADAPTER_SOCKET_ENV = "ZQNT_ADAPTER_SOCKET";
	public static final int DEFAULT_SOCKET_MODE = 0660;

	public EdgeNodeConfig {
		if (nodeSocket == null || adapterSocket == null) {
			throw new IllegalArgumentException("An Edge Node attach needs both " + NODE_SOCKET_ENV + " and " + ADAPTER_SOCKET_ENV);
		}
		if ((socketMode & 07) != 0 || (socketMode & ~0777) != 0) {
			throw new IllegalArgumentException("Socket mode " + Integer.toOctalString(socketMode) + ": only owner and group may have access");
		}
		nodeSocket = nodeSocket.toAbsolutePath().normalize();
		adapterSocket = adapterSocket.toAbsolutePath().normalize();
	}

	public EdgeNodeConfig(Path nodeSocket, Path adapterSocket) {
		this(nodeSocket, adapterSocket, DEFAULT_SOCKET_MODE);
	}

	/** The attach from the environment; empty when neither socket is set (direct mode). */
	public static Optional<EdgeNodeConfig> fromEnv() {
		return fromEnv(System.getenv());
	}

	public static Optional<EdgeNodeConfig> fromEnv(Map<String, String> env) {
		String node = blankToNull(env.get(NODE_SOCKET_ENV));
		String adapter = blankToNull(env.get(ADAPTER_SOCKET_ENV));
		if (node == null && adapter == null) {
			return Optional.empty();
		}
		return Optional.of(new EdgeNodeConfig(node == null ? null : Path.of(node), adapter == null ? null : Path.of(adapter)));
	}

	/** The gRPC target of the node socket. */
	public String nodeTarget() {
		return "unix://" + nodeSocket;
	}

	/** This adapter's socket as the node dials it, the {@code zqnt-adapter-endpoint} header: {@code unix:///abs/path}. */
	public String adapterEndpoint() {
		return "unix://" + adapterSocket;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
