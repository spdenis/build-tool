package com.example.multibuild.model;

public enum BuildMode {
    SNAPSHOT, RELEASE,
    // Snapshot build of a patch version slotted between the previous release and the next one,
    // e.g. 1.1.1-SNAPSHOT on the source branch → 1.1.0.1-<branch>-SNAPSHOT.
    PATCH
}
