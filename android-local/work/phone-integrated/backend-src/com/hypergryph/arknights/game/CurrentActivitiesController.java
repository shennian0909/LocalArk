package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.game.CurrentActivityState.Claim;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;

/**
 * Current-activity routes.
 *
 * /activity/getActivityCheckInReward takes the metadata-confirmed route.
 * /activity/actRewardOnly/setChoice comes from a real UI capture, which outranks the
 * metadata scan that found no REWARD_ONLY route: no claim route is invented here.
 *
 * Arcade battleStart/battleFinish live in ArcadeController. The generic ARCADE milestone
 * claim routes live here because they are activity routes and use the shared activity state.
 *
 * history's mapping is the device-confirmed candidate: 1 = claimable, 0 = taken. r15 showed
 * [1] rendering day one as claimable and the claim granting the real day-1 rewards; the
 * 0-render and repeat-rejection half is still being confirmed on device.
 *
 * Granting and persistence go through ShopTransactionSupport.execute(), which loads the
 * account, runs the transaction and persists once on success.
 */
@RestController
@RequestMapping("/activity")
public class CurrentActivitiesController {

    public CurrentActivitiesController() { }

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
     * Real CHECKIN_ONLY claim: /activity/getActivityCheckInReward with {activityId, index}.
     * Response is the client's own shape: items plus inherited playerDataDelta/pushMessage.
     */
    @PostMapping(value = "/getActivityCheckInReward", produces = "application/json;charset=UTF-8")
    public JSONObject getActivityCheckInReward(@RequestBody JSONObject body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        if (activityId == null || activityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        int index = body.getIntValue("index");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONArray granted = new JSONArray();
            Claim claim = CurrentActivityState.checkInClaim(
                    user, root(), activityId, index, nowSeconds(), granted);
            switch (claim) {
                case OK -> { }
                case NOT_OPEN -> throw new IllegalArgumentException("活动不在开放期");
                case ALREADY_CLAIMED -> throw new IllegalArgumentException("该奖励已领取");
                case NO_REWARD -> throw new IllegalArgumentException("没有该签到奖励");
                default -> throw new IllegalArgumentException("本地表未定义该活动");
            }
            // granted is the day's table itemList; hand each item to the real writer so the
            // inventory/consumable/nameCardStyle buckets actually change. The response reports
            // what was really granted, so a rejected type cannot be reported as success.
            JSONArray items = new JSONArray();
            for (int i = 0; i < granted.size(); i++) {
                ShopTransactionSupport.reward(user, granted.getJSONObject(i), 1, items);
            }
            return delta(user, items);
        });
    }

    /**
     * playerDataDelta for a claim: the activity container plus whichever buckets the reward
     * writer touched. Same shape the other mobile endpoints return.
     */
    private static JSONObject delta(JSONObject user, JSONArray granted) {
        JSONObject result = ShopTransactionSupport.success(user, granted);
        JSONObject modified = result.getJSONObject("playerDataDelta").getJSONObject("modified");
        modified.put("activity", user.getJSONObject("activity"));
        return result;
    }

    /**
     * REWARD_ONLY pool choice: /activity/actRewardOnly/setChoice with {activityId, poolId}.
     *
     * Captured from the real UI after picking pool_1. It only records the choice, so it is
     * allowed before claimStartTime: the real page accepts the selection then. Nothing is
     * granted here, and the claim route is deliberately absent until it is captured.
     */
    @PostMapping(value = "/actRewardOnly/setChoice", produces = "application/json;charset=UTF-8")
    public JSONObject setRewardOnlyChoice(@RequestBody JSONObject body,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        String poolId = body == null ? null : body.getString("poolId");
        if (activityId == null || activityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        if (poolId == null || poolId.trim().isEmpty()) {
            return error(response, 400, "poolId is required");
        }
        return ShopTransactionSupport.execute(response, request, user ->
            {
                choose(user, activityId, poolId);
                return delta(user, new JSONArray());
            });
    }

    /** Records the pool choice on the declared REWARD_ONLY container. */
    private static void choose(JSONObject user, String activityId, String poolId) {
        CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(root(), activityId);
        if (!lookup.present()) throw new IllegalArgumentException("本地表未定义该活动");
        if (!"REWARD_ONLY".equals(lookup.activity().type())) {
            throw new IllegalArgumentException("该活动不是自选类型");
        }
        if (!lookup.activity().isOpen(nowSeconds())) {
            throw new IllegalArgumentException("活动不在开放期");
        }
        JSONObject pools = CurrentActivitySupport.rewardOnlyPools(root(), activityId);
        if (pools == null || pools.getJSONObject(poolId) == null) {
            throw new IllegalArgumentException("没有该招募队列");
        }
        JSONObject state = ShopTransactionSupport.object(
                ShopTransactionSupport.object(user, "activity"),
                CurrentActivityState.REWARD_ONLY_CONTAINER).getJSONObject(activityId);
        if (state == null) {
            state = CurrentActivitySupport.seed("REWARD_ONLY");
            ShopTransactionSupport.object(
                    ShopTransactionSupport.object(user, "activity"),
                    CurrentActivityState.REWARD_ONLY_CONTAINER).put(activityId, state);
        }
        // hit is written by the claim side; once it is set the choice is locked.
        String hit = state.getString("hit");
        if (hit != null && !hit.isEmpty()) throw new IllegalArgumentException("已领取，不能再改");
        state.put("choice", poolId);
        if (!state.containsKey("hit")) state.put("hit", "");
    }

    /**
     * ARCADE milestone single claim: /activity/rewardMilestone
     * with {activityId,milestoneId}. The response is items plus the inherited
     * playerDataDelta shape.
     */
    @PostMapping(value = "/rewardMilestone", produces = "application/json;charset=UTF-8")
    public JSONObject rewardMilestone(@RequestBody JSONObject body,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        String milestoneId = body == null ? null : body.getString("milestoneId");
        if (activityId == null || activityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        if (milestoneId == null || milestoneId.trim().isEmpty()) {
            return error(response, 400, "milestoneId is required");
        }
        return ShopTransactionSupport.execute(response, request, user -> {
            long now = nowSeconds();
            JSONObject activity = arcadePayload(activityId, now);
            JSONObject definition = milestoneById(activity, milestoneId);
            JSONObject state = arcadeState(user, activityId);
            JSONObject milestone = state.getJSONObject("milestone");
            JSONArray got = milestone.getJSONArray("got");
            if (contains(got, milestoneId)) {
                throw new IllegalArgumentException("该里程碑奖励已领取");
            }
            if (milestone.getIntValue("point") < definition.getIntValue("needPointCnt")) {
                throw new IllegalArgumentException("里程碑点数不足");
            }

            JSONArray items = new JSONArray();
            ShopTransactionSupport.reward(
                    user, definition.getJSONObject("reward"), 1, items);
            got.add(milestoneId);
            return delta(user, items);
        });
    }

    /**
     * ARCADE milestone all-claim: /activity/rewardAllMilestone with {activityId}.
     * Response is the inherited delta plus milestoneList and items.
     */
    @PostMapping(value = "/rewardAllMilestone", produces = "application/json;charset=UTF-8")
    public JSONObject rewardAllMilestone(@RequestBody JSONObject body,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        if (activityId == null || activityId.trim().isEmpty()) {
            return error(response, 400, "activityId is required");
        }
        return ShopTransactionSupport.execute(response, request, user -> {
            long now = nowSeconds();
            JSONObject activity = arcadePayload(activityId, now);
            JSONObject state = arcadeState(user, activityId);
            JSONObject milestone = state.getJSONObject("milestone");
            JSONArray got = milestone.getJSONArray("got");
            JSONArray definitions = activity.getJSONArray("milestoneList");
            JSONArray items = new JSONArray();
            JSONArray claimed = new JSONArray();

            for (int i = 0; i < definitions.size(); i++) {
                JSONObject definition = definitions.getJSONObject(i);
                String milestoneId = definition.getString("mileStoneId");
                if (contains(got, milestoneId)
                        || milestone.getIntValue("point") < definition.getIntValue("needPointCnt")) {
                    continue;
                }
                ShopTransactionSupport.reward(
                        user, definition.getJSONObject("reward"), 1, items);
                got.add(milestoneId);
                claimed.add(milestoneId);
            }
            if (claimed.isEmpty()) {
                throw new IllegalArgumentException("没有可领取的里程碑奖励");
            }

            JSONObject result = delta(user, items);
            result.put("milestoneList", claimed);
            return result;
        });
    }

    /** Load and validate the ARCADE table data used by both milestone claims. */
    private static JSONObject arcadePayload(String activityId, long now) {
        CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(root(), activityId);
        if (!lookup.present()) throw new IllegalArgumentException("本地表未定义该活动");
        if (!"ARCADE".equals(lookup.activity().type())) {
            throw new IllegalArgumentException("该活动不是街机玩法");
        }
        if (now < lookup.activity().startTime() || now > lookup.activity().rewardEndTime()) {
            throw new IllegalArgumentException("活动不在领取期");
        }
        JSONObject payload = CurrentActivitySupport.typePayload(root(), "ARCADE", activityId);
        if (payload == null || payload.getJSONArray("milestoneList") == null) {
            throw new IllegalArgumentException("该活动没有里程碑数据");
        }
        return payload;
    }

    /** The declared PlayerArcadeActivity state, seeded only when absent from this save. */
    private static JSONObject arcadeState(JSONObject user, String activityId) {
        JSONObject entries = ShopTransactionSupport.object(
                ShopTransactionSupport.object(user, "activity"),
                CurrentActivityState.ARCADE_CONTAINER);
        JSONObject state = entries.getJSONObject(activityId);
        if (state == null) {
            state = CurrentActivitySupport.seed("ARCADE");
            entries.put(activityId, state);
        }
        JSONObject milestone = state.getJSONObject("milestone");
        if (milestone == null) {
            milestone = new JSONObject(true);
            milestone.put("point", 0);
            milestone.put("got", new JSONArray());
            state.put("milestone", milestone);
        }
        if (!milestone.containsKey("point")) milestone.put("point", 0);
        if (!milestone.containsKey("got")) milestone.put("got", new JSONArray());
        return state;
    }

    private static JSONObject milestoneById(JSONObject activity, String milestoneId) {
        JSONArray definitions = activity.getJSONArray("milestoneList");
        for (int i = 0; i < definitions.size(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            if (milestoneId.equals(definition.getString("mileStoneId"))) return definition;
        }
        throw new IllegalArgumentException("没有该里程碑");
    }

    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.size(); i++) {
            if (value.equals(values.getString(i))) return true;
        }
        return false;
    }
}
