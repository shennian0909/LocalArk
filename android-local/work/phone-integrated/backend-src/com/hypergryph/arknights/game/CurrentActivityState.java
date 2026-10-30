package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.File;

/**
 * Account state for the current activities.
 *
 * Shapes come from 2.7.81 client metadata:
 * - CHECKIN_ONLY -> PlayerCheckinOnlyTypeActivity{history:List<int>, dynOpt:List<string>,
 *   extraHistory:List<int>}
 * - ARCADE -> PlayerArcadeActivity{milestone:MilestoneInfo{point:int, got:List<string>},
 *   badge:Dictionary<string,BadgeInfo>, score}. BadgeInfo's wire field is "missionProgress"
 *   (C# m_missionProgress).
 * - REWARD_ONLY -> PlayerRewardOnlyActivity{choice:string, hit:string}
 *
 * history mapping (device-confirmed on r15): a day is appended as 1 = claimable and a
 * successful claim writes 0. r15 rendered [1] as day one claimable, the claim granted the
 * real day-1 rewards, and r16 confirmed the 0 render plus the repeat rejection.
 *
 * lastTs is not a field of PlayerCheckinOnlyTypeActivity in 2.7.81; it is written only as a
 * backend day marker (UTC+8 04:00 boundary) and the client ignored it on r15. A save that
 * already carries one keeps its value as the starting day.
 */
public final class CurrentActivityState {
    public static final String CHECKIN_CONTAINER = "CHECKIN_ONLY";
    public static final String ARCADE_CONTAINER = "ARCADE";
    public static final String REWARD_ONLY_CONTAINER = "REWARD_ONLY";
    private CurrentActivityState() {}

    public enum Claim { OK, NOT_OPEN, ALREADY_CLAIMED, NO_REWARD, NOT_PRESENT }

    /** Neutral seeds: the declared fields with empty values, no status codes or day markers. */
    public static JSONObject seed(String type) {
        JSONObject seed = new JSONObject(true);
        switch (type) {
            case "CHECKIN_ONLY" -> {
                seed.put("history", new JSONArray());
                seed.put("dynOpt", new JSONArray());
                seed.put("extraHistory", new JSONArray());
            }
            case "REWARD_ONLY" -> {
                seed.put("choice", "");
                seed.put("hit", "");
            }
            case "ARCADE" -> {
                JSONObject milestone = new JSONObject(true);
                milestone.put("point", 0);
                milestone.put("got", new JSONArray());
                seed.put("milestone", milestone);
                seed.put("badge", new JSONObject(true));
                seed.put("score", new JSONObject(true));
            }
            default -> { }
        }
        return seed;
    }

    /**
     * Claim one CHECKIN_ONLY day. Only an index that currently holds 1 can be taken, so a day
     * cannot be claimed twice and a day that has not opened yet cannot be taken early.
     *
     * On success the caller receives that day's reward items to grant.
     */
    public static Claim checkInClaim(JSONObject user, File root, String activityId,
                                     int index, long nowSeconds, JSONArray grantedOut) {
        CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(root, activityId);
        if (!lookup.present()) return Claim.NOT_PRESENT;
        if (!"CHECKIN_ONLY".equals(lookup.activity().type())) return Claim.NO_REWARD;
        if (!lookup.activity().isOpen(nowSeconds)) return Claim.NOT_OPEN;
        JSONArray rewards = CurrentActivitySupport.checkInRewards(root, activityId);
        if (rewards == null || index < 0 || index >= rewards.size()) return Claim.NO_REWARD;

        refreshCheckIn(user, root, activityId, nowSeconds);
        JSONObject state = entry(user, activityId);
        JSONArray history = state.getJSONArray("history");
        if (index >= history.size()) return Claim.NOT_OPEN;
        if (history.getIntValue(index) != 1) return Claim.ALREADY_CLAIMED;
        history.set(index, 0);
        JSONArray items = rewards.getJSONObject(index).getJSONArray("itemList");
        if (items != null) grantedOut.addAll(items);
        return Claim.OK;
    }

    /**
     * Open the next day when the server day changes. At most one day per day, so a long
     * offline gap does not flush the whole reward track at once.
     */
    static boolean refreshCheckIn(JSONObject user, File root, String activityId, long nowSeconds) {
        JSONObject state = entry(user, activityId);
        JSONArray history = state.getJSONArray("history");
        boolean first = !state.containsKey("lastTs");
        long previous = state.getLongValue("lastTs");
        boolean nextDay = day(nowSeconds) > day(previous);
        int days = CurrentActivitySupport.checkInRewards(root, activityId).size();
        boolean addDay = history.isEmpty() || (!first && nextDay && history.size() < days);
        if (addDay) history.add(1);
        if (first || nextDay) state.put("lastTs", nowSeconds);
        return addDay || first || nextDay;
    }

    static boolean refreshArcadeStages(JSONObject user, File root, String activityId, long nowSeconds) {
        JSONObject data = CurrentActivitySupport.typePayload(root, "ARCADE", activityId);
        JSONObject zones = data.getJSONObject("zoneAdditionalDataDict");
        JSONObject stages = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "dungeon"), "stages");
        boolean changed = false;
        for (String stageId : CurrentActivitySupport.stageIds(root, activityId)) {
            if (stages.containsKey(stageId)) continue;
            JSONObject definition = CurrentActivitySupport.stageDefinition(stageId);
            if (definition == null) continue;
            JSONObject zone = zones.getJSONObject(definition.getString("zoneId"));
            if (nowSeconds < zone.getLongValue("startTs") || nowSeconds > zone.getLongValue("endTs")) continue;
            if (!stageRequirementsMet(stages, definition.getJSONArray("unlockCondition"))) continue;
            JSONObject stage = JSONObject.parseObject("{\"state\":0,\"completeTimes\":0,\"practiceTimes\":0,\"startTimes\":0,\"noCostCnt\":1,\"hasBattleReplay\":0}");
            stage.put("stageId", stageId);
            stages.put(stageId, stage);
            changed = true;
        }
        return changed;
    }

    private static boolean stageRequirementsMet(JSONObject stages, JSONArray conditions) {
        for (int i = 0; i < conditions.size(); i++) {
            JSONObject condition = conditions.getJSONObject(i);
            JSONObject previous = stages.getJSONObject(condition.getString("stageId"));
            int required = "COMPLETE".equals(condition.getString("completeState")) ? 3 : 2;
            if (previous == null || previous.getIntValue("state") < required) return false;
        }
        return true;
    }

    /** Server day index on the UTC+8 04:00 boundary the game resets on. */
    private static long day(long seconds) {
        return Math.floorDiv(seconds + 14400L, 86400L);
    }

    private static JSONObject entry(JSONObject user, String activityId) {
        JSONObject activities = ShopTransactionSupport.object(user, "activity");
        JSONObject entries = ShopTransactionSupport.object(activities, CHECKIN_CONTAINER);
        JSONObject state = entries.getJSONObject(activityId);
        if (state == null) {
            state = CurrentActivitySupport.seed("CHECKIN_ONLY");
            entries.put(activityId, state);
        }
        return state;
    }
}
