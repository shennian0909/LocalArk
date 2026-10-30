package com.hypergryph.arknights.core.platform;

import com.alibaba.fastjson.JSONObject;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PlatformResolver {
    private PlatformResolver() {
    }

    public enum Platform {
        ANDROID("android", "Android"),
        WINDOWS("Windows", "Windows");

        private final String versionConfigKey;
        private final String protocolName;

        Platform(String versionConfigKey, String protocolName) {
            this.versionConfigKey = versionConfigKey;
            this.protocolName = protocolName;
        }

        public String versionConfigKey() {
            return versionConfigKey;
        }

        public String protocolName() {
            return protocolName;
        }
    }

    public enum LoginVersionStatus {
        OK,
        CLIENT_MISMATCH,
        RESOURCE_MISMATCH
    }

    public static Platform resolveLoginPlatform(JSONObject body, HttpServletRequest request) {
        return resolveLoginPlatform(body, request, null);
    }

    /**
     * Numeric platform values alone are ambiguous. If no explicit platform
     * evidence is present, a unique configured client/resource version pair may
     * identify the platform.
     */
    public static Platform resolveLoginPlatform(
            JSONObject body,
            HttpServletRequest request,
            JSONObject versions) {
        Platform fromBody = fromExplicitValue(body == null ? null : body.get("platform"));
        if (fromBody != null) {
            return fromBody;
        }

        Platform fromQuery = fromExplicitValue(request.getParameter("platform"));
        if (fromQuery != null) {
            return fromQuery;
        }

        Platform fromHeader = fromExplicitValue(request.getHeader("X-Platform"));
        if (fromHeader != null) {
            return fromHeader;
        }

        Platform fromUserAgent = fromUserAgentEvidence(request.getHeader("User-Agent"));
        if (fromUserAgent != null) {
            return fromUserAgent;
        }

        Platform fromVersions = fromUniqueVersionPair(
                versions,
                body == null ? null : body.getString("clientVersion"),
                body == null ? null : body.getString("assetsVersion"));
        return fromVersions == null ? Platform.WINDOWS : fromVersions;
    }

    public static Platform resolvePlatform(String explicitPlatform, String userAgent) {
        Platform explicit = fromExplicitValue(explicitPlatform);
        return explicit != null ? explicit : fromUserAgent(userAgent);
    }

    public static Platform resolveAssetPlatform(String pathPlatform, String userAgent) {
        Platform explicitPath = fromExplicitValue(pathPlatform);
        if (explicitPath != null) {
            return explicitPath;
        }

        String normalizedPath = normalize(pathPlatform);
        if ("ios".equals(normalizedPath) || "global".equals(normalizedPath)) {
            throw new IllegalArgumentException("Unsupported asset platform: " + pathPlatform);
        }

        // UA remains a compatibility fallback for legacy or incomplete asset URLs.
        return fromUserAgent(userAgent);
    }

    public static JSONObject versionConfig(JSONObject versions, Platform platform) {
        if (versions == null || platform == null) {
            return null;
        }

        JSONObject exact = versions.getJSONObject(platform.versionConfigKey());
        if (exact != null) {
            return exact;
        }

        for (Map.Entry<String, Object> entry : versions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(platform.versionConfigKey())
                    && entry.getValue() instanceof JSONObject) {
                return (JSONObject) entry.getValue();
            }
        }
        return null;
    }

    public static LoginVersionStatus checkLoginVersions(
            JSONObject versions,
            Platform platform,
            String clientVersion,
            String assetsVersion) {
        JSONObject expected = versionConfig(versions, platform);
        if (expected == null
                || !Objects.equals(expected.getString("clientVersion"), clientVersion)) {
            return LoginVersionStatus.CLIENT_MISMATCH;
        }
        if (!Objects.equals(expected.getString("resVersion"), assetsVersion)) {
            return LoginVersionStatus.RESOURCE_MISMATCH;
        }
        return LoginVersionStatus.OK;
    }

    public static Path assetCacheDirectory(String userDir, String assetsHash, Platform platform) {
        return java.nio.file.Paths.get(userDir, "assets", assetsHash, platform.protocolName(), "direct");
    }

    public static Path legacyWindowsAssetCacheDirectory(String userDir, String assetsHash) {
        return java.nio.file.Paths.get(userDir, "assets", assetsHash, "direct");
    }

    public static Path assetCacheFile(
            String userDir,
            String assetsHash,
            String fileName,
            Platform platform) {
        Path platformFile = assetCacheDirectory(userDir, assetsHash, platform).resolve(fileName);
        if (platform == Platform.WINDOWS && !Files.exists(platformFile)) {
            Path legacyFile = legacyWindowsAssetCacheDirectory(userDir, assetsHash).resolve(fileName);
            if (Files.exists(legacyFile)) {
                return legacyFile;
            }
        }
        return platformFile;
    }

    private static Platform fromExplicitValue(Object value) {
        // Numbers and numeric strings intentionally do not identify a platform.
        if (value instanceof Number) {
            return null;
        }
        return fromExplicitToken(value == null ? null : value.toString());
    }

    private static Platform fromExplicitToken(String value) {
        String normalized = normalize(value);
        if ("android".equals(normalized)) {
            return Platform.ANDROID;
        }
        if ("windows".equals(normalized) || "win".equals(normalized)) {
            return Platform.WINDOWS;
        }
        return null;
    }

    private static Platform fromUserAgent(String userAgent) {
        Platform evidence = fromUserAgentEvidence(userAgent);
        return evidence == null ? Platform.WINDOWS : evidence;
    }

    private static Platform fromUserAgentEvidence(String userAgent) {
        String normalized = normalize(userAgent);
        if (normalized.contains("android")) {
            return Platform.ANDROID;
        }
        if (normalized.contains("windows")) {
            return Platform.WINDOWS;
        }
        return null;
    }

    private static Platform fromUniqueVersionPair(
            JSONObject versions,
            String clientVersion,
            String assetsVersion) {
        if (versions == null || clientVersion == null || assetsVersion == null) {
            return null;
        }

        Platform matched = null;
        int matchCount = 0;
        for (Map.Entry<String, Object> entry : versions.entrySet()) {
            if (!(entry.getValue() instanceof JSONObject)) {
                continue;
            }
            JSONObject expected = (JSONObject) entry.getValue();
            if (Objects.equals(expected.getString("clientVersion"), clientVersion)
                    && Objects.equals(expected.getString("resVersion"), assetsVersion)) {
                matchCount++;
                matched = fromExplicitToken(entry.getKey());
            }
        }
        return matchCount == 1 ? matched : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
