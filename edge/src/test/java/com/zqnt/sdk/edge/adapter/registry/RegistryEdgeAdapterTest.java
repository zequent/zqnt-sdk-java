package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.protos.edge.v3.GetCapabilitiesRequest;
import com.zqnt.protos.edge.v3.GetCapabilitiesResponse;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.application.RegistryEdgeAdapter;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.Coordinates;
import com.zqnt.sdk.edge.adapter.domains.TakeOffRequest;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterGrpcServiceImpl;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterV3GrpcServiceImpl;
import com.zqnt.sdk.edge.application.ProtoJsonMapper;
import com.zqnt.sdk.edge.gateway.CommandExecutions;
import com.zqnt.sdk.edge.support.Structs;
import com.zqnt.utils.common.proto.AssetTypeEnum;
import com.zqnt.utils.common.proto.ErrorCode;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesRequest;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesResponse;
import com.zqnt.utils.devicecontrol.proto.CoordinateCommandRequest;
import com.zqnt.utils.devicecontrol.proto.CustomCommandRequest;
import com.zqnt.utils.devicecontrol.proto.CustomCommandResponse;
import com.zqnt.utils.devicecontrol.proto.GeoCoordinate;
import com.zqnt.utils.devicecontrol.proto.LookAtCommandRequest;
import com.zqnt.utils.devicecontrol.proto.ReturnToHomeCommandRequest;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An adapter writes each command once, in its registry. v2 typed RPCs, v2 SendCustomCommand and v3
 * ExecuteCommand all reach the same handler with the run's execution id, and what is advertised is
 * exactly what is registered and executable over v3. Only a 2.x platform reaches free-form commands,
 * and only through the legacy hook.
 */
public class RegistryEdgeAdapterTest {

	static final Map<String, Object> TAKEOFF_INPUT = Map.of("type", "object",
			"properties", Map.of(
					"latitude", Map.of("type", "number"),
					"longitude", Map.of("type", "number"),
					"altitude", Map.of("type", "number", "minimum", 2, "maximum", 500)));

	public static final class Drone extends RegistryEdgeAdapter {
		final List<CommandRequest> takeoffs = new ArrayList<>();

		public Drone() {
			super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
			registerCommand("flight.takeoff", TAKEOFF_INPUT, Map.of(), request -> {
				takeoffs.add(request);
				return CompletableFuture.completedFuture(CommandResult.accepted("climbing", "flight-7", request.sn()));
			});
			registerCommand("vendor.acme.beep", Map.of(), Map.of("type", "object",
					"properties", Map.of("beeps", Map.of("type", "integer"))), request ->
					CompletableFuture.completedFuture(CommandResult.success("beeped", request.sn(), Map.of("beeps", 3))));
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

	private static ExecuteCommandResponse executeV3(EdgeAdapterV3GrpcServiceImpl service, String commandId,
			Map<String, Object> params) {
		Capture<ExecuteCommandResponse> capture = new Capture<>();
		service.executeCommand(ExecuteCommandRequest.newBuilder()
				.setCommandExecutionId("capexec:run-1:node-1")
				.setCommand(Command.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).setCommandId(commandId)
						.setParams(Structs.toStruct(params)))
				.build(), capture);
		return capture.value;
	}

	@Test
	void theV2TypedTakeOffRunsTheRegisteredHandler() {
		Drone drone = new Drone();
		Coordinates coordinates = new Coordinates(52.5, 13.4, Double.NaN);

		CommandResult result = drone.takeOff(TakeOffRequest.builder().sn("SN-1").coordinates(coordinates).build()).join();

		assertTrue(result.isAccepted());
		assertEquals(Map.of("latitude", 52.5, "longitude", 13.4), drone.takeoffs.get(0).params());
		assertNull(drone.takeoffs.get(0).commandExecutionId());
	}

	@Test
	void v3ExecuteCommandReachesTheHandlerWithThePlatformsExecutionId() {
		Drone drone = new Drone();
		CommandExecutions executions = new CommandExecutions(Duration.ofMinutes(5));

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone, executions), "flight.takeoff",
				Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("capexec:run-1:node-1", drone.takeoffs.get(0).commandExecutionId());
		assertEquals("capexec:run-1:node-1", executions.resolve("SN-1", "flight.takeoff", "flight-7").orElseThrow().commandExecutionId());
	}

	@Test
	void v3InvalidParamsAreRejectedWithTheirReason() {
		Drone drone = new Drone();

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone), "flight.takeoff", Map.of("altitude", 900));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(CommandRegistry.INVALID_PARAMS, response.getResult().getError().getCode());
		assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, response.getResult().getError().getCategory());
		assertEquals("flight.takeoff: altitude must be at most 500", response.getResult().getError().getMessage());
		assertTrue(drone.takeoffs.isEmpty());
	}

	@Test
	void aSucceededCommandCarriesItsOutput() {
		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(new Drone()), "vendor.acme.beep", Map.of());

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertEquals(3.0, response.getResult().getResult().getFieldsOrThrow("beeps").getNumberValue());
	}

	@Test
	void capabilitiesAreExactlyTheRegistry() {
		Capture<GetCapabilitiesResponse> v3 = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(new Drone()).getCapabilities(GetCapabilitiesRequest.newBuilder()
				.setAsset(AssetRef.newBuilder().setSn("SN-1")).build(), v3);
		Capture<AssetCapabilitiesResponse> v2 = new Capture<>();
		new EdgeAdapterGrpcServiceImpl(new Drone(), null).getCapabilities(
				AssetCapabilitiesRequest.newBuilder().setSn("SN-1").build(), v2);

		assertEquals(List.of("flight.takeoff", "vendor.acme.beep"), v3.value.getCapabilities().getCapabilitiesList().stream()
				.map(com.zqnt.protos.capability.v3.Capability::getCommandId).toList());
		assertEquals("ASSET_TYPE_AIRCRAFT", v3.value.getCapabilities().getAssetType());
		assertEquals(List.of("flight.takeoff", "vendor.acme.beep"), v2.value.getCapabilities().getCapabilitiesList().stream()
				.map(com.zqnt.utils.devicecontrol.proto.Capability::getCommandId).toList());
	}

	@Test
	void v2SendCustomCommandRunsTheSameHandler() {
		Capture<CustomCommandResponse> capture = new Capture<>();
		new EdgeAdapterGrpcServiceImpl(new Drone(), null).sendCustomCommand(CustomCommandRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setSn("SN-1").setTid("t-1"))
				.setCommandId("vendor.acme.beep").build(), capture);

		assertFalse(capture.value.getHasErrors());
	}

	private static final String RUN = "capexec:run-2:node-4";

	private static final class BridgedDrone extends RegistryEdgeAdapter {
		final List<CommandRequest> commands = new ArrayList<>();
		final List<CommandRequest> legacy = new ArrayList<>();

		BridgedDrone() {
			super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
			for (String id : List.of("flight.takeoff", "navigation.go_to", "flight.return_to_home", "gimbal.look_at")) {
				registerCommand(id, TAKEOFF_INPUT, Map.of(), request -> {
					commands.add(request);
					return CompletableFuture.completedFuture(CommandResult.accepted("moving", "flight-8", request.sn()));
				});
			}
		}

		@Override
		protected CompletableFuture<CommandResult> legacyCustomCommand(CommandRequest request) {
			legacy.add(request);
			return CompletableFuture.completedFuture(CommandResult.success("sprayed", request.sn()));
		}
	}

	private static RequestBase base(String externalId) {
		return RequestBase.newBuilder().setSn("SN-1").setTid("t-1").setExternalId(externalId).build();
	}

	private static CustomCommandResponse sendV2(EdgeAdapterService adapter, String commandId, Map<String, Object> params) {
		Capture<CustomCommandResponse> capture = new Capture<>();
		new EdgeAdapterGrpcServiceImpl(adapter, new ProtoJsonMapper()).sendCustomCommand(CustomCommandRequest.newBuilder()
				.setBase(base(RUN)).setCommandId(commandId).setParams(Structs.toStruct(params)).build(), capture);
		return capture.value;
	}

	@Test
	void v3RefusesAnUnregisteredIdBeforeAnyAdapterCodeRuns() {
		BridgedDrone drone = new BridgedDrone();

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone), "vendor.acme.spray", Map.of("seconds", 3));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(CommandRegistry.NOT_SUPPORTED, response.getResult().getError().getCode());
		assertTrue(drone.legacy.isEmpty());
	}

	@Test
	void v2SendCustomCommandOfAnUnregisteredIdReachesTheLegacyHookAsSent() {
		BridgedDrone drone = new BridgedDrone();

		var response = sendV2(drone, "vendor.acme.spray", Map.of("seconds", "3"));

		assertFalse(response.getHasErrors());
		assertEquals(Map.of("seconds", "3"), drone.legacy.get(0).params());
		assertEquals(RUN, drone.legacy.get(0).commandExecutionId());
	}

	@Test
	void withoutALegacyHookAnUnregisteredV2CommandIsRefused() {
		var response = sendV2(new Drone(), "vendor.acme.spray", Map.of());

		assertTrue(response.getHasErrors());
		assertEquals(ErrorCode.ERROR_CODE_CLIENT, response.getError().getErrorCode());
	}

	@Test
	void aV2SendCustomCommandKeepsTheExecutionIdAndConvertsTextBeforeValidation() {
		BridgedDrone drone = new BridgedDrone();

		var response = sendV2(drone, "flight.takeoff", Map.of("latitude", "52.5200", "longitude", "13.405", "altitude", "40"));

		assertFalse(response.getHasErrors(), response.getError().getErrorMessage());
		assertEquals(Map.of("latitude", 52.52, "longitude", 13.405, "altitude", 40.0), drone.commands.get(0).params());
		assertEquals(RUN, drone.commands.get(0).commandExecutionId());
	}

	@Test
	void aV2TextValueThatIsNoNumberIsStillRejected() {
		BridgedDrone drone = new BridgedDrone();

		var response = sendV2(drone, "flight.takeoff", Map.of("latitude", "NaN"));

		assertTrue(response.getHasErrors());
		assertTrue(drone.commands.isEmpty());
	}

	@Test
	void v3TakesParamsAsSent() {
		BridgedDrone drone = new BridgedDrone();

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone), "flight.takeoff", Map.of("latitude", "52.52"));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(CommandRegistry.INVALID_PARAMS, response.getResult().getError().getCode());
		assertTrue(drone.commands.isEmpty());
	}

	@Test
	void theV2TypedFlightCommandsKeepTheExecutionIdOfTheRun() {
		BridgedDrone drone = new BridgedDrone();
		EdgeAdapterGrpcServiceImpl v2 = new EdgeAdapterGrpcServiceImpl(drone, new ProtoJsonMapper());
		GeoCoordinate target = GeoCoordinate.newBuilder().setLatitude(52.5).setLongitude(13.4).setAltitude(40).build();

		v2.takeOff(CoordinateCommandRequest.newBuilder().setBase(base(RUN)).setCoordinate(target).build(), new Capture<>());
		v2.goTo(CoordinateCommandRequest.newBuilder().setBase(base(RUN)).setCoordinate(target).build(), new Capture<>());
		v2.returnToHome(ReturnToHomeCommandRequest.newBuilder().setBase(base(RUN)).build(), new Capture<>());
		v2.lookAt(LookAtCommandRequest.newBuilder().setBase(base(RUN)).setCoordinate(target).build(), new Capture<>());

		assertEquals(List.of("flight.takeoff", "navigation.go_to", "flight.return_to_home", "gimbal.look_at"),
				drone.commands.stream().map(CommandRequest::commandId).toList());
		assertTrue(drone.commands.stream().allMatch(command -> RUN.equals(command.commandExecutionId())));
	}

	@Test
	void aV2CommandWithoutAnExternalIdHasNoExecutionId() {
		BridgedDrone drone = new BridgedDrone();

		new EdgeAdapterGrpcServiceImpl(drone, new ProtoJsonMapper()).takeOff(CoordinateCommandRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setSn("SN-1").setTid("t-1"))
				.setCoordinate(GeoCoordinate.newBuilder().setLatitude(52.5).setLongitude(13.4).setAltitude(40)).build(),
				new Capture<>());

		assertNull(drone.commands.get(0).commandExecutionId());
	}
}
