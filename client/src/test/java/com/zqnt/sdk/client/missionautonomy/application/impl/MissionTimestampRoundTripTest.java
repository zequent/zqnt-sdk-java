package com.zqnt.sdk.client.missionautonomy.application.impl;

import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.mission.proto.TaskProtoDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MissionTimestampRoundTripTest {

    private TimeZone original;

    @BeforeEach
    void useNonUtcZone() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void taskTimestampsSurviveTheProtoRoundTripUnshifted() {
        Instant created = Instant.parse("2026-09-10T13:56:55.338046200Z");
        Instant modified = Instant.parse("2026-09-10T14:30:00Z");

        TaskProtoDTO proto = TaskProtoDTO.newBuilder()
                .setCreatedAt(ProtobufHelpers.toTimestamp(created))
                .setModifiedAt(ProtobufHelpers.toTimestamp(modified))
                .build();

        var task = MissionAutonomyImpl.mapTaskProtoToDto(proto);

        assertEquals(created, task.getCreatedAt(),
                "createdAt must round-trip unchanged, whatever zone the caller runs in");
        assertEquals(modified, task.getModifiedAt());
    }

    @Test
    void absentTaskTimestampsStayNull() {
        var task = MissionAutonomyImpl.mapTaskProtoToDto(TaskProtoDTO.newBuilder().build());

        assertNull(task.getCreatedAt());
        assertNull(task.getModifiedAt());
    }
}
