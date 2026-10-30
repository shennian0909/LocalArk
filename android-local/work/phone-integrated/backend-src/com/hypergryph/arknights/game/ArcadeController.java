package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ARCADE routes for /activity/arcade: the museum (act1museum).
 *
 * battleStart is real and playable: it validates the activity, the table window and that the
 * client's own stage-status check can see the stage, then hands back a battleId. Museum
 * stages all cost 0 AP in the shipped table, so no AP is touched.
 *
 * battleFinish settles a run from the client's own decrypted result. The score is
 * battleData.stats.extraBattleInfo.gamecity_score, which is where the r16 capture put it
 * (8000, alongside KILL_COUNT/fish_* counters). It writes the per-stage highest score, pays
 * the milestone point of every rank tier newly crossed, and opens the next stage through
 * CurrentActivityState.refreshArcadeStages once this one reached the rank the table names in
 * constData.rankUnlockNextStage.
 *
 * milestone.point is accumulated here but milestone.got is never written, so the milestone
 * rewards stay unclaimed until the player claims them.
 */
@RestController
@RequestMapping("/activity/arcade")
public class ArcadeController {

    /** The museum's own score counter, from the r16 finish capture. */
    private static final String SCORE_KEY = "gamecity_score";
    /**
     * State a stage record must reach before the next one opens. constData.rankUnlockNextStage
     * names rank A, and the client's stage-status check accepts a predecessor at 2 or better.
     */
    private static final int CLEARED_STATE = 2;
    public ArcadeController() { }

    private static File root() {
        return com.codex.arklocal.BackendPaths.rootFile();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static JSONObject error(HttpServletResponse response, int status, String message) {
        if (response != null) response.setStatus(status);
        JSONObject result = new JSONObject(true);
        result.put("result", 1);
        result.put("error", message);
        return result;
    }

    /**
     * Start one museum stage: /activity/arcade/battleStart with
     * {activityId, stageId, squad, assistFriend}.
     *
     * The stage must be one the table assigns to the activity, and the account must already
     * carry a dungeon.stages record for it: without one the client's stage-status check
     * returns 2 and get_isUnlock rejects it, which is why the start button did nothing.
     */
    @PostMapping(value = "/battleStart", produces = "application/json;charset=UTF-8")
    public JSONObject battleStart(@RequestBody JSONObject body,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        String stageId = body == null ? null : body.getString("stageId");
        if (activityId == null || activityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        if (stageId == null || stageId.trim().isEmpty()) {
            return error(response, 400, "stageId is required");
        }
        return ShopTransactionSupport.execute(response, request, user -> {
            CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(root(), activityId);
            if (!lookup.present()) throw new IllegalArgumentException("本地表未定义该活动");
            if (!"ARCADE".equals(lookup.activity().type())) {
                throw new IllegalArgumentException("该活动不是街机玩法");
            }
            if (!lookup.activity().isOpen(nowSeconds())) {
                throw new IllegalArgumentException("活动不在开放期");
            }
            if (!CurrentActivitySupport.stageIds(root(), activityId).contains(stageId)) {
                throw new IllegalArgumentException("该活动没有这个关卡");
            }

            JSONObject stages = ShopTransactionSupport.object(
                    ShopTransactionSupport.object(user, "dungeon"), "stages");
            JSONObject record = stages.getJSONObject(stageId);
            if (record == null) {
                throw new IllegalArgumentException("关卡尚未解锁");
            }
            record.put("startTimes", record.getIntValue("startTimes") + 1);

            JSONObject receipt = BattleRepeatSupport.record(user, stageId, 1, false);
            receipt.put("activityId", activityId);

            JSONObject result = new JSONObject(true);
            result.put("result", 0);
            result.put("battleId", receipt.getString("battleId"));
            JSONObject modified = new JSONObject(true);
            JSONObject dungeon = new JSONObject(true);
            JSONObject onlyStage = new JSONObject(true);
            onlyStage.put(stageId, record);
            dungeon.put("stages", onlyStage);
            modified.put("dungeon", dungeon);
            modified.put("activity", user.getJSONObject("activity"));
            JSONObject delta = new JSONObject(true);
            delta.put("modified", modified);
            delta.put("deleted", new JSONObject(true));
            result.put("playerDataDelta", delta);
            // Museum stages cost 0 AP in the shipped table; these mirror quest's 0-AP path.
            result.put("isApProtect", 0);
            result.put("apFailReturn", 0);
            return result;
        });
    }

    /**
     * Settle one run: /activity/arcade/battleFinish with the client's encrypted result.
     *
     * The score is battleData.stats.extraBattleInfo.gamecity_score, which is where the r16
     * capture put it. A run that does not beat the stage's stored best changes nothing, so
     * replays cannot farm the milestone point. A run that crosses a rank tier pays only that
     * tier's own coin count, so A then S pays 80 then 40 rather than 80 twice.
     *
     * Stage opening is delegated to CurrentActivityState.refreshArcadeStages, the same helper
     * normalize uses, so the zone window and unlockCondition rules have one implementation.
     *
     * The response adds the museum fields on top of the inherited DefaultFinishBattleResponse
     * arrays. Museum stages grant nothing directly, so every reward array is empty and the
     * scales are 0; unlockStages carries the stages this run actually opened.
     */
    @PostMapping(value = "/battleFinish", produces = "application/json;charset=UTF-8")
    public JSONObject battleFinish(@RequestBody JSONObject body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (body == null || body.getString("data") == null) {
            return error(response, 400, "data is required");
        }
        String claimedActivityId = body.getString("activityId");
        if (claimedActivityId == null || claimedActivityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        return ShopTransactionSupport.execute(response, request, user -> {
            String loginTime = user.getJSONObject("pushFlags").getString("status");
            JSONObject battleData = Utils.BattleData_decrypt(body.getString("data"), loginTime);
            if (battleData == null) throw new IllegalArgumentException("Invalid battle data");
            JSONObject receipt = BattleRepeatSupport.pending(user, battleData);
            if (receipt == null) throw new IllegalArgumentException("Unknown or already settled battle");

            String activityId = receipt.getString("activityId");
            String stageId = receipt.getString("stageId");
            if (!activityId.equals(claimedActivityId)) {
                throw new IllegalArgumentException("活动与本次战斗不符");
            }
            if (!stageId.equals(battleData.getString("stageId"))) {
                throw new IllegalArgumentException("关卡与本次战斗不符");
            }
            CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(root(), activityId);
            if (!lookup.present()) throw new IllegalArgumentException("本地表未定义该活动");
            if (!lookup.activity().isOpen(nowSeconds())) {
                throw new IllegalArgumentException("活动不在开放期");
            }
            JSONObject data = CurrentActivitySupport.typePayload(root(), "ARCADE", activityId);
            JSONObject stageAddition = data == null ? null
                    : data.getJSONObject("stageAdditionDataDict").getJSONObject(stageId);
            if (stageAddition == null) throw new IllegalArgumentException("该活动没有这个关卡");
            JSONArray levels = stageAddition.getJSONObject("rankRewardData").getJSONArray("rankRewardLevelDatas");

            JSONObject arcade = arcadeEntry(user, activityId);
            JSONObject scores = ShopTransactionSupport.object(
                    ShopTransactionSupport.object(arcade, "score"), stageAddition.getString("zoneId"));
            int score = scoreOf(battleData);
            int previous = scores.getIntValue(stageId);

            int previousTier = tier(levels, previous);
            int pointDelta = 0;
            JSONArray unlockStages = new JSONArray();
            if (score > previous) {
                scores.put(stageId, score);
                // The milestone token is accumulated, never granted: milestone.got stays
                // empty until the player claims a milestone.
                pointDelta = coinDelta(levels, previousTier, tier(levels, score));
                JSONObject milestone = ShopTransactionSupport.object(arcade, "milestone");
                milestone.put("point", milestone.getIntValue("point") + pointDelta);
                if (tier(levels, score) >= unlockTier(levels, data)) {
                    unlockStages.addAll(openNext(user, activityId, stageId));
                }
            }

            receipt.put("settled", true);
            JSONObject result = new JSONObject(true);
            result.put("result", 0);
            result.put("score", score);
            result.put("prevHighestScore", previous);
            result.put("milestoneBefore", arcade.getJSONObject("milestone").getIntValue("point") - pointDelta);
            result.put("milestoneAdd", pointDelta);
            result.put("isMileStoneMax", milestoneMax(data, arcade));
            result.put("unlockBadgeList", ArcadeBadgeSupport.settle(user, root(), activityId));
            result.put("items", new JSONArray());
            // Inherited DefaultFinishBattleResponse fields. Museum stages award nothing on
            // clear, so every reward array is empty and both scales are 0.
            for (String key : new String[]{"rewards", "additionalRewards", "unusualRewards",
                    "furnitureRewards", "firstRewards", "itemReturn", "overrideRewards",
                    "diamondMaterialRewards", "pryResult"}) {
                result.put(key, new JSONArray());
            }
            result.put("unlockStages", unlockStages);
            result.put("alert", new JSONArray());
            result.put("suggestFriend", false);
            result.put("goldScale", 0);
            result.put("expScale", 0);
            // Museum stages cost 0 AP, so there is nothing to refund.
            result.put("apFailReturn", 0);
            JSONObject modified = new JSONObject(true);
            modified.put("dungeon", user.getJSONObject("dungeon"));
            modified.put("activity", user.getJSONObject("activity"));
            JSONObject delta = new JSONObject(true);
            delta.put("modified", modified);
            delta.put("deleted", new JSONObject(true));
            result.put("playerDataDelta", delta);
            return result;
        });
    }

    /**
     * The museum score: the one counter the r16 capture puts it in. A payload without it is
     * refused rather than silently scored as 0, because a 0 would overwrite nothing yet still
     * settle the receipt.
     */
    private static int scoreOf(JSONObject battleData) {
        JSONObject inner = battleData.getJSONObject("battleData");
        JSONObject stats = inner == null ? null : inner.getJSONObject("stats");
        JSONObject extra = stats == null ? null : stats.getJSONObject("extraBattleInfo");
        if (extra == null || !extra.containsKey(SCORE_KEY)) {
            throw new IllegalArgumentException("战斗数据没有本关得分");
        }
        int score = extra.getIntValue(SCORE_KEY);
        if (score < 0) throw new IllegalArgumentException("得分非法");
        return score;
    }

    /** Highest rank tier the score reached; 0 when it clears no tier. */
    private static int tier(JSONArray levels, int score) {
        int tier = 0;
        for (int i = 0; i < levels.size(); i++) {
            if (score >= levels.getJSONObject(i).getIntValue("rankScore")) tier = i + 1;
        }
        return tier;
    }

    /**
     * Milestone point for the tiers crossed, each tier paying only its own coin count.
     * Tiers past the stage's max reward rank carry -1 and pay nothing.
     */
    private static int coinDelta(JSONArray levels, int fromTier, int toTier) {
        int delta = 0;
        for (int i = fromTier; i < toTier; i++) {
            int coins = levels.getJSONObject(i).getIntValue("coinCnt");
            if (coins > 0) delta += coins;
        }
        return delta;
    }

    /** Tier that opens the next stage, from constData.rankUnlockNextStage. */
    private static int unlockTier(JSONArray levels, JSONObject data) {
        String wanted = data.getJSONObject("constData").getString("rankUnlockNextStage");
        for (int i = 0; i < levels.size(); i++) {
            if (levels.getJSONObject(i).getString("rank").equals(wanted)) return i + 1;
        }
        return 1;
    }

    /** The account's own ARCADE entry for the activity, created empty when first seen. */
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

    /**
     * Mark the stage cleared and let the shared seeding open whatever that now unlocks.
     * The state never moves backwards, so a later better run cannot re-lock anything.
     */
    private static JSONArray openNext(JSONObject user, String activityId, String stageId) {
        JSONObject stages = ShopTransactionSupport.object(
                ShopTransactionSupport.object(user, "dungeon"), "stages");
        JSONObject record = stages.getJSONObject(stageId);
        if (record != null && record.getIntValue("state") < CLEARED_STATE) {
            record.put("state", CLEARED_STATE);
        }
        JSONArray opened = new JSONArray();
        Set<String> before = new HashSet<String>(stages.keySet());
        if (CurrentActivityState.refreshArcadeStages(user, root(), activityId, nowSeconds())) {
            List<String> added = new ArrayList<String>();
            for (String id : stages.keySet()) if (!before.contains(id)) added.add(id);
            added.sort(null);
            opened.addAll(added);
        }
        return opened;
    }

    /** Whether the accumulated point already reached the last milestone the table lists. */
    private static boolean milestoneMax(JSONObject data, JSONObject arcade) {
        JSONArray milestones = data == null ? null : data.getJSONArray("milestoneList");
        if (milestones == null || milestones.isEmpty()) return true;
        int last = milestones.getJSONObject(milestones.size() - 1).getIntValue("needPointCnt");
        return arcade.getJSONObject("milestone").getIntValue("point") >= last;
    }
}
