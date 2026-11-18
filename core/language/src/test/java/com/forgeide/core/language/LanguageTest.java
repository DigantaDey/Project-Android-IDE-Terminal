package com.forgeide.core.language;
import com.forgeide.core.util.CancellationToken;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class LanguageTest {
 private final LanguageRegistry r=LanguageRegistry.builtins();
 private void tiles(String id,String source){int offset=0;for(Token t:r.tokenizer(id).tokenize(source)){assertEquals(id+" at "+offset,offset,t.start);assertTrue(t.length>0);offset=t.end();}assertEquals(id,source.length(),offset);}
 @Test public void associations(){assertEquals("cpp",r.forFile("src/hello.CPP").id());assertEquals("shellscript",r.forFile("Makefile").id());assertEquals("plaintext",r.forFile("unknown.zzz").id());assertEquals("typescript",r.forFile("a.tsx").id());}
 @Test public void highlightsKeywords(){List<Token> t=r.tokenizer("java").tokenize("public class Hello {}");assertEquals(TokenType.KEYWORD,t.get(0).type);}
 @Test(timeout=6000) public void allLexersTileMalformedAndUnicodeInputs(){Random random=new Random(42);String chars="abcXYZ1230_'\"<>{}[]()&;:#$/*=+-`~ \n\t\r😀";for(Language l:r.all()){for(String text:List.of("", "<a / broken attr='x'>hello &amp;</a>", "/* { */ ","a + /bad\n 42", "# header\n*bad [end", "r'''text\ntext'''", "<script>let x=1;</script><p>x</p>"))tiles(l.id(),text);for(int j=0;j<40;j++){StringBuilder b=new StringBuilder();for(int n=0;n<150;n++)b.append(chars.charAt(random.nextInt(chars.length())));tiles(l.id(),b.toString());}}}
 @Test public void pythonPrefixStringIncludesPrefix(){List<Token> t=r.tokenizer("python").tokenize("r'abc'");assertEquals(1,t.size());assertEquals(0,t.get(0).start);assertEquals(TokenType.STRING,t.get(0).type);}
 @Test public void jsonDiagnosticsHaveSourcePosition(){List<Diagnostic> d=new LocalLanguageService().diagnose(r.get("json"),"a.json","{\n \"x\": }",new CancellationToken());assertEquals(1,d.size());assertEquals(1,d.get(0).line);}
 @Test public void lexicalCompletionAndSymbols(){LocalLanguageService l=new LocalLanguageService();assertTrue(l.complete(r.get("python"),"ret",3,new CancellationToken()).contains("return"));assertEquals("greet",l.symbols(r.get("python"),"def greet(name):\n    return name",new CancellationToken()).get(0).name);}
 @Test public void foldsIgnoreBracesInStrings(){assertEquals(1,new LocalLanguageService().folds(r.get("java"),"class A {\n String s=\"{\";\n}",new CancellationToken()).size());}
}
