package app.videotv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the home screen page (static/launcher.js) can ask of the TV, as window.TV: the installed
 * apps and the TV's inputs, and opening them or the settings. The page's pictures of the apps
 * are answered from here too (see PageClient).
 */
public final class TvBridge {
    /** The page asks for an app's picture as http://appicon.invalid/<package>. */
    static final String ICON_HOST = "appicon.invalid";

    private final Context context;
    private final PackageManager pm;

    TvBridge(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    /** [{pkg, name, banner}] for every app that can be opened, TV apps first, then by name. */
    @JavascriptInterface
    public String apps() {
        List<JSONObject> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        try {
            for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<JSONObject> group = new ArrayList<>();
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    String pkg = info.activityInfo.packageName;
                    if (!seen.add(pkg)) continue;
                    group.add(new JSONObject().put("pkg", pkg).put("name", String.valueOf(info.loadLabel(pm)))
                            .put("banner", banner(pkg) != null));
                }
                Collections.sort(group, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
                found.addAll(group);
            }
        } catch (JSONException | RuntimeException ignored) { // show whatever was collected
        }
        return new JSONArray(found).toString();
    }

    @JavascriptInterface
    public void open(String pkg) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) start(launch);
    }

    /**
     * [{id, name, kind, on}] for the TV's inputs (tuners, AV, HDMI), on TVs that list them.
     * kind picks the tile's icon; on is false when the TV says nothing is plugged into that input.
     */
    @JavascriptInterface
    public String inputs() {
        JSONArray found = new JSONArray();
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager != null) {
                for (TvInputInfo input : manager.getTvInputList()) {
                    int type = input.getType();
                    String kind = type == TvInputInfo.TYPE_TUNER ? "antenna" : type == TvInputInfo.TYPE_HDMI ? "hdmi" : "av";
                    found.put(new JSONObject().put("id", input.getId()).put("name", String.valueOf(input.loadLabel(context)))
                            .put("kind", kind)
                            .put("on", manager.getInputState(input.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED));
                }
            }
        } catch (JSONException | RuntimeException ignored) { // a TV without this service has no input tiles
        }
        return found.toString();
    }

    /** Shows the input full-screen (InputActivity). */
    @JavascriptInterface
    public void openInput(String id, String name) {
        start(new Intent(context, InputActivity.class).putExtra(InputActivity.ID, id).putExtra(InputActivity.NAME, name));
    }

    /** What TV this is, for the report the home screen leaves on the server (.cache/tv-info.json). */
    @JavascriptInterface
    public String device() {
        try {
            return new JSONObject().put("brand", Build.BRAND).put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL).put("device", Build.DEVICE).put("board", Build.BOARD)
                    .put("hardware", Build.HARDWARE).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** kind is "wifi" or "settings". */
    @JavascriptInterface
    public void settings(String kind) {
        start(new Intent("wifi".equals(kind) ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS));
    }

    private void start(Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { // nothing on this TV answers it
        }
    }

    /** The wide picture a TV app brings for the home screen, if it has one. */
    private Drawable banner(String pkg) {
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable banner = launch != null ? pm.getActivityBanner(launch) : null;
            return banner != null ? banner : pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** The app's banner, or else its icon, as a PNG. Null when the app is gone. */
    byte[] icon(String pkg) {
        try {
            Drawable picture = banner(pkg);
            int width = 320, height = 180;
            if (picture == null) {
                picture = pm.getApplicationIcon(pkg);
                width = height = 144;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            picture.setBounds(0, 0, width, height);
            picture.draw(new Canvas(bitmap));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
