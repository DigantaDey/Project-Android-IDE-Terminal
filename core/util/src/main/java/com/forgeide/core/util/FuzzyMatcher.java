package com.forgeide.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Subsequence fuzzy matcher with scoring, used by the command palette and
 * quick-open. Rewards consecutive matches, word-boundary hits and early
 * matches so the most relevant entry floats to the top.
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {
    }

    public static final class Match {
        public final int score;
        public final List<int[]> ranges;

        Match(int score, List<int[]> ranges) {
            this.score = score;
            this.ranges = ranges;
        }
    }

    /** Returns a match with score > 0, or null when the pattern is not a subsequence. */
    public static Match match(String pattern, String text) {
        if (pattern == null || text == null) {
            return null;
        }
        if (pattern.isEmpty()) {
            return new Match(1, Collections.emptyList());
        }
        String p = pattern.toLowerCase();
        String t = text.toLowerCase();

        int ti = 0;
        int score = 0;
        int consecutive = 0;
        int lastMatchIndex = -2;
        List<int[]> ranges = new ArrayList<>();
        int rangeStart = -1;

        for (int pi = 0; pi < p.length(); pi++) {
            char pc = p.charAt(pi);
            int found = -1;
            while (ti < t.length()) {
                if (t.charAt(ti) == pc) {
                    found = ti;
                    break;
                }
                ti++;
            }
            if (found < 0) {
                return null;
            }
            int charScore = 1;
            if (found == lastMatchIndex + 1) {
                consecutive++;
                charScore += 10 + consecutive * 2; // streak bonus
            } else {
                consecutive = 0;
            }
            if (found == 0 || isBoundary(text.charAt(found - 1))) {
                charScore += 15; // word-boundary bonus
            }
            if (found < 8) {
                charScore += 8 - found; // early match bonus
            }
            score += charScore;
            if (found == lastMatchIndex + 1 && rangeStart >= 0) {
                // extend current range
            } else {
                if (rangeStart >= 0) {
                    ranges.add(new int[]{rangeStart, lastMatchIndex + 1});
                }
                rangeStart = found;
            }
            lastMatchIndex = found;
            ti = found + 1;
        }
        if (rangeStart >= 0) {
            ranges.add(new int[]{rangeStart, lastMatchIndex + 1});
        }
        // Shorter haystacks are preferred for equal quality
        score = score * 100 / Math.max(t.length(), 1);
        return new Match(score, ranges);
    }

    private static boolean isBoundary(char c) {
        return c == ' ' || c == '_' || c == '-' || c == '.' || c == '/' || c == '\\' || c == ':';
    }
}
