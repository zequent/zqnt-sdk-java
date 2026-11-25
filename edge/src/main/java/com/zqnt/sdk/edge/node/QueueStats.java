package com.zqnt.sdk.edge.node;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Edge Node's queue as an adapter sees it.
 *
 * @param connected whether the node has a link to the platform now
 * @param lanes     one entry per lane of the node's queue
 */
public record QueueStats(boolean connected, List<Lane> lanes) {

	/**
	 * One lane of the node's queue, and how hard the adapter should push into it.
	 *
	 * @param lane     e.g. {@code LANE_TELEMETRY}
	 * @param fill     items or bytes against the quota, whichever is fuller, 0..1
	 * @param oldest   the oldest queued item, null when the lane is empty
	 * @param throttle the share of its normal rate the adapter should send into this lane at, 0.1..1
	 */
	public record Lane(String lane, long items, long bytes, long maxItems, long maxBytes, double fill, long dropped,
			Instant oldest, double throttle) {
	}

	public QueueStats {
		lanes = List.copyOf(lanes);
	}

	public Optional<Lane> lane(String name) {
		return lanes.stream().filter(lane -> lane.lane().equals(name)).findFirst();
	}

	/** The share of its normal rate the adapter should send into a lane at; 1 for a lane the node did not report. */
	public double throttle(String name) {
		return lane(name).map(Lane::throttle).orElse(1.0);
	}

	/** Reads the node's wire form ({@code NodeQueueService} answers a {@code google.protobuf.Struct}). */
	public static QueueStats fromStruct(Struct struct) {
		Map<String, Value> fields = struct.getFieldsMap();
		List<Lane> lanes = fields.getOrDefault("lanes", Value.getDefaultInstance()).getListValue().getValuesList().stream()
				.map(value -> lane(value.getStructValue().getFieldsMap()))
				.toList();
		return new QueueStats(fields.getOrDefault("connected", Value.getDefaultInstance()).getBoolValue(), lanes);
	}

	private static Lane lane(Map<String, Value> f) {
		String oldest = string(f, "oldest");
		return new Lane(string(f, "lane"), (long) number(f, "items"), (long) number(f, "bytes"), (long) number(f, "max_items"),
				(long) number(f, "max_bytes"), number(f, "fill"), (long) number(f, "dropped"),
				oldest.isEmpty() ? null : Instant.parse(oldest), number(f, "throttle"));
	}

	private static String string(Map<String, Value> fields, String key) {
		return fields.getOrDefault(key, Value.getDefaultInstance()).getStringValue();
	}

	private static double number(Map<String, Value> fields, String key) {
		return fields.getOrDefault(key, Value.getDefaultInstance()).getNumberValue();
	}
}
