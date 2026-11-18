package com.forgeide.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.forgeide.app.git.*;
import com.forgeide.core.project.Workspace;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class GitAndVaultDeviceTest {

    @Test
    public void localGitCommitDiffBranchAndHistoryAreReal() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Workspace workspace = new Workspace(
            new File(context.getCacheDir(), "git-test-" + UUID.randomUUID()).toPath()
        );
        System.setProperty("user.home", context.getFilesDir() + "/state/git-home");
        try (SafeGit git = new SafeGit(workspace, new CredentialVault(context))) {
            git.init();
            File witness = new File(context.getCacheDir(), "hook-must-not-run-" + UUID.randomUUID());
            Path hook = workspace.resolve(".git/hooks/pre-commit");
            Files.createDirectories(hook.getParent());
            com.forgeide.core.util.AtomicFiles.write(
                hook,
                "#!/system/bin/sh\ntouch '" + witness.getAbsolutePath() + "'\n"
            );
            hook.toFile().setExecutable(true);
            workspace.write("hello.txt", "first\n");
            assertFalse(git.status().isEmpty());
            git.commit("Initial test", "Test Author", "test@example.invalid");
            assertTrue(git.status().isEmpty());
            assertEquals(1, git.history(null).size());
            assertFalse("Untrusted Git hooks must never run", witness.exists());
            workspace.write("hello.txt", "second\n");
            assertTrue(git.diff("hello.txt").contains("+second"));
            git.commit("Change file", "Test Author", "test@example.invalid");
            git.checkout("test-branch", true);
            assertEquals("test-branch", git.branch());
            assertEquals(2, git.history("hello.txt").size());
        }
    }

    @Test
    public void gitCannotLaunchRepositoryHooks() throws Exception {
        SafeGit.NoExecFS fs = new SafeGit.NoExecFS();
        assertFalse(fs.supportsExecute());
        assertFalse(fs.supportsSymlinks());
        try {
            fs.runInShell("echo unsafe", new String[0]);
            fail();
        } catch (SecurityException expected) {}
        try {
            fs.runProcess(new ProcessBuilder("/system/bin/sh"), null, null, (InputStream) null);
            fail();
        } catch (IOException expected) {}
    }

    @Test
    public void repositoryCannotDisableTlsChecksOrReenableRedirects() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Workspace workspace = new Workspace(
            new File(context.getCacheDir(), "git-tls-" + UUID.randomUUID()).toPath()
        );
        try (SafeGit git = new SafeGit(workspace, new CredentialVault(context))) {
            git.init();
        }
        String config =
            workspace.read(".git/config") +
            "\n[http]\n sslVerify = false\n followRedirects = true\n[http \"https://github.com\"]\n sslVerify = false\n followRedirects = true\n";
        workspace.write(".git/config", config);
        try (SafeGit git = new SafeGit(workspace, new CredentialVault(context))) {
            git.status();
            git.origin("https://github.com/example/fixture.git");
        }
        String stored = workspace.read(".git/config").toLowerCase(Locale.ROOT);
        assertFalse(stored.contains("sslverify = false"));
        assertFalse(stored.contains("followredirects = true"));
        assertTrue(stored.contains("sslverify = true"));
    }

    @Test
    public void repositoryCookieFilesCannotReadAppPrivateState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Workspace workspace = new Workspace(
            new File(context.getCacheDir(), "git-cookie-" + UUID.randomUUID()).toPath()
        );
        try (SafeGit git = new SafeGit(workspace, new CredentialVault(context))) {
            git.init();
        }
        workspace.write(
            ".git/config",
            workspace.read(".git/config") + "\n[http]\n cookieFile = /data/private/forbidden\n"
        );
        try (SafeGit git = new SafeGit(workspace, new CredentialVault(context))) {
            try {
                git.status();
                fail();
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("unsupported external"));
            }
        }
    }

    @Test
    public void tokenVaultEncryptsAndDeletesDeviceBoundSecret() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CredentialVault vault = new CredentialVault(context);
        String fake = "fixture-not-a-real-github-credential-" + UUID.randomUUID();
        try {
            vault.store(fake);
            assertEquals(fake, vault.read());
            assertTrue(vault.connected());
            String encrypted = context.getSharedPreferences("credential-vault", 0).getString("github", "");
            assertFalse(encrypted.contains(fake));
        } finally {
            vault.clear();
        }
        assertFalse(vault.connected());
        assertEquals("", vault.read());
    }
}
