package com.forgeide.core.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy word-wrap calculator. The column-based variant suits monospace text;
 * the measurer-based variant lets the Android view pass real Paint metrics.
 */
public final class WordWrap {

    /** Measures the rendered width of a text fragment. */
    public interface WidthMeasurer {
        float measure(String text, int start, int end);
    }

    /**
     * Computes wrap offsets for one logical line at a fixed column count.
     * Returns the offsets at which the line breaks (each break starts a new
     * visual row). Breaks prefer the last whitespace before the limit.
     */
    public static List<Integer> wrapLine(String line, int columns) {
        List<Integer> breaks = new ArrayList<>();
        if (columns <= 0) {
            return breaks;
        }
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + columns, line.length());
            if (end == line.length()) {
                break;
            }
            int breakAt = findBreak(line, start, end);
            breaks.add(breakAt);
            start = breakAt;
            while (start < line.length() && line.charAt(start) == ' ') {
                start++; // swallow the break whitespace
            }
        }
        return breaks;
    }

    /** Width-based variant using real text metrics. */
    public static List<Integer> wrapLine(String line, float maxWidth, WidthMeasurer measurer) {
        List<Integer> breaks = new ArrayList<>();
        int start = 0;
        int n = line.length();
        while (start < n) {
            int lo = start + 1;
            int hi = n;
            // binary search the longest prefix fitting maxWidth
            int fit = start;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (measurer.measure(line, start, mid) <= maxWidth) {
                    fit = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (fit >= n) {
                break;
            }
            if (fit <= start) {
                fit = start + 1; // always make progress
            }
            int breakAt = findBreak(line, start, fit);
            if (breakAt <= start) {
                breakAt = fit;
            }
            breaks.add(breakAt);
            start = breakAt;
            while (start < n && line.charAt(start) == ' ') {
                start++;
            }
        }
        return breaks;
    }

    private static int findBreak(String line, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                return i;
            }
        }
        return end; // hard break
    }
}
