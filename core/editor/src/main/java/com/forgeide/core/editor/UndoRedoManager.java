package com.forgeide.core.editor;

/**
 * Undo/redo stack driven by {@link Document} change events.
 *
 * <p>Consecutive typing is coalesced into single undo steps: inserts merge with
 * the previous insert when they are adjacent (broken by newlines or explicit
 * {@link #breakCoalescing()}), deletes merge while backspacing/deleting runs.
 * Compound groups let multi-edit operations (e.g. replace-all) undo atomically.</p>
 */
public final class UndoRedoManager implements Document.Listener {

    private static final int MAX_ENTRIES = 1000;

    private final Document document;
    private final java.util.ArrayDeque<Entry> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Entry> redoStack = new java.util.ArrayDeque<>();
    private boolean applying;
    private int compoundDepth;
    private Entry currentCompound;

    private static final class Entry {
        final java.util.List<TextChange> changes = new java.util.ArrayList<>();
        // Coalescing state of the most recent change
        boolean canMergeInsert;
        boolean canMergeDelete;

        void add(TextChange change) {
            changes.add(change);
            canMergeInsert = change.isInsert() && !change.insertedText.contains("\n");
            canMergeDelete = change.isDelete();
        }
    }

    public UndoRedoManager(Document document) {
        this.document = document;
        document.addListener(this);
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Clears history, e.g. after loading a file from disk. */
    public synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    /** Forces the next change to start a new undo entry. */
    public synchronized void breakCoalescing() {
        if (!undoStack.isEmpty()) {
            Entry top = undoStack.peek();
            top.canMergeInsert = false;
            top.canMergeDelete = false;
        }
    }

    /** Starts an atomic group of changes. Must be balanced with {@link #endCompound()}. */
    public synchronized void beginCompound() {
        compoundDepth++;
    }

    public synchronized void endCompound() {
        if (compoundDepth <= 0) {
            return;
        }
        compoundDepth--;
        if (compoundDepth == 0 && currentCompound != null) {
            if (!currentCompound.changes.isEmpty()) {
                currentCompound.canMergeInsert = false;
                currentCompound.canMergeDelete = false;
                pushUndo(currentCompound);
            }
            currentCompound = null;
        }
    }

    @Override
    public void onTextChanged(Document doc, TextChange change) {
        if (applying) {
            return; // Changes caused by undo/redo are recorded explicitly.
        }
        synchronized (this) {
            redoStack.clear();
            if (compoundDepth > 0) {
                if (currentCompound == null) {
                    currentCompound = new Entry();
                }
                currentCompound.add(change);
                return;
            }
            Entry top = undoStack.peek();
            if (top != null) {
                if (change.isInsert() && !change.insertedText.contains("\n") && top.canMergeInsert
                        && top.changes.get(top.changes.size() - 1).offset
                        + top.changes.get(top.changes.size() - 1).insertedText.length() == change.offset) {
                    top.add(change);
                    return;
                }
                if (change.isDelete() && top.canMergeDelete) {
                    TextChange last = top.changes.get(top.changes.size() - 1);
                    // Backspace: deletes immediately before the previous deletion
                    // Delete-forward: deletes at the same offset
                    if (change.offset + change.removedText.length() == last.offset
                            || change.offset == last.offset) {
                        top.add(change);
                        return;
                    }
                }
            }
            Entry entry = new Entry();
            entry.add(change);
            pushUndo(entry);
        }
    }

    public synchronized boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        Entry entry = undoStack.pop();
        apply(entry, true);
        redoStack.push(entry);
        return true;
    }

    public synchronized boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        Entry entry = redoStack.pop();
        apply(entry, false);
        undoStack.push(entry);
        return true;
    }

    private void apply(Entry entry, boolean reverse) {
        applying = true;
        try {
            // Apply inverse changes in reverse order for undo, forward for redo.
            if (reverse) {
                for (int i = entry.changes.size() - 1; i >= 0; i--) {
                    TextChange c = entry.changes.get(i).invert();
                    document.replace(c.offset, c.removedText.length(), c.insertedText);
                }
            } else {
                for (TextChange c : entry.changes) {
                    document.replace(c.offset, c.removedText.length(), c.insertedText);
                }
            }
        } finally {
            applying = false;
        }
    }

    private void pushUndo(Entry entry) {
        undoStack.push(entry);
        while (undoStack.size() > MAX_ENTRIES) {
            undoStack.removeLast();
        }
    }
}
