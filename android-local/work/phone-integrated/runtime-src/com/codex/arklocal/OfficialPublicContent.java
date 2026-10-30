package com.codex.arklocal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Official version and announcement metadata with bounded network access and
 * offline fallback. No request is made until one of the content getters runs.
 */
public final class OfficialPublicContent {
    static final String VERSION_URL =
            "https://ak-conf.hypergryph.com/config/prod/official/Android/version";
    static final String ANNOUNCEMENT_URL =
            "https://ak-conf.hypergryph.com/config/prod/announce_meta/Android/announcement.meta.json";
    static final String PREANNOUNCEMENT_URL =
            "https://ak-conf.hypergryph.com/config/prod/announce_meta/Android/preannouncement.meta.json";
    static final int TIMEOUT_MILLIS = 3000;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;
    static final String CACHE_FILE = "public-content-cache.json";
    private static final int CACHE_SCHEMA = 1;
    private static final Pattern RES_VERSION = Pattern.compile(
            "^\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-[0-9A-Za-z]+$");

    interface Fetcher {
        Response fetch(String url) throws Exception;
    }

    static final class Response {
        final String body;
        final String lastModified;

        Response(String body, String lastModified) {
            this.body = body;
            this.lastModified = lastModified == null ? "" : lastModified;
        }
    }

    private static final class CacheEntry {
        final JSONObject payload;
        final long updatedAt;
        final String lastModified;

        CacheEntry(JSONObject payload, long updatedAt, String lastModified) {
            this.payload = payload;
            this.updatedAt = updatedAt;
            this.lastModified = lastModified == null ? "" : lastModified;
        }

        boolean fresh(long now) {
            return updatedAt > 0 && now - updatedAt < CACHE_TTL_MILLIS;
        }
    }

    private final File cacheFile;
    private final Fetcher fetcher;
    private final JSONObject bundledVersion;
    private final JSONObject bundledAnnouncement;
    private final JSONObject bundledPreannouncement;
    private final String bundledClientVersion;
    private final JSONObject status = new JSONObject(true);
    private JSONObject cache = new JSONObject(true);
    private CacheEntry cachedVersion;
    private CacheEntry cachedAnnouncement;
    private CacheEntry cachedPreannouncement;

    public OfficialPublicContent(File root, JSONObject bundledConfig) {
        this(root, bundledConfig, new HttpFetcher());
    }

    OfficialPublicContent(File root, JSONObject bundledConfig, Fetcher fetcher) {
        if (fetcher == null) throw new IllegalArgumentException("fetcher");
        this.fetcher = fetcher;
        cacheFile = root == null ? null : new File(root, CACHE_FILE);
        bundledVersion = normalizeBundledVersion(bundledConfig);
        bundledAnnouncement = normalizeBundledAnnouncement(bundledConfig);
        bundledPreannouncement = normalizeBundledPreannouncement(bundledConfig);
        bundledClientVersion = bundledVersion == null
                ? ""
                : value(bundledVersion, "clientVersion");
        loadCache();
        prepareStatus();
    }

    public synchronized JSONObject version() {
        long now = System.currentTimeMillis();
        if (cachedVersion != null && cachedVersion.fresh(now)) {
            publishVersion("cache", cachedVersion, "", "", false);
            return copy(cachedVersion.payload);
        }
        try {
            Response response = fetch(VERSION_URL);
            JSONObject candidate = normalizeVersion(parseResponse(response));
            String officialClientVersion = value(candidate, "clientVersion");
            if (!bundledClientVersion.equals(officialClientVersion)) {
                publishVersionFallback(
                        "客户端版本与官方不一致，需要新版 APK",
                        officialClientVersion,
                        true);
                return fallbackVersion();
            }
            String floor = versionFloor();
            if (floor != null && compareVersions(value(candidate, "resVersion"), floor) < 0) {
                publishVersionFallback(
                        "官方资源版本低于当前内置或缓存版本",
                        officialClientVersion,
                        false);
                return fallbackVersion();
            }
            CacheEntry accepted = new CacheEntry(
                    candidate,
                    now,
                    response.lastModified);
            String cacheError = rememberVersion(accepted);
            publishVersion("network", accepted, cacheError, "", false);
            return copy(candidate);
        } catch (Exception failure) {
            publishVersionFallback(errorFor(failure), "", false);
            return fallbackVersion();
        }
    }

    public synchronized JSONObject announcement() {
        long now = System.currentTimeMillis();
        if (cachedAnnouncement != null && cachedAnnouncement.fresh(now)) {
            publish("announcement", "cache", cachedAnnouncement, "");
            return copy(cachedAnnouncement.payload);
        }
        try {
            Response response = fetch(ANNOUNCEMENT_URL);
            JSONObject candidate = normalizeAnnouncement(parseResponse(response));
            CacheEntry accepted = new CacheEntry(candidate, now, response.lastModified);
            String cacheError = rememberAnnouncement(accepted);
            publish("announcement", "network", accepted, cacheError);
            return copy(candidate);
        } catch (Exception failure) {
            publishFallback("announcement", cachedAnnouncement, errorFor(failure));
            return fallbackAnnouncement();
        }
    }

    public synchronized JSONObject preannouncement() {
        long now = System.currentTimeMillis();
        if (cachedPreannouncement != null && cachedPreannouncement.fresh(now)) {
            publish("preannouncement", "cache", cachedPreannouncement, "");
            return copy(cachedPreannouncement.payload);
        }
        try {
            Response response = fetch(PREANNOUNCEMENT_URL);
            JSONObject candidate = normalizePreannouncement(parseResponse(response));
            CacheEntry accepted = new CacheEntry(candidate, now, response.lastModified);
            String cacheError = rememberPreannouncement(accepted);
            publish("preannouncement", "network", accepted, cacheError);
            return copy(candidate);
        } catch (Exception failure) {
            publishFallback("preannouncement", cachedPreannouncement, errorFor(failure));
            return fallbackPreannouncement();
        }
    }

    public synchronized JSONObject status() {
        return JSON.parseObject(JSON.toJSONString(status));
    }

    private Response fetch(String url) throws Exception {
        return fetcher.fetch(url);
    }

    private JSONObject parseResponse(Response response) throws IOException {
        if (response == null || response.body == null) throw new IOException("响应为空");
        if (response.body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("响应超过 2 MiB");
        }
        try {
            JSONObject parsed = JSON.parseObject(response.body);
            if (parsed == null) throw new IOException("响应不是 JSON 对象");
            return parsed;
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("响应不是有效的 JSON 对象");
        }
    }

    private JSONObject fallbackVersion() {
        if (cachedVersion != null) return copy(cachedVersion.payload);
        return bundledVersion == null ? new JSONObject(true) : copy(bundledVersion);
    }

    private void publishVersionFallback(
            String error, String officialClientVersion, boolean updateRequired) {
        if (cachedVersion != null) {
            status.put("version", versionStatus(
                    "cache", cachedVersion.payload, cachedVersion.updatedAt,
                    cachedVersion.lastModified, error, officialClientVersion, updateRequired));
        } else if (bundledVersion != null) {
            status.put("version", versionStatus(
                    "bundled", bundledVersion, 0, "", error, officialClientVersion, updateRequired));
        } else {
            publishUnavailable("version");
        }
    }

    private void publishVersion(
            String source,
            CacheEntry entry,
            String error,
            String officialClientVersion,
            boolean updateRequired) {
        status.put(
                "version",
                versionStatus(
                        source,
                        entry.payload,
                        entry.updatedAt,
                        entry.lastModified,
                        error,
                        officialClientVersion,
                        updateRequired));
    }

    private JSONObject versionStatus(
            String source,
            JSONObject payload,
            long updatedAt,
            String lastModified,
            String error,
            String officialClientVersion,
            boolean updateRequired) {
        JSONObject result = resourceStatus(source, updatedAt, lastModified, error);
        result.put("clientVersion", payload == null ? "" : value(payload, "clientVersion"));
        result.put("officialClientVersion", officialClientVersion == null ? "" : officialClientVersion);
        result.put("updateRequired", updateRequired);
        result.put("message", updateRequired ? "客户端版本与官方不一致，需要新版 APK" : "");
        return result;
    }

    private JSONObject fallbackAnnouncement() {
        if (cachedAnnouncement != null) return copy(cachedAnnouncement.payload);
        return bundledAnnouncement == null
                ? new JSONObject(true)
                : copy(bundledAnnouncement);
    }

    private JSONObject fallbackPreannouncement() {
        if (cachedPreannouncement != null) return copy(cachedPreannouncement.payload);
        return bundledPreannouncement == null
                ? new JSONObject(true)
                : copy(bundledPreannouncement);
    }

    private void publishFallback(String key, CacheEntry cached, String error) {
        if (cached != null) {
            publish(key, "cache", cached, error);
        } else {
            JSONObject bundled = bundled(key);
            if (bundled == null) publishUnavailable(key);
            else publish(key, "bundled", new CacheEntry(bundled, 0, ""), error);
        }
    }

    private void publish(String key, String source, CacheEntry entry, String error) {
        status.put(key, resourceStatus(source, entry.updatedAt, entry.lastModified, error));
    }

    private void publishUnavailable(String key) {
        JSONObject value = resourceStatus("unavailable", 0, "", "没有可用的内置或缓存内容");
        if (key.equals("version")) {
            value.put("clientVersion", bundledClientVersion);
            value.put("officialClientVersion", "");
            value.put("updateRequired", false);
            value.put("message", "");
        }
        status.put(key, value);
    }

    private static JSONObject resourceStatus(
            String source, long updatedAt, String lastModified, String error) {
        JSONObject result = new JSONObject(true);
        result.put("source", source);
        // updatedAt is this client's successful fetch time; lastModified is upstream metadata.
        result.put("updatedAt", updatedAt);
        result.put("lastModified", lastModified == null ? "" : lastModified);
        result.put("error", error == null ? "" : error);
        return result;
    }

    private void prepareStatus() {
        status.put("cacheTtlSeconds", CACHE_TTL_MILLIS / 1000L);
        status.put("version", initialVersionStatus());
        status.put("announcement", initialStatus(cachedAnnouncement, bundledAnnouncement));
        status.put("preannouncement", initialStatus(cachedPreannouncement, bundledPreannouncement));
    }

    private JSONObject initialVersionStatus() {
        if (cachedVersion != null) {
            return versionStatus(
                    "cache",
                    cachedVersion.payload,
                    cachedVersion.updatedAt,
                    cachedVersion.lastModified,
                    "",
                    "",
                    false);
        }
        if (bundledVersion != null) {
            return versionStatus("bundled", bundledVersion, 0, "", "", "", false);
        }
        return resourceStatus("unavailable", 0, "", "没有可用的内置内容");
    }

    private static JSONObject initialStatus(CacheEntry cached, JSONObject bundled) {
        if (cached != null) {
            return resourceStatus(
                    "cache", cached.updatedAt, cached.lastModified, "");
        }
        if (bundled != null) return resourceStatus("bundled", 0, "", "");
        return resourceStatus("unavailable", 0, "", "没有可用的内置内容");
    }

    private String versionFloor() {
        String bundled = bundledVersion == null ? null : value(bundledVersion, "resVersion");
        String cached = cachedVersion == null ? null : value(cachedVersion.payload, "resVersion");
        if (bundled == null || bundled.isEmpty()) return cached;
        if (cached == null || cached.isEmpty()) return bundled;
        return compareVersions(bundled, cached) >= 0 ? bundled : cached;
    }

    private static int compareVersions(String left, String right) {
        return left.compareTo(right);
    }

    private JSONObject bundled(String key) {
        if (key.equals("version")) return bundledVersion;
        if (key.equals("announcement")) return bundledAnnouncement;
        return bundledPreannouncement;
    }

    private void loadCache() {
        if (cacheFile == null || !cacheFile.isFile()) return;
        try {
            byte[] bytes = Files.readAllBytes(cacheFile.toPath());
            JSONObject loaded = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            if (loaded == null || loaded.getIntValue("schema") != CACHE_SCHEMA) return;
            cache = loaded;
            cachedVersion = readVersionEntry();
            cachedAnnouncement = readEntry("announcement", false);
            cachedPreannouncement = readEntry("preannouncement", true);
        } catch (Exception ignored) {
            cache = new JSONObject(true);
        }
    }

    private CacheEntry readVersionEntry() {
        try {
            CacheEntry raw = readRawEntry("version");
            if (raw == null) return null;
            JSONObject normalized = normalizeVersion(copy(raw.payload));
            if (!bundledClientVersion.equals(value(normalized, "clientVersion"))) return null;
            if (bundledVersion != null
                    && compareVersions(
                                    value(normalized, "resVersion"),
                                    value(bundledVersion, "resVersion"))
                            < 0) {
                return null;
            }
            return new CacheEntry(normalized, raw.updatedAt, raw.lastModified);
        } catch (Exception ignored) {
            return null;
        }
    }

    private CacheEntry readEntry(String key, boolean preannouncement) {
        CacheEntry raw = readRawEntry(key);
        if (raw == null) return null;
        try {
            JSONObject normalized = preannouncement
                    ? normalizePreannouncement(copy(raw.payload))
                    : normalizeAnnouncement(copy(raw.payload));
            return new CacheEntry(normalized, raw.updatedAt, raw.lastModified);
        } catch (Exception ignored) {
            return null;
        }
    }

    private CacheEntry readRawEntry(String key) {
        try {
            JSONObject entry = cache.getJSONObject(key);
            if (entry == null) return null;
            long updatedAt = entry.getLongValue("updatedAt");
            if (updatedAt <= 0) return null;
            JSONObject payload = entry.getJSONObject("payload");
            if (payload == null) return null;
            return new CacheEntry(copy(payload), updatedAt, value(entry, "lastModified"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String rememberVersion(CacheEntry entry) {
        cachedVersion = entry;
        return remember("version", entry);
    }

    private String rememberAnnouncement(CacheEntry entry) {
        cachedAnnouncement = entry;
        return remember("announcement", entry);
    }

    private String rememberPreannouncement(CacheEntry entry) {
        cachedPreannouncement = entry;
        return remember("preannouncement", entry);
    }

    private String remember(String key, CacheEntry entry) {
        JSONObject stored = new JSONObject(true);
        stored.put("updatedAt", entry.updatedAt);
        stored.put("lastModified", entry.lastModified);
        stored.put("payload", copy(entry.payload));
        cache.put(key, stored);
        cache.put("schema", CACHE_SCHEMA);
        if (cacheFile == null) return "缓存目录不可用";
        File parent = cacheFile.getParentFile();
        File temporary = null;
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("无法创建缓存目录");
            }
            temporary = File.createTempFile("public-content-cache-", ".tmp", parent);
            Files.write(
                    temporary.toPath(),
                    JSON.toJSONString(cache).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temporary.toPath(),
                        cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary.toPath(),
                        cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return "";
        } catch (Exception failure) {
            return errorFor(failure);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private static JSONObject normalizeBundledVersion(JSONObject config) {
        if (config == null) return null;
        try {
            JSONObject version = config.getJSONObject("version");
            return version == null ? null : normalizeVersion(version.getJSONObject("android"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject normalizeBundledAnnouncement(JSONObject config) {
        if (config == null) return null;
        try {
            JSONObject announce = config.getJSONObject("announce");
            return announce == null ? null : normalizeAnnouncement(announce.getJSONObject("announcement"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject normalizeBundledPreannouncement(JSONObject config) {
        if (config == null) return null;
        try {
            JSONObject announce = config.getJSONObject("announce");
            return announce == null
                    ? null
                    : normalizePreannouncement(announce.getJSONObject("preannouncement"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject normalizeVersion(JSONObject input) throws IOException {
        if (input == null) throw new IOException("版本响应不是 JSON 对象");
        String resVersion = input.getString("resVersion");
        String clientVersion = input.getString("clientVersion");
        if (resVersion == null || !RES_VERSION.matcher(resVersion).matches()
                || clientVersion == null || clientVersion.isEmpty()) {
            throw new IOException("版本响应字段无效");
        }
        JSONObject result = new JSONObject(true);
        result.put("resVersion", resVersion);
        result.put("clientVersion", clientVersion);
        return result;
    }

    private static JSONObject normalizeAnnouncement(JSONObject input) throws IOException {
        if (input == null) throw new IOException("公告响应不是 JSON 对象");
        Object listValue = input.get("announceList");
        if (!(listValue instanceof JSONArray)) {
            throw new IOException("公告响应缺少 announceList");
        }
        JSONArray list = (JSONArray) listValue;
        JSONArray publicList = new JSONArray(list.size());
        for (Object value : list) {
            if (!(value instanceof JSONObject)) throw new IOException("公告列表项不是 JSON 对象");
            JSONObject item = (JSONObject) value;
            JSONObject publicItem = new JSONObject(true);
            copyString(item, publicItem, "announceId");
            copyString(item, publicItem, "title");
            copyBoolean(item, publicItem, "isWebUrl");
            copyString(item, publicItem, "webUrl");
            copyNumber(item, publicItem, "day");
            copyNumber(item, publicItem, "month");
            copyString(item, publicItem, "group");
            copyString(item, publicItem, "type");
            publicList.add(publicItem);
        }
        JSONObject result = new JSONObject(true);
        copyString(input, result, "focusAnnounceId");
        result.put("announceList", publicList);
        Object extraValue = input.get("extra");
        if (extraValue instanceof JSONObject) {
            JSONObject extra = (JSONObject) extraValue;
            JSONObject publicExtra = new JSONObject(true);
            copyBoolean(extra, publicExtra, "enable");
            copyString(extra, publicExtra, "name");
            result.put("extra", publicExtra);
        }
        return result;
    }

    private static JSONObject normalizePreannouncement(JSONObject input) throws IOException {
        if (input == null) throw new IOException("预公告响应不是 JSON 对象");
        JSONObject result = new JSONObject(true);
        copyString(input, result, "preAnnounceId");
        copyBoolean(input, result, "actived");
        copyNumber(input, result, "preAnnounceType");
        copyString(input, result, "preAnnounceUrl");
        if (result.isEmpty()) throw new IOException("预公告响应字段无效");
        return result;
    }

    private static void copyString(JSONObject source, JSONObject target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, String.valueOf(value));
    }

    private static void copyBoolean(JSONObject source, JSONObject target, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean) target.put(key, value);
    }

    private static void copyNumber(JSONObject source, JSONObject target, String key) {
        Object value = source.get(key);
        if (value instanceof Number) target.put(key, value);
    }

    private static String value(JSONObject object, String key) {
        Object value = object == null ? null : object.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static JSONObject copy(JSONObject source) {
        return JSON.parseObject(JSON.toJSONString(source));
    }

    private static String errorFor(Throwable failure) {
        String name = failure == null ? "Exception" : failure.getClass().getSimpleName();
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isEmpty()) return name;
        String safe = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (safe.length() > 160) safe = safe.substring(0, 160);
        return name + ": " + safe;
    }

    static final class HttpFetcher implements Fetcher {
        @Override
        public Response fetch(String url) throws IOException {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "ArkLocal-PublicContent/1");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_RESPONSE_BYTES) throw new IOException("响应超过 2 MiB");
                try (InputStream input = connection.getInputStream()) {
                    byte[] body = readLimited(input);
                    return new Response(
                            new String(body, StandardCharsets.UTF_8),
                            connection.getHeaderField("Last-Modified"));
                }
            } finally {
                connection.disconnect();
            }
        }

        static byte[] readLimited(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("响应超过 2 MiB");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
