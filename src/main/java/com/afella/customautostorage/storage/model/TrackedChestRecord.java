package com.afella.customautostorage.storage.model;

import java.util.Collections;
import java.util.List;

public record TrackedChestRecord(String world, int x, int y, int z, String sortingMode, int horizontalRadius,
                                 int verticalRadius, String ownerUuid, String claimAccessUuid,
                                 List<String> ignoredTargets, boolean transferAnywhereIfMissing) {
    public TrackedChestRecord(String world, int x, int y, int z, String sortingMode, int horizontalRadius, int verticalRadius, String ownerUuid, String claimAccessUuid, List<String> ignoredTargets, boolean transferAnywhereIfMissing) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sortingMode = sortingMode;
        this.horizontalRadius = horizontalRadius;
        this.verticalRadius = verticalRadius;
        this.ownerUuid = ownerUuid;
        this.claimAccessUuid = claimAccessUuid;
        this.ignoredTargets = ignoredTargets != null ? ignoredTargets : Collections.emptyList();
        this.transferAnywhereIfMissing = transferAnywhereIfMissing;
    }
}
