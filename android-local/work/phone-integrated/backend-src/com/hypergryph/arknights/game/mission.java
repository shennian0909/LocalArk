package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mission")
public class mission {
    public mission() {}

    /** Mirrors MissionProgressSupport.typeName(int); the client sends these names as strings. */
    private static final Set<String> MISSION_TYPES = Set.of(
            "MAIN", "DAILY", "WEEKLY", "GUIDE", "SUB", "ACTIVITY", "OPENSERVER",
            "TOWERSEASON", "RETRO", "SPECIAL_OPERATOR", "SPECIAL_OPERATOR_WEEKLY");

    @PostMapping(value = "/confirmMission", produces = "application/json;charset=UTF-8")
    public JSONObject confirmMission(@RequestBody JSONObject body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String missionId = body == null ? null : body.getString("missionId");
        if (missionId == null || missionId.isEmpty()) return invalid(response, "missionId is required");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject missionBefore = missionSnapshot(user);
            MissionProgressSupport.normalize(user, nowSeconds());
            JSONArray items = new JSONArray();
            claim(user, missionId, items);
            return delta(user, missionBefore, items);
        });
    }

    @PostMapping(value = "/autoConfirmMissions", produces = "application/json;charset=UTF-8")
    public JSONObject autoConfirmMissions(@RequestBody JSONObject body,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        String type = missionType(body);
        if (type == null) return invalid(response, "invalid mission type");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject missionBefore = missionSnapshot(user);
            MissionProgressSupport.normalize(user, nowSeconds());
            JSONArray items = new JSONArray();
            // MAIN, DAILY, and WEEKLY rows can unlock the next row when this one settles at state 3.
            // Keep scanning until a pass unlocks nothing claimable.
            do {
                JSONArray ids = claimableIds(user, type);
                if (ids.isEmpty()) break;
                for (int i = 0; i < ids.size(); i++) claim(user, ids.getString(i), items);
            } while ("MAIN".equals(type) || "DAILY".equals(type) || "WEEKLY".equals(type));
            return delta(user, missionBefore, items);
        });
    }

    @PostMapping(value = "/confirmMissionList", produces = "application/json;charset=UTF-8")
    public JSONObject confirmMissionList(@RequestBody JSONObject body,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        JSONArray ids = body == null ? null : body.getJSONArray("missionIds");
        if (ids == null) return invalid(response, "missionIds is required");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject missionBefore = missionSnapshot(user);
            MissionProgressSupport.normalize(user, nowSeconds());
            return confirmList(user, missionBefore, ids);
        });
    }

    @PostMapping(value = "/confirmMissionGroup", produces = "application/json;charset=UTF-8")
    public JSONObject confirmMissionGroup(@RequestBody JSONObject body,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        String groupId = body == null ? null : body.getString("missionGroupId");
        if (groupId == null || groupId.isEmpty()) return invalid(response, "missionGroupId is required");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject missionBefore = missionSnapshot(user);
            MissionProgressSupport.normalize(user, nowSeconds());
            JSONObject group = MissionProgressSupport.group(root(), groupId);
            if (group == null) throw new IllegalArgumentException("unknown mission group");
            JSONObject groups = ShopTransactionSupport.object(
                    ShopTransactionSupport.object(user, "mission"), "missionGroups");
            if (groups.getIntValue(groupId) == 1) {
                throw new IllegalArgumentException("mission group already claimed");
            }
            JSONArray missionIds = group.getJSONArray("missionIds");
            for (int i = 0; i < missionIds.size(); i++) {
                JSONObject state = MissionProgressSupport.state(user, missionIds.getString(i));
                if (state == null || state.getIntValue("state") != MissionProgressSupport.FINISHED) {
                    throw new IllegalArgumentException("mission group is not complete");
                }
            }
            JSONArray rewards = MissionProgressSupport.rewards(group);
            requireSupported(rewards);
            JSONArray items = grant(user, rewards);
            groups.put(groupId, 1);
            MissionProgressSupport.activateNextMissionGroup(user, groupId, nowSeconds());
            return delta(user, missionBefore, items);
        });
    }

    @PostMapping(value = "/exchangeMissionRewards", produces = "application/json;charset=UTF-8")
    public JSONObject exchangeMissionRewards(@RequestBody JSONObject body,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        String rewardId = body == null ? null : body.getString("targetRewardsId");
        if (rewardId == null || rewardId.isEmpty()) return invalid(response, "targetRewardsId is required");
        return ShopTransactionSupport.execute(response, request, user -> {
            long ts = nowSeconds();
            JSONObject missionBefore = missionSnapshot(user);
            MissionProgressSupport.normalize(user, ts);
            JSONObject reward = MissionProgressSupport.periodicReward(root(), rewardId);
            if (reward == null) throw new IllegalArgumentException("unknown mission reward");
            if (!MissionProgressSupport.activePeriodicReward(root(), rewardId, ts)) {
                throw new IllegalArgumentException("mission reward is outside its current period");
            }
            JSONObject mission = ShopTransactionSupport.object(user, "mission");
            JSONObject missionRewards = ShopTransactionSupport.object(mission, "missionRewards");
            JSONObject states = ShopTransactionSupport.object(missionRewards, "rewards");
            String type = reward.getString("type");
            JSONObject typedStates = ShopTransactionSupport.object(states, type);
            if (typedStates.getIntValue(rewardId) == 1) {
                throw new IllegalArgumentException("mission reward already claimed");
            }
            String pointKey = "DAILY".equals(type) ? "dailyPoint" : "weeklyPoint";
            if (missionRewards.getIntValue(pointKey) < reward.getIntValue("periodicalPointCost")) {
                throw new IllegalArgumentException("mission reward is not unlocked");
            }
            JSONArray rewards = MissionProgressSupport.rewards(reward);
            requireSupported(rewards);
            JSONArray items = grant(user, rewards);
            // The client allocates the remaining point pool across unclaimed reward costs.
            missionRewards.put(pointKey, missionRewards.getIntValue(pointKey)
                    - reward.getIntValue("periodicalPointCost"));
            typedStates.put(rewardId, 1);
            return delta(user, missionBefore, items);
        });
    }

    private static JSONObject confirmList(JSONObject user, JSONObject missionBefore, JSONArray ids) {
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.getString(i);
            if (id == null || id.isEmpty() || !unique.add(id)) {
                throw new IllegalArgumentException("invalid mission id");
            }
        }
        JSONArray items = new JSONArray();
        // A list can contain the next tier, which becomes claimable only after the preceding
        // item settles. Validate each id at its turn so that state transition remains visible.
        for (String id : unique) claim(user, id, items);
        return delta(user, missionBefore, items);
    }

    /** The MAIN page's batch button covers its MAIN list and the SUB column beside it. */
    private static JSONArray claimableIds(JSONObject user, String type) {
        String[] kinds = "MAIN".equals(type) ? new String[]{"MAIN", "SUB"} : new String[]{type};
        JSONArray ids = new JSONArray();
        for (String kind : kinds) {
            Set<String> active = MissionProgressSupport.activeMissionIds(root(), kind, nowSeconds());
            JSONObject rows = user.getJSONObject("mission").getJSONObject("missions")
                    .getJSONObject(kind);
            if (rows == null) continue;
            for (String id : rows.keySet()) {
                if (!MissionProgressSupport.claimable(rows.getJSONObject(id))) continue;
                if (active != null && !active.contains(id)) continue;
                ids.add(id);
            }
        }
        return ids;
    }

    private static void claim(JSONObject user, String missionId, JSONArray items) {
        JSONObject definition = MissionProgressSupport.definition(root(), missionId);
        JSONObject state = MissionProgressSupport.state(user, missionId);
        requireClaimable(missionId, definition, state);
        JSONArray rewards = MissionProgressSupport.rewards(definition);
        requireSupported(rewards);
        items.addAll(grant(user, rewards));
        state.put("state", MissionProgressSupport.FINISHED);

        String groupId = definition.getString("missionGroup");
        JSONObject group = MissionProgressSupport.group(root(), groupId);
        if (group != null && MissionProgressSupport.rewards(group).isEmpty()) {
            boolean finished = true;
            for (Object id : group.getJSONArray("missionIds")) {
                JSONObject member = MissionProgressSupport.state(user, String.valueOf(id));
                if (member == null || member.getIntValue("state") != MissionProgressSupport.FINISHED) {
                    finished = false;
                    break;
                }
            }
            if (finished) {
                ShopTransactionSupport.object(user.getJSONObject("mission"), "missionGroups").put(groupId, 1);
                MissionProgressSupport.activateNextMissionGroup(user, groupId, nowSeconds());
            }
        }
        MissionProgressSupport.normalize(user, nowSeconds());

        String type = definition.getString("type");
        int points = definition.getIntValue("periodicalPoint");
        if (points > 0 && ("DAILY".equals(type) || "WEEKLY".equals(type))) {
            JSONObject missionRewards = ShopTransactionSupport.object(
                    ShopTransactionSupport.object(user, "mission"), "missionRewards");
            String pointKey = "DAILY".equals(type) ? "dailyPoint" : "weeklyPoint";
            long value = (long) missionRewards.getIntValue(pointKey) + points;
            if (value > Integer.MAX_VALUE) throw new ArithmeticException("mission point overflow");
            missionRewards.put(pointKey, value);
        }
    }

    private static void requireClaimable(String missionId, JSONObject definition, JSONObject state) {
        if (definition == null) throw new IllegalArgumentException("unknown mission");
        if (!MissionProgressSupport.claimable(state)) {
            throw new IllegalArgumentException("mission is not claimable: " + missionId);
        }
        if ("OPENSERVER".equals(definition.getString("type"))
                && !MissionProgressSupport.activeOpenServerMission(root(), missionId, nowSeconds())) {
            throw new IllegalArgumentException("mission is outside its open-server window");
        }
        if (("DAILY".equals(definition.getString("type"))
                || "WEEKLY".equals(definition.getString("type")))
                && !MissionProgressSupport.activeMissionIds(
                        root(), definition.getString("type"), nowSeconds()).contains(missionId)) {
            throw new IllegalArgumentException("mission is outside its current period");
        }
    }

    private static void requireSupported(JSONArray rewards) {
        if (!MissionProgressSupport.rewardsSupported(rewards)) {
            throw new IllegalArgumentException("mission reward type is unsupported");
        }
    }

    private static JSONArray grant(JSONObject user, JSONArray rewards) {
        JSONArray items = new JSONArray();
        for (int i = 0; i < rewards.size(); i++) {
            ShopTransactionSupport.reward(user, rewards.getJSONObject(i), 1, items);
        }
        return items;
    }

    private static JSONObject delta(JSONObject user, JSONArray items) {
        JSONObject result = ShopTransactionSupport.success(user, items);
        MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"));
        return result;
    }

    /**
     * Same as {@link #delta} but carries removals that really happened inside the transaction.
     * The periodic table rotation in {@code normalize} can drop daily/weekly rows the client
     * still holds, and those are the only ones it accepts in a {@code deleted} block.
     */
    private static JSONObject delta(JSONObject user, JSONObject missionBefore, JSONArray items) {
        JSONObject result = ShopTransactionSupport.success(user, items);
        MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"), missionBefore);
        return result;
    }

    /** Deep copy of the mission block taken before a transaction, so removals stay provable. */
    private static JSONObject missionSnapshot(JSONObject user) {
        JSONObject mission = user == null ? null : user.getJSONObject("mission");
        return mission == null ? null : com.alibaba.fastjson.JSON.parseObject(mission.toJSONString());
    }

    /**
     * The wire body is {"type":"MAIN"}. Reading the raw value keeps the string form and the
     * legacy numeric form on one path, so a string type no longer reaches getIntValue and
     * throws NumberFormatException before the transaction can turn it into a 400.
     */
    private static String missionType(JSONObject body) {
        if (body == null) return null;
        Object raw = body.get("type");
        if (raw instanceof String) return MISSION_TYPES.contains(raw) ? (String) raw : null;
        if (raw instanceof Number) return MissionProgressSupport.typeName(((Number) raw).intValue());
        return null;
    }

    private static JSONObject invalid(HttpServletResponse response, String message) {
        if (response != null) response.setStatus(400);
        JSONObject result = ShopTransactionSupport.error(message);
        result.put("result", 1);
        return result;
    }

    private static File root() {
        return com.codex.arklocal.BackendPaths.rootFile();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
