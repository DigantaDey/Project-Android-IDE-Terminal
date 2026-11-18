package com.forgeide.core.editor;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutoCloseEngineTest {

    private final AutoCloseEngine engine = AutoCloseEngine.cStyle();

    @Test
    public void openBracketInsertsPairAtEnd() {
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('(', "f", ""));
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('{', "f() ", ""));
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('[', "a = ", ""));
    }

    @Test
    public void openBracketBeforeWordDoesNotPair() {
        assertEquals(AutoCloseEngine.Action.INSERT_SINGLE, engine.onCharTyped('(', "f", "x"));
    }

    @Test
    public void closingBracketOvertypes() {
        assertEquals(AutoCloseEngine.Action.OVERTYPE, engine.onCharTyped(')', "f(", ")"));
        assertEquals(AutoCloseEngine.Action.OVERTYPE, engine.onCharTyped('}', "{", "}"));
    }

    @Test
    public void closingBracketDoesNotOvertypeWhenUnbalanced() {
        // one '(' open and we type ')' before ')' -- that's balanced, overtype ok
        assertEquals(AutoCloseEngine.Action.OVERTYPE, engine.onCharTyped(')', "f(", ")"));
        // two '(' open, one ')' typed before ')' -- insert another is fine, no overtype
        assertEquals(AutoCloseEngine.Action.INSERT_SINGLE, engine.onCharTyped(')', "f(g(", ")"));
    }

    @Test
    public void quotesPairInOpeningContext() {
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('"', "s = ", ""));
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('"', "", ""));
        assertEquals(AutoCloseEngine.Action.INSERT_PAIR, engine.onCharTyped('"', "f(", ""));
    }

    @Test
    public void quotesDoNotPairAfterWordChar() {
        assertEquals(AutoCloseEngine.Action.INSERT_SINGLE, engine.onCharTyped('"', "word", ""));
    }

    @Test
    public void quoteOvertypesIdenticalQuote() {
        assertEquals(AutoCloseEngine.Action.OVERTYPE_QUOTE, engine.onCharTyped('"', "s = \"text", "\""));
    }

    @Test
    public void backspaceDeletesEmptyPair() {
        assertEquals(1, engine.backspacePairDelete("f(", ")"));
        assertEquals(1, engine.backspacePairDelete("s = \"", "\""));
        assertEquals(0, engine.backspacePairDelete("f(x", ")"));
        assertEquals(0, engine.backspacePairDelete("", ")"));
    }
}
