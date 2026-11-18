package com.forgeide.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.forgeide.core.project.Workspace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline static HTML/CSS preview. Active code uses the isolated runtime, not privileged WebView bridges. */
public final class PreviewActivity extends Activity {

    public static final int PREVIEW_ID = 1010,
        PREVIEW_UNAVAILABLE_ID = 1011;
    private WebView browser;
    private Workspace workspace;
    private LinearLayout root;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = ((ForgeApplication) getApplication()).workspace;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161C);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("  ‹  Static preview · scripts & network disabled");
        title.setTextColor(0xFFDBDFE8);
        title.setBackgroundColor(0xFF1A1D24);
        title.setPadding(dp(12), dp(18), dp(12), dp(18));
        title.setContentDescription("Back to editor. Static preview, scripts and network disabled");
        title.setFocusable(true);
        title.setOnClickListener(v -> finish());
        root.addView(title);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        try {
            // Some AOSP images and devices have no enabled provider. Never crash the IDE or
            // weaken settings/open an external browser as a substitute for a confined renderer.
            if (WebView.getCurrentWebViewPackage() == null) {
                showUnavailable("Android has no enabled WebView provider.");
                return;
            }
            browser = new WebView(this);
            configureBrowser();
            browser.setId(PREVIEW_ID);
            root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
            String file = getIntent().getStringExtra("file");
            if (file == null) file = "index.html";
            browser.loadUrl("https://forge.invalid/" + android.net.Uri.encode(file, "/"));
        } catch (RuntimeException | LinkageError unavailable) {
            // Broken/disabled provider updates and missing native WebView libraries are also
            // recoverable capability failures. Do not catch OOM or other VM-fatal errors.
            ((ForgeApplication) getApplication()).log(
                "Static preview unavailable: " + unavailable.getClass().getSimpleName()
            );
            showUnavailable("Android could not start its WebView provider.");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showUnavailable(String reason) {
        releaseBrowser();
        if (root.findViewById(PREVIEW_UNAVAILABLE_ID) != null) return;
        LinearLayout message = new LinearLayout(this);
        message.setOrientation(LinearLayout.VERTICAL);
        message.setPadding(dp(24), dp(32), dp(24), dp(24));
        TextView description = new TextView(this);
        description.setId(PREVIEW_UNAVAILABLE_ID);
        description.setText(
            "Static preview unavailable\n\n" +
                reason +
                " Enable or update Android System WebView in Android settings, then reopen the preview.\n\n" +
                "Your source files, editor and isolated runtimes are unaffected. No scripts, network requests, downloads or external browser were enabled."
        );
        description.setTextColor(0xFFDBDFE8);
        description.setTextSize(16);
        description.setTextIsSelectable(true);
        message.addView(description);
        Button back = new Button(this);
        back.setText("Back to editor");
        back.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(24);
        message.addView(back, params);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(message);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void configureBrowser() {
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
                    return (
                        !"https".equals(request.getUrl().getScheme()) ||
                        !"forge.invalid".equals(request.getUrl().getHost())
                    );
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (
                        !"https".equals(request.getUrl().getScheme()) ||
                        !"forge.invalid".equals(request.getUrl().getHost())
                    ) return denied();
                    try {
                        String path = request.getUrl().getPath();
                        if (path == null || !path.startsWith("/")) return denied();
                        path = path.substring(1);
                        java.nio.file.Path p = workspace.resolve(path);
                        if (
                            !java.nio.file.Files.isRegularFile(p) ||
                            java.nio.file.Files.size(p) > 8 * 1024 * 1024
                        ) return denied();
                        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(path).toLowerCase(Locale.ROOT)
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
                    showUnavailable("Android stopped the preview renderer.");
                    return true;
                }
            }
        );
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

    private void releaseBrowser() {
        WebView current = browser;
        browser = null;
        if (current == null) return;
        if (
            current.getParent() instanceof android.view.ViewGroup
        ) ((android.view.ViewGroup) current.getParent()).removeView(current);
        try {
            current.stopLoading();
        } catch (RuntimeException | LinkageError ignored) {}
        try {
            current.destroy();
        } catch (RuntimeException | LinkageError ignored) {}
    }

    @Override
    protected void onDestroy() {
        releaseBrowser();
        super.onDestroy();
    }
}
