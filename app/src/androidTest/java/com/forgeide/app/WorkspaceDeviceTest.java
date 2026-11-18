package com.forgeide.app;

import android.graphics.Bitmap;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.core.util.AtomicFiles;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WorkspaceDeviceTest {
    @Rule public ActivityScenarioRule<MainActivity> activity=new ActivityScenarioRule<>(MainActivity.class);

    @Test public void editUndoRedoSaveAndRecreatePreserveRealDocument() throws Exception {
        AtomicReference<ForgeApplication> state=new AtomicReference<>();
        AtomicReference<ForgeApplication.OpenDocument> doc=new AtomicReference<>();
        AtomicReference<String> original=new AtomicReference<>();
        String marker="// device-test-"+System.currentTimeMillis()+"\n";
        activity.getScenario().onActivity(a->{
            ForgeApplication app=(ForgeApplication)a.getApplication();state.set(app);
            CodeEditor editor=a.findViewById(MainActivity.EDITOR_ID);
            ForgeApplication.OpenDocument d=app.find(app.activePath);doc.set(d);original.set(d.content.toString());
            d.content.insert(0,0,marker);
            assertTrue(d.dirty);assertTrue(editor.getText().toString().startsWith(marker));
            editor.undo();assertEquals(original.get(),editor.getText().toString());
            editor.redo();assertTrue(editor.getText().toString().startsWith(marker));
        });
        state.get().saveAll().get(10,TimeUnit.SECONDS);
        assertFalse(doc.get().dirty);
        assertTrue(doc.get().owner.read(doc.get().path).startsWith(marker));
        activity.getScenario().recreate();
        activity.getScenario().onActivity(a->{
            CodeEditor editor=a.findViewById(MainActivity.EDITOR_ID);
            assertTrue(editor.getText().toString().startsWith(marker));
            editor.getText().replace(0,editor.getText().length(),original.get());
        });
        state.get().saveAll().get(10,TimeUnit.SECONDS);
    }

    @Test public void commandPaletteAndNativeControlsArePresent() {
        activity.getScenario().onActivity(a->{
            assertNotNull(a.findViewById(MainActivity.RUN_ID));
            assertNotNull(a.findViewById(MainActivity.SAVE_ID));
            View palette=a.findViewById(MainActivity.PALETTE_ID);
            assertEquals("Command palette",palette.getContentDescription());
            assertTrue(palette.performClick());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void recoveryJournalIsDurableOutsideProject() throws Exception {
        AtomicReference<ForgeApplication> state=new AtomicReference<>();
        AtomicReference<ForgeApplication.OpenDocument> document=new AtomicReference<>();
        AtomicReference<String> original=new AtomicReference<>();
        activity.getScenario().onActivity(a->{
            ForgeApplication app=(ForgeApplication)a.getApplication();state.set(app);
            ForgeApplication.OpenDocument doc=app.find(app.activePath);document.set(doc);original.set(doc.content.toString());
            doc.content.insert(0,0,"// recovery-device-test\n");
        });
        ForgeApplication app=state.get();ForgeApplication.OpenDocument doc=document.get();
        app.io.submit(()->{app.journal(doc);return null;}).get(10,TimeUnit.SECONDS);
        assertTrue(app.recovery.recover().stream().anyMatch(e->e.path.equals(doc.path)&&e.text.startsWith("// recovery-device-test")));
        activity.getScenario().onActivity(a->doc.content.replace(0,doc.content.length(),original.get()));
        app.saveAll().get(10,TimeUnit.SECONDS);
    }

    @Test public void captureNativeWorkspaceScreenshot() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"test-screenshots");
        assertTrue(dir.isDirectory()||dir.mkdirs());
        try(OutputStream out=new FileOutputStream(new File(dir,"workspace.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        bitmap.recycle();
    }
}
