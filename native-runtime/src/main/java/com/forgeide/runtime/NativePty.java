package com.forgeide.runtime;

import java.io.IOException;

/** Only loaded by the separately installed companion, never by the IDE. */
final class NativePty {

    static {
        System.loadLibrary("forge-pty");
    }

    static native long[] start(
        String directory,
        String[] arguments,
        String[] environment,
        int rows,
        int columns,
        boolean network
    ) throws IOException;

    static native int waitFor(int pid);

    static native void resize(int fd, int rows, int columns);

    static native void killAll();

    private NativePty() {}
}
