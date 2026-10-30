package com.codex.arklocal.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** A window-local UI bridge. No service, network, storage editing or overlay permission. */
public final class LocalOverlay {
    public static final String ACTION_STATUS = "status";
    public static final String ACTION_START = "start";
    public static final String ACTION_REFILL = "refill";
    public static final String ACTION_SYNC = "sync";
    public static final String ACTION_DIAGNOSTICS = "diagnostics";
    public static final String ACTION_GACHA_OPTIONS = "gacha-options";
    public static final String ACTION_GACHA_SELECT = "gacha-select";

    /**
     * Called on a worker thread. Implementations own the real work and must eventually
     * complete the callback. UI work (e.g. an export picker) must be posted to the main thread.
     * Messages must be Chinese, user-facing summaries without technical or secret data.
     */
    public interface Controller {
        void perform(Activity activity, String action, Callback callback);
    }

    public interface Callback {
        /** May be called on any thread; only the first terminal callback is accepted. */
        void onResult(boolean success, String message);

        /**
         * Optional terminal callback for ACTION_STATUS, with separately reported states.
         * The two-argument callback remains supported for combined status summaries.
         */
        default void onStatus(boolean success, String backendStatus,
                              String saveStatus, String message) {
            onResult(success, message);
        }

        /**
         * Optional typed result for {@link #ACTION_GACHA_OPTIONS}. The default keeps
         * alternate controllers source-compatible and maps to the ordinary callback.
         */
        default void onGachaOptions(boolean success, List<GachaOption> options,
                                    String selectedPool, String message) {
            onResult(success, message);
        }
    }

    /** A validated, user-selectable gacha content entry. */
    public static final class GachaOption {
        private final String id;
        private final String name;

        public GachaOption(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String id() { return id; }
        public String name() { return name; }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Neither keys nor values keep an abandoned Activity alive.
    private static final WeakHashMap<Activity, WeakReference<OverlayState>> STATES =
            new WeakHashMap<>();
    // A module-wide gate survives Activity recreation without retaining an Activity.
    // Closing/rotating the UI must not allow a second mutation while the first is running.
    private static ResultReceiver inFlight;
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2,
            new ThreadFactory() {
                private int count;
                @Override public synchronized Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "ArkLocal-UI-" + (++count));
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final int TEXT = Color.rgb(239, 243, 250);
    private static final int MUTED = Color.rgb(178, 190, 208);
    private static final int CARD = Color.rgb(28, 35, 48);
    private static final String PREFS = "com.codex.arklocal.ui.position.v1";
    // Defence in depth; the Controller must still redact and localize its output.
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)(https?://|localhost|127\\.0\\.0\\.1|(?:\\d{1,3}\\.){3}\\d{1,3}"
            + "|(?:sqlite|exception|stacktrace)"
            + "|\\b(?:sql|tcp|udp|http|https|root|adb)\\b"
            + "|\\b(?:select|insert|update|delete|create|drop|alter)\\b"
            + "|(?:端口|数据库|堆栈|无障碍|SYSTEM_ALERT_WINDOW)"
            + "|(?:java|android|com)\\.[a-z_][a-z0-9_.]*"
            + "|(?:[a-z]:[\\\\/]|/(?:data|sdcard|storage)/)"
            + "|\\b[a-z0-9._-]+:\\d{2,5}\\b)");

    private LocalOverlay() { }

    /** Idempotent for an Activity/window; safe to call from any thread. */
    public static void attach(Activity activity, Controller controller) {
        if (activity == null || controller == null) {
            throw new IllegalArgumentException("Activity and Controller are required");
        }
        final WeakReference<Activity> owner = new WeakReference<>(activity);
        onMain(() -> {
            Activity current = owner.get();
            if (!usable(current)) return;
            View decorView = current.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorView;
            WeakReference<OverlayState> reference = STATES.get(current);
            OverlayState state = reference == null ? null : reference.get();
            if (state != null && !state.disposed && state.decor == decor
                    && state.ball.getParent() == decor) {
                state.controller = controller;
                state.positionBall();
                return;
            }
            if (state != null) state.dispose();
            // Also recover the state from its own view if the weak map entry is absent.
            for (int i = 0; i < decor.getChildCount(); i++) {
                Object tag = decor.getChildAt(i).getTag();
                if (tag instanceof OverlayState) {
                    OverlayState existing = (OverlayState) tag;
                    if (!existing.disposed && existing.activity.get() == current) {
                        existing.controller = controller;
                        STATES.put(current, new WeakReference<>(existing));
                        existing.positionBall();
                        return;
                    }
                }
            }
            OverlayState created = new OverlayState(current, decor, controller);
            STATES.put(current, new WeakReference<>(created));
            created.install();
        });
    }

    /** Removes this Activity's UI. Pending work continues without updating disposed views. */
    public static void detach(Activity activity) {
        if (activity == null) return;
        final WeakReference<Activity> owner = new WeakReference<>(activity);
        onMain(() -> {
            Activity current = owner.get();
            if (current == null) return;
            WeakReference<OverlayState> reference = STATES.remove(current);
            OverlayState state = reference == null ? null : reference.get();
            if (state != null) state.dispose();
        });
    }

    private static void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void renderAll() {
        for (WeakReference<OverlayState> reference : new ArrayList<>(STATES.values())) {
            OverlayState state = reference.get();
            if (state != null && !state.disposed) state.render();
        }
    }

    private static String userText(String value, String fallback, int limit) {
        // Raw diagnostic logs do not belong in this panel. Bound processing on MAIN too.
        if (value == null || value.length() > 4096 || value.trim().isEmpty()) return fallback;
        String clean = value.trim();
        if (TECHNICAL.matcher(clean).find()) return fallback;
        clean = clean.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        if (clean.length() <= limit) return clean;
        int end = limit;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }

    private static final class OverlayState implements ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        final WeakReference<Activity> activity;
        final ViewGroup decor;
        final TextView ball;
        final SharedPreferences preferences;
        final Rect visible = new Rect();
        final Rect previousVisible = new Rect();
        final int[] decorLocation = new int[2];
        final int touchSlop;
        final List<Button> operationButtons = new ArrayList<>();
        Controller controller;
        Dialog dialog;
        Dialog gachaDialog;
        TextView backendView;
        TextView saveView;
        TextView summaryView;
        TextView operationView;
        TextView progressLabel;
        ProgressBar progress;
        Button refresh;
        String backend = "尚未查询";
        String save = "尚未查询";
        String summary = "打开面板后将查询当前状态。";
        String operationMessage = "";
        String pendingLabel = "";
        float fractionX;
        float fractionY;
        float downRawX;
        float downRawY;
        float downBallX;
        float downBallY;
        boolean dragging;
        boolean touchActive;
        boolean disposed;
        boolean busy;
        boolean positioned;

        OverlayState(Activity owner, ViewGroup root, Controller parent) {
            activity = new WeakReference<>(owner);
            decor = root;
            controller = parent;
            preferences = owner.getApplicationContext().getSharedPreferences(PREFS, 0);
            fractionX = fraction(preferences.getFloat("x", 1f), 1f);
            fractionY = fraction(preferences.getFloat("y", .35f), .35f);
            touchSlop = ViewConfiguration.get(owner).getScaledTouchSlop();
            ball = new TextView(owner);
            ball.setText("本地");
            ball.setTextColor(Color.WHITE);
            ball.setTextSize(14);
            ball.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            ball.setGravity(Gravity.CENTER);
            ball.setContentDescription("本地助手，点按打开，拖动移动位置");
            ball.setBackground(shape(Color.rgb(53, 93, 157), 24));
            ball.setElevation(dp(8));
            ball.setClickable(true);
            ball.setFocusable(true);
            ball.setTag(this);
            ball.setOnClickListener(view -> showPanel());
            ball.setOnTouchListener((view, event) -> onTouch(event));
        }

        void install() {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48));
            params.gravity = Gravity.TOP | Gravity.LEFT;
            ball.addOnAttachStateChangeListener(this);
            decor.addView(ball, params);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(this);
            positionBall();
        }

        int dp(float value) {
            return Math.round(value * decor.getResources().getDisplayMetrics().density);
        }

        GradientDrawable shape(int color, int radiusDp) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(dp(radiusDp));
            return background;
        }

        boolean updateBounds() {
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return false;
            decor.getWindowVisibleDisplayFrame(visible);
            decor.getLocationOnScreen(decorLocation);
            visible.offset(-decorLocation[0], -decorLocation[1]);
            if (!visible.intersect(0, 0, decor.getWidth(), decor.getHeight())) {
                visible.set(0, 0, decor.getWidth(), decor.getHeight());
            }
            WindowInsets insets = decor.getRootWindowInsets();
            DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout != null) {
                visible.left = Math.max(visible.left, cutout.getSafeInsetLeft());
                visible.top = Math.max(visible.top, cutout.getSafeInsetTop());
                visible.right = Math.min(visible.right,
                        decor.getWidth() - cutout.getSafeInsetRight());
                visible.bottom = Math.min(visible.bottom,
                        decor.getHeight() - cutout.getSafeInsetBottom());
            }
            return !visible.isEmpty();
        }

        float minX() { return visible.left + Math.min(dp(8), Math.max(0, visible.width() - dp(48)) / 2); }
        float minY() { return visible.top + Math.min(dp(8), Math.max(0, visible.height() - dp(48)) / 2); }
        float maxX() { return Math.max(minX(), visible.right - dp(48) - (minX() - visible.left)); }
        float maxY() { return Math.max(minY(), visible.bottom - dp(48) - (minY() - visible.top)); }

        void positionBall() {
            if (disposed || !updateBounds()) return;
            if (!positioned || !visible.equals(previousVisible)) {
                // A geometry change cancels an in-progress tap/drag to avoid a false click.
                touchActive = false;
                dragging = false;
                ball.setPressed(false);
                ball.setX(minX() + (maxX() - minX()) * fractionX);
                ball.setY(minY() + (maxY() - minY()) * fractionY);
                previousVisible.set(visible);
                positioned = true;
                resizePanel();
            }
        }

        void clampBall(float x, float y) {
            ball.setX(Math.max(minX(), Math.min(maxX(), x)));
            ball.setY(Math.max(minY(), Math.min(maxY(), y)));
        }

        void savePosition() {
            fractionX = maxX() == minX() ? fractionX
                    : fraction((ball.getX() - minX()) / (maxX() - minX()), fractionX);
            fractionY = maxY() == minY() ? fractionY
                    : fraction((ball.getY() - minY()) / (maxY() - minY()), fractionY);
            preferences.edit().putFloat("x", fractionX).putFloat("y", fractionY).apply();
        }

        boolean onTouch(MotionEvent event) {
            if (disposed) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!updateBounds()) return false;
                    touchActive = true;
                    dragging = false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downBallX = ball.getX();
                    downBallY = ball.getY();
                    ball.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (!touchActive) return true;
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (dx * dx + dy * dy > (float) touchSlop * touchSlop) dragging = true;
                    if (dragging) clampBall(downBallX + dx, downBallY + dy);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        touchActive = false;
                        ball.setPressed(false);
                        if (dragging) savePosition();
                        else ball.performClick();
                        dragging = false;
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    if (touchActive && dragging) savePosition();
                    touchActive = false;
                    dragging = false;
                    ball.setPressed(false);
                    return true;
                default:
                    return true;
            }
        }

        @Override public void onGlobalLayout() { positionBall(); }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { dispose(); }

        TextView text(String value, int size, int color) {
            TextView view = new TextView(decor.getContext());
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setLineSpacing(dp(3), 1f);
            return view;
        }

        void addSpaced(LinearLayout parent, View child, int gap) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(gap);
            parent.addView(child, params);
        }

        Button button(String label, int color) {
            Button result = new Button(decor.getContext());
            result.setText(label);
            result.setTextSize(16);
            result.setTextColor(TEXT);
            result.setAllCaps(false);
            result.setMinHeight(dp(48));
            result.setMinimumHeight(dp(48));
            result.setPadding(dp(16), dp(12), dp(16), dp(12));
            result.setBackground(shape(color, 12));
            return result;
        }

        void addOperation(LinearLayout parent, String label, String action) {
            Button actionButton = button(label, Color.rgb(48, 64, 88));
            actionButton.setOnClickListener(view -> request(action, label));
            operationButtons.add(actionButton);
            addSpaced(parent, actionButton, 10);
        }

        void showPanel() {
            Activity owner = activity.get();
            if (disposed || !usable(owner)) return;
            if (dialog != null && dialog.isShowing()) return;
            Dialog created = new Dialog(owner);
            created.requestWindowFeature(Window.FEATURE_NO_TITLE);
            created.setCanceledOnTouchOutside(true);

            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(20), dp(20), dp(20), dp(20));
            content.setBackground(shape(Color.rgb(19, 25, 36), 20));
            TextView title = text("本地助手", 22, TEXT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            addSpaced(content, title, 0);
            addSpaced(content, text("管理本地服务与存档", 15, MUTED), 6);

            LinearLayout statusCard = new LinearLayout(owner);
            statusCard.setOrientation(LinearLayout.VERTICAL);
            statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            statusCard.setBackground(shape(CARD, 14));
            addSpaced(statusCard, text("本地服务", 15, MUTED), 0);
            backendView = text(backend, 16, TEXT);
            addSpaced(statusCard, backendView, 4);
            addSpaced(statusCard, text("存档状态", 15, MUTED), 14);
            saveView = text(save, 16, TEXT);
            addSpaced(statusCard, saveView, 4);
            summaryView = text(summary, 15, MUTED);
            addSpaced(statusCard, summaryView, 12);
            refresh = button("刷新状态", Color.rgb(39, 48, 65));
            refresh.setOnClickListener(view -> request(ACTION_STATUS, "查询状态"));
            addSpaced(statusCard, refresh, 12);
            addSpaced(content, statusCard, 18);

            LinearLayout progressRow = new LinearLayout(owner);
            progressRow.setGravity(Gravity.CENTER_VERTICAL);
            progress = new ProgressBar(owner);
            progress.setIndeterminate(true);
            progressRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
            progressLabel = text("", 15, MUTED);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            labelParams.leftMargin = dp(10);
            progressRow.addView(progressLabel, labelParams);
            addSpaced(content, progressRow, 14);

            operationView = text(operationMessage, 15, TEXT);
            operationView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addSpaced(content, operationView, 6);
            addOperation(content, "启动本地服务", ACTION_START);
            addOperation(content, "补满抽卡资源", ACTION_REFILL);
            addOperation(content, "补齐养成道具", "materials");
            addOperation(content, "解锁时装", "skins");
            addOperation(content, "同步存档", ACTION_SYNC);
            addOperation(content, "诊断信息", ACTION_DIAGNOSTICS);
            Button fold = button("折叠", Color.rgb(39, 48, 65));
            fold.setOnClickListener(view -> created.dismiss());
            addSpaced(content, fold, 18);
            Button close = button("关闭悬浮球", Color.rgb(39, 48, 65));
            close.setOnClickListener(view -> dispose());
            addSpaced(content, close, 10);

            ScrollView scroll = new ScrollView(owner);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            created.setContentView(scroll);
            created.setOnDismissListener(ignored -> clearPanel(created));
            dialog = created;
            try {
                created.show();
                Window window = created.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    window.setDimAmount(.55f);
                    window.setGravity(Gravity.CENTER);
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                resizePanel();
                render();
                if (!busy) request(ACTION_STATUS, "查询状态");
            } catch (WindowManager.BadTokenException | IllegalStateException exception) {
                if (created.isShowing()) created.dismiss();
                clearPanel(created);
                if (!usable(activity.get())) dispose();
            }
        }

        void resizePanel() {
            if (dialog == null || !dialog.isShowing() || visible.isEmpty()) return;
            Window window = dialog.getWindow();
            if (window == null) return;
            int width = Math.max(1, Math.min(dp(420), visible.width() - dp(24)));
            // A bounded scrolling surface stays usable in landscape and at large font scales.
            int height = Math.max(1, Math.min(dp(720), visible.height() - dp(24)));
            window.setLayout(width, height);
        }

        void completeGachaOptions(boolean success, List<GachaOption> options,
                                  String selectedPool, String message) {
            if (disposed || !usable(activity.get())) return;
            if (!success) {
                operationMessage = "自选寻访未打开："
                        + userText(message, "请确认本地服务已就绪后重试。", 600);
                render();
                return;
            }
            List<GachaOption> safe = new ArrayList<>();
            if (options != null) {
                for (GachaOption option : options) {
                    if (option != null && option.id() != null
                            && !option.id().trim().isEmpty()) {
                        safe.add(option);
                    }
                }
            }
            operationMessage = safe.isEmpty()
                    ? "未找到可用的寻访内容，请确认本地数据已更新。"
                    : userText(message, "请选择要替换当前寻访的出货内容。", 600);
            render();
            showGachaChooser(safe, selectedPool);
        }

        void showGachaChooser(List<GachaOption> options, String selectedPool) {
            Activity owner = activity.get();
            if (disposed || !usable(owner) || dialog == null || !dialog.isShowing()) return;
            if (gachaDialog != null && gachaDialog.isShowing()) gachaDialog.dismiss();
            gachaDialog = null;

            Dialog created = new Dialog(owner);
            created.requestWindowFeature(Window.FEATURE_NO_TITLE);
            created.setCanceledOnTouchOutside(true);

            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(20), dp(18), dp(20), dp(18));
            content.setBackground(shape(Color.rgb(19, 25, 36), 20));
            TextView title = text("自选寻访内容", 22, TEXT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            addSpaced(content, title, 0);

            TextView warning = text(
                    "替换当前寻访的出货内容；游戏横幅与消耗保持原样。沿用本地保底计数，不为所选内容单独计算。新手寻访不受影响。",
                    15, Color.rgb(255, 211, 130));
            warning.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addSpaced(content, warning, 10);

            String selectedName = null;
            if (selectedPool != null && !selectedPool.trim().isEmpty()) {
                for (GachaOption option : options) {
                    if (selectedPool.equals(option.id())) {
                        selectedName = choiceText(option.name(), option.id(), 100);
                        break;
                    }
                }
            }
            addSpaced(content, text(selectedName == null
                    ? "当前：原卡池（不替换）"
                    : "当前：" + selectedName, 15, MUTED), 8);

            EditText search = new EditText(owner);
            search.setSingleLine(true);
            search.setTextSize(16);
            search.setTextColor(TEXT);
            search.setHintTextColor(MUTED);
            search.setHint("搜索寻访名称或卡池 ID");
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setPadding(dp(14), dp(10), dp(14), dp(10));
            search.setBackground(shape(Color.rgb(39, 48, 65), 12));
            addSpaced(content, search, 12);

            LinearLayout optionList = new LinearLayout(owner);
            optionList.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(owner);
            scroll.setFillViewport(false);
            scroll.addView(optionList, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addSpaced(content, scroll, 10);
            // Keep actions reachable with hundreds of options; only the list scrolls here.
            LinearLayout.LayoutParams listBounds=(LinearLayout.LayoutParams)scroll.getLayoutParams();
            listBounds.height=dp(220);scroll.setLayoutParams(listBounds);

            renderGachaChoices(optionList, options, selectedPool, "");
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count,
                                                        int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before,
                                                    int count) { }
                @Override public void afterTextChanged(Editable text) {
                    renderGachaChoices(optionList, options, selectedPool,
                            text == null ? "" : text.toString());
                }
            });

            Button restore = button("恢复原卡池", Color.rgb(39, 48, 65));
            restore.setOnClickListener(view -> {
                if (inFlight != null || disposed) return;
                Dialog chooser = gachaDialog;
                if (chooser != null) chooser.dismiss();
                request(ACTION_GACHA_SELECT + ":", "恢复原卡池");
            });
            addSpaced(content, restore, 12);
            Button close = button("返回", Color.rgb(39, 48, 65));
            close.setOnClickListener(view -> created.dismiss());
            addSpaced(content, close, 8);

            ScrollView frame = new ScrollView(owner);
            frame.setFillViewport(true);
            frame.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            created.setContentView(frame);
            created.setOnDismissListener(ignored -> {
                if (gachaDialog == created) gachaDialog = null;
            });
            gachaDialog = created;
            try {
                created.show();
                Window window = created.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    window.setDimAmount(.55f);
                    window.setGravity(Gravity.CENTER);
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    int width = Math.max(1, Math.min(dp(420), visible.width() - dp(24)));
                    int height = Math.max(1, Math.min(dp(700), visible.height() - dp(24)));
                    window.setLayout(width, height);
                }
            } catch (WindowManager.BadTokenException | IllegalStateException exception) {
                if (gachaDialog == created) gachaDialog = null;
                if (created.isShowing()) created.dismiss();
            }
        }

        void renderGachaChoices(LinearLayout optionList, List<GachaOption> options,
                                String selectedPool, String query) {
            optionList.removeAllViews();
            String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            int matches = 0;
            for (GachaOption option : options) {
                String name = option.name() == null ? "" : option.name();
                String id = option.id();
                if (!needle.isEmpty()
                        && !name.toLowerCase(Locale.ROOT).contains(needle)
                        && !id.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                String label = choiceText(name, id, 100);
                boolean duplicateName = false;
                for (GachaOption other : options) {
                    if (other != option && name.equals(other.name())) {
                        duplicateName = true;
                        break;
                    }
                }
                if (duplicateName) label += " · " + choiceText(id, id, 80);
                if (id.equals(selectedPool)) label = "当前 · " + label;
                Button choice = button(label, Color.rgb(48, 64, 88));
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setOnClickListener(view -> {
                    if (inFlight != null || disposed) return;
                    Dialog chooser = gachaDialog;
                    if (chooser != null) chooser.dismiss();
                    request(ACTION_GACHA_SELECT + ":" + id, "选择寻访内容");
                });
                addSpaced(optionList, choice, matches == 0 ? 0 : 8);
                matches++;
            }
            if (matches == 0) {
                addSpaced(optionList, text(query == null || query.trim().isEmpty()
                        ? "暂无可用寻访内容。"
                        : "没有匹配的寻访内容。", 15, MUTED), 8);
            }
        }

        String choiceText(String value, String fallback, int limit) {
            String clean = value == null ? "" : value.trim();
            if (clean.isEmpty()) clean = fallback == null ? "" : fallback.trim();
            clean = clean.replaceAll("[\\p{Cntrl}]", "");
            if (clean.length() <= limit) return clean;
            int end = limit;
            if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
            return clean.substring(0, end) + "…";
        }

        void clearPanel(Dialog expected) {
            if (dialog != expected) return;
            dialog = null;
            Dialog chooser = gachaDialog;
            gachaDialog = null;
            if (chooser != null && chooser.isShowing()) chooser.dismiss();
            backendView = null;
            saveView = null;
            summaryView = null;
            operationView = null;
            progress = null;
            progressLabel = null;
            refresh = null;
            operationButtons.clear();
        }

        void render() {
            busy = inFlight != null;
            pendingLabel = busy ? inFlight.label : "";
            if (dialog == null) return;
            backendView.setText(backend);
            saveView.setText(save);
            summaryView.setText(summary);
            summaryView.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
            operationView.setText(operationMessage);
            operationView.setVisibility(operationMessage.isEmpty() ? View.GONE : View.VISIBLE);
            progressLabel.setText(busy ? pendingLabel + "，请稍候…" : "");
            View progressRow = (View) progress.getParent();
            progressRow.setVisibility(busy ? View.VISIBLE : View.GONE);
            refresh.setEnabled(!busy);
            refresh.setAlpha(busy ? .5f : 1f);
            for (Button button : operationButtons) {
                button.setEnabled(!busy);
                button.setAlpha(busy ? .5f : 1f);
            }
        }

        void request(String action, String label) {
            Activity owner = activity.get();
            if (disposed || inFlight != null || !usable(owner) || controller == null) return;
            if (!ACTION_STATUS.equals(action)) operationMessage = "";
            final Controller target = controller;
            final WeakReference<OverlayState> stateReference = new WeakReference<>(this);
            final WeakReference<Activity> ownerReference = new WeakReference<>(owner);
            final ResultReceiver receiver = new ResultReceiver(stateReference, action, label);
            inFlight = receiver;
            renderAll();
            WORKER.execute(() -> {
                Activity current = ownerReference.get();
                if (!usable(current)) {
                    receiver.onResult(false, "原界面已关闭，操作未执行。请重试。");
                    return;
                }
                // The parent must also respect Activity lifecycle during asynchronous work.
                try {
                    target.perform(current, action, receiver);
                } catch (RuntimeException exception) {
                    receiver.onResult(false, "操作未完成，请稍后重试。");
                }
            });
        }

        void complete(String action, boolean success, String message,
                      String backendStatus, String saveStatus, boolean detailedStatus) {
            if (disposed || !usable(activity.get())) return;
            if (ACTION_STATUS.equals(action)) {
                if (success) {
                    if (detailedStatus) {
                        backend = userText(backendStatus, "暂未提供服务状态", 180);
                        save = userText(saveStatus, "暂未提供存档状态", 180);
                        summary = userText(message, "状态已更新。", 600);
                    } else {
                        // Never infer running/saved/resource counts from a generic success.
                        backend = "请查看下方状态说明";
                        save = "请查看下方状态说明";
                        summary = userText(message, "暂未提供详细状态，请稍后刷新。", 600);
                    }
                } else {
                    summary = "状态更新未完成，当前显示可能是上次结果。"
                            + userText(message, "请稍后刷新。", 600);
                }
            } else {
                operationMessage = (success ? "操作完成：" : "操作未完成：")
                        + userText(message, success ? "已收到完成确认。" : "请稍后重试。", 600);
            }
            render();
            // Refresh only after real completion; even a successful mutation does not imply
            // the service is running or that a particular save/resource value now exists.
            if (success && !ACTION_STATUS.equals(action)) request(ACTION_STATUS, "更新状态");
        }

        void dispose() {
            if (disposed) return;
            disposed = true;
            controller = null;
            touchActive = false;
            busy = false;
            Activity owner = activity.get();
            WeakReference<OverlayState> reference = owner == null ? null : STATES.get(owner);
            if (reference != null && reference.get() == this) STATES.remove(owner);
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            ball.removeOnAttachStateChangeListener(this);
            ball.setOnTouchListener(null);
            ball.setOnClickListener(null);
            ball.setTag(null);
            if (dialog != null) {
                Dialog old = dialog;
                old.dismiss();
                clearPanel(old);
            }
            if (ball.getParent() == decor) decor.removeView(ball);
        }
    }

    private static float fraction(float value, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback
                : Math.max(0f, Math.min(1f, value));
    }

    private static final class ResultReceiver implements Callback {
        final WeakReference<OverlayState> state;
        final String action;
        final String label;
        final AtomicBoolean completed = new AtomicBoolean();

        ResultReceiver(WeakReference<OverlayState> state, String action, String label) {
            this.state = state;
            this.action = action;
            this.label = label;
        }

        @Override public void onResult(boolean success, String message) {
            deliver(success, message, null, null, false);
        }

        @Override public void onStatus(boolean success, String backendStatus,
                                       String saveStatus, String message) {
            deliver(success, message, backendStatus, saveStatus, true);
        }

        @Override public void onGachaOptions(boolean success, List<GachaOption> options,
                                             String selectedPool, String message) {
            deliverGachaOptions(success, options, selectedPool, message);
        }

        void deliverGachaOptions(boolean success, List<GachaOption> options,
                                 String selectedPool, String message) {
            if (!completed.compareAndSet(false, true)) return;
            MAIN.post(() -> {
                if (inFlight != this) return;
                inFlight = null;
                OverlayState current = state.get();
                if (current == null || current.disposed || !usable(current.activity.get())) {
                    current = null;
                    for (WeakReference<OverlayState> reference :
                            new ArrayList<>(STATES.values())) {
                        OverlayState candidate = reference.get();
                        if (candidate != null && !candidate.disposed
                                && usable(candidate.activity.get())) {
                            current = candidate;
                            break;
                        }
                    }
                }
                renderAll();
                if (current != null) current.completeGachaOptions(
                        success, options, selectedPool, message);
            });
        }

        void deliver(boolean success, String message, String backendStatus,
                     String saveStatus, boolean detailedStatus) {
            if (!completed.compareAndSet(false, true)) return;
            MAIN.post(() -> {
                if (inFlight != this) return;
                inFlight = null;
                OverlayState current = state.get();
                if (current == null || current.disposed || !usable(current.activity.get())) {
                    current = null;
                    for (WeakReference<OverlayState> reference :
                            new ArrayList<>(STATES.values())) {
                        OverlayState candidate = reference.get();
                        if (candidate != null && !candidate.disposed
                                && usable(candidate.activity.get())) {
                            current = candidate;
                            break;
                        }
                    }
                }
                renderAll();
                if (current != null) current.complete(action, success, message,
                        backendStatus, saveStatus, detailedStatus);
            });
        }
    }
}
