package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.BackendPaths;
import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/activity/actRewardOnly")
public class RewardOnlyController {
    // Captured from the real act1reward page with its claim period open.
    @PostMapping(value = "/getReward", produces = "application/json;charset=UTF-8")
    public JSONObject getReward(@RequestBody JSONObject body, HttpServletRequest request,
                                HttpServletResponse response) {
        String activityId = body == null ? null : body.getString("activityId");
        String poolId = body == null ? null : body.getString("poolId");
        if (activityId == null || poolId == null) return ShopTransactionSupport.error("activityId and poolId are required");
        return ShopTransactionSupport.execute(response, request, user -> {
            CurrentActivitySupport.Lookup lookup = CurrentActivitySupport.lookup(BackendPaths.rootFile(), activityId);
            long now = System.currentTimeMillis() / 1000L;
            if (!lookup.present() || !"REWARD_ONLY".equals(lookup.activity().type()))
                throw new IllegalArgumentException("没有该特别招募活动");
            JSONObject definition = CurrentActivitySupport.dynActs(BackendPaths.rootFile()).getJSONObject(activityId);
            if (!lookup.activity().isOpen(now) || now < definition.getJSONObject("constData").getLongValue("claimStartTime"))
                throw new IllegalArgumentException("特别招募尚未开放或已结束");
            JSONObject pool = CurrentActivitySupport.rewardOnlyPools(BackendPaths.rootFile(), activityId).getJSONObject(poolId);
            if (pool == null) throw new IllegalArgumentException("没有该招募队列");
            JSONObject states = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "activity"),
                    CurrentActivityState.REWARD_ONLY_CONTAINER);
            JSONObject state = states.getJSONObject(activityId);
            if (state == null) {
                state = CurrentActivityState.seed("REWARD_ONLY");
                states.put(activityId, state);
            }
            if (!state.getString("hit").isEmpty()) throw new IllegalArgumentException("特别招募奖励已领取");
            JSONArray candidates = pool.getJSONArray("charIdList");
            String charId = candidates.getString(ThreadLocalRandom.current().nextInt(candidates.size()));
            JSONObject reward = new JSONObject(true);
            reward.put("id", charId);
            reward.put("type", "CHAR");
            reward.put("count", 1);
            JSONArray items = new JSONArray();
            ShopTransactionSupport.reward(user, reward, 1, items);
            state.put("choice", poolId);
            state.put("hit", charId);
            JSONObject result = ShopTransactionSupport.success(user, items);
            result.getJSONObject("playerDataDelta").getJSONObject("modified").put("activity", user.getJSONObject("activity"));
            return result;
        });
    }
}
