package com.zqnt.sdk.edge.support;

import com.google.protobuf.Timestamp;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.core.ProtobufHelpers;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MapperSupport {

    private MapperSupport() {
    }

    public static <T> void set(Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    public static <T> void setIf(BooleanSupplier condition, Consumer<T> setter, Supplier<T> valueSupplier) {
        if (condition.getAsBoolean()) {
            T value = valueSupplier.get();
            if (value != null) {
                setter.accept(value);
            }
        }
    }

    public static String defaultString(String value) {
        return value == null ? "" : value;
    }

    public static Instant toInstant(Timestamp timestamp) {
        return ProtobufHelpers.toInstant(timestamp);
    }

    public static Timestamp toTimestamp(Instant instant) {
        return ProtobufHelpers.toTimestamp(instant);
    }

    public static RequestBase.Builder requestBase(String sn, String tid, Instant timestamp) {
        return RequestBase.newBuilder()
                .setSn(defaultString(sn))
                .setTid(defaultString(tid))
                .setTimestamp(timestamp != null ? toTimestamp(timestamp) : ProtobufHelpers.now());
    }
}
