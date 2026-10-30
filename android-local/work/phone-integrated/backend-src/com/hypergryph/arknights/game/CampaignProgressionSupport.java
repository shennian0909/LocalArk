package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Restores extermination (CAMPAIGN) stage rows from the shipped stage table.
 *
 * Only stages whose own unlockCondition is met by saved clears are added, and a
 * non-empty unlockCondition is required unless a cleared predecessor in the shipped
 * battle chain introduces the stage. Extermination stages never appear in
 * battle/stage.json, so the mainline chain cannot be reused to open them.
 */
final class CampaignProgressionSupport {
    private CampaignProgressionSupport() {}

    static JSONObject normalize(JSONObject user, JSONObject stageTable) {
        CampaignBattleSupport.normalizeRewardFlags(user);
        JSONObject stages = user.getJSONObject("dungeon").getJSONObject("stages");
        JSONObject added = new JSONObject(true);
        for (String id : stageTable.keySet()) {
            JSONObject definition = stageTable.getJSONObject(id);
            if (!isExtermination(definition) || stages.containsKey(id)) continue;
            JSONArray conditions = definition.getJSONArray("unlockCondition");
            if (conditions == null || conditions.isEmpty()) continue;
            if (!conditionsMet(conditions, stages)) continue;
            stages.put(id, entry(id));
            added.put(id, stages.getJSONObject(id));
        }
        return added;
    }

    static boolean isExtermination(JSONObject stage) {
        return stage != null && "CAMPAIGN".equals(stage.getString("stageType"));
    }

    private static JSONObject entry(String id) {
        JSONObject entry = new JSONObject(true);
        entry.put("stageId", id);
        entry.put("state", 0);
        entry.put("completeTimes", 0);
        entry.put("startTimes", 0);
        entry.put("practiceTimes", 0);
        entry.put("hasBattleReplay", 0);
        entry.put("noCostCnt", 1);
        return entry;
    }

    private static boolean conditionsMet(JSONArray conditions, JSONObject stages) {
        for (int i = 0; i < conditions.size(); i++) {
            JSONObject condition = conditions.getJSONObject(i);
            JSONObject saved = stages.getJSONObject(condition.getString("stageId"));
            String required = condition.getString("completeState");
            if (saved == null) return false;
            if ("PASS".equals(required)) {
                if (saved.getIntValue("state") < 2) return false;
            } else if ("COMPLETE".equals(required)) {
                if (saved.getIntValue("state") < 3) return false;
            } else return false;
        }
        return true;
    }
}
