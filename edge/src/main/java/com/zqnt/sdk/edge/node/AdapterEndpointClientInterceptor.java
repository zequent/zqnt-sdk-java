package com.zqnt.sdk.edge.node;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** Names the adapter's own socket on every call to the Edge Node, so the node knows where to send commands. */
public class AdapterEndpointClientInterceptor implements ClientInterceptor {

	public static final Metadata.Key<String> ADAPTER_ENDPOINT =
			Metadata.Key.of("zqnt-adapter-endpoint", Metadata.ASCII_STRING_MARSHALLER);

	private final String endpoint;

	public AdapterEndpointClientInterceptor(String endpoint) {
		this.endpoint = endpoint;
	}

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions, Channel next) {
		return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
			@Override
			public void start(Listener<RespT> responseListener, Metadata headers) {
				headers.put(ADAPTER_ENDPOINT, endpoint);
				super.start(responseListener, headers);
			}
		};
	}
}
