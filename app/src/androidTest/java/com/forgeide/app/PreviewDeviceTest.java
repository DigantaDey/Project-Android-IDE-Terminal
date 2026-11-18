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
    public void staticPreviewRendersConfinedHtmlWithoutActiveCapabilities() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ForgeApplication app = (ForgeApplication) context.getApplicationContext();
        String file = ".forge-preview-device.html";
        app.workspace.write(
            file,
            "<!doctype html><title>Confined preview fixture</title><h1>Offline</h1><script>document.title='unsafe'</script>"
        );
        CountDownLatch loaded = new CountDownLatch(1);
        try (
            ActivityScenario<PreviewActivity> scenario = ActivityScenario.launch(
                new Intent(context, PreviewActivity.class).putExtra("file", file)
            )
        ) {
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(PreviewActivity.PREVIEW_ID);
                assertFalse(web.getSettings().getJavaScriptEnabled());
                assertFalse(web.getSettings().getAllowFileAccess());
                assertFalse(web.getSettings().getAllowContentAccess());
                assertTrue(web.getSettings().getBlockNetworkLoads());
                WebViewClient confined = web.getWebViewClient();
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
                    }
                );
                web.reload();
            });
            assertTrue("Local HTML must actually load", loaded.await(15, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                WebView web = activity.findViewById(PreviewActivity.PREVIEW_ID);
                assertEquals("Confined preview fixture", web.getTitle());
            });
        } finally {
            app.workspace.delete(file);
        }
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
