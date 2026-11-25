package com.zqnt.sdk.edge.node;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The adapter's gRPC server on its Unix socket. The socket gets its mode before it appears at its
 * path: it is bound in a fresh private directory next to it and moved into place, so no other user
 * can connect in between. A stale socket at the path is replaced; any other file there is an error.
 * Kept apart from {@link EdgeNode} so the node's clients work without grpc-netty-shaded.
 */
final class EdgeNodeServer implements AutoCloseable {

	private static final PosixFilePermission[] BITS = {
			PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
			PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
			PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};

	private final Server server;
	private final EventLoopGroup group;
	private final Path path;

	private EdgeNodeServer(Server server, EventLoopGroup group, Path path) {
		this.server = server;
		this.group = group;
		this.path = path;
	}

	static EdgeNodeServer start(EdgeNodeConfig config, BindableService... services) throws IOException {
		Path path = config.adapterSocket();
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !isSocket(path)) {
			throw new IOException(path + " exists and is not a socket");
		}
		Files.createDirectories(path.getParent());
		Path privateDir = Files.createTempDirectory(path.getParent(), ".zqnt-sock-",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		Path bound = privateDir.resolve("s");
		EventLoopGroup group = new EpollEventLoopGroup(1);
		NettyServerBuilder builder = NettyServerBuilder.forAddress(new DomainSocketAddress(bound.toString()))
				.channelType(EpollServerDomainSocketChannel.class)
				.bossEventLoopGroup(group)
				.workerEventLoopGroup(group);
		for (BindableService service : services) {
			builder.addService(service);
		}
		Server server = builder.build();
		try {
			server.start();
			Files.setPosixFilePermissions(bound, permissions(config.socketMode()));
			Files.move(bound, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			server.shutdownNow();
			group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
			throw e;
		} finally {
			Files.deleteIfExists(privateDir);
		}
		return new EdgeNodeServer(server, group, path);
	}

	Server server() {
		return server;
	}

	@Override
	public void close() {
		server.shutdown();
		try {
			if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow();
		} catch (InterruptedException e) {
			server.shutdownNow();
			Thread.currentThread().interrupt();
		}
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		try {
			if (isSocket(path)) Files.deleteIfExists(path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static boolean isSocket(Path path) throws IOException {
		return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
				&& Files.readAttributes(path, "unix:mode", LinkOption.NOFOLLOW_LINKS).get("mode") instanceof Integer mode
				&& (mode & 0170000) == 0140000;
	}

	private static Set<PosixFilePermission> permissions(int mode) {
		Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
		for (int i = 0; i < BITS.length; i++) {
			if ((mode & (1 << i)) != 0) permissions.add(BITS[i]);
		}
		return permissions;
	}
}
