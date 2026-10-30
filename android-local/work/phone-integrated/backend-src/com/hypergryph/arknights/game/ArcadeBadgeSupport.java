package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;

/**
 * The museum badge book (秘籍打印卡) for one ARCADE activity, currently act1museum.
 *
 * The saved badge dictionary is keyed by badgeTierId, not badgeId: there are 8 badges and 10
 * tiers, and each row is
 * <pre>
 * "badge_fish_1": {"status": 2, "missionProgress": [192500, 70000]}
 * </pre>
 * status is Error=0 / InProgress=1 / Unlocked=2 and missionProgress is [current, target].
 * Both names are the wire names the client deserializes; the metadata field behind
 * missionProgress is m_missionProgress, and the progress getters read index 0 then 1 of that
 * int[], so the pair is current first.
 *
 * An activity played before this helper existed carries badge={} while score is already
 * populated, which is the 0/0 display. normalize rebuilds every tier row from the stored
 * score, so a saved 192500 on act1museum_m1_01 unlocks badge_fish_1 without the player
 * replaying anything.
 *
 * The buffs these badges grant are not settled here: ArcadeStartBattleResponse declares no
 * field of its own, and the rune each tier grants is the tier's own runeData in the table,
 * which the client reads itself. Nothing is added to battleStart for them.
 */
public final class ArcadeBadgeSupport {

    private static final String ACTIVITY_ID = "act1museum";

    /**
     * The ULTIMATE tier targets. The table carries them only as the Chinese unlockDesc text
     * (累计最高分达到600000分 and the two others); it has no numeric field for them, so they
     * are the literal numbers from that text for this activity.
     */
    private static final int[] ULTIMATE_TARGETS = {600000, 1200000, 2500000};

    /**
     * The COMMON tier targets, likewise only present as unlockDesc text (累计获取扭蛋机道具12次,
     * 击败大富翁或宝藏神偷3次, 部署超时空电话亭、菜单电话机10次). They are the denominators only:
     * no counter is settled yet, so these tiers are initialized and never unlocked.
     */
    private static final int[] COMMON_TARGETS = {12, 3, 10};

    private static final int STATUS_IN_PROGRESS = 1;
    private static final int STATUS_UNLOCKED = 2;

    private ArcadeBadgeSupport() { }

    /**
     * Rebuild every tier row of the activity from the stored score and report whether the
     * dictionary changed, so the caller can tell a real repair apart from a no-op re-entry.
     */
    public static boolean normalize(JSONObject user, File root, String activityId) {
        return reconcile(user, root, activityId, new JSONArray());
    }

    /**
     * Settle the badge book after the caller has already raised the score for this run, and
     * return the tier ids this run unlocked. A tier that was already unlocked is not reported
     * again, so a replay cannot unlock twice.
     */
    public static JSONArray settle(JSONObject user, File root, String activityId) {
        JSONArray unlocked = new JSONArray();
        reconcile(user, root, activityId, unlocked);
        return unlocked;
    }

    /**
     * Walk every tier the activity declares and bring its row up to date, reporting whether
     * the dictionary changed.
     *
     * Score-driven tiers measure the all-zone sum for ULTIMATE badges, which is what the
     * client's own all-zone aggregate does, and their own zone's first stage for ZONE badges,
     * which is the stage their unlockDesc names. COMMON tiers have no counter settled yet, so
     * their progress stays whatever was saved and they cannot be unlocked here.
     *
     * A tier whose target is missing from the table would otherwise read as satisfied at 0, so
     * the crossing test requires a real target. Tiers a previous pass already unlocked keep
     * status 2 and are not crossed again.
     */
    private static boolean reconcile(JSONObject user, File root, String activityId, JSONArray unlocked) {
        if (!ACTIVITY_ID.equals(activityId)) return false;
        JSONObject data = CurrentActivitySupport.typePayload(root, "ARCADE", activityId);
        if (data == null) return false;
        JSONObject arcade = arcadeEntry(user, activityId);
        JSONObject badges = badges(arcade);
        String before = badges.toJSONString();
        JSONArray tierIds = tierIds(data);
        for (int i = 0; i < tierIds.size(); i++) {
            String tierId = tierIds.getString(i);
            int target = target(data, tierId);
            boolean scoreDriven = scoreDriven(data, tierId);
            JSONObject row = badges.getJSONObject(tierId);
            if (row == null) {
                row = new JSONObject(true);
                badges.put(tierId, row);
            }
            // A row that already went further keeps its own number: the saved state is the
            // record of what the player achieved, and this pass can only add to it.
            int current = scoreDriven
                    ? Math.max(current(data, arcade, tierId), storedCurrent(row))
                    : storedCurrent(row);
            if (scoreDriven && target > 0 && current >= target
                    && row.getIntValue("status") != STATUS_UNLOCKED) {
                row.put("status", STATUS_UNLOCKED);
                unlocked.add(tierId);
            }
            if (row.getIntValue("status") == 0) row.put("status", STATUS_IN_PROGRESS);
            row.put("missionProgress", pair(current, target));
        }
        return !before.equals(badges.toJSONString());
    }

    private static JSONObject arcadeEntry(JSONObject user, String activityId) {
        JSONObject entries = ShopTransactionSupport.object(
                ShopTransactionSupport.object(user, "activity"),
                CurrentActivityState.ARCADE_CONTAINER);
        JSONObject state = entries.getJSONObject(activityId);
        if (state == null) {
            state = CurrentActivitySupport.seed("ARCADE");
            entries.put(activityId, state);
        }
        return state;
    }

    private static JSONObject badges(JSONObject arcade) {
        return ShopTransactionSupport.object(arcade, "badge");
    }

    /** Every tier id the activity declares, in table order. */
    private static JSONArray tierIds(JSONObject data) {
        JSONArray ids = new JSONArray();
        JSONObject badges = data.getJSONObject("badgeDataDict");
        if (badges == null) return ids;
        for (String badgeId : badges.keySet()) {
            JSONObject badge = badges.getJSONObject(badgeId);
            JSONObject tiers = badge == null ? null : badge.getJSONObject("tiers");
            if (tiers == null) continue;
            for (String tierId : tiers.keySet()) ids.add(tierId);
        }
        return ids;
    }

    /** The score a tier is measured against: its zone's first stage best, or the all-zone sum. */
    private static int current(JSONObject data, JSONObject arcade, String tierId) {
        JSONObject badge = badgeOf(data, tierId);
        if (badge == null || !badge.getBooleanValue("hasScore")) return 0;
        String zoneId = badge.getString("scoreZone");
        if (zoneId == null || zoneId.isEmpty()) return allZoneScore(arcade);
        JSONObject scores = arcade.getJSONObject("score");
        JSONObject zone = scores == null ? null : scores.getJSONObject(zoneId);
        if (zone == null) return 0;
        String stageId = firstStage(data, zoneId);
        return stageId == null ? 0 : zone.getIntValue(stageId);
    }

    private static int allZoneScore(JSONObject arcade) {
        JSONObject scores = arcade.getJSONObject("score");
        if (scores == null) return 0;
        int total = 0;
        for (String zoneId : scores.keySet()) {
            JSONObject zone = scores.getJSONObject(zoneId);
            if (zone == null) continue;
            for (String stageId : zone.keySet()) total += zone.getIntValue(stageId);
        }
        return total;
    }

    private static boolean scoreDriven(JSONObject data, String tierId) {
        JSONObject badge = badgeOf(data, tierId);
        return badge != null && badge.getBooleanValue("hasScore");
    }

    private static int target(JSONObject data, String tierId) {
        JSONObject badge = badgeOf(data, tierId);
        if (badge == null) return 0;
        if (!badge.getBooleanValue("hasScore")) return commonTarget(badge.getString("badgeId"));
        String zoneId = badge.getString("scoreZone");
        if (zoneId == null || zoneId.isEmpty()) return ultimateTarget(tierId);
        return rankScore(data, zoneId);
    }

    /**
     * The rank the badge's unlockDesc names, read from the stage's own reward table instead of
     * from that text: maxRewardRank is S for every museum first stage, and rankScore is the
     * score that rank starts at.
     */
    private static int rankScore(JSONObject data, String zoneId) {
        String stageId = firstStage(data, zoneId);
        if (stageId == null) return 0;
        JSONObject addition = data.getJSONObject("stageAdditionDataDict").getJSONObject(stageId);
        JSONObject reward = addition == null ? null : addition.getJSONObject("rankRewardData");
        if (reward == null) return 0;
        String rank = reward.getString("maxRewardRank");
        JSONArray levels = reward.getJSONArray("rankRewardLevelDatas");
        if (levels == null) return 0;
        for (int i = 0; i < levels.size(); i++) {
            JSONObject level = levels.getJSONObject(i);
            if (rank != null && rank.equals(level.getString("rank"))) return level.getIntValue("rankScore");
        }
        return 0;
    }

    private static String firstStage(JSONObject data, String zoneId) {
        JSONObject zone = data.getJSONObject("zoneAdditionalDataDict").getJSONObject(zoneId);
        JSONArray stages = zone == null ? null : zone.getJSONArray("stages");
        if (stages == null || stages.isEmpty()) return null;
        return stages.getString(0);
    }

    private static int ultimateTarget(String tierId) {
        return tierConstant(ULTIMATE_TARGETS, tierId.substring(tierId.lastIndexOf('_') + 1));
    }

    /**
     * COMMON targets are indexed by the badge, not by the tier: badge_comn_1..3 each hold one
     * tier, and every tier id ends in the same "_1", so the tier suffix cannot tell them apart.
     */
    private static int commonTarget(String badgeId) {
        return tierConstant(COMMON_TARGETS, badgeId.substring(badgeId.lastIndexOf('_') + 1));
    }

    private static int tierConstant(int[] targets, String suffix) {
        try {
            int index = Integer.parseInt(suffix) - 1;
            if (index >= 0 && index < targets.length) return targets[index];
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return 0;
    }

    private static JSONObject badgeOf(JSONObject data, String tierId) {
        JSONObject tiers = data.getJSONObject("tireBadgeIdDict");
        String badgeId = tiers == null ? null : tiers.getString(tierId);
        if (badgeId == null) return null;
        return data.getJSONObject("badgeDataDict").getJSONObject(badgeId);
    }

    private static int storedCurrent(JSONObject row) {
        JSONArray pair = row.getJSONArray("missionProgress");
        return pair == null || pair.isEmpty() ? 0 : pair.getIntValue(0);
    }

    private static JSONArray pair(int current, int target) {
        JSONArray pair = new JSONArray();
        pair.add(current);
        pair.add(target);
        return pair;
    }
}
