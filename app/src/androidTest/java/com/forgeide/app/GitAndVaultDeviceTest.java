package com.forgeide.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.git.*;
import com.forgeide.core.project.Workspace;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GitAndVaultDeviceTest {
    @Test public void localGitCommitDiffBranchAndHistoryAreReal() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Workspace workspace=new Workspace(new File(context.getCacheDir(),"git-test-"+UUID.randomUUID()).toPath());
        System.setProperty("user.home",context.getFilesDir()+"/state/git-home");
        try(SafeGit git=new SafeGit(workspace,new CredentialVault(context))){
            git.init();
            File witness=new File(context.getCacheDir(),"hook-must-not-run-"+UUID.randomUUID());
            Path hook=workspace.resolve(".git/hooks/pre-commit");Files.createDirectories(hook.getParent());
            com.forgeide.core.util.AtomicFiles.write(hook,"#!/system/bin/sh\ntouch '"+witness.getAbsolutePath()+"'\n");hook.toFile().setExecutable(true);
            workspace.write("hello.txt","first\n");assertFalse(git.status().isEmpty());
            git.commit("Initial test","Test Author","test@example.invalid");assertTrue(git.status().isEmpty());assertEquals(1,git.history(null).size());assertFalse("Untrusted Git hooks must never run",witness.exists());
            workspace.write("hello.txt","second\n");assertTrue(git.diff("hello.txt").contains("+second"));
            git.commit("Change file","Test Author","test@example.invalid");
            git.checkout("test-branch",true);assertEquals("test-branch",git.branch());assertEquals(2,git.history("hello.txt").size());
        }
    }
    @Test public void gitCannotLaunchRepositoryHooks() throws Exception {
        SafeGit.NoExecFS fs=new SafeGit.NoExecFS();assertFalse(fs.supportsExecute());assertFalse(fs.supportsSymlinks());
        try{fs.runInShell("echo unsafe",new String[0]);fail();}catch(SecurityException expected){}
        try{fs.runProcess(new ProcessBuilder("/system/bin/sh"),null,null,(InputStream)null);fail();}catch(IOException expected){}
    }
    @Test public void tokenVaultEncryptsAndDeletesDeviceBoundSecret() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();CredentialVault vault=new CredentialVault(context);String fake="fixture-not-a-real-github-credential-"+UUID.randomUUID();
        try{vault.store(fake);assertEquals(fake,vault.read());assertTrue(vault.connected());String encrypted=context.getSharedPreferences("credential-vault",0).getString("github","");assertFalse(encrypted.contains(fake));}finally{vault.clear();}
        assertFalse(vault.connected());assertEquals("",vault.read());
    }
}
