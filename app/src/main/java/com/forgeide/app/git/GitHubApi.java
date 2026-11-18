package com.forgeide.app.git;

import com.forgeide.core.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit, user-initiated HTTPS requests. Redirects are disabled so Authorization cannot leak. */
public final class GitHubApi {

    private final CredentialVault vault;

    public GitHubApi(CredentialVault vault) {
        this.vault = vault;
    }

    private JsonObject request(String path, String body) throws Exception {
        return request(path, body, vault.read());
    }

    private JsonObject request(String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com" + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "ForgeIDE");
        if (token.isEmpty()) throw new IOException("Connect GitHub in the IDE first");
        c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException(
                "GitHub request returned HTTP " + status + ". Check the account and token permissions."
            );
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 1024 * 1024) throw new IOException("GitHub response too large");
                    out.write(buffer, 0, n);
                }
                return JsonObject.parse(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            c.disconnect();
        }
    }

    public String verifyToken(String token) throws Exception {
        return request("/user", null, token).getString("login", "");
    }

    public String account() throws Exception {
        return request("/user", null).getString("login", "");
    }

    public String createRepository(String name, boolean privateRepo) throws Exception {
        if (!name.matches("[A-Za-z0-9_.-]{1,100}")) throw new IOException("Invalid repository name");
        return request(
            "/user/repos",
            Json.write(Map.of("name", name, "private", privateRepo, "auto_init", false))
        ).getString("clone_url", "");
    }
}
