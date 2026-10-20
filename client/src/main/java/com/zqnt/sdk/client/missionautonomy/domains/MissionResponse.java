package com.zqnt.sdk.client.missionautonomy.domains;

import com.zqnt.utils.missionautonomy.domains.MissionDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionResponse {
    private boolean success;
    private String tid;
    private String missionId;
    private Instant timestamp;
    private ErrorInfo error;
    private ProgressInfo progress;
    private MissionDTO missionData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorInfo {
        private String errorCode;
        private String errorMessage;
        private Instant timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressInfo {
        private float progress;
        private String state;
        private float leftTimeInSeconds;
    }

}
