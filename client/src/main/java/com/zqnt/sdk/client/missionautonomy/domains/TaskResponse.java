package com.zqnt.sdk.client.missionautonomy.domains;

import com.zqnt.utils.missionautonomy.domains.TaskDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private boolean success;
    private String tid;
    private String taskId;
    private Instant timestamp;
    private ErrorInfo error;
    private ProgressInfo progress;
    private TaskDTO taskData;

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
