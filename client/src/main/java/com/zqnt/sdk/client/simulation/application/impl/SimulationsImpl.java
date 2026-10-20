package com.zqnt.sdk.client.simulation.application.impl;

import com.zqnt.protos.application_simulation.v3.CancelSimulationRequest;
import com.zqnt.protos.application_simulation.v3.CancelSimulationResponse;
import com.zqnt.protos.application_simulation.v3.GetSimulationTraceRequest;
import com.zqnt.protos.application_simulation.v3.GetSimulationTraceResponse;
import com.zqnt.protos.application_simulation.v3.SimulationServiceGrpc;
import com.zqnt.protos.application_simulation.v3.SimulationStatus;
import com.zqnt.protos.application_simulation.v3.SimulationTrace;
import com.zqnt.protos.application_simulation.v3.StartSimulationRequest;
import com.zqnt.protos.application_simulation.v3.StartSimulationResponse;
import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.grpc.GrpcResilience;
import com.zqnt.sdk.client.simulation.application.Simulations;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class SimulationsImpl implements Simulations {

    private final SimulationServiceGrpc.SimulationServiceStub stub;
    private final GrpcResilience resilience;
    private final int requestTimeoutSeconds;

    private SimulationsImpl(GrpcClientConfig config, ManagedChannel channel) {
        this.stub = SimulationServiceGrpc.newStub(channel);
        this.resilience = new GrpcResilience(
                config.getMaxRetryAttempts(),
                config.getRetryDelayMillis(),
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerWaitDurationMillis());
        this.requestTimeoutSeconds = config.getRequestTimeoutSeconds();
    }

    public static Simulations create(GrpcClientConfig config, ManagedChannel channel) {
        return new SimulationsImpl(config, channel);
    }

    @Override
    public CompletableFuture<StartSimulationResponse> startSimulation(StartSimulationRequest request) {
        Objects.requireNonNull(request, "request");
        requireText(request.getApplicationId(), "applicationId");
        RequestContext.Builder context = request.getContext().toBuilder();
        if (context.getRequestId().isBlank()) context.setRequestId(UUID.randomUUID().toString());
        if (context.getIdempotencyKey().isBlank()) context.setIdempotencyKey(UUID.randomUUID().toString());
        StartSimulationRequest start = request.toBuilder().setContext(context).build();
        return unary((s, o) -> s.startSimulation(start, o));
    }

    @Override
    public CompletableFuture<SimulationTrace> simulationTrace(String executionId) {
        requireText(executionId, "executionId");
        var request = GetSimulationTraceRequest.newBuilder()
                .setContext(context())
                .setExecutionId(executionId)
                .build();
        return this.<GetSimulationTraceResponse>unary((s, o) -> s.getSimulationTrace(request, o))
                .thenApply(GetSimulationTraceResponse::getTrace);
    }

    @Override
    public CompletableFuture<SimulationStatus> cancelSimulation(String executionId) {
        requireText(executionId, "executionId");
        var request = CancelSimulationRequest.newBuilder()
                .setContext(context())
                .setExecutionId(executionId)
                .build();
        return this.<CancelSimulationResponse>unary((s, o) -> s.cancelSimulation(request, o))
                .thenApply(CancelSimulationResponse::getStatus);
    }

    @Override
    public CompletableFuture<SimulationTrace> awaitSimulation(String executionId, Duration interval, Duration timeout) {
        requireText(executionId, "executionId");
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
        CompletableFuture<SimulationTrace> outcome = new CompletableFuture<>();
        if (timeout != null) {
            CompletableFuture.delayedExecutor(timeout.toNanos(), TimeUnit.NANOSECONDS).execute(() ->
                    outcome.completeExceptionally(CommandException.waitTimedOut(
                            "simulation " + executionId + " did not end within " + timeout)));
        }
        poll(executionId, interval, outcome);
        return outcome;
    }

    @Override
    public CompletableFuture<SimulationTrace> simulate(StartSimulationRequest request, Duration interval, Duration timeout) {
        return startSimulation(request)
                .thenCompose(started -> awaitSimulation(started.getExecutionId(), interval, timeout));
    }

    private void poll(String executionId, Duration interval, CompletableFuture<SimulationTrace> outcome) {
        if (outcome.isDone()) return;
        simulationTrace(executionId).whenComplete((trace, failure) -> {
            if (failure != null) {
                outcome.completeExceptionally(CommandException.from(failure));
            } else if (Simulations.isFinal(trace.getStatus())) {
                outcome.complete(trace);
            } else {
                CompletableFuture.delayedExecutor(interval.toNanos(), TimeUnit.NANOSECONDS)
                        .execute(() -> poll(executionId, interval, outcome));
            }
        });
    }

    private <T> CompletableFuture<T> unary(BiConsumer<SimulationServiceGrpc.SimulationServiceStub, StreamObserver<T>> call) {
        try {
            return resilience.<T>executeWithResilienceAsync(() -> {
                        CompletableFuture<T> future = new CompletableFuture<>();
                        call.accept(stub.withDeadlineAfter(requestTimeoutSeconds, TimeUnit.SECONDS), completing(future));
                        return future;
                    })
                    .exceptionallyCompose(failure -> CompletableFuture.failedFuture(CommandException.from(failure)));
        } catch (RuntimeException circuitOpen) {
            return CompletableFuture.failedFuture(CommandException.from(circuitOpen));
        }
    }

    private static RequestContext context() {
        return RequestContext.newBuilder().setRequestId(UUID.randomUUID().toString()).build();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static <T> StreamObserver<T> completing(CompletableFuture<T> future) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                future.complete(value);
            }

            @Override
            public void onError(Throwable failure) {
                future.completeExceptionally(failure);
            }

            @Override
            public void onCompleted() {
                if (!future.isDone()) future.completeExceptionally(new IllegalStateException("no response"));
            }
        };
    }
}
