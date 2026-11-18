package com.forgeide.core.git;

import com.forgeide.core.util.CancellationToken;
import java.util.*;

/** Trusted IDE git operations. Credentials are supplied by a host vault, never by run environments. */
public interface GitService extends AutoCloseable {
    final class Change {

        public final String path, status;

        public Change(String path, String status) {
            this.path = path;
            this.status = status;
        }

        @Override
        public String toString() {
            return status + "  " + path;
        }
    }

    List<Change> status() throws Exception;
    String branch() throws Exception;
    void init() throws Exception;
    void commit(String message, String author, String email) throws Exception;
    List<String> history(String file) throws Exception;
    String diff(String file) throws Exception;
    List<String> branches() throws Exception;
    void checkout(String branch, boolean create) throws Exception;
    void merge(String branch) throws Exception;
    void stash(boolean pop) throws Exception;
    void remote(String operation, CancellationToken cancel) throws Exception;

    @Override
    void close();
}
