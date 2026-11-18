package com.forgeide.runtime;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Linux host smoke test of the actual JNI launcher; NEVER invokes its UID-wide killAll. */
public final class NativePtyHostTest {

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) exercisePty();
        System.out.println("10 real PTY cold starts, resize, pipes, foreground Ctrl-C and subsequent commands passed");
    }

    private static void exercisePty() throws Exception {
        long[] process = NativePty.start(
            "/",
            new String[] { "/bin/bash", "--noprofile", "--norc", "-i" },
            new String[] { "PATH=/usr/bin:/bin", "HOME=/tmp", "TERM=xterm", "PS1=forge $ " },
            24, 80, false
        );
        // Same descriptor ownership rules as Android's duplicated PFD streams, without android.jar.
        FileDescriptor descriptor = new FileDescriptor();
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        field.set(descriptor, (int) process[1]);
        boolean reaped = false;
        try (FileInputStream input = new FileInputStream(descriptor);
             FileOutputStream output = new FileOutputStream(descriptor)) {
            StringBuffer text = new StringBuffer();
            CountDownLatch first = new CountDownLatch(1), after = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try {
                    byte[] bytes = new byte[8192];
                    int n;
                    while ((n = input.read(bytes)) != -1) {
                        text.append(new String(bytes, 0, n, StandardCharsets.UTF_8));
                        // Bash may put bracketed-paste ANSI controls before the first result.
                        // Prefixes are hex-encoded in input, so terminal echo cannot satisfy these.
                        if (text.toString().contains("TTY_READY_42_32_100\r\n")) first.countDown();
                        if (text.toString().contains("AFTER_INTERRUPT_42\r\n")) after.countDown();
                    }
                } catch (IOException e) {
                    text.append("[" + e + "]"); // PTY EIO is normal only after its slave closes.
                }
            }, "host-pty-test-output");
            reader.setDaemon(true);
            reader.start();
            try {
                NativePty.resize((int) process[1], 32, 100);
                // A foreground child prints readiness AFTER job-control setup, then sleeps.
                send(output, "bash --noprofile --norc -c 'test -t 0 || exit 1; set -- $(stty size); printf \"\\x54\\x54\\x59_READY_%d_%s_%s\\n\" \"$((6*7))\" \"$1\" \"$2\"; exec sleep 60'\r");
                if (!first.await(5, TimeUnit.SECONDS)) throw new AssertionError("first: " + text);
                output.write(new byte[] { 3 });
                output.flush();
                send(output, "printf '\\x50\\x49\\x50\\x45_RESULT_'; printf '%d\\n' 42 | cat; printf '\\x41\\x46\\x54\\x45\\x52_INTERRUPT_%d\\n' \"$((6*7))\"\r");
                if (!after.await(5, TimeUnit.SECONDS)) throw new AssertionError("after: " + text);
                if (!text.toString().contains("PIPE_RESULT_42\r\n")) throw new AssertionError("pipe: " + text);
                send(output, "exit\r");
                int exit = NativePty.waitFor((int) process[0]);
                reaped = true;
                if (exit != 0) throw new AssertionError("exit " + exit + ": " + text);
            } finally {
                if (!reaped) {
                    ProcessHandle.of(process[0]).ifPresent(ProcessHandle::destroyForcibly);
                    NativePty.waitFor((int) process[0]);
                }
            }
        }
    }

    private static void send(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
