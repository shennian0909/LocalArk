package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginNoticeSupport {
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private LoginNoticeSupport() { }

    public static void beginSession(String secret) {
        if (secret == null || secret.isEmpty()) return;
        PENDING.add(secret);
    }

    static JSONArray consumePushMessages(String secret) {
        if (secret == null || !PENDING.remove(secret)) return new JSONArray();
        return messages();
    }

    private static JSONArray messages() {
        JSONArray messages = new JSONArray();
        JSONObject data = new JSONObject(true);
        data.put("content", "Welcome to Local Arknights!这是一个开源项目,如果你为它付费了,那你一定被骗了");
        data.put("loop", 1);
        data.put("majorVersion", "369");

        JSONObject payload = new JSONObject(true);
        payload.put("data", data.toJSONString());

        JSONObject message = new JSONObject(true);
        message.put("path", "flushAlerts");
        message.put("payload", payload);
        messages.add(message);
        return messages;
    }
}
