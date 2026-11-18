package com.forgeide.app.execution;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.forgeide.core.runtime.*;
import com.forgeide.core.sandbox.*;
import com.forgeide.core.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** OS-enforced, ephemeral isolated UID. No app files, inherited permissions, native shell or credentials. */
public final class ExecutionService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler watchdog=new Handler(Looper.getMainLooper());
    private CancellationToken token;private ScriptDebugger debugger;private volatile boolean running;
    private void verifyCaller(){if(Binder.getCallingUid()!=getApplicationInfo().uid)throw new SecurityException("Execution is private to Forge IDE");}
    private final IExecutionService.Stub binder=new IExecutionService.Stub(){
        @Override public synchronized void run(ParcelFileDescriptor source,Bundle config,IExecutionCallback callback){
            verifyCaller();if(running)throw new IllegalStateException("An execution is already active");running=true;token=new CancellationToken();
            long time=Math.max(100,Math.min(60000,config.getLong("timeout",10000)));
            int output=Math.max(1024,Math.min(1024*1024,config.getInt("output",262144)));
            // Watchdog is outside the interpreter. Native allocation or interpreter bugs cannot defeat the kill.
            watchdog.postDelayed(()->{if(running)android.os.Process.killProcess(android.os.Process.myPid());},time+1000);
            worker.execute(()->{
                try(ParcelFileDescriptor.AutoCloseInputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(source)){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                    while((n=stream.read(buf))!=-1){if(bytes.size()+n>512*1024)throw new IOException("Source exceeds 512 KiB");bytes.write(buf,0,n);}
                    callback.onEvent(Json.write(Map.of("event","sandboxReady","uid",android.os.Process.myUid(),"isolated",android.os.Process.myUid()!=getApplicationInfo().uid,"hostFilesReadable",getFilesDir().canRead(),"networkPermission",checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)));
                    String code=bytes.toString(StandardCharsets.UTF_8.name());
                    if(config.getString("language","javascript").equals("sql")){
                        int exit=SqlRuntime.run(code,output,text->{try{callback.onOutput(text);}catch(RemoteException e){throw new IllegalStateException(e);}},error->{try{callback.onOutput(config.getString("file","main.sql")+":1:1: error: "+error+"\n");}catch(RemoteException e){throw new IllegalStateException(e);}});
                        callback.onFinished(exit,"",0,0);return;
                    }
                    Map<String,String> env=new LinkedHashMap<>();Bundle values=config.getBundle("environment");if(values!=null)for(String key:values.keySet()){if(env.size()>=100)break;env.put(key,values.getString(key,""));}
                    if(config.getBoolean("debug")){
                        Map<Integer,String> points=new HashMap<>();int[] lines=config.getIntArray("breakpoints");String[] conditions=config.getStringArray("conditions");
                        if(lines!=null)for(int i=0;i<Math.min(lines.length,1000);i++)points.put(lines[i],conditions!=null&&i<conditions.length?conditions[i]:"");
                        debugger=new ScriptDebugger(points,json->{try{callback.onEvent(json);}catch(RemoteException e){token.cancel();}});
                    }
                    SandboxPolicy policy=new SandboxPolicy(time,output,SandboxPolicy.safeDefault().required);
                    String[] args=config.getStringArray("arguments");
                    JavaScriptRuntime.Result result=new JavaScriptRuntime().run(code,config.getString("file","main.js"),args==null?List.of():Arrays.asList(args),env,config.getString("stdin",""),policy,token,text->{try{callback.onOutput(text);}catch(RemoteException e){token.cancel();}},debugger);
                    callback.onFinished(result.exitCode,result.error,result.line,result.column);
                }catch(Exception e){try{callback.onFinished(1,String.valueOf(e.getMessage()),0,0);}catch(RemoteException ignored){}}
                finally{running=false;watchdog.removeCallbacksAndMessages(null);debugger=null;}
            });
        }
        @Override public void cancel(){verifyCaller();android.os.Process.killProcess(android.os.Process.myPid());}
        @Override public void control(String action,String expression){verifyCaller();ScriptDebugger current=debugger;if(current!=null)current.control(action,expression);}
    };
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){android.os.Process.killProcess(android.os.Process.myPid());return false;}
    @Override public void onDestroy(){if(running)android.os.Process.killProcess(android.os.Process.myPid());worker.shutdownNow();watchdog.removeCallbacksAndMessages(null);super.onDestroy();}
}
