package com.forgeide.core.util;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared asynchronous LSP / Debug Adapter Protocol transport. Owns streams, not a host process. */
public final class ProtocolClient implements AutoCloseable {
    private final InputStream input; private final OutputStream output; private final boolean dap;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long,CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"protocol-timeouts");t.setDaemon(true);return t; });
    private final Consumer<JsonObject> events; private volatile boolean closed;
    public ProtocolClient(InputStream input, OutputStream output, boolean dap, Consumer<JsonObject> events) {
        this.input=input;this.output=output;this.dap=dap;this.events=events;
        Thread reader=new Thread(this::readLoop,dap?"dap-reader":"lsp-reader");reader.setDaemon(true);reader.start();
    }
    public CompletableFuture<JsonObject> request(String method, Map<String,?> params) {
        CompletableFuture<JsonObject> future=new CompletableFuture<>(); if(closed){future.completeExceptionally(new IOException("Protocol disconnected"));return future;}
        long id=sequence.incrementAndGet();pending.put(id,future);
        Map<String,Object> m=new LinkedHashMap<>();
        if(dap){m.put("seq",id);m.put("type","request");m.put("command",method);m.put("arguments",params);}
        else {m.put("jsonrpc","2.0");m.put("id",id);m.put("method",method);m.put("params",params);}
        ScheduledFuture<?> timeout=deadlines.schedule(() -> {CompletableFuture<JsonObject> p=pending.remove(id);if(p!=null)p.completeExceptionally(new TimeoutException(method+" timed out"));},30,TimeUnit.SECONDS);
        future.whenComplete((v,e)->{pending.remove(id);timeout.cancel(false);});
        try{FramedJson.write(output,m);}catch(IOException e){future.completeExceptionally(e);}return future;
    }
    public void notify(String method,Map<String,?> params)throws IOException {if(dap)throw new IllegalStateException("DAP uses requests/events");FramedJson.write(output,Map.of("jsonrpc","2.0","method",method,"params",params));}
    private void readLoop(){
        Throwable failure=new EOFException("Protocol peer closed");
        try{Map<String,Object> raw;while(!closed&&(raw=FramedJson.read(input))!=null){JsonObject m=new JsonObject(raw);
            boolean response=dap?m.getString("type","").equals("response"):m.has("id")&&!m.has("method");
            if(response){long id=m.getLong(dap?"request_seq":"id",-1);CompletableFuture<JsonObject> f=pending.remove(id);if(f!=null){if(m.has("error")||(dap&&!m.getBoolean("success",false)))f.completeExceptionally(new IOException(dap?m.getString("message","Adapter request failed"):m.getObject("error").getString("message","Server request failed")));else f.complete(m);}}
            else if(events!=null)events.accept(m);
        }}catch(Throwable e){failure=e;}finally{closed=true;for(CompletableFuture<JsonObject> f:pending.values())f.completeExceptionally(failure);pending.clear();deadlines.shutdownNow();try{input.close();output.close();}catch(IOException ignored){}}
    }
    @Override public void close()throws IOException{closed=true;deadlines.shutdownNow();for(CompletableFuture<JsonObject> f:pending.values())f.completeExceptionally(new IOException("Protocol closed"));pending.clear();input.close();output.close();}
}
