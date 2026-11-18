package com.forgeide.core.editor;

import static org.junit.Assert.*;

import org.junit.Test;

public class IndentationEngineTest {

    private final IndentationEngine spaces4 = new IndentationEngine(4, false);
    private final IndentationEngine tabs = new IndentationEngine(4, true);

    @Test
    public void braceLanguageDeepensAfterOpenBrace() {
        String indent = spaces4.indentForNewLine(IndentRules.braces(), "    void f() {");
        assertEquals("        ", indent);
    }

    @Test
    public void braceLanguageCopiesIndent() {
        assertEquals("    ", spaces4.indentForNewLine(IndentRules.braces(), "    int x = 1;"));
        assertEquals("", spaces4.indentForNewLine(IndentRules.braces(), "int x = 1;"));
    }

    @Test
    public void pythonDeepensAfterColon() {
        IndentationEngine py = new IndentationEngine(4, false);
        assertEquals("        ", py.indentForNewLine(IndentRules.python(), "    if x:"));
        assertEquals("    ", py.indentForNewLine(IndentRules.python(), "    x = 1"));
    }

    @Test
    public void pythonDedentsAfterReturn() {
        IndentationEngine py = new IndentationEngine(4, false);
        assertEquals("    ", py.indentForNewLine(IndentRules.python(), "        return x"));
    }

    @Test
    public void tabsAreRespected() {
        assertEquals("\t\t", tabs.indentForNewLine(IndentRules.braces(), "\tvoid f() {"));
    }

    @Test
    public void dedentTriggerAdjustsLine() {
        String adjusted = spaces4.adjustForDedentTrigger(IndentRules.braces(), "        }");
        assertEquals("    }", adjusted);
        assertNull(spaces4.adjustForDedentTrigger(IndentRules.braces(), "        x = 1;"));
        assertNull(spaces4.adjustForDedentTrigger(IndentRules.braces(), "}")); // no indent to remove
    }

    @Test
    public void indentLinesSkipsBlankLines() {
        Document doc = new Document("a\n\nb");
        spaces4.indentLines(doc, 0, 2);
        assertEquals("    a\n\n    b", doc.text());
    }

    @Test
    public void dedentLinesRemovesUpToOneLevel() {
        Document doc = new Document("        a\n\tb\n    c\nx");
        spaces4.dedentLines(doc, 0, 3);
        assertEquals("    a\nb\nc\nx", doc.text());
    }
}
