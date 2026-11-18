package com.forgeide.core.editor;

import com.forgeide.core.util.Listeners;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-oriented text document optimized for editor workloads.
 *
 * <p>Lines are held as {@code StringBuilder}s in an {@code ArrayList}, giving O(1)
 * random line access and fast intra-line edits. A lazily rebuilt line-start index
 * makes offset/position conversion O(log n). All mutating operations emit
 * {@link TextChange} events so undo/redo, tokenization and views can observe edits.
 *
 * <p>All public methods are synchronized so background threads (indexing,
 * tokenization) can safely take {@linkplain #snapshot() snapshots}.</p>
 */
public final class Document {

    public interface Listener {
        /** Called <em>after</em> a change has been applied. */
        void onTextChanged(Document document, TextChange change);
    }

    private final ArrayList<StringBuilder> lines = new ArrayList<>();
    private final Listeners<Listener> listeners = new Listeners<>();
    private int[] lineStartIndex;
    private int lengthCache = -1;

    public Document() {
        this("");
    }

    public Document(String initialText) {
        lines.add(new StringBuilder());
        setText(initialText == null ? "" : initialText);
    }

    // ---------------------------------------------------------------
    // Content access
    // ---------------------------------------------------------------

    public synchronized int lineCount() {
        return lines.size();
    }

    public synchronized String line(int index) {
        checkLine(index);
        return lines.get(index).toString();
    }

    public synchronized int lineLength(int index) {
        checkLine(index);
        return lines.get(index).length();
    }

    public synchronized int length() {
        if (lengthCache < 0) {
            int total = 0;
            for (int i = 0; i < lines.size(); i++) {
                total += lines.get(i).length();
                if (i < lines.size() - 1) {
                    total += 1; // newline separator
                }
            }
            lengthCache = total;
        }
        return lengthCache;
    }

    public synchronized char charAt(int offset) {
        Position p = positionAt(offset);
        if (offset >= length()) throw new IndexOutOfBoundsException();
        return p.column == lines.get(p.line).length() ? '\n' : lines.get(p.line).charAt(p.column);
    }

    public synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized String text(Range range) {
        clamp(range.start);
        clamp(range.end);
        return text().substring(range.start, range.end);
    }

    public synchronized String lineRange(int startLine, int endLineExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, startLine); i < Math.min(endLineExclusive, lines.size()); i++) {
            sb.append(lines.get(i));
            if (i < endLineExclusive - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Immutable snapshot of the full text for background processing. */
    public synchronized String snapshot() {
        return text();
    }

    // ---------------------------------------------------------------
    // Position mapping
    // ---------------------------------------------------------------

    public synchronized int offsetAt(Position position) {
        return offsetAt(position.line, position.column);
    }

    public synchronized int offsetAt(int line, int column) {
        checkLine(line);
        int col = Math.max(0, Math.min(column, lines.get(line).length()));
        ensureLineStartIndex();
        return lineStartIndex[line] + col;
    }

    public synchronized Position positionAt(int offset) {
        clamp(offset);
        ensureLineStartIndex();
        int line = binarySearchLine(lineStartIndex, offset);
        return new Position(line, offset - lineStartIndex[line]);
    }

    // ---------------------------------------------------------------
    // Edits
    // ---------------------------------------------------------------

    public synchronized void setText(String newText) {
        String old = text();
        String normalized = normalizeNewlines(newText == null ? "" : newText);
        lines.clear();
        lines.add(new StringBuilder());
        invalidate();
        if (!normalized.isEmpty()) {
            insertInternal(0, normalized);
        }
        invalidate();
        notifyChange(new TextChange(0, old, normalized));
    }

    public synchronized void insert(int offset, String text) {
        clamp(offset);
        if (text == null || text.isEmpty()) {
            return;
        }
        String normalized = normalizeNewlines(text);
        insertInternal(offset, normalized);
        invalidate();
        notifyChange(new TextChange(offset, "", normalized));
    }

    public synchronized void delete(int offset, int length) {
        if (length <= 0) {
            return;
        }
        clamp(offset);
        clamp(offset + length);
        String removed = text(new Range(offset, offset + length));
        deleteInternal(offset, length);
        invalidate();
        notifyChange(new TextChange(offset, removed, ""));
    }

    public synchronized void replace(int offset, int length, String text) {
        if (length <= 0) {
            insert(offset, text);
            return;
        }
        clamp(offset);
        clamp(offset + length);
        String removed = text(new Range(offset, offset + length));
        String normalized = normalizeNewlines(text == null ? "" : text);
        deleteInternal(offset, length);
        invalidate();
        if (!normalized.isEmpty()) {
            insertInternal(offset, normalized);
        }
        invalidate();
        notifyChange(new TextChange(offset, removed, normalized));
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChange(TextChange change) {
        listeners.forEach(l -> l.onTextChanged(this, change));
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private static String normalizeNewlines(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void insertInternal(int offset, String text) {
        Position p = positionAt(offset);
        StringBuilder line = lines.get(p.line);
        int nl = text.indexOf('\n');
        if (nl < 0) {
            line.insert(p.column, text);
            return;
        }
        // Split the current line at the caret.
        String tail = line.substring(p.column);
        line.delete(p.column, line.length());
        String[] parts = text.split("\n", -1);
        line.append(parts[0]);
        int insertAt = p.line + 1;
        for (int i = 1; i < parts.length - 1; i++) {
            lines.add(insertAt++, new StringBuilder(parts[i]));
        }
        StringBuilder last = new StringBuilder(parts[parts.length - 1]);
        last.append(tail);
        lines.add(insertAt, last);
    }

    private void deleteInternal(int offset, int length) {
        Position start = positionAt(offset);
        Position end = positionAt(offset + length);
        if (start.line == end.line) {
            lines.get(start.line).delete(start.column, end.column);
            return;
        }
        StringBuilder first = lines.get(start.line);
        first.delete(start.column, first.length());
        first.append(lines.get(end.line).substring(end.column));
        lines.subList(start.line + 1, end.line + 1).clear();
    }

    private void invalidate() {
        lineStartIndex = null;
        lengthCache = -1;
    }

    private void ensureLineStartIndex() {
        if (lineStartIndex != null && lineStartIndex.length == lines.size()) {
            return;
        }
        int[] index = new int[lines.size()];
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            index[i] = offset;
            offset += lines.get(i).length() + 1;
        }
        lineStartIndex = index;
    }

    private static int binarySearchLine(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private void checkLine(int line) {
        if (line < 0 || line >= lines.size()) {
            throw new IndexOutOfBoundsException("Line " + line + " out of " + lines.size());
        }
    }

    private void clamp(int offset) {
        if (offset < 0 || offset > length()) {
            throw new IndexOutOfBoundsException("Offset " + offset + " out of " + length());
        }
    }
}
