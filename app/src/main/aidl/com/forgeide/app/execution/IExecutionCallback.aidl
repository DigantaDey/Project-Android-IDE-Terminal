package com.forgeide.app.execution;
oneway interface IExecutionCallback {
    void onOutput(String text);
    void onErrorOutput(String text);
    void onEvent(String json);
    void onFinished(int exitCode, String error, int line, int column);
}
