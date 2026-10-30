package com.codex.arklocal;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded helper: restores Unity focus while the first-entry local user-protocol page is shown.
 *
 * The protocol page is rendered by UniWebView inside a UniWebViewDialog. That dialog stays
 * focusable, so while it is open the underlying Unity view loses focus and the checkbox /
 * next button rendered by Unity below the web view stop responding (BACK is also swallowed
 * because UniWebViewDialog._backButtonEnable is false by default).
 *
 * This class polls the UniWebViewManager registry on the main thread and, for dialogs whose
 * WebView URL is exactly one of the local protocol pages, adds
 * WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE to the dialog window. That keeps
 * FLAG_NOT_TOUCH_MODAL (already set by UniWebViewDialog), so touches inside the web view are
 * still delivered to it, while touches outside fall through to the Unity view.
 *
 * Explicit non-goals: no auto-accept, no click/JS injection, no fake progression, no
 * modification of any dialog that is not showing an exact local protocol path. The flag is
 * removed again as soon as a patched dialog navigates away or disappears.
 */
public final class LocalProtocolWindow {

    private static final String TAG = "LocalProtocolWindow";
    private static final long POLL_INTERVAL_MS = 500L;

    private static final String MANAGER_CLASS = "com.onevcat.uniwebview.UniWebViewManager";
    private static final String DIALOG_CLASS = "com.onevcat.uniwebview.UniWebViewDialog";

    /** Hosts the local protocol pages are served on (see LocalHttpServer protocol routes). */
    private static final String[] HOSTS = {"http://127.0.0.1:10029", "http://localhost:10029"};
    private static final String[] PROTOCOL_PATHS = {
            "/protocol/local-test",
            "/protocol/plain/ak/service",
            "/protocol/plain/ak/privacy",
            "/protocol/plain/ak/children_privacy",
            "/protocol/plain/ak/overview_of_changes"
    };

    private static final Set<String> MATCH_URLS = buildMatchUrls();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static WeakReference<Activity> activityRef = new WeakReference<Activity>(null);
    private static boolean polling;
    /** Windows we added FLAG_NOT_FOCUSABLE to, so it can be removed again. */
    private static final Set<Window> patched = new HashSet<Window>();

    private LocalProtocolWindow() {
    }

    /** Called by ModuleRuntime on the accepted activity's resume. Safe to call repeatedly. */
    public static void attach(Activity activity) {
        if (activity == null) return;
        synchronized (LOCK) {
            activityRef = new WeakReference<Activity>(activity);
            if (polling) return;
            polling = true;
        }
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    /** Called by ModuleRuntime on activity destroy. */
    public static void detach(Activity activity) {
        if (activity == null) return;
        synchronized (LOCK) {
            Activity current = activityRef.get();
            if (current != null && current != activity) return;
            activityRef = new WeakReference<Activity>(null);
            if (!polling) return;
            polling = false;
        }
        MAIN.removeCallbacks(TICK);
        restoreAll();
    }

    private static final Runnable TICK = new Runnable() {
        public void run() {
            Activity activity;
            synchronized (LOCK) {
                if (!polling) return;
                activity = activityRef.get();
            }
            if (activity == null || activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                stop();
                return;
            }
            try {
                scan(activity.getClassLoader());
            } catch (Throwable ignored) {
                // Reflection against a third-party view library must never break the game loop.
            }
            MAIN.postDelayed(TICK, POLL_INTERVAL_MS);
        }
    };

    private static void stop() {
        synchronized (LOCK) {
            if (!polling) return;
            polling = false;
        }
        MAIN.removeCallbacks(TICK);
        restoreAll();
    }

    /** Only for the poll loop; must run on the main thread. */
    private static void scan(ClassLoader gameLoader) throws Exception {
        List<Object> dialogs = collectShowingDialogs(gameLoader);
        Set<Window> stillMatching = new HashSet<Window>();

        for (Object dialog : dialogs) {
            if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName())) continue;
            String url = readUrl(dialog);
            if (!isProtocolUrl(url)) continue;

            Window window = windowOf(dialog);
            if (window == null) continue;

            // Already ours from an earlier scan: keep it, but never touch it again.
            if (patched.contains(window)) {
                stillMatching.add(window);
                continue;
            }
            // Not focusable for a reason we did not create: leave the window alone, and do not
            // track it, so restore() can never clear a flag we never set.
            if (!isFocusable(window)) continue;

            if (patched.add(window)) {
                window.addFlags(WindowManagerFlags.NOT_FOCUSABLE);
                android.util.Log.i(TAG, "protocol window focus released: " + url);
            }
            stillMatching.add(window);
        }

        // Any window we patched that is gone or no longer on a protocol page gets its flag back.
        java.util.Iterator<Window> it = patched.iterator();
        while (it.hasNext()) {
            Window window = it.next();
            if (stillMatching.contains(window)) continue;
            it.remove();
            try {
                window.clearFlags(WindowManagerFlags.NOT_FOCUSABLE);
                android.util.Log.i(TAG, "protocol window focus restored");
            } catch (Throwable ignored) {
            }
        }
    }

    /** True only when this window can currently take focus, i.e. it lacks FLAG_NOT_FOCUSABLE. */
    private static boolean isFocusable(Window window) {
        try {
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            if (attrs == null) return false;
            return (attrs.flags & WindowManagerFlags.NOT_FOCUSABLE) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void restoreAll() {
        java.util.Iterator<Window> it = patched.iterator();
        while (it.hasNext()) {
            Window window = it.next();
            it.remove();
            try {
                window.clearFlags(WindowManagerFlags.NOT_FOCUSABLE);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Only the currently showing dialogs are considered. allDialogs() also returns hidden or
     * already-dismissed dialogs, and releasing/returning focus on those has no visible benefit
     * while risking a focus restore against a window the user cannot see.
     */
    private static List<Object> collectShowingDialogs(ClassLoader gameLoader) throws Exception {
        List<Object> out = new ArrayList<Object>();
        Class<?> managerType = Class.forName(MANAGER_CLASS, false, gameLoader);
        Object manager = managerType.getMethod("Instance").invoke(null);
        if (manager == null) return out;

        try {
            Object showing = managerType.getMethod("getShowingWebViewDialogs").invoke(manager);
            if (showing instanceof Collection<?>) out.addAll((Collection<?>) showing);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Window windowOf(Object dialog) {
        if (dialog instanceof Dialog) return ((Dialog) dialog).getWindow();
        try {
            Object window = dialog.getClass().getMethod("getWindow").invoke(dialog);
            return window instanceof Window ? (Window) window : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reads the URL shown by the dialog's UniWebView. Returns null when unavailable. */
    private static String readUrl(Object dialog) {
        Object webView;
        try {
            webView = dialog.getClass().getMethod("getWebView").invoke(dialog);
        } catch (Throwable ignored) {
            return null;
        }
        if (webView == null) return null;
        if (webView instanceof WebView) return ((WebView) webView).getUrl();
        try {
            Object url = webView.getClass().getMethod("getUrl").invoke(webView);
            return url instanceof String ? (String) url : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isProtocolUrl(String url) {
        return url != null && MATCH_URLS.contains(url);
    }

    private static Set<String> buildMatchUrls() {
        Set<String> urls = new HashSet<String>();
        for (String host : HOSTS) {
            for (String path : PROTOCOL_PATHS) urls.add(host + path);
        }
        return urls;
    }

    /** Constant holder so the class stays compilable without the full layout-params import. */
    private static final class WindowManagerFlags {
        static final int NOT_FOCUSABLE = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    }
}
