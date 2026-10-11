package app.videotv;

import android.net.Uri;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;

/**
 * Routes the page's links and pictures, and tells the activity when the server couldn't be reached.
 * A top-level class on purpose: build-tools 34's d8 crashes on nested classes compiled by recent JDKs.
 */
final class PageClient extends WebViewClient {
    private final MainActivity activity;
    private final TvBridge bridge;

    PageClient(MainActivity activity, TvBridge bridge) {
        this.activity = activity;
        this.bridge = bridge;
    }

    // Films open in the native player; every other link stays in the page.
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return activity.openLink(request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation") // the only form of this callback before Android 7
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return activity.openLink(url);
    }

    // Pictures of the installed apps come from the TV itself.
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!TvBridge.ICON_HOST.equals(url.getHost())) return null;
        byte[] png = url.getLastPathSegment() == null ? null : bridge.icon(url.getLastPathSegment());
        return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png == null ? new byte[0] : png));
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame() && "http".equals(request.getUrl().getScheme())) activity.failed();
    }

    // A server that answers but has no home screen page (an older server.py) is no use either.
    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (request.isForMainFrame() && response.getStatusCode() >= 400) activity.failed();
    }

    @Override
    @SuppressWarnings("deprecation") // the only error callback on Android 5.x
    public void onReceivedError(WebView view, int code, String description, String failingUrl) {
        if (failingUrl != null && failingUrl.startsWith("http:")) activity.failed();
    }
}
