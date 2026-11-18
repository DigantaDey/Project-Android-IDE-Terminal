package com.forgeide.core.util;

/** Regex input with a shared work/deadline budget. Catastrophic backtracking cannot monopolize a worker. */
public final class BoundedText implements CharSequence {

    public static final class LimitExceeded extends RuntimeException {

        public LimitExceeded() {
            super("Search exceeded its work/time budget. Narrow the pattern or search a smaller file.");
        }
    }

    private static final class Budget {

        final long deadline;
        long remaining = 10_000_000;
        int reads;

        Budget(long millis) {
            deadline = System.nanoTime() + millis * 1_000_000;
        }
    }

    private final String text;
    private final int start, end;
    private final Budget budget;

    public BoundedText(String text, long millis) {
        this(text, 0, text.length(), new Budget(millis));
    }

    private BoundedText(String text, int start, int end, Budget budget) {
        this.text = text;
        this.start = start;
        this.end = end;
        this.budget = budget;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length()) throw new IndexOutOfBoundsException();
        if (
            --budget.remaining < 0 ||
            ((++budget.reads & 1023) == 0 &&
                (Thread.currentThread().isInterrupted() || System.nanoTime() > budget.deadline))
        ) throw new LimitExceeded();
        return text.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
        if (from < 0 || to < from || to > length()) throw new IndexOutOfBoundsException();
        return new BoundedText(text, start + from, start + to, budget);
    }

    @Override
    public String toString() {
        return text.substring(start, end);
    }
}
