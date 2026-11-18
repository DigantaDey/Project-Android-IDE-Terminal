package com.forgeide.core.debug;

import com.forgeide.core.util.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real DAP requests for independently installed debuggers. Host shell launching is deliberately absent. */
public final class DapClient implements AutoCloseable {
    private final ProtocolClient rpc;
    public DapClient(InputStream in,OutputStream out,Consumer<JsonObject> events){rpc=new ProtocolClient(in,out,true,events);}
    public CompletableFuture<JsonObject> initialize(){return rpc.request("initialize",Map.of("clientID","forgeide","adapterID","provider","linesStartAt1",true,"columnsStartAt1",true,"pathFormat","path","supportsVariableType",true));}
    public CompletableFuture<JsonObject> launch(Map<String,?> config){return rpc.request("launch",config);}
    public CompletableFuture<JsonObject> breakpoints(String file,List<Map<String,Object>> points){return rpc.request("setBreakpoints",Map.of("source",Map.of("path",file),"breakpoints",points));}
    public CompletableFuture<JsonObject> control(String command,int threadId){return rpc.request(command,Map.of("threadId",threadId));}
    public CompletableFuture<JsonObject> stack(int threadId){return rpc.request("stackTrace",Map.of("threadId",threadId,"startFrame",0,"levels",100));}
    public CompletableFuture<JsonObject> scopes(int frameId){return rpc.request("scopes",Map.of("frameId",frameId));}
    public CompletableFuture<JsonObject> variables(int reference){return rpc.request("variables",Map.of("variablesReference",reference));}
    public CompletableFuture<JsonObject> evaluate(String expression,int frameId){return rpc.request("evaluate",Map.of("expression",expression,"frameId",frameId,"context","watch"));}
    public CompletableFuture<JsonObject> configured(){return rpc.request("configurationDone",Map.of());}
    @Override public void close()throws IOException{rpc.close();}
}
