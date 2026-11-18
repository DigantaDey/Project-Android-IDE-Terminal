package com.forgeide.runtime.api;
import android.os.Bundle;
interface INativeCallback {
    void onReady(in Bundle info);
    void onOutput(in byte[] bytes);
    void onExit(int status, String message);
}
