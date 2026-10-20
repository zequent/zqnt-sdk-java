package com.zqnt.sdk.edge.adapter.application;

import com.zqnt.sdk.edge.adapter.domains.*;
import com.zqnt.sdk.edge.adapter.registry.CommandHandler;
import com.zqnt.sdk.edge.adapter.registry.CommandRegistry;
import com.zqnt.sdk.edge.adapter.registry.CommandRequest;
import com.zqnt.sdk.edge.adapter.registry.CommandSchemas;
import com.zqnt.utils.common.proto.AssetTypeEnum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for an adapter that writes each command once, in its {@link CommandRegistry}:
 *
 * <pre>{@code
 * public class AcmeAdapter extends RegistryEdgeAdapter {
 *     public AcmeAdapter() {
 *         super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
 *         registerCommand("flight.takeoff", TAKEOFF_INPUT, Map.of(), this::takeOff);
 *     }
 * }
 * }</pre>
 *
 * Capabilities (v2 and v3) are derived from the registry only, and v3 executes nothing else. The v2
 * typed RPCs and {@code SendCustomCommand} are routed onto the same handlers, with the typed request
 * turned into the params of the matching built-in id ({@code takeOff} becomes {@code flight.takeoff}
 * with latitude/longitude/altitude) and the request's execution id passed on, so a command behaves the
 * same whichever contract the platform speaks. A v2 platform may send values as text; the bridge
 * converts them by the command's input schema ({@link CommandSchemas#fromText}) before validation.
 * A v2 {@code SendCustomCommand} for an id that is not registered goes to
 * {@link #legacyCustomCommand}, which refuses it unless the adapter overrides it.
 */
@SuppressWarnings("deprecation")
public abstract class RegistryEdgeAdapter implements EdgeAdapterService {

	private final CommandRegistry commands = new CommandRegistry();
	private final AssetTypeEnum assetType;

	protected RegistryEdgeAdapter(AssetTypeEnum assetType) {
		this.assetType = assetType == null ? AssetTypeEnum.ASSET_TYPE_UNKNOWN : assetType;
	}

	public CommandRegistry commands() {
		return commands;
	}

	@Override
	public final Optional<CommandRegistry> commandRegistry() {
		return Optional.of(commands);
	}

	protected Capability registerCommand(String id, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
			CommandHandler handler) {
		return commands.register(id, inputSchema, outputSchema, handler);
	}

	@Override
	public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
		CurrentCapabilities current = CurrentCapabilities.of(sn, assetType, commands.capabilities());
		current.setTelemetryFields(commands.telemetryFields());
		current.setPayloads(commands.payloads(sn));
		return CompletableFuture.completedFuture(current);
	}

	@Override
	public final CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
			Map<String, Object> params) {
		return sendCustomCommand(new CommandRequest(sn, componentId, commandType, params, null));
	}

	@Override
	public final CompletableFuture<CommandResult> sendCustomCommand(CommandRequest request) {
		if (commands.contains(request.commandId())) {
			return bridge(request);
		}
		try {
			CompletableFuture<CommandResult> result = legacyCustomCommand(request);
			return result == null
					? CompletableFuture.failedFuture(new IllegalStateException(request.commandId() + " legacy handler returned no result"))
					: result;
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * A v2 {@code SendCustomCommand} for an id that is not registered. Only 2.x platforms reach this;
	 * v3 refuses an unregistered id before any adapter code runs. Refuses by default.
	 *
	 * @param request params exactly as sent, neither converted nor validated
	 */
	protected CompletableFuture<CommandResult> legacyCustomCommand(CommandRequest request) {
		return CompletableFuture.completedFuture(CommandResult.notImplemented(
				request.commandId() + " is not supported by this adapter", request.sn()));
	}

	private CompletableFuture<CommandResult> bridge(CommandRequest request) {
		Map<String, Object> params = commands.capability(request.commandId())
				.map(capability -> CommandSchemas.fromText(capability.getInputSchema(), request.params()))
				.orElse(request.params());
		return commands.execute(request.withParams(params));
	}

	private CompletableFuture<CommandResult> run(String sn, String commandId, Map<String, Object> params) {
		return run(sn, commandId, params, null);
	}

	private CompletableFuture<CommandResult> run(String sn, String commandId, Map<String, Object> params,
			String externalId) {
		String executionId = externalId == null || externalId.isBlank() ? null : externalId;
		return bridge(new CommandRequest(sn, null, commandId, params, executionId));
	}

	@Override
	public final CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
		return run(request.getSn(), "flight.takeoff", coordinates(request.getCoordinates()), request.getExternalId());
	}

	@Override
	public final CompletableFuture<CommandResult> goTo(GoToRequest request) {
		return run(request.getSn(), "navigation.go_to", coordinates(request.getCoordinates()), request.getExternalId());
	}

	@Override
	public final CompletableFuture<CommandResult> returnToHome(ReturnToHomeRequest request) {
		return run(request.getSn(), "flight.return_to_home", params("altitude", request.getAltitude()),
				request.getExternalId());
	}

	@Override
	public final CompletableFuture<CommandResult> enterManualControl(String sn) {
		return run(sn, "flight.manual.enter", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> exitManualControl(String sn) {
		return run(sn, "flight.manual.exit", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> lookAt(LookAtRequest request) {
		return run(request.getSn(), "gimbal.look_at", params("latitude", request.getLatitude(),
				"longitude", request.getLongitude(), "altitude", request.getAltitude(),
				"locked", request.getLocked(), "payloadIndex", request.getPayloadIndex()), request.getExternalId());
	}

	@Override
	public final CompletableFuture<CommandResult> enableGimbalTracking(String sn, boolean enabled) {
		return run(sn, "gimbal.tracking", params("enabled", enabled));
	}

	@Override
	public final CompletableFuture<CommandResult> takePhoto(TakePhotoRequest request) {
		return run(request.getSn(), "camera.take_photo", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> changeLens(ChangeLensRequest request) {
		return run(request.getSn(), "camera.change_lens", params("lens", request.getLens(), "videoId", request.getVideoId()));
	}

	@Override
	public final CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
		return run(request.getSn(), "camera.change_zoom", params("lens", request.getLens(),
				"payloadIndex", request.getPayloadIndex(), "zoom", request.getZoom()));
	}

	@Override
	public final CompletableFuture<CommandResult> startLiveStream(LiveStreamStartRequest request) {
		return run(request.getSn(), "stream.start", params("videoId", request.getVideoId(),
				"streamServer", request.getStreamServer(), "videoType", request.getVideoType()));
	}

	@Override
	public final CompletableFuture<CommandResult> stopLiveStream(LiveStreamStopRequest request) {
		return run(request.getSn(), "stream.stop", params("videoId", request.getVideoId()));
	}

	@Override
	public final CompletableFuture<CommandResult> liveStreamSplitScreen(String sn, boolean enabled) {
		return run(sn, "stream.split_screen", params("enabled", enabled));
	}

	@Override
	public final CompletableFuture<CommandResult> openCover(String sn) {
		return run(sn, "dock.open_cover", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
		return run(sn, "dock.close_cover", params("force", force));
	}

	@Override
	public final CompletableFuture<CommandResult> startCharging(String sn) {
		return run(sn, "dock.start_charging", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> stopCharging(String sn) {
		return run(sn, "dock.stop_charging", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> rebootAsset(String sn) {
		return run(sn, "asset.reboot", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> bootUpSubAsset(String sn) {
		return run(sn, "asset.boot_sub_asset", params("enabled", true));
	}

	@Override
	public final CompletableFuture<CommandResult> bootDownSubAsset(String sn) {
		return run(sn, "asset.boot_sub_asset", params("enabled", false));
	}

	@Override
	public final CompletableFuture<CommandResult> enterRemoteDebugMode(String sn) {
		return run(sn, "asset.remote_debug", params("enabled", true));
	}

	@Override
	public final CompletableFuture<CommandResult> closeRemoteDebugMode(String sn) {
		return run(sn, "asset.remote_debug", params("enabled", false));
	}

	@Override
	public final CompletableFuture<CommandResult> changeAcMode(String sn, String mode) {
		return run(sn, "asset.change_ac_mode", params("mode", mode));
	}

	private static Map<String, Object> coordinates(Coordinates coordinates) {
		if (coordinates == null) {
			return Map.of();
		}
		return params("latitude", present(coordinates.getLatitude()), "longitude", present(coordinates.getLongitude()),
				"altitude", present(coordinates.getAltitude()));
	}

	/** Omitted coordinates arrive as NaN; they are absent params, not numbers. */
	private static Double present(Double value) {
		return value == null || value.isNaN() ? null : value;
	}

	private static Map<String, Object> params(Object... keysAndValues) {
		Map<String, Object> params = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			Object value = keysAndValues[i + 1];
			if (value instanceof Float number && number.isNaN()) value = null;
			if (value != null) params.put((String) keysAndValues[i], value);
		}
		return params;
	}
}
