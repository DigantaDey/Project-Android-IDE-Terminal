package com.forgeide.core.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class UtilSupportTest {

    @Test
    public void cancellationTokenFiresListeners() {
        CancellationToken token = new CancellationToken();
        final boolean[] fired = { false };
        token.onCancel(() -> fired[0] = true);
        assertFalse(token.isCancelled());
        token.cancel();
        assertTrue(token.isCancelled());
        assertTrue(fired[0]);
        // Listener registered after cancellation fires immediately
        final boolean[] late = { false };
        token.onCancel(() -> late[0] = true);
        assertTrue(late[0]);
        try {
            token.throwIfCancelled();
            fail("expected CancellationException");
        } catch (CancellationToken.CancellationException expected) {
            // ok
        }
    }

    @Test
    public void listenersAllowSelfRemovalDuringDispatch() {
        Listeners<Runnable> listeners = new Listeners<>();
        final int[] calls = { 0 };
        Runnable r = new Runnable() {
            @Override
            public void run() {
                calls[0]++;
            }
        };
        Runnable selfRemoving = new Runnable() {
            @Override
            public void run() {
                calls[0]++;
                // copy reference for removal
                listeners.remove(this);
            }
        };
        listeners.add(selfRemoving);
        listeners.add(r);
        listeners.forEach(Runnable::run);
        listeners.forEach(Runnable::run);
        assertEquals(3, calls[0]); // selfRemoving once, r twice
    }

    @Test
    public void leadingWhitespaceColumnsExpandsTabs() {
        assertEquals(0, TextUtils.leadingWhitespaceColumns("abc", 4));
        assertEquals(4, TextUtils.leadingWhitespaceColumns("\tabc", 4));
        assertEquals(4, TextUtils.leadingWhitespaceColumns("  \tabc", 4));
        assertEquals(3, TextUtils.leadingWhitespaceColumns("   abc", 4));
    }

    @Test
    public void detectIndentationPrefersTabs() {
        String tabs = "void f() {\n\tint a;\n\tif (a) {\n\t\tb();\n\t}\n}";
        int[] result = TextUtils.detectIndentation(tabs, 4);
        assertTrue(result[1] == 1);
    }

    @Test
    public void detectIndentationFindsSpaceWidth() {
        String spaces = "def f():\n  x = 1\n  if x:\n    y = 2\n  z = 3\n";
        int[] result = TextUtils.detectIndentation(spaces, 4);
        assertEquals(2, result[0]);
        assertFalse(result[1] == 1);
    }

    @Test
    public void levenshteinWorks() {
        assertEquals(0, TextUtils.levenshtein("abc", "abc"));
        assertEquals(1, TextUtils.levenshtein("abc", "abd"));
        assertEquals(3, TextUtils.levenshtein("kitten", "sitting"));
    }

    @Test
    public void validatesFileNames() {
        assertTrue(TextUtils.isValidFileName("Main.java"));
        assertTrue(TextUtils.isValidFileName("a b-c_d.txt"));
        assertFalse(TextUtils.isValidFileName(""));
        assertFalse(TextUtils.isValidFileName("a/b"));
        assertFalse(TextUtils.isValidFileName("a\\b"));
        assertFalse(TextUtils.isValidFileName("trailing "));
        assertFalse(TextUtils.isValidFileName(".."));
    }

    @Test
    public void ringBufferSinkRetainsNewest() {
        Logger.RingBufferSink sink = new Logger.RingBufferSink(16);
        for (int i = 0; i < 50; i++) {
            sink.log(Logger.Level.INFO, "t", "m" + i, null);
        }
        assertEquals(16, sink.entries().size());
        assertEquals("m49", sink.entries().get(15).message);
    }
}
