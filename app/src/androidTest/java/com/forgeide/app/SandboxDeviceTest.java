package com.forgeide.app;

import android.content.Context;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.execution.ExecutionClient;
import com.forgeide.core.sandbox.SandboxPolicy;
import com.forgeide.core.toolchain.RunConfiguration;
import com.forgeide.core.util.JsonObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SandboxDeviceTest {
    private RunConfiguration config(){return new RunConfiguration("Device test","javascript","test.js","",List.of(),Map.of(),"",List.of(),List.of(),List.of());}
    @Test public void runtimeHasDifferentUidAndNoNetworkPermission() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ExecutionClient client=new ExecutionClient(context);
        AtomicReference<CompletableFuture<Integer>> completion=new AtomicReference<>();
        AtomicReference<JsonObject> identity=new AtomicReference<>();
        StringBuffer output=new StringBuffer();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->completion.set(client.run("print(6 * 7, typeof Packages, typeof java, typeof require);",config(),SandboxPolicy.safeDefault(),List.of(),false,output::append,output::append,event->{JsonObject j=JsonObject.parse(event);if(j.getString("event","").equals("sandboxReady"))identity.set(j);})));
        assertEquals(Integer.valueOf(0),completion.get().get(20,TimeUnit.SECONDS));
        assertTrue(output.toString(),output.toString().contains("42 undefined undefined undefined"));
        JsonObject ready=identity.get();assertNotNull("Runtime must report OS isolation identity",ready);
        assertNotEquals(android.os.Process.myUid(),ready.getInt("uid",0));
        assertFalse(ready.getBoolean("hostFilesReadable",true));assertTrue(ready.getBoolean("isolated",false));assertFalse(ready.getBoolean("networkPermission",true));
    }
    @Test public void runawayExecutionIsBoundedAndCanBeCancelled() throws Exception {
        ExecutionClient client=new ExecutionClient(InstrumentationRegistry.getInstrumentation().getTargetContext());
        AtomicReference<CompletableFuture<Integer>> completion=new AtomicReference<>();
        SandboxPolicy policy=new SandboxPolicy(500,1024,SandboxPolicy.safeDefault().required);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->completion.set(client.run("while (true) {}",config(),policy,List.of(),false,s->{},s->{},s->{})));
        int exit=completion.get().get(12,TimeUnit.SECONDS);assertTrue(exit==124||exit==137);
    }
    @Test public void cancelIsImmediateAndOldCallbacksCannotKillTheNextRun() throws Exception {
        ExecutionClient client=new ExecutionClient(InstrumentationRegistry.getInstrumentation().getTargetContext());
        AtomicReference<CompletableFuture<Integer>> first=new AtomicReference<>();
        CountDownLatch ready=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->first.set(client.run("while(true){}",config(),SandboxPolicy.safeDefault(),List.of(),false,s->{},s->{},s->ready.countDown())));
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(client::cancel);
        assertEquals(Integer.valueOf(130),first.get().get(5,TimeUnit.SECONDS));
        AtomicReference<CompletableFuture<Integer>> second=new AtomicReference<>();StringBuffer output=new StringBuffer();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->second.set(client.run("print('second run');",config(),SandboxPolicy.safeDefault(),List.of(),false,output::append,output::append,s->{})));
        assertEquals(output.toString(),Integer.valueOf(0),second.get().get(15,TimeUnit.SECONDS));assertTrue(output.toString().contains("second run"));
    }
    @Test public void realSqliteRunsInMemoryWithoutAHostFilesystem() throws Exception {
        ExecutionClient client=new ExecutionClient(InstrumentationRegistry.getInstrumentation().getTargetContext());
        AtomicReference<CompletableFuture<Integer>> result=new AtomicReference<>();StringBuffer output=new StringBuffer();
        RunConfiguration sql=new RunConfiguration("SQL test","sql","test.sql","",List.of(),Map.of(),"",List.of(),List.of(),List.of());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->result.set(client.run("CREATE TABLE test(n INTEGER); INSERT INTO test VALUES(42); SELECT n FROM test;",sql,SandboxPolicy.safeDefault(),List.of(),false,output::append,output::append,s->{})));
        assertEquals(output.toString(),Integer.valueOf(0),result.get().get(15,TimeUnit.SECONDS));assertTrue(output.toString(),output.toString().contains("42"));
    }
    @Test public void strictUnsupportedLimitsRejectBeforeBinding() {
        Set<SandboxPolicy.Restriction> required=EnumSet.copyOf(SandboxPolicy.safeDefault().required);required.add(SandboxPolicy.Restriction.HARD_MEMORY_LIMIT);
        try{new ExecutionClient(InstrumentationRegistry.getInstrumentation().getTargetContext()).run("print(1)",config(),new SandboxPolicy(1000,1024,required),List.of(),false,s->{},s->{},s->{});fail("Must fail closed");}catch(SecurityException expected){}
    }
}
