package com.forgeide.core.util;

/** Progress callback for long-running operations. All methods must be cheap. */
public interface ProgressReporter {
    ProgressReporter NONE = new ProgressReporter() {
        @Override
        public void onProgress(int completed, int total, String message) {}
    };

    /**
     * @param completed units of work done, or -1 for indeterminate
     * @param total     total units, or -1 for indeterminate
     * @param message   human-readable description of the current step
     */
    void onProgress(int completed, int total, String message);
}
