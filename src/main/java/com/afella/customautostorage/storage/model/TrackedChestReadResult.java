package com.afella.customautostorage.storage.model;

import java.util.List;

public final class TrackedChestReadResult {
    public final List<TrackedChestRecord> records;
    public final boolean failed;
    public final FailureReason failureReason;

    private TrackedChestReadResult(List<TrackedChestRecord> records, boolean failed, FailureReason failureReason) {
        this.records = records != null ? records : List.of();
        this.failed = failed;
        this.failureReason = failureReason != null ? failureReason : TrackedChestReadResult.FailureReason.NONE;
    }

    public static TrackedChestReadResult success(List<TrackedChestRecord> records) {
        return new TrackedChestReadResult(records, false, TrackedChestReadResult.FailureReason.NONE);
    }

    public static TrackedChestReadResult failure() {
        return failure(TrackedChestReadResult.FailureReason.STORAGE_UNAVAILABLE);
    }

    public static TrackedChestReadResult failure(FailureReason failureReason) {
        FailureReason reason = failureReason != null ? failureReason : TrackedChestReadResult.FailureReason.STORAGE_UNAVAILABLE;
        return new TrackedChestReadResult(List.of(), true, reason);
    }

    public enum FailureReason {
        NONE,
        STORAGE_UNAVAILABLE,
        INCOMPATIBLE_SCHEMA;

        FailureReason() {
        }
    }
}
