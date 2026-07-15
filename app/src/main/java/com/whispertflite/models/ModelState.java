package com.whispertflite.models;

/** Lifecycle state of a model as shown in the catalog. */
public enum ModelState {
    AVAILABLE,    // not on disk
    DOWNLOADING,  // download in progress
    DOWNLOADED,   // on disk, not selected
    ACTIVE        // on disk and selected (selectedModelId == id)
}
