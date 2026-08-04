package com.iraqlobster.tricorder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole Android side of the tricorder.
 *
 * It contains no instrument logic — that all lives in tricorder.html. What this
 * class provides is the one thing a WebView cannot get for free:
 *
 *   A SECURE ORIGIN. Camera, microphone, geolocation and the Generic Sensor
 *   APIs are refused on file:// URLs, which would silently kill the BIO, EM,
 *   MET and NAV modes. So instead of loading the asset directly, requests to
 *   https://appassets.androidplatform.net are intercepted and answered out of
 *   the APK's own assets. WebView treats that as a genuine https origin, the
 *   sensor APIs are allowed, and not one byte touches the network — this app
 *   does not even hold the INTERNET permission.
 *
 * The rest is the permission bridge: Android's runtime grant and the page's
 * own request are two separate gates, and both have to be opened.
 */
public class MainActivity extends Activity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_URL = ORIGIN + "/tricorder.html";
    private static final int RC_PERMISSIONS = 41;

    private WebView web;

    /** A page permission request parked while Android's dialog is up. */
    private PermissionRequest pendingWebRequest;
    /** A geolocation callback parked for the same reason. */
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        // Nothing is loaded from disk or from a content provider: everything
        // comes through the interceptor below, so these stay shut.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return serveFromAssets(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // The instrument never navigates anywhere. Anything that tries
                // to leave the app origin is simply refused.
                return !req.getUrl().toString().startsWith(ORIGIN);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleWebPermission(request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebRequest == request) pendingWebRequest = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)
                        || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, RC_PERMISSIONS);
            }
        });

        // Service workers fetch on their own path, so they need the same
        // interceptor or the offline cache registration fails inside the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                    return serveFromAssets(req.getUrl());
                }
            });
        }

        setContentView(web);
        web.loadUrl(START_URL);
    }

    // ---- serving the APK's own files over the https origin ------------------

    private WebResourceResponse serveFromAssets(Uri uri) {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"appassets.androidplatform.net".equals(uri.getHost())) {
            // Not ours. Returning an empty response keeps the app airtight:
            // with no INTERNET permission there is nothing to fall through to.
            return new WebResourceResponse("text/plain", "utf-8", null);
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) path = "tricorder.html";
        // No traversal out of assets/.
        if (path.contains("..")) return new WebResourceResponse("text/plain", "utf-8", null);

        try {
            InputStream in = getAssets().open(path);
            WebResourceResponse res = new WebResourceResponse(mimeOf(path), "utf-8", in);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-store");
            // Lets sw.js claim the whole origin even if it is moved later.
            headers.put("Service-Worker-Allowed", "/");
            res.setResponseHeaders(headers);
            res.setStatusCodeAndReasonPhrase(200, "OK");
            return res;
        } catch (IOException notFound) {
            WebResourceResponse res = new WebResourceResponse("text/plain", "utf-8", null);
            res.setStatusCodeAndReasonPhrase(404, "Not Found");
            res.setResponseHeaders(Collections.<String, String>emptyMap());
            return res;
        }
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".webmanifest") || path.endsWith(".json")) return "application/manifest+json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // ---- permission bridge --------------------------------------------------

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The page asked for the camera or the microphone. Android has to say yes
     * first; only then can the page's own request be granted.
     */
    private void handleWebPermission(PermissionRequest request) {
        List<String> needed = new ArrayList<String>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && !granted(Manifest.permission.CAMERA)) {
                needed.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && !granted(Manifest.permission.RECORD_AUDIO)) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        pendingWebRequest = request;
        requestPermissions(needed.toArray(new String[0]), RC_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != RC_PERMISSIONS) {
            super.onRequestPermissionsResult(code, permissions, results);
            return;
        }
        boolean anyGranted = false;
        for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) anyGranted = true;

        if (pendingWebRequest != null) {
            PermissionRequest req = pendingWebRequest;
            pendingWebRequest = null;
            if (anyGranted) req.grant(req.getResources()); else req.deny();
        }
        if (pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, anyGranted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    // ---- window plumbing ----------------------------------------------------

    private void goImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();      // also releases the camera and mic
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
