package com.forgeide.core.language;

import com.forgeide.core.editor.IndentRules;
import java.util.*;

/** Thread-safe, extensible language and lexer registry. No network dependency. */
public final class LanguageRegistry {

    private final Map<String, Language> languages = new LinkedHashMap<>();
    private final Map<String, Tokenizer> tokenizers = new HashMap<>();

    public synchronized void register(Language language, Tokenizer tokenizer) {
        languages.put(language.id(), language);
        if (tokenizer != null) tokenizers.put(language.id(), tokenizer);
    }

    public synchronized Language get(String id) {
        return languages.get(id);
    }

    public synchronized Tokenizer tokenizer(String id) {
        return tokenizers.getOrDefault(id, text ->
            text.isEmpty() ? List.of() : List.of(new Token(0, text.length(), TokenType.PLAIN))
        );
    }

    public synchronized List<Language> all() {
        return new ArrayList<>(languages.values());
    }

    public synchronized Language forFile(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        for (Language l : languages.values()) if (l.fileNames().contains(name)) return l;
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Language l : languages.values()) if (l.extensions().contains(ext)) return l;
        return languages.get("plaintext");
    }

    private static Set<String> words(String text) {
        return new HashSet<>(Arrays.asList(text.split("\\s+")));
    }

    public static LanguageRegistry builtins() {
        LanguageRegistry r = new LanguageRegistry();
        r.register(new Language.Builder("plaintext", "Plain text").extensions("txt", "log").build(), null);
        String c =
            "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Bool _Complex _Imaginary";
        String cpp =
            c +
            " alignas alignof asm bool catch class concept constexpr consteval constinit decltype delete dynamic_cast explicit export false friend mutable namespace new noexcept nullptr operator private protected public reinterpret_cast requires static_assert static_cast template this thread_local throw true try typename using virtual wchar_t override final";
        String java =
            "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record sealed permits yield";
        addC(r, "c", "C", c, new String[] { "c", "h" }, true, false);
        addC(r, "cpp", "C++", cpp, new String[] { "cpp", "cc", "cxx", "hpp", "hxx", "hh" }, true, false);
        addC(r, "java", "Java", java, new String[] { "java" }, false, true);
        addC(
            r,
            "kotlin",
            "Kotlin",
            "fun val var object class interface package import as is in when if else for while return null true false data sealed override private public internal suspend by lazy",
            new String[] { "kt", "kts" },
            false,
            true
        );
        String js =
            "async await break case catch class const continue debugger default delete do else export extends false finally for from function get if import in instanceof let new null of return set static super switch this throw true try typeof undefined var void while with yield";
        Set<String> built = words(
            "console Math JSON Object Array Number String Boolean Date Promise Map Set Error RegExp print parseInt parseFloat NaN Infinity require module exports"
        );
        Language javascript = new Language.Builder("javascript", "JavaScript")
            .extensions("js", "mjs", "cjs", "jsx")
            .lineComment("//")
            .blockComment("/*", "*/")
            .indentRules(IndentRules.braces())
            .keywordsFrom(js)
            .build();
        JsTokenizer jsLexer = new JsTokenizer(javascript.keywords(), built);
        r.register(javascript, jsLexer);
        Language ts = new Language.Builder("typescript", "TypeScript")
            .extensions("ts", "tsx")
            .lineComment("//")
            .blockComment("/*", "*/")
            .indentRules(IndentRules.braces())
            .keywordsFrom(
                js +
                    " abstract any as asserts boolean declare enum implements infer interface is keyof namespace never number private protected public readonly string symbol type unknown"
            )
            .build();
        r.register(ts, new JsTokenizer(ts.keywords(), built));
        Language py = new Language.Builder("python", "Python")
            .extensions("py", "pyw", "pyi")
            .lineComment("#")
            .indentRules(IndentRules.python())
            .keywordsFrom(
                "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case"
            )
            .build();
        r.register(
            py,
            new PythonTokenizer(
                py.keywords(),
                words(
                    "print len range str int float list dict set tuple open enumerate zip sum min max abs sorted input isinstance super self Exception ValueError TypeError"
                )
            )
        );
        CssTokenizer css = new CssTokenizer();
        r.register(
            new Language.Builder("html", "HTML")
                .extensions("html", "htm")
                .blockComment("<!--", "-->")
                .build(),
            new MarkupTokenizer(true, jsLexer, css)
        );
        r.register(
            new Language.Builder("xml", "XML")
                .extensions("xml", "svg", "xhtml", "pom")
                .blockComment("<!--", "-->")
                .build(),
            new MarkupTokenizer(false, null, null)
        );
        r.register(
            new Language.Builder("css", "CSS")
                .extensions("css", "scss", "less")
                .blockComment("/*", "*/")
                .indentRules(IndentRules.braces())
                .build(),
            css
        );
        r.register(
            new Language.Builder("json", "JSON")
                .extensions("json", "ipynb", "jsonl")
                .indentRules(IndentRules.braces())
                .build(),
            new JsonTokenizer()
        );
        r.register(
            new Language.Builder("markdown", "Markdown")
                .extensions("md", "markdown", "mdown")
                .wordWrapPreferred(true)
                .build(),
            new MarkdownTokenizer()
        );
        Language sql = new Language.Builder("sql", "SQL")
            .extensions("sql")
            .lineComment("--")
            .blockComment("/*", "*/")
            .keywordsFrom(
                "SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER ON AS AND OR NOT NULL TRUE FALSE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE DROP ALTER INDEX PRIMARY KEY FOREIGN REFERENCES GROUP BY ORDER ASC DESC LIMIT OFFSET HAVING DISTINCT UNION ALL WITH CASE WHEN THEN ELSE END IS LIKE IN EXISTS COUNT SUM AVG MIN MAX BEGIN COMMIT ROLLBACK"
            )
            .build();
        r.register(sql, new ScriptTokenizer(sql.keywords(), true));
        Language sh = new Language.Builder("shellscript", "Shell / Bash")
            .extensions("sh", "bash", "zsh")
            .fileNames(".bashrc", ".profile", "Makefile")
            .lineComment("#")
            .keywordsFrom(
                "if then else elif fi case esac for while until do done in function select time coproc export local readonly return break continue source alias unalias set unset shift eval exec exit echo printf cd pwd ls cat mkdir rm cp mv touch help"
            )
            .build();
        r.register(sh, new ScriptTokenizer(sh.keywords(), false));
        return r;
    }

    private static void addC(
        LanguageRegistry r,
        String id,
        String name,
        String words,
        String[] ext,
        boolean pp,
        boolean annotations
    ) {
        Language l = new Language.Builder(id, name)
            .extensions(ext)
            .lineComment("//")
            .blockComment("/*", "*/")
            .indentRules(IndentRules.braces())
            .keywordsFrom(words)
            .build();
        r.register(l, new CLikeTokenizer(l.keywords(), Set.of(), pp, annotations));
    }
}
