package com.zqnt.sdk.client.simulation.application.impl;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.application_simulation.v3.CancelSimulationRequest;
import com.zqnt.protos.application_simulation.v3.CancelSimulationResponse;
import com.zqnt.protos.application_simulation.v3.EdgeType;
import com.zqnt.protos.application_simulation.v3.GetSimulationTraceRequest;
import com.zqnt.protos.application_simulation.v3.GetSimulationTraceResponse;
import com.zqnt.protos.application_simulation.v3.NodeOutcome;
import com.zqnt.protos.application_simulation.v3.NodeScenario;
import com.zqnt.protos.application_simulation.v3.NodeStatus;
import com.zqnt.protos.application_simulation.v3.SimulationServiceGrpc;
import com.zqnt.protos.application_simulation.v3.SimulationStatus;
import com.zqnt.protos.application_simulation.v3.SimulationTrace;
import com.zqnt.protos.application_simulation.v3.StartSimulationRequest;
import com.zqnt.protos.application_simulation.v3.StartSimulationResponse;
import com.zqnt.protos.application_simulation.v3.TraceStep;
import com.zqnt.protos.application_simulation.v3.TraceTransition;
import com.zqnt.protos.application_simulation.v3.VirtualAsset;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.common.v3.RequestContext;
import com.zqnt.sdk.client.commands.domains.CommandException;
import com.zqnt.sdk.client.config.GrpcClientConfig;
import com.zqnt.sdk.client.config.ServiceConfig;
import com.zqnt.sdk.client.simulation.application.Simulations;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimulationsImplTest {

    private static final Duration POLL = Duration.ofMillis(10);

    private final AtomicReference<StartSimulationRequest> lastStart = new AtomicReference<>();
    private final AtomicReference<GetSimulationTraceRequest> lastTraceRequest = new AtomicReference<>();
    private final AtomicReference<CancelSimulationRequest> lastCancel = new AtomicReference<>();
    private final AtomicReference<Status> refuseWith = new AtomicReference<>();
    private final Deque<SimulationStatus> statuses = new ArrayDeque<>();
    private final AtomicInteger traceCalls = new AtomicInteger();
    private Server server;
    private ManagedChannel channel;
    private Simulations simulations;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FakeSimulationService())
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        simulations = SimulationsImpl.create(GrpcClientConfig.builder()
                .missionAutonomyConfig(ServiceConfig.builder().serviceName("mission-autonomy").host("in-process").port(0).build())
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
    void startGeneratesAnIdempotencyKeyWhenTheCallerGivesNone() {
        StartSimulationRequest request = StartSimulationRequest.newBuilder()
                .setApplicationId("app-1")
                .addNodes(NodeScenario.newBuilder().setCommandId("flight.takeoff").setOutcome(NodeOutcome.NODE_OUTCOME_FAILURE)
                        .setErrorCode("flight.not_airborne"))
                .addFleet(VirtualAsset.newBuilder().setSn("DRONE-1").setBatteryPercent(18))
                .build();

        StartSimulationResponse started = simulations.startSimulation(request).join();

        assertEquals("exec-1", started.getExecutionId());
        assertEquals(SimulationStatus.SIMULATION_STATUS_RUNNING, started.getStatus());
        RequestContext context = lastStart.get().getContext();
        assertFalse(context.getIdempotencyKey().isBlank());
        assertFalse(context.getRequestId().isBlank());
        assertEquals(request.getNodesList(), lastStart.get().getNodesList());
        assertEquals(request.getFleetList(), lastStart.get().getFleetList());
    }

    @Test
    void startKeepsTheCallersIdempotencyKey() {
        simulations.startSimulation(StartSimulationRequest.newBuilder()
                .setContext(RequestContext.newBuilder().setIdempotencyKey("retry-me"))
                .setApplicationId("app-1")
                .build()).join();

        assertEquals("retry-me", lastStart.get().getContext().getIdempotencyKey());
    }

    @Test
    void startWithoutAnApplicationIsRefusedBeforeItIsSent() {
        assertThrows(IllegalArgumentException.class,
                () -> simulations.startSimulation(StartSimulationRequest.getDefaultInstance()));
        assertNull(lastStart.get());
    }

    @Test
    void traceIsTheOneThePlatformAnswers() {
        statuses.add(SimulationStatus.SIMULATION_STATUS_FAILED);

        SimulationTrace trace = simulations.simulationTrace("exec-1").join();

        assertEquals("exec-1", lastTraceRequest.get().getExecutionId());
        assertEquals(traceOf("exec-1", SimulationStatus.SIMULATION_STATUS_FAILED), trace);
        assertEquals(EdgeType.EDGE_TYPE_FAILURE, trace.getSteps(0).getNext(0).getEdgeType());
    }

    @Test
    void cancelAnswersTheStatus() {
        SimulationStatus status = simulations.cancelSimulation("exec-1").join();

        assertEquals("exec-1", lastCancel.get().getExecutionId());
        assertEquals(SimulationStatus.SIMULATION_STATUS_CANCELLING, status);
    }

    @Test
    void anUnknownRunFailsWithNotFound() {
        refuseWith.set(Status.NOT_FOUND.withDescription("no simulation exec-9"));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> simulations.simulationTrace("exec-9").join());

        assertEquals(Status.Code.NOT_FOUND, assertInstanceOf(CommandException.class, thrown.getCause()).getStatus());
    }

    @Test
    void awaitPollsUntilTheRunHasEnded() {
        statuses.add(SimulationStatus.SIMULATION_STATUS_PENDING);
        statuses.add(SimulationStatus.SIMULATION_STATUS_RUNNING);
        statuses.add(SimulationStatus.SIMULATION_STATUS_CANCELLING);
        statuses.add(SimulationStatus.SIMULATION_STATUS_CANCELLED);

        SimulationTrace trace = simulations.awaitSimulation("exec-1", POLL, Duration.ofSeconds(5)).join();

        assertEquals(SimulationStatus.SIMULATION_STATUS_CANCELLED, trace.getStatus());
        assertEquals(4, traceCalls.get());
    }

    @Test
    void awaitFailsWithTimeoutWhileTheRunKeepsGoing() {
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> simulations.awaitSimulation("exec-1", POLL, Duration.ofMillis(100)).join());

        CommandException failure = assertInstanceOf(CommandException.class, thrown.getCause());
        assertEquals(ErrorCategory.ERROR_CATEGORY_TIMEOUT, failure.getCategory());
        assertNull(lastCancel.get());
    }

    @Test
    void simulateStartsAndWaitsForTheTrace() {
        statuses.add(SimulationStatus.SIMULATION_STATUS_RUNNING);
        statuses.add(SimulationStatus.SIMULATION_STATUS_SUCCEEDED);

        SimulationTrace trace = simulations.simulate(StartSimulationRequest.newBuilder().setApplicationId("app-1").build(),
                POLL, Duration.ofSeconds(5)).join();

        assertEquals("exec-1", lastTraceRequest.get().getExecutionId());
        assertEquals(SimulationStatus.SIMULATION_STATUS_SUCCEEDED, trace.getStatus());
    }

    private static SimulationTrace traceOf(String executionId, SimulationStatus status) {
        return SimulationTrace.newBuilder()
                .setExecutionId(executionId)
                .setStatus(status)
                .setAssetSn("VIRTUAL-exec-1-DRONE-1")
                .setFastForward(true)
                .addSteps(TraceStep.newBuilder()
                        .setNodeId("takeoff")
                        .setCommandId("flight.takeoff")
                        .setStatus(NodeStatus.NODE_STATUS_FAILED)
                        .setOutput(Struct.newBuilder().putFields("battery_percent", Value.newBuilder().setNumberValue(18).build()))
                        .addNext(TraceTransition.newBuilder().setTargetNodeId("return").setEdgeType(EdgeType.EDGE_TYPE_FAILURE)
                                .setReason("failed: flight.not_airborne"))
                        .setSimulatedBy("scenario: failure"))
                .addSkippedNodeIds("land")
                .build();
    }

    private final class FakeSimulationService extends SimulationServiceGrpc.SimulationServiceImplBase {
        @Override
        public void startSimulation(StartSimulationRequest request, StreamObserver<StartSimulationResponse> responses) {
            lastStart.set(request);
            responses.onNext(StartSimulationResponse.newBuilder()
                    .setExecutionId("exec-1").setStatus(SimulationStatus.SIMULATION_STATUS_RUNNING).build());
            responses.onCompleted();
        }

        @Override
        public void getSimulationTrace(GetSimulationTraceRequest request, StreamObserver<GetSimulationTraceResponse> responses) {
            lastTraceRequest.set(request);
            traceCalls.incrementAndGet();
            Status status = refuseWith.get();
            if (status != null) {
                responses.onError(status.asRuntimeException());
                return;
            }
            SimulationStatus next = statuses.size() > 1 ? statuses.poll() : statuses.peek();
            responses.onNext(GetSimulationTraceResponse.newBuilder()
                    .setTrace(traceOf(request.getExecutionId(), next == null ? SimulationStatus.SIMULATION_STATUS_RUNNING : next))
                    .build());
            responses.onCompleted();
        }

        @Override
        public void cancelSimulation(CancelSimulationRequest request, StreamObserver<CancelSimulationResponse> responses) {
            lastCancel.set(request);
            responses.onNext(CancelSimulationResponse.newBuilder().setStatus(SimulationStatus.SIMULATION_STATUS_CANCELLING).build());
            responses.onCompleted();
        }
    }
}
