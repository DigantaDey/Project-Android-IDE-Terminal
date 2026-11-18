package com.forgeide.core.editor;

/** Describes a single edit applied to a document. Carries enough data to invert it. */
public final class TextChange {

    /** Offset at which the change begins. */
    public final int offset;
    /** Text removed by the change ("" for pure inserts). */
    public final String removedText;
    /** Text inserted by the change ("" for pure deletes). */
    public final String insertedText;

    public TextChange(int offset, String removedText, String insertedText) {
        this.offset = offset;
        this.removedText = removedText == null ? "" : removedText;
        this.insertedText = insertedText == null ? "" : insertedText;
    }

    public boolean isInsert() {
        return removedText.isEmpty() && !insertedText.isEmpty();
    }

    public boolean isDelete() {
        return !removedText.isEmpty() && insertedText.isEmpty();
    }

    /** The inverse change; applying it reverts this change. */
    public TextChange invert() {
        return new TextChange(offset, insertedText, removedText);
    }

    @Override
    public String toString() {
        return "TextChange@" + offset + " -" + removedText.length() + " +" + insertedText.length();
    }
}
