package com.forgeide.app;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PreviewDeviceTest {

    @Test
    public void staticPreviewIsConfinedOrExplainsMissingSystemProvider() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ForgeApplication app = (ForgeApplication) context.getApplicationContext();
        String file = ".forge-preview-device.html";
        app.workspace.write(
            file,
            "<!doctype html><title>Confined preview fixture</title><h1>Offline</h1><script>document.title='unsafe'</script>"
        );
        CountDownLatch loaded = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean unavailable =
            new java.util.concurrent.atomic.AtomicBoolean();
        try (
            ActivityScenario<PreviewActivity> scenario = ActivityScenario.launch(
                new Intent(context, PreviewActivity.class).putExtra("file", file)
            )
        ) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(PreviewActivity.PREVIEW_ID);
                if (web == null) {
                    assertNull(
                        "Only devices without a system renderer should take the missing-provider path",
                        WebView.getCurrentWebViewPackage()
                    );
                    assertUnavailable(activity);
                    unavailable.set(true);
                    loaded.countDown();
                    return;
                }
                assertFalse(web.getSettings().getJavaScriptEnabled());
                assertFalse(web.getSettings().getAllowFileAccess());
                assertFalse(web.getSettings().getAllowContentAccess());
                assertTrue(web.getSettings().getBlockNetworkLoads());
                WebViewClient confined = web.getWebViewClient();
                assertTrue(confined.shouldOverrideUrlLoading(web, request("http://forge.invalid/main.html")));
                assertTrue(
                    confined.shouldOverrideUrlLoading(web, request("content://forge.invalid/main.html"))
                );
                assertEquals(
                    403,
                    confined
                        .shouldInterceptRequest(web, request("https://outside.invalid/private"))
                        .getStatusCode()
                );
                assertEquals(
                    403,
                    confined
                        .shouldInterceptRequest(web, request("file:///data/local/tmp/private"))
                        .getStatusCode()
                );
                assertEquals(
                    403,
                    confined
                        .shouldInterceptRequest(web, request("https://forge.invalid/../state/settings.json"))
                        .getStatusCode()
                );
                web.setWebViewClient(
                    new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(
                            WebView view,
                            WebResourceRequest resource
                        ) {
                            return confined.shouldInterceptRequest(view, resource);
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest resource) {
                            return confined.shouldOverrideUrlLoading(view, resource);
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            loaded.countDown();
                        }

                        @Override
                        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                            return confined.onRenderProcessGone(view, detail);
                        }
                    }
                );
                web.reload();
            });
            assertTrue("Local HTML must actually load", loaded.await(15, TimeUnit.SECONDS));
            if (unavailable.get()) {
                scenario.onActivity(PreviewDeviceTest::assertUnavailable);
                return;
            }
            // Newer WebView builds commit the document title asynchronously after
            // onPageFinished, so read it on the UI thread until it appears. A page that
            // never renders still fails the assertion, just after the polling window.
            java.util.concurrent.atomic.AtomicReference<String> title =
                new java.util.concurrent.atomic.AtomicReference<>("");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                CountDownLatch read = new CountDownLatch(1);
                scenario.onActivity(activity -> {
                    WebView web = activity.findViewById(PreviewActivity.PREVIEW_ID);
                    title.set(web == null ? "" : web.getTitle());
                    read.countDown();
                });
                read.await();
                if ("Confined preview fixture".equals(title.get())) break;
                Thread.sleep(100);
            }
            assertEquals("Confined preview fixture", title.get());
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(PreviewActivity.PREVIEW_ID);
                // Simulate the Android callback; the real HTML must have rendered before recovery.
                assertTrue(web.getWebViewClient().onRenderProcessGone(web, null));
                assertUnavailable(activity);
            });
        } finally {
            app.workspace.delete(file);
        }
    }

    private static void assertUnavailable(PreviewActivity activity) {
        android.widget.TextView message = activity.findViewById(PreviewActivity.PREVIEW_UNAVAILABLE_ID);
        assertNotNull("Missing WebView must display a native capability error instead of crashing", message);
        assertTrue(message.isShown());
        assertTrue(message.getText().toString().contains("Static preview unavailable"));
        assertTrue(
            message.getText().toString().contains("source files, editor and isolated runtimes are unaffected")
        );
        assertNull(activity.findViewById(PreviewActivity.PREVIEW_ID));
    }

    private static WebResourceRequest request(String value) {
        return new WebResourceRequest() {
            @Override
            public Uri getUrl() {
                return Uri.parse(value);
            }

            @Override
            public boolean isForMainFrame() {
                return true;
            }

            @Override
            public boolean isRedirect() {
                return false;
            }

            @Override
            public boolean hasGesture() {
                return false;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public Map<String, String> getRequestHeaders() {
                return Map.of();
            }
        };
    }
}
