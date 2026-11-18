package com.forgeide.core.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Tracks foldable regions and which of them are collapsed, and answers
 * visibility queries the view needs (which lines are hidden, what region
 * starts at a line).
 */
public final class FoldingModel {

    private List<FoldingRegion> regions = new ArrayList<>();
    private final TreeSet<Integer> foldedStartLines = new TreeSet<>();

    public synchronized void setRegions(List<FoldingRegion> newRegions) {
        regions = new ArrayList<>(newRegions);
        Collections.sort(regions, (a, b) ->
            a.startLine != b.startLine
                ? Integer.compare(a.startLine, b.startLine)
                : Integer.compare(b.endLine, a.endLine)
        ); // outer regions first
        // Drop folds that no longer correspond to a region.
        List<Integer> valid = new ArrayList<>();
        for (FoldingRegion r : regions) {
            valid.add(r.startLine);
        }
        foldedStartLines.retainAll(new TreeSet<>(valid));
    }

    public synchronized List<FoldingRegion> regions() {
        return Collections.unmodifiableList(regions);
    }

    public synchronized boolean fold(int startLine) {
        if (findRegion(startLine) == null) {
            return false;
        }
        return foldedStartLines.add(startLine);
    }

    public synchronized boolean unfold(int startLine) {
        return foldedStartLines.remove(startLine);
    }

    public synchronized boolean toggle(int startLine) {
        if (foldedStartLines.contains(startLine)) {
            unfold(startLine);
            return false;
        }
        return fold(startLine);
    }

    public synchronized void unfoldAll() {
        foldedStartLines.clear();
    }

    public synchronized void foldAll() {
        for (FoldingRegion r : regions) {
            // Only fold top-level regions for a sane default
            boolean nested = false;
            for (FoldingRegion other : regions) {
                if (other != r && other.startLine < r.startLine && other.endLine >= r.endLine) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                foldedStartLines.add(r.startLine);
            }
        }
    }

    public synchronized boolean isFolded(int startLine) {
        return foldedStartLines.contains(startLine);
    }

    public synchronized FoldingRegion findRegion(int startLine) {
        for (FoldingRegion r : regions) {
            if (r.startLine == startLine) {
                return r;
            }
        }
        return null;
    }

    /** True when the line is hidden inside some folded region. */
    public synchronized boolean isLineVisible(int line) {
        for (int start : foldedStartLines) {
            if (start >= line) {
                break;
            }
            FoldingRegion r = findRegion(start);
            if (r != null && line > r.startLine && line <= r.endLine) {
                return false;
            }
        }
        return true;
    }

    /**
     * Maps a visual (visible-only) row index to the actual document line.
     * The view iterates rows; folded-away lines are skipped.
     */
    public synchronized int visibleRowToLine(int row, int lineCount) {
        int visible = -1;
        for (int line = 0; line < lineCount; line++) {
            if (isLineVisible(line)) {
                visible++;
                if (visible == row) {
                    return line;
                }
            }
        }
        return -1;
    }

    public synchronized int visibleRowCount(int lineCount) {
        int count = 0;
        for (int line = 0; line < lineCount; line++) {
            if (isLineVisible(line)) {
                count++;
            }
        }
        return count;
    }
}
