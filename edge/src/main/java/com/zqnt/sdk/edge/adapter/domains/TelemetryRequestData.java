package com.zqnt.sdk.edge.adapter.domains;

import com.zqnt.utils.edge.sdk.domains.TelemetryData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryRequestData {

	private String tid;
	private String sn;
	private String assetId;
	private Instant timestamp;
	private TelemetryData telemetry;

}
