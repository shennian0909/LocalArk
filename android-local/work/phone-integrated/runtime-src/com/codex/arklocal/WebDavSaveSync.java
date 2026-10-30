package com.codex.arklocal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** One complete snapshot per WebDAV file; login and uploads share this transfer lock. */
public final class WebDavSaveSync {
    private static final int LIMIT = 64 * 1024 * 1024;
    public enum Outcome { UPLOADED, DOWNLOADED, CURRENT, REMOTE_NEWER }
    public interface Source {
        Snapshot read() throws Exception;
        boolean replace(String snapshot, long timestamp, long expectedLocalTimestamp) throws Exception;
    }
    public static final class Snapshot {
        public final String json;
        public final long timestamp;
        public Snapshot(String json, long timestamp) { this.json = json; this.timestamp = timestamp; }
    }
    public static final class Config {
        final URI file;
        final String authorization;
        final URI appDirectory;
        public Config(String directory, String username, String password) {
            URI uri = URI.create(directory.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("请输入有效的 WebDAV 文件夹地址");
            String directoryUrl = uri.toASCIIString().replaceAll("/+$", "");
            // Nutstore lists its WebDAV root, but PUT files there returns 404.
            boolean nutstoreRoot = "dav.jianguoyun.com".equalsIgnoreCase(uri.getHost())
                    && "/dav".equals(uri.getPath().replaceAll("/+$", ""));
            appDirectory = nutstoreRoot ? URI.create(directoryUrl + "/Arknights-local/") : null;
            file = URI.create((appDirectory == null ? directoryUrl : appDirectory.toASCIIString().replaceAll("/+$", "")) + "/arknights-save.json");
            authorization = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static final class Remote {
        String json, etag;
        long timestamp;
    }
    private final Source source;
    public WebDavSaveSync(Source source) { this.source = source; }

    public synchronized Outcome synchronize(Config config, boolean allowDownload) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Remote remote = download(config);
            Snapshot local = source.read();
            if (remote != null && remote.timestamp > local.timestamp) {
                if (!allowDownload) return Outcome.REMOTE_NEWER;
                if (source.replace(remote.json, remote.timestamp, local.timestamp)) return Outcome.DOWNLOADED;
                continue;
            }
            if (remote != null && remote.timestamp == local.timestamp) return Outcome.CURRENT;
            if (upload(config, local, remote)) return Outcome.UPLOADED;
        }
        throw new IOException("存档正在其他设备变动，请稍后重试");
    }

    private Remote download(Config config) throws IOException {
        HttpURLConnection connection = open(config, "GET");
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            requireStatus(status, 200);
            JSONObject root;
            try (InputStream input = connection.getInputStream()) {
                root = JSON.parseObject(new String(read(input), StandardCharsets.UTF_8));
            } catch (RuntimeException invalid) { throw new IOException("云端存档格式无效，本地存档已保留"); }
            JSONObject metadata = root == null ? null : root.getJSONObject("_localSync");
            if (root == null || !"arklocal-snapshot".equals(root.getString("format")) || root.getIntValue("version") != 1
                    || root.getJSONObject("tables") == null || root.getJSONObject("nextIds") == null
                    || metadata == null || !(metadata.get("updatedAt") instanceof Number) || metadata.getLongValue("updatedAt") < 0)
                throw new IOException("云端文件不是有效同步存档，本地存档已保留");
            Remote remote = new Remote();
            remote.timestamp = metadata.getLongValue("updatedAt");
            root.remove("_localSync");
            remote.json = JSON.toJSONString(root, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue);
            remote.etag = connection.getHeaderField("ETag");
            return remote;
        } finally { connection.disconnect(); }
    }

    private boolean upload(Config config, Snapshot snapshot, Remote remote) throws IOException {
        JSONObject root = JSON.parseObject(snapshot.json);
        JSONObject metadata = new JSONObject(true);
        metadata.put("updatedAt", snapshot.timestamp);
        root.put("_localSync", metadata);
        byte[] bytes = JSON.toJSONString(root, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("存档超过同步大小限制");
        if (remote == null && config.appDirectory != null) createAppDirectory(config);
        HttpURLConnection connection = open(config, "PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (remote == null) connection.setRequestProperty("If-None-Match", "*");
        else if (remote.etag != null && !remote.etag.startsWith("W/")) connection.setRequestProperty("If-Match", remote.etag);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status == 412) return false;
            if (status != 200 && status != 201 && status != 204) requireStatus(status, 201);
            return true;
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(Config config, String method) throws IOException {
        // The game installs an SDK-only proxy in this process; cloud saves use their configured server.
        HttpURLConnection connection = (HttpURLConnection) config.file.toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", config.authorization);
        return connection;
    }

    private static void createAppDirectory(Config config) throws IOException {
        URI uri = config.appDirectory;
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        // HttpURLConnection rejects MKCOL. Use a direct HTTP/1.1 request for this WebDAV method.
        try (java.net.Socket transport = new java.net.Socket()) {
            transport.connect(new java.net.InetSocketAddress(uri.getHost(), port), 8000);
            transport.setSoTimeout(12000);
            try (java.net.Socket connection = https
                    ? ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                            .createSocket(transport, uri.getHost(), port, true)
                    : transport) {
                connection.setSoTimeout(12000);
                if (https) {
                    javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) connection;
                    javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                }
                String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + port);
                String request = "MKCOL " + uri.getRawPath() + " HTTP/1.1\r\nHost: " + host
                        + "\r\nAuthorization: " + config.authorization
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().flush();
                String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(
                        connection.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*"))
                    throw new IOException("WebDAV 创建子文件夹时返回了无效响应");
                int status = Integer.parseInt(statusLine.substring(9, 12));
                if (status != 201 && status != 405) requireStatus(status, 201);
            }
        }
    }
    private static void requireStatus(int status, int expected) throws IOException {
        if (status == expected) return;
        if (status == 401 || status == 403) throw new IOException("WebDAV 账号或访问权限不正确");
        if (status == 409) throw new IOException("请先在 WebDAV 服务器创建填写的文件夹");
        if (status == 404) throw new IOException("WebDAV 目标文件夹不存在或不允许上传，请选择可写子文件夹");
        if (status >= 300 && status < 400) throw new IOException("请填写 WebDAV 最终地址，当前地址发生跳转");
        throw new IOException("WebDAV 同步未完成（" + status + "）");
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int length;
        while ((length = input.read(block)) != -1) {
            if (bytes.size() > LIMIT - length) throw new IOException("云端存档超过大小限制");
            bytes.write(block, 0, length);
        }
        return bytes.toByteArray();
    }
}
