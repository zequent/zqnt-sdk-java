package com.zqnt.sdk.client.connector.domains;

import com.zqnt.utils.asset.domains.AssetDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetMonitoringResponse {
    private boolean success;
    private String tid;
    private Instant timestamp;
    private List<AssetDTO> assets;
    private ConnectorResponse.ErrorInfo error;
}
