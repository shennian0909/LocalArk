package com.codex.arklocal;

import android.content.Context;
import android.content.SharedPreferences;
import com.codex.arklocal.storage.LocalStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class LocalSaveSync {
    private final LocalStore store;
    private final File root;
    private final SharedPreferences preferences;
    private final ScheduledExecutorService uploads = Executors.newSingleThreadScheduledExecutor();
    private final WebDavSaveSync transfers;
    private ScheduledFuture<?> pending;
    private volatile String status = "尚未配置云存档";

    LocalSaveSync(Context context, LocalStore store, File root, long initialTimestamp) {
        this.store = store;
        this.root = root;
        preferences = context.getSharedPreferences("arklocal-webdav", Context.MODE_PRIVATE);
        if (!preferences.contains("modified-at")) {
            // A schema upgrade is not newer gameplay; retain the timestamp captured before migration.
            preferences.edit().putLong("modified-at", initialTimestamp).commit();
        }
        transfers = new WebDavSaveSync(new WebDavSaveSync.Source() {
            @Override public WebDavSaveSync.Snapshot read() {
                synchronized (LocalBackend.DATA_LOCK) {
                    return new WebDavSaveSync.Snapshot(store.exportSnapshot(), timestamp());
                }
            }
            @Override public boolean replace(String json, long modifiedAt, long expected) throws Exception {
                synchronized (LocalBackend.DATA_LOCK) {
                    if (timestamp() != expected) return false;
                    File directory = new File(root, "save-backups");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot preserve pre-sync save");
                    File backup = new File(directory, "before-cloud-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json");
                    try (FileOutputStream output = new FileOutputStream(backup)) {
                        store.exportSnapshot(output);
                        output.getFD().sync();
                    }
                    store.replaceSnapshot(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                    if (!preferences.edit().putLong("modified-at", modifiedAt).commit())
                        throw new IOException("Cannot persist imported save timestamp");
                    return true;
                }
            }
        });
        store.jdbcTemplate().setChangeListener(this::changed);
        if (!directory().isEmpty()) status = enabled() ? "已启用自动同步，登录时检查云存档" : "自动同步未启用，可手动同步";
    }

    String directory() { return preferences.getString("directory", ""); }
    String username() { return preferences.getString("username", ""); }
    String password() { return preferences.getString("password", ""); }
    boolean enabled() { return preferences.getBoolean("enabled", false); }
    String status() { return directory().isEmpty() ? "尚未配置云存档" : status; }
    private long timestamp() { return preferences.getLong("modified-at", 0); }

    void configure(String directory, String username, String password, boolean enabled) throws IOException {
        new WebDavSaveSync.Config(directory, username, password);
        if (!preferences.edit().putString("directory", directory.trim()).putString("username", username)
                .putString("password", password).putBoolean("enabled", enabled).commit())
            throw new IOException("同步配置未保存");
    }

    private synchronized void changed() {
        long updated = Math.max(System.currentTimeMillis(), timestamp() + 1);
        if (!preferences.edit().putLong("modified-at", updated).commit()) {
            status = "同步时间未保存，本地进度仍在";
            return;
        }
        if (!enabled()) return;
        if (pending != null) pending.cancel(false);
        pending = uploads.schedule(() -> {
            try { synchronize(false); }
            catch (Exception unavailable) { status = "自动同步未完成，本地进度已保留；可打开同步存档重试"; }
        }, 500, TimeUnit.MILLISECONDS);
    }

    String synchronize(boolean allowDownload) throws Exception {
        WebDavSaveSync.Config config = new WebDavSaveSync.Config(directory(), username(), password());
        WebDavSaveSync.Outcome outcome;
        try { outcome = transfers.synchronize(config, allowDownload); }
        catch (Exception failure) {
            status = "同步未完成，本地存档已保留：" + failure.getMessage();
            throw failure;
        }
        switch (outcome) {
            case UPLOADED: status = "最新存档已上传"; break;
            case DOWNLOADED: status = "已同步云端最新存档，重新登录游戏后刷新"; break;
            case REMOTE_NEWER: status = "云端有更新的存档，下次登录时自动同步"; break;
            default: status = "本地与云端存档已一致";
        }
        return status;
    }

    void beforeLogin() {
        if (!enabled()) return;
        try { synchronize(true); }
        catch (Exception unavailable) { status = "登录时未能检查云存档，当前使用本地进度；请在同步存档中重试"; }
    }
}
