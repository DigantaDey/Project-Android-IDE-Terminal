package com.forgeide.core.util;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;

public class SafetyRegressionTest {

    @Test
    public void jsonRejectsDeepNestingAndDuplicateKeys() {
        for (String text : new String[] {
            "[".repeat(200) + "0" + "]".repeat(200),
            "{\"key\":1,\"key\":2}",
            "1e9999",
        })
            try {
                Json.parse(text);
                fail();
            } catch (Json.JsonException expected) {}
    }

    @Test
    public void unicodeOutputIsByteBoundedWithoutSplittingPairs() {
        StringBuilder out = new StringBuilder();
        OutputLimiter limiter = new OutputLimiter(9, out::append);
        try {
            limiter.accept("😀😀😀");
            fail();
        } catch (OutputLimiter.LimitExceeded expected) {}
        assertEquals("😀😀", out.toString());
        assertEquals(8, out.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void cancellationTokenNoneCannotBeCancelled() {
        CancellationToken.NONE.cancel();
        assertFalse(CancellationToken.NONE.isCancelled());
    }

    @Test
    public void cancellationContinuesAfterListenerFailure() {
        CancellationToken c = new CancellationToken();
        boolean[] closed = { false };
        c.onCancel(() -> {
            throw new IllegalStateException();
        });
        c.onCancel(() -> closed[0] = true);
        c.cancel();
        assertTrue(closed[0]);
    }

    @Test
    public void racingRegistrationIsNotLost() throws Exception {
        ExecutorService e = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                CancellationToken c = new CancellationToken();
                java.util.concurrent.atomic.AtomicInteger count =
                    new java.util.concurrent.atomic.AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> a = e.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException x) {
                        throw new RuntimeException(x);
                    }
                    c.cancel();
                });
                Future<?> b = e.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException x) {
                        throw new RuntimeException(x);
                    }
                    c.onCancel(count::incrementAndGet);
                });
                start.countDown();
                a.get();
                b.get();
                assertEquals(1, count.get());
            }
        } finally {
            e.shutdownNow();
        }
    }
}
