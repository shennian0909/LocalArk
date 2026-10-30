package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Repairs missing mainline entries from actual saved clears and the shipped prerequisites. */
public final class MainlineProgressionSupport {
    private MainlineProgressionSupport() {}

    public static JSONObject normalize(JSONObject user, JSONObject stageTable, JSONObject legacyChain) {
        JSONObject stages = user.getJSONObject("dungeon").getJSONObject("stages");
        JSONObject added = new JSONObject(true);
        for (String id : stageTable.keySet()) {
            JSONObject definition = stageTable.getJSONObject(id);
            if (!isMainline(definition) || stages.containsKey(id)) continue;
            JSONArray conditions = definition.getJSONArray("unlockCondition");
            if (conditions == null || conditions.isEmpty()) {
                // Chapter roots have no in-row prerequisites. Only a real, cleared
                // predecessor in the existing chapter chain can introduce them.
                if (!hasClearedPredecessor(id, stages, stageTable, legacyChain)) continue;
            } else if (!conditionsMet(conditions, stages)) continue;
            JSONObject entry = new JSONObject(true);
            entry.put("stageId", id);
            entry.put("state", 0);
            entry.put("completeTimes", 0);
            entry.put("startTimes", 0);
            entry.put("practiceTimes", 0);
            entry.put("hasBattleReplay", 0);
            entry.put("noCostCnt", 1);
            stages.put(id, entry);
            added.put(id, entry);
        }
        JSONObject status = user.getJSONObject("status");
        JSONObject current = stageTable.getJSONObject(status.getString("mainStageProgress"));
        int progress = current == null ? -1 : current.getIntValue("slProgress");
        for (String id : stages.keySet()) {
            JSONObject definition = stageTable.getJSONObject(id);
            if (definition != null && isMainline(definition)
                    && "NORMAL".equals(definition.getString("difficulty"))
                    && definition.getIntValue("slProgress") > progress) {
                progress = definition.getIntValue("slProgress");
                status.put("mainStageProgress", id);
            }
        }
        return added;
    }

    static boolean isMainline(JSONObject stage) {
        String zone = stage.getString("zoneId");
        String type = stage.getString("stageType");
        return zone != null && zone.matches("main_[0-9]+")
                && ("MAIN".equals(type) || "SUB".equals(type) || "SPECIAL_STORY".equals(type))
                && "NONE".equals(stage.getString("diffGroup"))
                && stage.get("extraCondition") == null;
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

    private static boolean hasClearedPredecessor(String target, JSONObject stages,
                                                 JSONObject stageTable, JSONObject chain) {
        for (String source : chain.keySet()) {
            JSONObject definition = stageTable.getJSONObject(source);
            JSONObject saved = stages.getJSONObject(source);
            if (definition == null || !isMainline(definition) || saved == null
                    || saved.getIntValue("state") < 2) continue;
            JSONObject links = chain.getJSONObject(source);
            if (target.equals(links.getString("next")) || target.equals(links.getString("sub"))) return true;
            if (saved.getIntValue("state") >= 3
                    && (target.equals(links.getString("star")) || target.equals(links.getString("hard")))) return true;
        }
        return false;
    }
}
