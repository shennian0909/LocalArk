package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;

final class SandboxPermSupport {
    private SandboxPermSupport() { }

    static JSONObject changeTopic(JSONObject user, String topicId, long now) {
        JSONObject sandbox = requiredRoot(user);
        requireEnterableTopic(sandbox, topicId);
        sandbox.put("topic", topicId);
        sandbox.put("loadTs", now);
        return result(user);
    }

    static JSONObject pinTopic(JSONObject user, String topicId) {
        JSONObject sandbox = requiredRoot(user);
        if (!topicId.isEmpty()) requireKnownTopic(sandbox, topicId);
        sandbox.put("pin", topicId);
        return result(user);
    }

    static JSONObject enterHome(JSONObject user, String topicId, long now) {
        JSONObject sandbox = requiredRoot(user);
        if ("true".equals(String.valueOf(sandbox.get("isClose"))))
            throw new IllegalArgumentException("sandbox topics are closed");
        if (!topicId.equals(sandbox.getString("topic")))
            throw new IllegalArgumentException("sandbox topic has not been selected");
        requireEnterableTopic(sandbox, topicId);
        sandbox.put("loadTs", now);
        if (sandbox.getJSONObject("template").getJSONObject("SANDBOX_V3").containsKey(topicId))
            SandboxProgressSupport.refreshHomeProgress(user, topicId);
        return result(user);
    }

    static JSONObject requiredRoot(JSONObject user) {
        JSONObject sandbox = user.getJSONObject("sandboxPerm");
        if (sandbox == null) throw new IllegalArgumentException("sandboxPerm is not initialized");
        return sandbox;
    }

    static void requireKnownTopic(JSONObject sandbox, String topicId) {
        JSONObject summary = sandbox.getJSONObject("summary");
        JSONObject template = sandbox.getJSONObject("template");
        if (summary == null || template == null)
            throw new IllegalArgumentException("sandboxPerm has no topic catalog");
        for (String kind : template.keySet()) {
            JSONObject summaryStates = summary.getJSONObject(kind);
            JSONObject templateStates = template.getJSONObject(kind);
            if (summaryStates != null && templateStates != null
                    && summaryStates.containsKey(topicId) && templateStates.containsKey(topicId))
                return;
        }
        throw new IllegalArgumentException("unknown sandbox topic");
    }

    static void requireEnterableTopic(JSONObject sandbox, String topicId) {
        requireKnownTopic(sandbox, topicId);
        JSONObject template = sandbox.getJSONObject("template");
        for (String kind : template.keySet()) {
            JSONObject states = template.getJSONObject(kind);
            if (states != null && states.get(topicId) instanceof JSONObject) return;
        }
        throw new IllegalArgumentException("sandbox topic state is not initialized");
    }

    static JSONObject result(JSONObject user) {
        JSONObject modified = new JSONObject(true);
        modified.put("sandboxPerm", user.getJSONObject("sandboxPerm"));
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        result.put("pushMessage", null);
        return result;
    }
}
