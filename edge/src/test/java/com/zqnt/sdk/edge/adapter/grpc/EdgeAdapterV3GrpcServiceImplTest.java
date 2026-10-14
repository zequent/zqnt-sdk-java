package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.edge.v3.CancelCommandRequest;
import com.zqnt.protos.edge.v3.CancelCommandResponse;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.ChangeZoomRequest;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.GoToRequest;
import com.zqnt.sdk.edge.adapter.domains.TakeOffRequest;
import com.zqnt.utils.devicecontrol.proto.CapabilityState;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 has no per-command RPCs. An adapter written for v2 without a registry -- typed {@code takeOff},
 * its own {@code sendCustomCommand} for vendor ids -- answers v3 {@code ExecuteCommand} for every
 * built-in id it advertises as available, on its typed method. Any other id, an advertised vendor id
 * included, is REJECTED before it reaches the adapter, never an aborted call.
 */
class EdgeAdapterV3GrpcServiceImplTest {

	private static final class Adapter implements EdgeAdapterService {
		final List<TakeOffRequest> takeoffs = new ArrayList<>();
		final List<String> closed = new ArrayList<>();
		final List<String[]> startedTasks = new ArrayList<>();
		final List<String> cancelled = new ArrayList<>();
		final List<String> custom = new ArrayList<>();

		@Override
		public CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> getCapabilities(String sn) {
			java.util.Set<com.zqnt.sdk.edge.adapter.domains.Capability> advertised = new java.util.LinkedHashSet<>();
			for (String id : List.of("flight.takeoff", "navigation.go_to", "dock.open_cover", "dock.close_cover",
					"mission.start", "vendor.acme.spray", "mission.waypoint.execute")) {
				var capability = new com.zqnt.sdk.edge.adapter.domains.Capability();
				capability.setCommand(id);
				if (id.equals("dock.close_cover")) {
					capability.setState(CapabilityState.CAPABILITY_STATE_TEMPORARILY_UNAVAILABLE);
				}
				advertised.add(capability);
			}
			return CompletableFuture.completedFuture(new com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities(
					sn, null, advertised, System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
			takeoffs.add(request);
			return CompletableFuture.completedFuture(CommandResult.success("airborne", request.getSn()));
		}

		@Override
		public CompletableFuture<CommandResult> goTo(GoToRequest request) {
			return CompletableFuture.completedFuture(CommandResult.accepted("flying", "dji-77", request.getSn()));
		}

		@Override
		public CompletableFuture<CommandResult> openCover(String sn) {
			return CompletableFuture.completedFuture(CommandResult.error("cover jammed", sn));
		}

		@Override
		public CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
			closed.add(sn);
			return CompletableFuture.completedFuture(CommandResult.success("closed", sn));
		}

		@Override
		public CompletableFuture<CommandResult> startTask(String taskId, String tid) {
			startedTasks.add(new String[]{taskId, tid});
			return CompletableFuture.completedFuture(CommandResult.success("started", "SN-1"));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			custom.add(commandType);
			return CompletableFuture.completedFuture(CommandResult.success("done", sn));
		}

		@Override
		public CompletableFuture<CommandResult> cancelExecution(String sn, String externalExecutionId) {
			cancelled.add(externalExecutionId);
			return CompletableFuture.completedFuture(CommandResult.success("stopped", sn));
		}
	}

	private static final class Capture<T> implements StreamObserver<T> {
		T value;

		@Override
		public void onNext(T value) {
			this.value = value;
		}

		@Override
		public void onError(Throwable t) {
			fail("unexpected error: " + t);
		}

		@Override
		public void onCompleted() {
		}
	}

	private static ExecuteCommandRequest request(String commandId, Map<String, Object> params) {
		Struct.Builder struct = Struct.newBuilder();
		params.forEach((key, value) -> struct.putFields(key, value instanceof Number number
				? Value.newBuilder().setNumberValue(number.doubleValue()).build()
				: Value.newBuilder().setStringValue(String.valueOf(value)).build()));
		return ExecuteCommandRequest.newBuilder()
				.setCommandExecutionId("cx-1")
				.setCommand(Command.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).setCommandId(commandId)
						.setParams(struct))
				.build();
	}

	private static ExecuteCommandResponse execute(EdgeAdapterService adapter, String commandId, Map<String, Object> params) {
		Capture<ExecuteCommandResponse> capture = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(adapter).executeCommand(request(commandId, params), capture);
		assertNotNull(capture.value, "a response");
		return capture.value;
	}

	@Test
	void anAdvertisedVendorIdIsRejectedAndNeverReachesTheAdapter() {
		Adapter adapter = new Adapter();

		var spray = execute(adapter, "vendor.acme.spray", Map.of("seconds", 3));
		var waypoints = execute(adapter, "mission.waypoint.execute", Map.of());

		for (var response : List.of(spray, waypoints)) {
			assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
			assertEquals(com.zqnt.sdk.edge.adapter.registry.CommandRegistry.NOT_SUPPORTED, response.getResult().getError().getCode());
		}
		assertTrue(adapter.custom.isEmpty());
	}

	@Test
	void anAdvertisedBuiltInIdRunsOnItsTypedMethod() {
		Adapter adapter = new Adapter();
		var response = execute(adapter, "flight.takeoff", Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertEquals("cx-1", response.getResult().getCommandExecutionId());
		assertEquals(1, adapter.takeoffs.size());
		assertEquals("SN-1", adapter.takeoffs.get(0).getSn());
		assertTrue(adapter.custom.isEmpty());
	}

	@Test
	void aMissionCommandStartsTheTaskUnderThePlatformsRunId() {
		Adapter adapter = new Adapter();
		var response = execute(adapter, "mission.start", Map.of("taskId", "task-4"));

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertArrayEquals(new String[]{"task-4", "cx-1"}, adapter.startedTasks.get(0));
	}

	@Test
	void anAdvertisedBuiltInThatIsUnavailableIsRejected() {
		Adapter adapter = new Adapter();
		var response = execute(adapter, "dock.close_cover", Map.of());

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(com.zqnt.sdk.edge.adapter.registry.CommandRegistry.NOT_SUPPORTED, response.getResult().getError().getCode());
		assertTrue(adapter.closed.isEmpty());
	}

	@Test
	void anUnknownIdIsRejectedNotAborted() {
		var response = execute(new Adapter(), "vendor.nope.nothing", Map.of());

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(com.zqnt.sdk.edge.adapter.registry.CommandRegistry.NOT_SUPPORTED, response.getResult().getError().getCode());
		assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, response.getResult().getError().getCategory());
	}

	@Test
	void anIdTheAdapterWouldRunButDoesNotAdvertiseNeverReachesIt() {
		Adapter adapter = new Adapter();

		var response = execute(adapter, "flight.return_to_home", Map.of());

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(com.zqnt.sdk.edge.adapter.registry.CommandRegistry.NOT_SUPPORTED, response.getResult().getError().getCode());
		assertTrue(adapter.custom.isEmpty());
	}

	/** Declares take-off ASYNCHRONOUS and the cover ON_REPLY: both typed methods answer a plain success. */
	private static final class DeclaringAdapter implements EdgeAdapterService {
		@Override
		public CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("climbing", request.getSn()));
		}

		@Override
		public CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> getCapabilities(String sn) {
			var takeoff = new com.zqnt.sdk.edge.adapter.domains.Capability();
			takeoff.setCommand("flight.takeoff");
			takeoff.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
			takeoff.setCompletionEvent("flight.takeoff.completed");
			var cover = new com.zqnt.sdk.edge.adapter.domains.Capability();
			cover.setCommand("dock.open_cover");
			cover.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ON_REPLY);
			return CompletableFuture.completedFuture(new com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities(
					sn, null, java.util.Set.of(takeoff, cover), System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<CommandResult> openCover(String sn) {
			return CompletableFuture.completedFuture(CommandResult.success("open", sn));
		}
	}

	@Test
	void aDeclaredAsynchronousCommandWaitsEvenWhenItsHandlerAnsweredAPlainSuccess() {
		var response = execute(new DeclaringAdapter(), "flight.takeoff",
				Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("cx-1", response.getResult().getCommandExecutionId());
	}

	@Test
	void aDeclaredOnReplyCommandIsDoneOnItsReply() {
		var response = execute(new DeclaringAdapter(), "dock.open_cover", Map.of());

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
	}

	@Test
	void theCompletionModeIsPublishedWithTheCapability() {
		var takeoff = new com.zqnt.sdk.edge.adapter.domains.Capability();
		takeoff.setCommand("flight.takeoff");
		takeoff.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
		takeoff.setCompletionEvent("flight.takeoff.completed");
		takeoff.getEvents().add(new com.zqnt.sdk.edge.adapter.domains.CapabilityEvent("flight.takeoff.completed", "airborne", null));

		var v3 = CapabilityMappers.toV3(takeoff);

		assertEquals(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS, v3.getCompletion());
		assertEquals("flight.takeoff.completed", v3.getCompletionEvent());
		assertEquals("flight.takeoff.completed", v3.getEvents(0).getName());
	}

	@Test
	void aLongCommandIsAcceptedUnderItsExternalId() {
		var response = execute(new Adapter(), "navigation.go_to", Map.of("latitude", 52.5, "longitude", 13.4));

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("dji-77", response.getResult().getResult().getFieldsOrThrow("external_execution_id").getStringValue());
	}

	@Test
	void anAdapterFailureIsFailedWithItsMessage() {
		var response = execute(new Adapter(), "dock.open_cover", Map.of());

		assertEquals(CommandState.COMMAND_STATE_FAILED, response.getResult().getState());
		assertEquals("cover jammed", response.getResult().getError().getMessage());
	}

	@Test
	void cancelStopsTheExecutionItWasAcceptedUnder() {
		Adapter adapter = new Adapter();
		Capture<CancelCommandResponse> capture = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(adapter).cancelCommand(
				CancelCommandRequest.newBuilder().setCommandExecutionId("dji-77").build(), capture);

		assertEquals(CommandState.COMMAND_STATE_CANCELLED, capture.value.getResult().getState());
		assertEquals(List.of("dji-77"), adapter.cancelled);
	}

	private static final class SchemaAdapter implements EdgeAdapterService {
		final List<Float> zooms = new ArrayList<>();

		@Override
		public CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> getCapabilities(String sn) {
			var zoom = new com.zqnt.sdk.edge.adapter.domains.Capability();
			zoom.setCommand("camera.change_zoom");
			zoom.setInputSchema(Map.of("type", "object", "required", List.of("zoom"),
					"properties", Map.of("zoom", Map.of("type", "integer", "minimum", 1))));
			return CompletableFuture.completedFuture(new com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities(
					sn, null, java.util.Set.of(zoom), System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
			zooms.add(request.getZoom());
			return CompletableFuture.completedFuture(CommandResult.success("zoomed", request.getSn()));
		}
	}

	@Test
	void anAdvertisedSchemaValidatesParamsOfALegacyAdapterToo() {
		SchemaAdapter adapter = new SchemaAdapter();

		var rejected = execute(adapter, "camera.change_zoom", Map.of("zoom", 0));
		var accepted = execute(adapter, "camera.change_zoom", Map.of("zoom", 4));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, rejected.getResult().getState());
		assertEquals("command.invalid_params", rejected.getResult().getError().getCode());
		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, accepted.getResult().getState());
		assertEquals(List.of(4.0f), adapter.zooms);
	}

	@Test
	void cancelReachesTheAdapterUnderItsOwnIdForACommandAcceptedOverV3() {
		Adapter adapter = new Adapter();
		var executions = new com.zqnt.sdk.edge.gateway.CommandExecutions(java.time.Duration.ofMinutes(5));
		var service = new EdgeAdapterV3GrpcServiceImpl(adapter, executions);
		Capture<ExecuteCommandResponse> accepted = new Capture<>();
		service.executeCommand(request("navigation.go_to", Map.of("latitude", 52.5, "longitude", 13.4)).toBuilder()
				.setCommandExecutionId("capexec:run-9:node-2").build(), accepted);

		Capture<CancelCommandResponse> cancelled = new Capture<>();
		service.cancelCommand(CancelCommandRequest.newBuilder().setCommandExecutionId("capexec:run-9:node-2").build(), cancelled);

		assertEquals(CommandState.COMMAND_STATE_CANCELLED, cancelled.value.getResult().getState());
		assertEquals("navigation.go_to", cancelled.value.getResult().getCommandId());
		assertEquals(List.of("dji-77"), adapter.cancelled);
		assertTrue(executions.get("capexec:run-9:node-2").isEmpty());
	}
}
