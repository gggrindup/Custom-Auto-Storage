package com.afella.customautostorage.storage.backend;

import com.afella.customautostorage.storage.model.TrackedChestReadResult;
import com.afella.customautostorage.storage.model.TrackedChestRecord;
import java.nio.file.Path;
import java.util.Collection;

public interface TrackedChestStorageBackend {
    String name();

    TrackedChestReadResult read(Path var1);

    boolean write(Path var1, Collection<TrackedChestRecord> var2);
}
