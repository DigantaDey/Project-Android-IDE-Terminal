package com.forgeide.app.git;

import com.forgeide.core.git.*;
import com.forgeide.core.project.Workspace;
import com.forgeide.core.util.CancellationToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.*;

/** Pure-Java Git, with host hooks/processes, symlinks and credential-bearing URLs disabled. */
public final class SafeGit implements GitService {

    public static final class NoExecFS extends FS {

        @Override
        public FS newInstance() {
            return new NoExecFS();
        }

        // Android uses Unix dot-file names; java.nio's DOS attribute view is unavailable.
        @Override
        public void setHidden(File path, boolean hidden) {}

        @Override
        public boolean supportsExecute() {
            return false;
        }

        @Override
        public boolean supportsSymlinks() {
            return false;
        }

        @Override
        public boolean isCaseSensitive() {
            return true;
        }

        @Override
        public boolean canExecute(File file) {
            return false;
        }

        @Override
        public boolean setExecute(File file, boolean value) {
            return false;
        }

        @Override
        public boolean retryFailedLockFileCommit() {
            return false;
        }

        @Override
        protected File discoverGitExe() {
            return null;
        }

        @Override
        public ProcessBuilder runInShell(String command, String[] args) {
            throw new SecurityException("Native Git hooks and processes are disabled");
        }

        @Override
        public int runProcess(ProcessBuilder p, OutputStream out, OutputStream err, InputStream in)
            throws IOException {
            throw new IOException("Host processes are disabled");
        }

        @Override
        public int runProcess(ProcessBuilder p, OutputStream out, OutputStream err, String input)
            throws IOException {
            throw new IOException("Host processes are disabled");
        }
    }

    private final Workspace workspace;
    private final CredentialVault vault;
    private Git git;

    public SafeGit(Workspace workspace, CredentialVault vault) {
        this.workspace = workspace;
        this.vault = vault;
    }

    private synchronized Git git() throws Exception {
        if (git != null) return git;
        Path meta = workspace.resolve(".git");
        if (!Files.isDirectory(meta)) throw new IOException("Initialize or clone a repository first");
        // Imported repository metadata is untrusted. Deny links, alternates and external configuration.
        try (java.util.stream.Stream<Path> paths = Files.walk(meta)) {
            for (Iterator<Path> it = paths.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (Files.isSymbolicLink(p)) throw new IOException("Git metadata symlinks are not permitted");
            }
        }
        if (
            Files.exists(meta.resolve("commondir")) || Files.exists(meta.resolve("objects/info/alternates"))
        ) throw new IOException("External Git object/worktree stores are not permitted");
        String config = com.forgeide.core.util.AtomicFiles.readUtf8(workspace.resolve(".git/config"));
        if (
            config.matches("(?is).*(\\[\\s*(include|includeIf|filter|url|gpg)\\b|sshCommand|hooksPath).*")
        ) throw new IOException("Repository config contains unsupported external integrations");
        Repository r = new FileRepositoryBuilder()
            .setGitDir(meta.toFile())
            .setWorkTree(workspace.root().toFile())
            .setFS(new NoExecFS())
            .build();
        r.getConfig().setString("http", null, "followRedirects", "false");
        r.getConfig().setBoolean("commit", null, "gpgsign", false);
        r.getConfig().setBoolean("core", null, "symlinks", false);
        git = new Git(r);
        return git;
    }

    @Override
    public void init() throws Exception {
        if (Files.exists(workspace.resolve(".git"))) return;
        try (Git created = Git.init().setDirectory(workspace.root().toFile()).setFs(new NoExecFS()).call()) {
            created.getRepository().getConfig().setBoolean("core", null, "symlinks", false);
            created.getRepository().getConfig().save();
        }
    }

    @Override
    public List<Change> status() throws Exception {
        Status s = git().status().call();
        Map<String, String> paths = new TreeMap<>();
        for (String p : s.getUntracked()) paths.put(p, "?");
        for (String p : s.getModified()) paths.put(p, "M");
        for (String p : s.getAdded()) paths.put(p, "A");
        for (String p : s.getChanged()) paths.put(p, "M");
        for (String p : s.getMissing()) paths.put(p, "D");
        for (String p : s.getRemoved()) paths.put(p, "D");
        for (String p : s.getConflicting()) paths.put(p, "U");
        List<Change> out = new ArrayList<>();
        paths.forEach((p, v) -> out.add(new Change(p, v)));
        return out;
    }

    @Override
    public String branch() throws Exception {
        return git().getRepository().getBranch();
    }

    @Override
    public void commit(String message, String author, String email) throws Exception {
        if (message.isBlank()) throw new IOException("Commit message is required");
        git().add().addFilepattern(".").call();
        git().add().setUpdate(true).addFilepattern(".").call();
        git()
            .commit()
            .setMessage(message)
            .setAuthor(author, email)
            .setCommitter(author, email)
            .setSign(false)
            .call();
    }

    @Override
    public List<String> history(String file) throws Exception {
        org.eclipse.jgit.api.LogCommand log = git().log().setMaxCount(100);
        if (file != null && !file.isEmpty()) log.addPath(file);
        List<String> out = new ArrayList<>();
        for (RevCommit c : log.call())
            out.add(
                c.abbreviate(8).name() + "  " + c.getShortMessage() + "\n" + c.getAuthorIdent().getName()
            );
        return out;
    }

    @Override
    public String diff(String file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(bytes)) {
            formatter.setRepository(git().getRepository());
            formatter.setContext(3);
            if (file != null && !file.isEmpty()) formatter.setPathFilter(PathFilter.create(file));
            for (org.eclipse.jgit.diff.DiffEntry e : git().diff().call()) formatter.format(e);
            formatter.flush();
        }
        String text = bytes.toString(StandardCharsets.UTF_8.name());
        return text.length() > 262144 ? text.substring(0, 262144) + "\n[diff truncated]" : text;
    }

    @Override
    public List<String> branches() throws Exception {
        List<String> out = new ArrayList<>();
        for (Ref r : git().branchList().call()) out.add(Repository.shortenRefName(r.getName()));
        return out;
    }

    @Override
    public void checkout(String branch, boolean create) throws Exception {
        if (!Repository.isValidRefName("refs/heads/" + branch)) throw new IOException("Invalid branch name");
        git().checkout().setName(branch).setCreateBranch(create).call();
    }

    @Override
    public void merge(String branch) throws Exception {
        Ref r = git().getRepository().findRef(branch);
        if (r == null) throw new IOException("Branch does not exist");
        MergeResult result = git().merge().include(r).call();
        if (!result.getMergeStatus().isSuccessful()) throw new IOException(
            "Merge " + result.getMergeStatus() + ". Resolve conflicts in the editor, then commit."
        );
    }

    @Override
    public void stash(boolean pop) throws Exception {
        if (pop) {
            git().stashApply().call();
            git().stashDrop().call();
        } else git().stashCreate().call();
    }

    private CredentialsProvider credentials(String remote) throws Exception {
        java.net.URI uri = RemotePolicy.https(remote);
        if (!uri.getHost().equalsIgnoreCase("github.com")) return null;
        String token = vault.read();
        return token.isEmpty()
            ? null
            : new UsernamePasswordCredentialsProvider("x-access-token", token) {
                  @Override
                  public boolean get(URIish uri, CredentialItem... items) {
                      return (
                          "https".equalsIgnoreCase(uri.getScheme()) &&
                          "github.com".equalsIgnoreCase(uri.getHost()) &&
                          (uri.getPort() == -1 || uri.getPort() == 443) &&
                          super.get(uri, items)
                      );
                  }
              };
    }

    private static ProgressMonitor progress(CancellationToken cancel) {
        return new ProgressMonitor() {
            public void start(int n) {}

            public void beginTask(String t, int n) {}

            public void update(int n) {}

            public void endTask() {}

            public boolean isCancelled() {
                return cancel.isCancelled();
            }
        };
    }

    @Override
    public void remote(String operation, CancellationToken cancel) throws Exception {
        String remote = git().getRepository().getConfig().getString("remote", "origin", "url");
        if (remote == null) throw new IOException("Configure an origin HTTPS URL first");
        CredentialsProvider creds = credentials(remote);
        for (String section : git().getRepository().getConfig().getSubsections("remote"))
            for (String key : List.of("url", "pushurl"))
                for (String url : git().getRepository().getConfig().getStringList("remote", section, key))
                    RemotePolicy.https(url);
        if (operation.equals("fetch")) git()
            .fetch()
            .setRemote("origin")
            .setTimeout(30)
            .setProgressMonitor(progress(cancel))
            .setCredentialsProvider(creds)
            .call();
        else if (operation.equals("pull")) {
            PullResult r = git()
                .pull()
                .setRemote("origin")
                .setTimeout(30)
                .setProgressMonitor(progress(cancel))
                .setCredentialsProvider(creds)
                .call();
            if (!r.isSuccessful()) throw new IOException("Pull requires conflict resolution");
        } else if (operation.equals("push")) {
            for (PushResult r : git()
                .push()
                .setRemote("origin")
                .setTimeout(30)
                .setProgressMonitor(progress(cancel))
                .setCredentialsProvider(creds)
                .call())
                for (RemoteRefUpdate update : r.getRemoteUpdates())
                    if (
                        update.getStatus() != RemoteRefUpdate.Status.OK &&
                        update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE
                    ) throw new IOException("Push " + update.getStatus() + ": " + update.getMessage());
        } else throw new IOException("Unknown remote operation");
    }

    public void origin(String url) throws Exception {
        RemotePolicy.https(url);
        StoredConfig config = git().getRepository().getConfig();
        config.setString("remote", "origin", "url", url);
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();
    }

    public void cloneFrom(String url, CancellationToken cancel) throws Exception {
        RemotePolicy.https(url);
        init();
        origin(url);
        FetchResult fetched = git()
            .fetch()
            .setRemote("origin")
            .setTimeout(30)
            .setProgressMonitor(progress(cancel))
            .setCredentialsProvider(credentials(url))
            .call();
        Ref head = fetched.getAdvertisedRef(Constants.HEAD);
        String ref = null;
        if (head != null && head.isSymbolic()) ref = head.getTarget().getName();
        if (ref == null && head != null) for (Ref r : fetched.getAdvertisedRefs())
            if (r.getName().startsWith(Constants.R_HEADS) && r.getObjectId().equals(head.getObjectId())) {
                ref = r.getName();
                break;
            }
        if (ref == null) for (Ref r : fetched.getAdvertisedRefs())
            if (r.getName().startsWith(Constants.R_HEADS)) {
                ref = r.getName();
                break;
            }
        if (ref == null) return;
        String branch = Repository.shortenRefName(ref);
        git()
            .checkout()
            .setName(branch)
            .setCreateBranch(true)
            .setStartPoint("origin/" + branch)
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .call();
    }

    @Override
    public void close() {
        if (git != null) {
            git.close();
            git = null;
        }
    }
}
