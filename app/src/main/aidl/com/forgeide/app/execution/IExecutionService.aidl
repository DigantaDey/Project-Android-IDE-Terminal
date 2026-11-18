package com.forgeide.app.execution;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import com.forgeide.app.execution.IExecutionCallback;
interface IExecutionService {
    void run(in ParcelFileDescriptor source, in Bundle configuration, IExecutionCallback callback);
    void cancel();
    oneway void control(String action, String expression);
}
