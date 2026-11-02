package com.zqnt.sdk.edge.node;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Reads the Edge Node's queue ({@code zqnt.edgenode.local.v1.NodeQueueService}): how full each lane
 * is, whether the node has a link to the platform, and the throttle the adapter should apply.
 * <pre>{@code
 * double rate = normalRate * node.queue().get().join().throttle("LANE_TELEMETRY");
 * AutoCloseable watch = node.queue().watch(stats -> limiter.setRate(normalRate * stats.throttle("LANE_TELEMETRY")));
 * }</pre>
 */
public class NodeQueueClient {

	public static final String SERVICE = "zqnt.edgenode.local.v1.NodeQueueService";

	private static final MethodDescriptor<Empty, Struct> GET = method("GetQueueStats", MethodDescriptor.MethodType.UNARY);
	private static final MethodDescriptor<Empty, Struct> WATCH = method("WatchQueueStats", MethodDescriptor.MethodType.SERVER_STREAMING);

	private final Channel channel;
	private volatile QueueStats latest;

	public NodeQueueClient(Channel nodeChannel) {
		this.channel = nodeChannel;
	}

	/** The stats last read by {@link #get()} or {@link #watch}; null before the first. */
	public QueueStats latest() {
		return latest;
	}

	public CompletableFuture<QueueStats> get() {
		CompletableFuture<QueueStats> result = new CompletableFuture<>();
		ClientCalls.asyncUnaryCall(channel.newCall(GET, CallOptions.DEFAULT), Empty.getDefaultInstance(),
				observer(result::complete, result::completeExceptionally, () -> { }));
		return result;
	}

	/**
	 * Calls {@code listener} with the stats at once, then whenever a lane's throttle or the link changes
	 * (at least every 10 s), until the returned handle is closed or the stream fails ({@code onError}).
	 */
	public AutoCloseable watch(Consumer<QueueStats> listener, Consumer<Throwable> onError) {
		Context.CancellableContext context = Context.current().withCancellation();
		context.run(() -> ClientCalls.asyncServerStreamingCall(channel.newCall(WATCH, CallOptions.DEFAULT),
				Empty.getDefaultInstance(), observer(listener, onError, () -> { })));
		return () -> context.cancel(null);
	}

	private StreamObserver<Struct> observer(Consumer<QueueStats> next, Consumer<Throwable> error, Runnable completed) {
		return new StreamObserver<>() {
			@Override
			public void onNext(Struct value) {
				QueueStats stats = QueueStats.fromStruct(value);
				latest = stats;
				next.accept(stats);
			}

			@Override
			public void onError(Throwable t) {
				error.accept(t);
			}

			@Override
			public void onCompleted() {
				completed.run();
			}
		};
	}

	private static MethodDescriptor<Empty, Struct> method(String name, MethodDescriptor.MethodType type) {
		return MethodDescriptor.<Empty, Struct>newBuilder()
				.setType(type)
				.setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
				.setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
				.setResponseMarshaller(ProtoUtils.marshaller(Struct.getDefaultInstance()))
				.build();
	}
}
