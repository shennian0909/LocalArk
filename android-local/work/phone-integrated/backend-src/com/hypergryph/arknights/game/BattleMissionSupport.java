package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;

final class BattleMissionSupport {
    private static final JSONObject ENEMIES = IOTools.ReadJsonFile(
            "data/excel/enemy_handbook_table.json").getJSONObject("enemyData");

    private BattleMissionSupport() { }

    static JSONObject context(JSONObject receipt, JSONObject stats, JSONObject stage, int rating) {
        int runs = receipt.getIntValue("runs");
        JSONObject result = new JSONObject(true);
        result.put("stageId", receipt.getString("stageId"));
        result.put("rating", rating);
        result.put("runs", runs);
        // Real 0-1 captures report 11 kills for both one- and three-run auto-deploys.
        result.put("kills", stats.getIntValue("killedEnemiesCnt") * runs);
        result.put("friendAssist", receipt.getBooleanValue("friendAssist"));
        int bosses = 0;
        JSONArray enemies = stats.getJSONArray("enemyStats");
        if ("MAIN".equals(stage.getString("stageType")) && enemies != null) {
            for (int i = 0; i < enemies.size(); i++) {
                JSONObject entry = enemies.getJSONObject(i);
                JSONObject key = entry.getJSONObject("Key");
                JSONObject enemy = ENEMIES.getJSONObject(key.getString("enemyId"));
                if ("HP_ZERO".equals(key.getString("counterType"))
                        && key.getIntValue("isInvalidKilled") == 0
                        && enemy != null && "BOSS".equals(enemy.getString("enemyLevel"))) {
                    bosses += entry.getIntValue("Value");
                }
            }
        }
        result.put("bossKills", bosses * runs);
        return result;
    }
}
