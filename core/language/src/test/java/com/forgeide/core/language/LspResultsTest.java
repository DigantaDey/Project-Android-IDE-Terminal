package com.forgeide.core.language;

import static org.junit.Assert.*;

import com.forgeide.core.util.*;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

public class LspResultsTest {

    private static Map<String, Object> pos(int line, int col) {
        return Map.of("line", line, "character", col);
    }

    private static Map<String, Object> range(int start, int end) {
        return Map.of("start", pos(0, start), "end", pos(0, end));
    }

    private static JsonObject result(Object value) {
        return new JsonObject(Map.of("result", value));
    }

    @Test
    public void projectUrisRoundTripUnicodeSpacesAndPrimaryUserAlias() {
        LspProjectUris uris = new LspProjectUris("/data/user/0/com.forgex/cache/sessions/id/project");
        String name = "src/বাংলা 🐍 #file?.ts";
        String uri = uris.uri(name);
        assertTrue(uri.contains("%20"));
        assertEquals(name, uris.relative(uri));
        assertEquals(name, uris.relative(uri.replace("/data/user/0/", "/data/data/")));
        assertTrue(uris.rootUri().endsWith("/"));
    }

    @Test
    public void serverUrisCannotAccessOtherProjectsMetadataOrHostResources() {
        LspProjectUris uris = new LspProjectUris("/copy/project");
        for (String value : List.of(
            "file:///copy/project2/secret",
            "file:///copy/project/../private",
            "file:///copy/project/%2e%2e/private",
            "file:///copy/project/.git/config",
            "file:///copy/project/src/.forge/settings.json",
            "file:///data/data/com.forgeide.app/files/secret",
            "https://example.com/source",
            "content://provider/file",
            "file://host/copy/project/main.ts",
            "file:///copy/project/main.ts?x",
            "file:///copy/project/main.ts#x",
            "file:///copy/project/a%5cb.ts",
            "file:///copy/project/a%00b.ts",
            "file:///copy/project/"
        ))
            assertNull(value, uris.relative(value));
        for (String name : List.of("../x", "/etc/passwd", ".forge/token", "dir//a", "a/./b", "a\\b", "a:b"))
            try {
                uris.uri(name);
                fail(name);
            } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void utf16PositionsHandleMixedNewlinesAndRejectSurrogateSplits() {
        String source = "a😀\r\nβ\rthird\n";
        assertEquals(3, LspResults.offset(source, new JsonObject(pos(0, 3))));
        assertEquals(6, LspResults.offset(source, new JsonObject(pos(1, 1))));
        assertEquals(7, LspResults.offset(source, new JsonObject(pos(2, 0))));
        assertEquals(-1, LspResults.offset(source, new JsonObject(pos(0, 2))));
        assertEquals(-1, LspResults.offset(source, new JsonObject(pos(1, 2))));
        assertEquals(-1, LspResults.offset(source, new JsonObject(pos(-1, 0))));
        assertEquals(-1, LspResults.offset(source, new JsonObject(Map.of("line", 0.5, "character", 0))));
        assertEquals(
            -1,
            LspResults.offset(source, new JsonObject(Map.of("line", 4294967296L, "character", 0)))
        );
    }

    @Test
    public void completionListsRespectReplacementRangesAndPlainTextOnly() {
        List<Object> items = List.of(
            Map.of(
                "label",
                "toFixed",
                "detail",
                "number method",
                "textEdit",
                Map.of("range", range(6, 10), "newText", "toFixed")
            ),
            Map.of("label", "snippet", "insertTextFormat", 2, "insertText", "${1:value}"),
            Map.of("label", "autoImport", "additionalTextEdits", List.of(Map.of())),
            Map.of("label", "command", "command", Map.of("command", "download")),
            Map.of("label", "outside", "textEdit", Map.of("range", range(99, 101), "newText", "bad"))
        );
        List<LspResults.Completion> found = LspResults.completions(
            result(Map.of("items", items, "isIncomplete", false)),
            "value.toFi",
            10,
            6
        );
        assertEquals(1, found.size());
        assertEquals("toFixed", found.get(0).text);
        assertEquals(6, found.get(0).start);
        assertEquals(10, found.get(0).end);
    }

    @Test
    public void simpleCompletionAndInsertReplaceRangesAreBounded() {
        assertEquals(
            100,
            LspResults.completions(
                result(Collections.nCopies(3000, Map.of("label", "field"))),
                "x.",
                2,
                2
            ).size()
        );
        assertTrue(LspResults.completions(result(List.of(Map.of("label", "field"))), "x", 1, 2).isEmpty());
        JsonObject response = result(
            List.of(
                Map.of(
                    "label",
                    "field",
                    "textEdit",
                    Map.of("replace", range(2, 4), "insert", range(2, 3), "newText", "field")
                )
            )
        );
        LspResults.Completion item = LspResults.completions(response, "x.fi", 3, 2).get(0);
        assertEquals(4, item.end);
    }

    @Test
    public void navigationAcceptsLocationAndLinkButNeverExternalUris() {
        LspProjectUris uris = new LspProjectUris("/copy");
        JsonObject locations = result(
            List.of(
                Map.of("uri", uris.uri("one.ts"), "range", range(2, 3)),
                Map.of("targetUri", uris.uri("two.ts"), "targetSelectionRange", range(4, 5)),
                Map.of("uri", "file:///runtime/typescript/lib/lib.es5.d.ts", "range", range(0, 1))
            )
        );
        List<LspResults.Location> found = LspResults.locations(locations, uris);
        assertEquals(2, found.size());
        assertEquals("one.ts", found.get(0).file);
        assertEquals("two.ts", found.get(1).file);
        assertEquals(4, found.get(1).column);
    }

    @Test
    public void symbolsFlattenHierarchyAndSymbolInformationSafely() {
        LspProjectUris uris = new LspProjectUris("/copy");
        JsonObject response = result(
            List.of(
                Map.of(
                    "name",
                    "Rocket",
                    "selectionRange",
                    range(0, 1),
                    "children",
                    List.of(Map.of("name", "speed", "range", range(2, 3)))
                ),
                Map.of("name", "other", "location", Map.of("uri", uris.uri("other.py"), "range", range(4, 5)))
            )
        );
        List<LspResults.Location> found = LspResults.symbols(response, uris, "main.py");
        assertEquals(3, found.size());
        assertEquals("speed", found.get(1).label);
        assertEquals("other.py", found.get(2).file);
    }

    @Test
    public void hoverIsBoundedTextAndDoesNotRenderOrFetchContent() {
        assertEquals(
            "number\nhttps://untrusted.invalid",
            LspResults.hover(
                result(
                    Map.of(
                        "contents",
                        List.of(
                            Map.of("language", "typescript", "value", "number"),
                            Map.of("kind", "markdown", "value", "https://untrusted.invalid")
                        )
                    )
                )
            )
        );
        assertEquals(16384, LspResults.hover(result(Map.of("contents", "x".repeat(20000)))).length());
    }

    @Test
    public void diagnosticsHaveSafeCoordinatesBoundedMessagesAndProviderLabels() {
        LspProjectUris uris = new LspProjectUris("/copy");
        JsonObject params = new JsonObject(
            Map.of(
                "uri",
                uris.uri("main.ts"),
                "diagnostics",
                List.of(
                    Map.of("message", "x".repeat(10000), "severity", 2, "range", range(1, 2)),
                    Map.of("message", "bad", "range", range(-1, 0))
                )
            )
        );
        List<Diagnostic> found = LspResults.diagnostics(params, uris, "typescript");
        assertEquals(1, found.size());
        assertEquals(8192, found.get(0).message.length());
        assertEquals(Diagnostic.Severity.WARNING, found.get(0).severity);
        assertEquals("LSP · typescript", found.get(0).source);
    }

    @Test
    public void formattingAppliesNonOverlappingEditsInOriginalCoordinateSpace() throws Exception {
        assertEquals(
            "A b C",
            LspResults.applyEdits(
                "a b c",
                result(
                    List.of(
                        Map.of("range", range(0, 1), "newText", "A"),
                        Map.of("range", range(4, 5), "newText", "C")
                    )
                )
            )
        );
    }

    @Test
    public void formattingRejectsInvalidOverlappingOversizeAndSurrogateEditsAtomically() throws Exception {
        for (JsonObject response : List.of(
            result(
                List.of(
                    Map.of("range", range(0, 2), "newText", "a"),
                    Map.of("range", range(1, 3), "newText", "b")
                )
            ),
            result(List.of(Map.of("range", range(2, 1), "newText", "a"))),
            result(List.of(Map.of("range", range(0, 99), "newText", "a"))),
            result(List.of(Map.of("range", range(0, 0), "newText", "x".repeat(LspResults.MAX_TEXT + 1))))
        )) {
            try {
                LspResults.applyEdits("abc", response);
                fail();
            } catch (IOException expected) {}
        }
        try {
            LspResults.applyEdits("😀", result(List.of(Map.of("range", range(0, 1), "newText", "X"))));
            fail();
        } catch (IOException expected) {}
    }
}
