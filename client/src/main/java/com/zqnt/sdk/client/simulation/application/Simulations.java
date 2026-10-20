package com.zqnt.sdk.client.simulation.application;

import com.zqnt.protos.application_simulation.v3.SimulationStatus;
import com.zqnt.protos.application_simulation.v3.SimulationTrace;
import com.zqnt.protos.application_simulation.v3.StartSimulationRequest;
import com.zqnt.protos.application_simulation.v3.StartSimulationResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Simulated runs of an Application: the platform's own engine runs it on a virtual fleet, every
 * command is answered from its published contract and the request's scenario, and no command leaves
 * the platform. A simulated run never holds, commands or changes a real asset.
 *
 * <p>Every future fails with a {@link com.zqnt.sdk.client.commands.domains.CommandException} carrying
 * the gRPC status: {@code INVALID_ARGUMENT} for an unusable scenario or fleet, {@code NOT_FOUND} for an
 * Application or run that is not the caller's organization's.</p>
 */
public interface Simulations {

    /**
     * Starts a simulated run and completes once it is running, with its execution id and status. A
     * request without {@code context.idempotency_key} gets a new one; a start retried with the same
     * key answers the run it started.
     */
    CompletableFuture<StartSimulationResponse> startSimulation(StartSimulationRequest request);

    /** The path the run took so far, or in full once it has ended. */
    CompletableFuture<SimulationTrace> simulationTrace(String executionId);

    /** Stops a simulated run; completes with its status, {@code CANCELLING} while its commands stop. */
    CompletableFuture<SimulationStatus> cancelSimulation(String executionId);

    /**
     * Polls the trace every {@code interval} until the run is {@code SUCCEEDED}, {@code FAILED},
     * {@code CANCELLED} or {@code TIMED_OUT} and completes with that trace, whatever the outcome.
     * When {@code timeout} elapses first ({@code null} waits without a limit) the future fails with
     * category {@code TIMEOUT} and the run keeps going ({@link #cancelSimulation} stops it).
     */
    CompletableFuture<SimulationTrace> awaitSimulation(String executionId, Duration interval, Duration timeout);

    /** Starts a simulated run and waits for its trace as {@link #awaitSimulation} does. */
    CompletableFuture<SimulationTrace> simulate(StartSimulationRequest request, Duration interval, Duration timeout);

    static boolean isFinal(SimulationStatus status) {
        return switch (status) {
            case SIMULATION_STATUS_SUCCEEDED, SIMULATION_STATUS_FAILED, SIMULATION_STATUS_CANCELLED,
                 SIMULATION_STATUS_TIMED_OUT -> true;
            default -> false;
        };
    }
}
