package com.forgeide.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import com.forgeide.core.project.Workspace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline static HTML/CSS preview. Active code uses the isolated runtime, not privileged WebView bridges. */
public final class PreviewActivity extends Activity {

    private WebView browser;
    private Workspace workspace;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = ((ForgeApplication) getApplication()).workspace;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("  ‹  Static preview · scripts & network disabled");
        title.setTextColor(0xFFDBDFE8);
        title.setBackgroundColor(0xFF1A1D24);
        title.setPadding(12, 18, 12, 18);
        title.setOnClickListener(v -> finish());
        root.addView(title);
        browser = new WebView(this);
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setDomStorageEnabled(false);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !"forge.invalid".equals(request.getUrl().getHost());
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (
                        !"https".equals(request.getUrl().getScheme()) ||
                        !"forge.invalid".equals(request.getUrl().getHost())
                    ) return denied();
                    try {
                        String path = request.getUrl().getPath();
                        if (path == null) return denied();
                        path = path.substring(1);
                        java.nio.file.Path p = workspace.resolve(path);
                        if (java.nio.file.Files.size(p) > 8 * 1024 * 1024) return denied();
                        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(path)
                        );
                        if (mime == null) mime = "text/plain";
                        Map<String, String> headers = new HashMap<>();
                        headers.put(
                            "Content-Security-Policy",
                            "default-src 'none'; style-src 'unsafe-inline' https://forge.invalid; img-src https://forge.invalid data:; font-src https://forge.invalid; script-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
                        );
                        headers.put("X-Content-Type-Options", "nosniff");
                        headers.put("Cache-Control", "no-store");
                        return new WebResourceResponse(
                            mime,
                            "UTF-8",
                            200,
                            "OK",
                            headers,
                            java.nio.file.Files.newInputStream(p)
                        );
                    } catch (Exception e) {
                        return denied();
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    view.destroy();
                    Toast.makeText(
                        PreviewActivity.this,
                        "Preview renderer stopped",
                        Toast.LENGTH_LONG
                    ).show();
                    finish();
                    return true;
                }
            }
        );
        String file = getIntent().getStringExtra("file");
        if (file == null) file = "index.html";
        browser.loadUrl("https://forge.invalid/" + android.net.Uri.encode(file, "/"));
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            Map.of(),
            new ByteArrayInputStream(
                "Resource is outside the private project preview".getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    @Override
    protected void onDestroy() {
        if (browser != null) {
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }
}
