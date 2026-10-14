package com.zqnt.sdk.edge.conformance;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.Capability;
import com.zqnt.sdk.edge.adapter.domains.ChangeZoomRequest;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.adapter.domains.GoToRequest;
import com.zqnt.sdk.edge.adapter.domains.ReturnToHomeRequest;
import com.zqnt.sdk.edge.adapter.domains.TakePhotoRequest;
import com.zqnt.sdk.edge.adapter.registry.RegistryEdgeAdapterTest;
import com.zqnt.utils.events.proto.CommandExecutionEvent;
import com.zqnt.utils.events.proto.CommandExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EdgeAdapterConformanceTest {

	@Test
	void aRegistryAdapterConformsByConstruction() {
		EdgeAdapterConformance.check(new RegistryEdgeAdapterTest.Drone(), "SN-1").assertPassed();
	}

	/** Advertises flight.takeoff but cannot run it; runs navigation.go_to but does not advertise it. */
	private static final class DriftingAdapter implements EdgeAdapterService {
		@Override
		public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
			Capability takeoff = new Capability();
			takeoff.setCommand("flight.takeoff");
			Capability zoom = new Capability();
			zoom.setCommand("camera.change_zoom");
			zoom.setInputSchema(Map.of("type", "object", "required", List.of("zoom"),
					"properties", Map.of("zoom", Map.of("type", "integer", "minimum", 1, "maximum", 1)),
					"additionalProperties", Map.of("type", "strng")));
			Capability rth = new Capability();
			rth.setCommand("flight.return_to_home");
			rth.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
			rth.setCompletionEvent("flight.return_to_home.completed");
			return CompletableFuture.completedFuture(new CurrentCapabilities(sn, null, Set.of(takeoff, zoom, rth), 0));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> goTo(GoToRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("flying", request.getSn()));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("ok", request.getSn()));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> returnToHome(ReturnToHomeRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("ok", request.getSn()));
		}
	}

	@Test
	void advertisementAndExecutionDriftIsFound() {
		var report = EdgeAdapterConformance.check(new DriftingAdapter(), "SN-1");

		assertFalse(report.passed());
		assertTrue(report.problems().contains("flight.takeoff is advertised but not executable"), report.problems().toString());
		assertTrue(report.problems().contains("navigation.go_to is executable but not advertised"), report.problems().toString());
		assertTrue(report.problems().contains("camera.change_zoom input schema.additionalProperties.type has an unknown type \"strng\""),
				report.problems().toString());
		assertTrue(report.problems().contains(
				"flight.return_to_home completes with flight.return_to_home.completed but does not declare that event"),
				report.problems().toString());
		assertThrows(AssertionError.class, report::assertPassed);
	}

	/** Implements open cover typed and a vendor command in its own routing; advertises both, and a cover it cannot close now. */
	private static final class TypedAdapter implements EdgeAdapterService {
		final List<String> custom = new java.util.ArrayList<>();

		@Override
		public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
			Capability open = new Capability();
			open.setCommand("dock.open_cover");
			Capability close = new Capability();
			close.setCommand("dock.close_cover");
			close.setState(com.zqnt.utils.devicecontrol.proto.CapabilityState.CAPABILITY_STATE_TEMPORARILY_UNAVAILABLE);
			Capability spray = new Capability();
			spray.setCommand("vendor.acme.spray");
			return CompletableFuture.completedFuture(new CurrentCapabilities(sn, null, Set.of(open, close, spray), 0));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> openCover(String sn) {
			return CompletableFuture.completedFuture(CommandResult.success("open", sn));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
			return CompletableFuture.completedFuture(CommandResult.success("closed", sn));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			custom.add(commandType);
			return CompletableFuture.completedFuture(CommandResult.success("sprayed", sn));
		}
	}

	@Test
	void anAdvertisedVendorIdOfAnAdapterWithoutRegistryIsNotExecutable() {
		TypedAdapter adapter = new TypedAdapter();

		var report = EdgeAdapterConformance.check(adapter, "SN-1");

		assertEquals(List.of("vendor.acme.spray is advertised but not executable"), report.problems());
		assertTrue(adapter.custom.isEmpty());
	}

	/** Implements takePhoto, which runs camera.take_photo and its alias camera.capture_photo. */
	private static final class CameraAdapter implements EdgeAdapterService {
		final Set<String> advertised;

		CameraAdapter(String... advertised) {
			this.advertised = Set.of(advertised);
		}

		@Override
		public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
			Set<Capability> capabilities = new java.util.HashSet<>();
			for (String id : advertised) {
				Capability capability = new Capability();
				capability.setCommand(id);
				capabilities.add(capability);
			}
			return CompletableFuture.completedFuture(new CurrentCapabilities(sn, null, capabilities, 0));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> takePhoto(TakePhotoRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("photo", request.getSn()));
		}
	}

	@Test
	void anAliasOfAnAdvertisedIdIsNotReportedAsUnadvertised() {
		EdgeAdapterConformance.check(new CameraAdapter("camera.take_photo"), "SN-1").assertPassed();
		EdgeAdapterConformance.check(new CameraAdapter("camera.capture_photo"), "SN-1").assertPassed();
	}

	@Test
	void aTypedMethodWhoseIdsAreAllUnadvertisedIsFound() {
		var report = EdgeAdapterConformance.check(new CameraAdapter(), "SN-1");

		assertEquals(List.of("camera.capture_photo is executable but not advertised", "camera.take_photo is executable but not advertised"),
				report.problems().stream().sorted().toList());
	}

	@Test
	void aCommandTargetingAnUnreportedPayloadIsFound() {
		var drone = new RegistryEdgeAdapterTest.Drone();
		drone.commands().capability("vendor.acme.beep").orElseThrow()
				.setTargetType(com.zqnt.utils.devicecontrol.proto.CapabilityTargetType.CAPABILITY_TARGET_TYPE_PAYLOAD);
		drone.commands().capability("vendor.acme.beep").orElseThrow().setTargetRef("speaker-1");

		var report = EdgeAdapterConformance.check(drone, "SN-1");
		assertTrue(report.problems().contains("vendor.acme.beep targets payload 'speaker-1', which is not reported"),
				report.problems().toString());

		drone.commands().setPayloads("SN-1", List.of(com.zqnt.protos.capability.v3.Payload.newBuilder()
				.setRef("speaker-1").setKind("speaker").build()));
		EdgeAdapterConformance.check(drone, "SN-1").assertPassed();
	}

	@Test
	void aCommandEventWithoutOccurredAtIsFound() {
		var report = EdgeAdapterConformance.checkCommandEvents(List.of(
				CommandEvent.newBuilder().setCommandExecutionId("capexec:r:n").setAsset(AssetRef.newBuilder().setSn("SN-1"))
						.setState(CommandState.COMMAND_STATE_SUCCEEDED).setOccurredAt(Timestamp.newBuilder().setSeconds(1)).build(),
				CommandEvent.newBuilder().setCommandExecutionId("capexec:r:n").setCommandId("flight.takeoff")
						.setAsset(AssetRef.newBuilder().setSn("SN-1")).setState(CommandState.COMMAND_STATE_SUCCEEDED).build()));

		assertEquals(List.of("event 1 (flight.takeoff) has no occurred_at"), report.problems());
	}

	@Test
	void theSdkGivesAnAdaptersCompletionEventItsOccurredAt() {
		var report = EdgeAdapterConformance.checkCommandExecutionEvents("SN-1", List.of(CommandExecutionEvent.newBuilder()
				.setExternalExecutionId("flight-7").setCommandId("flight.takeoff")
				.setStatus(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_SUCCEEDED).build()));

		report.assertPassed();
	}
}
