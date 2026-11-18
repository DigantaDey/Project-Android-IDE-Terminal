package com.forgeide.app.execution;

import android.content.*;
import android.os.*;
import com.forgeide.core.debug.Breakpoints;
import com.forgeide.core.toolchain.*;
import com.forgeide.core.sandbox.*;
import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns one isolated execution; service death completes the run and discards the sandbox. */
public final class ExecutionClient {
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    private IExecutionService service;private ServiceConnection connection;private CompletableFuture<Integer> future;
    public ExecutionClient(Context context){this.context=context.getApplicationContext();}
    public boolean running(){return future!=null&&!future.isDone();}
    public void control(String action,String expression){try{if(service!=null)service.control(action,expression);}catch(RemoteException ignored){}}
    public void cancel(){CompletableFuture<Integer> f=future;if(f!=null)f.complete(130);try{if(service!=null)service.cancel();}catch(RemoteException ignored){}cleanup();}
    private synchronized void cleanup(){if(connection!=null){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}connection=null;}service=null;}
    public synchronized CompletableFuture<Integer> run(String source,RunConfiguration config,SandboxPolicy policy,List<Breakpoints.Point> points,boolean debug,Consumer<String> output,Consumer<String> errors,Consumer<String> events){
        if(running())throw new IllegalStateException("Stop the active execution first");
        policy.requireSupported(SandboxPolicy.safeDefault().required);
        if(source.getBytes(StandardCharsets.UTF_8).length>512*1024)throw new IllegalArgumentException("Execution source is limited to 512 KiB");
        future=new CompletableFuture<>();CompletableFuture<Integer> result=future;
        Bundle b=new Bundle();b.putLong("timeout",policy.timeoutMillis);b.putInt("output",policy.outputBytes);b.putString("file",config.file);b.putString("stdin",config.stdin);b.putStringArray("arguments",config.arguments.toArray(new String[0]));b.putBoolean("debug",debug);
        Bundle env=new Bundle();config.environment.forEach(env::putString);b.putBundle("environment",env);
        List<Breakpoints.Point> selected=new ArrayList<>();for(Breakpoints.Point p:points)if(p.file.equals(config.file))selected.add(p);
        int[] lines=new int[selected.size()];String[] conditions=new String[selected.size()];for(int i=0;i<lines.length;i++){lines[i]=selected.get(i).line+1;conditions[i]=selected.get(i).condition;}b.putIntArray("breakpoints",lines);b.putStringArray("conditions",conditions);
        connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                service=IExecutionService.Stub.asInterface(binder);
                try{
                    binder.linkToDeath(()->{if(!result.isDone()){errors.accept("Sandbox terminated by watchdog, resource pressure, or Android.\n");result.complete(137);}main.post(()->cleanup());},0);
                    ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
                    service.run(pipe[0],b,new IExecutionCallback.Stub(){
                        @Override public void onOutput(String text){output.accept(text);}
                        @Override public void onEvent(String json){events.accept(json);}
                        @Override public void onFinished(int exit,String error,int line,int column){if(!error.isEmpty())errors.accept(config.file+":"+Math.max(1,line)+":"+Math.max(1,column)+": error: "+error+"\n");result.complete(exit);main.post(()->cleanup());}
                    });pipe[0].close();
                    CompletableFuture.runAsync(()->{try(OutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){stream.write(source.getBytes(StandardCharsets.UTF_8));}catch(IOException e){result.completeExceptionally(e);main.post(()->cleanup());}});
                }catch(Exception e){result.completeExceptionally(e);cleanup();}
            }
            @Override public void onServiceDisconnected(ComponentName name){if(!result.isDone())result.complete(137);cleanup();}
        };
        if(!context.bindService(new Intent(context,ExecutionService.class),connection,Context.BIND_AUTO_CREATE)){result.completeExceptionally(new IOException("Could not bind isolated runtime"));cleanup();}
        main.postDelayed(()->{if(!result.isDone()){errors.accept("Host execution deadline reached; sandbox stopped.\n");cancel();}},policy.timeoutMillis+2500);
        return result;
    }
}
