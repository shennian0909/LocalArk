package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;

/** Retro block unlocks mutate only the detached account snapshot. */
final class RetroSupport {
    private RetroSupport() { }

    static JSONObject unlock(JSONObject user, JSONObject table, String retroId) {
        JSONObject activities = table.getJSONObject("retroActList");
        if (activities == null || !activities.containsKey(retroId)) {
            throw new IllegalArgumentException("unknown retro block");
        }

        JSONObject retro = user.getJSONObject("retro");
        if (retro == null) throw new IllegalArgumentException("retro state unavailable");
        JSONObject blocks = retro.getJSONObject("block");
        JSONObject block = blocks == null ? null : blocks.getJSONObject(retroId);
        if (block == null) throw new IllegalArgumentException("retro block state unavailable");

        if (block.getIntValue("locked") == 0) return success(user);

        int cost = table.getIntValue("retroUnlockCost");
        int coin = retro.getIntValue("coin");
        if (cost < 0 || coin < cost) throw new IllegalArgumentException("insufficient retro coin");
        retro.put("coin", coin - cost);
        block.put("locked", 0);
        return success(user);
    }

    private static JSONObject success(JSONObject user) {
        JSONObject modified = new JSONObject(true);
        modified.put("retro", user.getJSONObject("retro"));
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        return result;
    }
}
