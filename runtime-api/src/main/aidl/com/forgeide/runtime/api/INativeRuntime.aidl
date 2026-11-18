package com.forgeide.runtime.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.forgeide.runtime.api.INativeCallback;
interface INativeRuntime {
    Bundle describe();
    void start(String id, in ParcelFileDescriptor snapshot, in Bundle configuration, INativeCallback callback);
    void write(String id, in byte[] input);
    void resize(String id, int rows, int columns);
    void exportProject(String id, in ParcelFileDescriptor destination);
    void stop(String id);
    void stopAll();
}
