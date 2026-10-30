package com.codex.arklocal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public final class LocalSaveMigrations {
    private LocalSaveMigrations() {}

    public static boolean repairSandboxSave(JSONObject user) {
        JSONObject sandbox = user.getJSONObject("sandboxPerm");
        JSONObject template = sandbox == null ? null : sandbox.getJSONObject("template");
        JSONObject topics = template == null ? null : template.getJSONObject("SANDBOX_V3");
        if (topics == null) return false;
        boolean changed = false;
        for (String topicId : topics.keySet()) {
            JSONObject topic = topics.getJSONObject(topicId);
            if (topic == null) continue;
            changed |= renameSandboxField(topic, "basement", "base");
            changed |= renameSandboxField(topic, "development", "tech");
            JSONObject npc = topic.getJSONObject("npc");
            if (npc != null) changed |= renameSandboxField(npc, "basement", "base");
            JSONObject current = topic == null ? null : topic.getJSONObject("current");
            if (current != null) changed |= renameSandboxField(current, "eventInfo", "event");
            JSONObject save = current == null ? null : current.getJSONObject("save");
            JSONObject tasks = save == null ? null : save.getJSONObject("taskSave");
            if (tasks != null && tasks.get("current") instanceof JSONArray
                    && tasks.getJSONArray("current").isEmpty()) {
                // r32 wrote an empty list where the client expects an optional claimed-task object.
                tasks.put("current", null);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean renameSandboxField(JSONObject object, String previous, String wireName) {
        if (!object.containsKey(previous)) return false;
        Object value = object.remove(previous);
        if (!object.containsKey(wireName)) object.put(wireName, value);
        return true;
    }

    /** Upgrade this app's pre-r32 markers: 1 meant reached, while only 2 recorded a payout. */
    public static boolean upgradeCampaignRewards(JSONObject user) {
        if (user.getIntValue("_localCampaignRewardFormat") >= 2) return false;
        JSONObject campaign = user.getJSONObject("campaignsV2");
        if (campaign == null) return false;
        JSONObject instances = campaign.getJSONObject("instances");
        JSONObject sweep = campaign.getJSONObject("sweepMaxKills");
        if (sweep == null) { sweep = new JSONObject(true); campaign.put("sweepMaxKills", sweep); }
        if (instances != null) for (String id : instances.keySet()) {
            JSONObject instance = instances.getJSONObject(id);
            JSONArray flags = instance.getJSONArray("rewardStatus");
            if (flags != null) for (int i = 0; i < flags.size(); i++) {
                int previous = flags.getIntValue(i);
                if (previous == 1) flags.set(i, 0);
                else if (previous == 2) flags.set(i, 1);
            }
            int kills = instance.getIntValue("maxKills");
            if (kills > sweep.getIntValue(id)) sweep.put(id, kills);
        }
        user.put("_localCampaignRewardFormat", 2);
        return true;
    }
}
