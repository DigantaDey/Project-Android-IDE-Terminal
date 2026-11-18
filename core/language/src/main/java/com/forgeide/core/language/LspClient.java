package com.forgeide.core.language;

import com.forgeide.core.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Optional LSP provider transport. Availability/capabilities are returned by initialize, never assumed. */
public final class LspClient implements AutoCloseable {
    private final ProtocolClient rpc;
    public LspClient(InputStream in,OutputStream out,Consumer<JsonObject> events){rpc=new ProtocolClient(in,out,false,events);}
    public CompletableFuture<JsonObject> initialize(String rootUri){return rpc.request("initialize",Map.of("processId",0,"rootUri",rootUri,"capabilities",Map.of("textDocument",Map.of("completion",Map.of("completionItem",Map.of("snippetSupport",false)))))).thenApply(r->{try{rpc.notify("initialized",Map.of());}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}return r;});}
    public void open(String uri,String language,int version,String text)throws IOException{rpc.notify("textDocument/didOpen",Map.of("textDocument",Map.of("uri",uri,"languageId",language,"version",version,"text",text)));}
    public void change(String uri,int version,String text)throws IOException{rpc.notify("textDocument/didChange",Map.of("textDocument",Map.of("uri",uri,"version",version),"contentChanges",List.of(Map.of("text",text))));}
    public void closeDocument(String uri)throws IOException{rpc.notify("textDocument/didClose",Map.of("textDocument",Map.of("uri",uri)));}
    public CompletableFuture<JsonObject> at(String method,String uri,int line,int column){return rpc.request("textDocument/"+method,Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",line,"character",column)));}
    public CompletableFuture<JsonObject> references(String uri,int line,int column){return rpc.request("textDocument/references",Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",line,"character",column),"context",Map.of("includeDeclaration",true)));}
    public CompletableFuture<JsonObject> symbols(String uri){return rpc.request("textDocument/documentSymbol",Map.of("textDocument",Map.of("uri",uri)));}
    public CompletableFuture<JsonObject> format(String uri,int tabSize,boolean spaces){return rpc.request("textDocument/formatting",Map.of("textDocument",Map.of("uri",uri),"options",Map.of("tabSize",tabSize,"insertSpaces",spaces)));}
    @Override public void close()throws IOException{rpc.close();}
}
