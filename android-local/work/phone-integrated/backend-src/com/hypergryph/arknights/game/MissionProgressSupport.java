package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.BackendPaths;
import com.hypergryph.arknights.ArknightsApplication;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Rebuilds guard- and server-campaign mission progress from durable player state. */
public final class MissionProgressSupport {
    /**
     * MissionHoldingState as the shipped client and the official save both use it:
     * 0 = locked, 1 = not yet taken, 2 = active, 3 = rewarded/claimed.
     *
     * Official relogin evidence: daily_7001 is state 2 with progress 0/1, and its dependent
     * daily_7002 is state 1. Progress alone decides whether an active row can be claimed.
     */
    static final int ACTIVE = 2;
    static final int FINISHED = 3;

    private static final Set<String> RECONSTRUCTIBLE_TYPES =
            Set.of("MAIN", "GUIDE", "SUB", "OPENSERVER");
    private static final Map<String, Tables> TABLE_CACHE = new ConcurrentHashMap<>();
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int RESET_HOUR = 4;

    private MissionProgressSupport() {}

    /**
     * Rebuilds durable permanent-task progress and maintains the current daily/weekly table
     * group. It never lowers permanent progress or invents action counts.
     */
    public static boolean normalize(JSONObject user, long ts) {
        return normalize(user, BackendPaths.rootFile(), ts);
    }

    public static boolean normalize(JSONObject user, File root, long ts) {
        if (user == null) throw new IllegalArgumentException("user is required");
        JSONObject missionData = user.getJSONObject("mission");
        JSONObject missions = missionData == null ? null : missionData.getJSONObject("missions");
        if (missions == null) return false;

        Tables tables = tables(root);
        boolean changed = preparePeriodic(user, tables, "DAILY", "lastDailyTs",
                dailyPeriodTs(ts), tables.activeDailyMissionIds(ts));
        changed |= preparePeriodic(user, tables, "WEEKLY", "lastWeeklyTs",
                weeklyPeriodTs(ts), tables.activeWeeklyMissionIds(ts));
        changed |= migrateLegacyRewardCosts(user, tables, "DAILY", ts);
        changed |= migrateLegacyRewardCosts(user, tables, "WEEKLY", ts);
        Set<String> activeOpenServerMissions = tables.activeOpenServerMissionIds(ts);
        changed |= preparePermanentGroups(user, tables);
        changed |= openReadyMissions(user, tables, ts);
        for (String type : missions.keySet()) {
            if (!RECONSTRUCTIBLE_TYPES.contains(type)) continue;
            JSONObject rows = missions.getJSONObject(type);
            if (rows == null) continue;
            for (String missionId : rows.keySet()) {
                if ("OPENSERVER".equals(type) && !activeOpenServerMissions.contains(missionId)) continue;
                JSONObject definition = tables.missions.getJSONObject(missionId);
                JSONObject state = rows.getJSONObject(missionId);
                changed |= rebuild(user, tables, definition, state);
            }
        }
        return changed;
    }

    /**
     * Applies one successfully completed player action. Callers invoke this once, immediately
     * after the real route succeeds and before persisting that route's user snapshot.
     *
     * <p>Events: {@code upgradeChar}, {@code upgradeSkill}, {@code recruit},
     * {@code battleFinish}, {@code practiceFinish}, and {@code squadChange}. Public recruitment
     * supplies {@code mode="normal"}; battle completion supplies its result {@code rating}.
     */
    public static boolean onAction(JSONObject user, String event, JSONObject context, long ts) {
        return onAction(user, BackendPaths.rootFile(), event, context, ts);
    }

    static boolean onAction(JSONObject user, File root, String event, JSONObject context, long ts) {
        if (user == null || event == null) return false;
        JSONObject missionData = user.getJSONObject("mission");
        JSONObject missions = missionData == null ? null : missionData.getJSONObject("missions");
        if (missions == null) return false;

        Tables tables = tables(root);
        boolean changed = preparePeriodic(user, tables, "DAILY", "lastDailyTs",
                dailyPeriodTs(ts), tables.activeDailyMissionIds(ts));
        changed |= preparePeriodic(user, tables, "WEEKLY", "lastWeeklyTs",
                weeklyPeriodTs(ts), tables.activeWeeklyMissionIds(ts));
        // Settle row states before the event, so a save still carrying the old state-1 rows is
        // active by the time the event lands. Otherwise the first real action after an upgrade
        // would be skipped by advance() and lost.
        changed |= normalize(user, root, ts);
        changed |= applyAction(user, tables, event, context == null ? new JSONObject() : context, ts);
        changed |= normalize(user, root, ts);
        return changed;
    }

    static JSONObject definition(File root, String missionId) {
        if (missionId == null) return null;
        return tables(root).missions.getJSONObject(missionId);
    }

    static JSONObject group(File root, String groupId) {
        if (groupId == null) return null;
        return tables(root).groups.getJSONObject(groupId);
    }

    static JSONObject periodicReward(File root, String rewardId) {
        if (rewardId == null) return null;
        return tables(root).periodicRewards.getJSONObject(rewardId);
    }

    static boolean activePeriodicReward(File root, String rewardId, long ts) {
        return activePeriodicReward(tables(root), rewardId, ts);
    }

    private static boolean activePeriodicReward(Tables tables, String rewardId, long ts) {
        JSONObject reward = tables.periodicRewards.getJSONObject(rewardId);
        if (reward == null) return false;
        if ("WEEKLY".equals(reward.getString("type")))
            return ts >= reward.getLongValue("beginTime") && ts <= reward.getLongValue("endTime");
        JSONObject period = tables.dailyPeriod(ts);
        return "DAILY".equals(reward.getString("type")) && period != null
                && reward.getString("groupId").equals(period.getString("rewardGroupId"));
    }

    static boolean activeOpenServerMission(File root, String missionId, long ts) {
        return missionId != null && tables(root).activeOpenServerMissionIds(ts).contains(missionId);
    }

    static Set<String> activeMissionIds(File root, String type, long ts) {
        Tables tables = tables(root);
        return switch (type) {
            case "DAILY" -> tables.activeDailyMissionIds(ts);
            case "WEEKLY" -> tables.activeWeeklyMissionIds(ts);
            case "OPENSERVER" -> tables.activeOpenServerMissionIds(ts);
            default -> null;
        };
    }

    /**
     * Pushes the current mission block only.
     *
     * Without a before-snapshot there is no safe way to name removals: the client logs
     * "Trying to delete invalid field" for every key it never had, so this overload sends
     * {@code modified} alone. Callers that rotate periodic tables must pass the snapshot
     * taken before their transaction and use the three-argument form.
     */
    static void writeDelta(JSONObject user, JSONObject delta) {
        JSONObject mission = user.getJSONObject("mission");
        delta.getJSONObject("modified").put("mission", mission);
    }

    /**
     * Pushes the current mission block and, when {@code missionBefore} is supplied, only the
     * keys that really disappeared from it.
     *
     * The client rejects deleting a key it does not hold, and the previous behaviour
     * enumerated every expired table id regardless of what the save ever contained, which
     * flooded the log with hundreds of "Trying to delete invalid field, name [...]" errors.
     */
    static void writeDelta(JSONObject user, JSONObject delta, JSONObject missionBefore) {
        JSONObject mission = user.getJSONObject("mission");
        delta.getJSONObject("modified").put("mission", mission);
        if (missionBefore == null) return;
        JSONObject deletedMission = diffKeys(missionBefore, mission);
        if (!deletedMission.isEmpty()) ShopTransactionSupport.object(delta, "deleted").put("mission", deletedMission);
    }

    /** Recursively collects keys present in {@code before} but absent from {@code after}. */
    private static JSONObject diffKeys(JSONObject before, JSONObject after) {
        JSONObject removed = new JSONObject(true);
        for (String key : before.keySet()) {
            if (after == null || !after.containsKey(key)) {
                removed.put(key, null);
                continue;
            }
            Object beforeValue = before.get(key);
            Object afterValue = after.get(key);
            if (!(beforeValue instanceof JSONObject) || !(afterValue instanceof JSONObject)) continue;
            JSONObject nested = diffKeys((JSONObject) beforeValue, (JSONObject) afterValue);
            if (!nested.isEmpty()) removed.put(key, nested);
        }
        return removed;
    }

    static boolean activateNextMissionGroup(JSONObject user, String groupId, long ts) {
        if (user == null || groupId == null) return false;
        Tables tables = tables(BackendPaths.rootFile());
        boolean changed = false;
        for (String childId : tables.groups.keySet()) {
            JSONObject child = tables.groups.getJSONObject(childId);
            if (!groupId.equals(child.getString("preMissionGroup"))) continue;
            String type = child.getString("type");
            if (!"MAIN".equals(type) && !"GUIDE".equals(type) && !"SUB".equals(type)) continue;
            changed |= activateMissionGroup(user, tables, child);
        }
        return changed;
    }

    private static boolean preparePermanentGroups(JSONObject user, Tables tables) {
        JSONObject groups = ShopTransactionSupport.object(user.getJSONObject("mission"), "missionGroups");
        boolean changed = false;
        // Rewardless groups have no separate claim request; durable claimed members prove completion.
        for (String groupId : tables.groups.keySet()) {
            JSONObject group = tables.groups.getJSONObject(groupId);
            if (!RECONSTRUCTIBLE_TYPES.contains(group.getString("type"))
                    || "OPENSERVER".equals(group.getString("type")) || !rewards(group).isEmpty()
                    || groups.getIntValue(groupId) == 1) continue;
            boolean finished = true;
            for (Object id : group.getJSONArray("missionIds")) {
                JSONObject row = state(user, String.valueOf(id));
                if (row == null || row.getIntValue("state") != FINISHED) { finished = false; break; }
            }
            if (finished) { groups.put(groupId, 1); changed = true; }
        }
        for (String groupId : tables.groups.keySet()) {
            JSONObject group = tables.groups.getJSONObject(groupId);
            String type = group.getString("type");
            if (!"MAIN".equals(type) && !"GUIDE".equals(type) && !"SUB".equals(type)) continue;
            String parent = group.getString("preMissionGroup");
            if (parent == null || groups.getIntValue(parent) == 1)
                changed |= activateMissionGroup(user, tables, group);
        }
        return changed;
    }

    private static boolean activateMissionGroup(JSONObject user, Tables tables, JSONObject group) {
        JSONObject missionData = user.getJSONObject("mission");
        JSONObject groups = ShopTransactionSupport.object(missionData, "missionGroups");
        String groupId = group.getString("id");
        boolean changed = !groups.containsKey(groupId);
        if (changed) groups.put(groupId, 0);
        JSONObject rows = ShopTransactionSupport.object(
                missionData.getJSONObject("missions"), group.getString("type"));
        for (Object id : group.getJSONArray("missionIds")) {
            String missionId = String.valueOf(id);
            JSONObject definition = tables.missions.getJSONObject(missionId);
            if (definition == null) continue;
            JSONObject row = rows.getJSONObject(missionId);
            if (row == null) {
                row = initialMission(definition);
                if (!dependenciesMet(user, tables, definition)) row.put("state", 0);
                rows.put(missionId, row);
                changed = true;
            } else if (row.getIntValue("state") == 0 && dependenciesMet(user, tables, definition)) {
                row.put("state", ACTIVE);
                changed = true;
            }
        }
        return changed;
    }

    static JSONObject state(JSONObject user, String missionId) {
        JSONObject missionData = user.getJSONObject("mission");
        JSONObject missions = missionData == null ? null : missionData.getJSONObject("missions");
        if (missions == null || missionId == null) return null;
        for (String type : missions.keySet()) {
            JSONObject rows = missions.getJSONObject(type);
            JSONObject state = rows == null ? null : rows.getJSONObject(missionId);
            if (state != null) return state;
        }
        return null;
    }

    private static boolean openReadyMissions(JSONObject user, Tables tables, long ts) {
        JSONObject missions = user.getJSONObject("mission").getJSONObject("missions");
        boolean changed = false;
        Set<String> activeOpenServerMissions = tables.activeOpenServerMissionIds(ts);
        for (String type : new String[]{"MAIN", "GUIDE", "SUB", "DAILY", "WEEKLY", "OPENSERVER"}) {
            JSONObject rows = missions.getJSONObject(type);
            if (rows == null) continue;
            for (String id : rows.keySet()) {
                JSONObject row = rows.getJSONObject(id);
                JSONObject definition = tables.missions.getJSONObject(id);
                if (definition == null) continue;
                if ("OPENSERVER".equals(type) && !activeOpenServerMissions.contains(id)) continue;
                int state = row.getIntValue("state");
                if (state != 0 && state != 1) continue;
                if (state == 0 && !dependenciesMet(user, tables, definition)) continue;
                // A row stays at 1 only while its own preMissionIds chain is unfinished. The group
                // flag records the group reward, not access: official group guide_g_6 is flag 0 yet
                // holds eight state-3 rows, so an unclaimed group never demotes its members.
                if (predecessorsSettled(user, definition)) {
                    row.put("state", ACTIVE);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean predecessorsSettled(JSONObject user, JSONObject definition) {
        JSONArray required = definition.getJSONArray("preMissionIds");
        if (required == null) return true;
        for (int i = 0; i < required.size(); i++) {
            JSONObject previous = state(user, required.getString(i));
            if (previous == null || previous.getIntValue("state") != FINISHED) return false;
        }
        return true;
    }

    private static boolean dependenciesMet(JSONObject user, Tables tables, JSONObject definition) {
        if (definition == null) return false;
        JSONObject group = tables.groups.getJSONObject(definition.getString("missionGroup"));
        String parent = group == null ? null : group.getString("preMissionGroup");
        JSONObject groups = user.getJSONObject("mission").getJSONObject("missionGroups");
        if (parent != null && (groups == null || groups.getIntValue(parent) != 1)) return false;
        JSONArray required = definition.getJSONArray("preMissionIds");
        if (required != null) for (int i = 0; i < required.size(); i++) {
            JSONObject previous = state(user, required.getString(i));
            if (previous == null || previous.getIntValue("state") != FINISHED) return false;
        }
        String condition = definition.getString("unlockCondition");
        if (condition == null) return true;
        if (!"StageCondition".equals(condition)) return false;
        JSONArray param = definition.getJSONArray("unlockParam");
        JSONObject stages = stages(user);
        JSONObject stage = stages == null ? null : stages.getJSONObject(param.getString(1));
        return stage != null && stage.getIntValue("state") >= param.getIntValue(0);
    }

    static boolean claimable(JSONObject state) {
        if (state == null || state.getIntValue("state") != ACTIVE) return false;
        JSONArray progress = state.getJSONArray("progress");
        if (progress == null || progress.isEmpty()) return false;
        for (int i = 0; i < progress.size(); i++) {
            JSONObject row = progress.getJSONObject(i);
            if (row.getIntValue("value") < row.getIntValue("target")) return false;
        }
        return true;
    }

    static JSONArray rewards(JSONObject definition) {
        JSONArray rewards = definition == null ? null : definition.getJSONArray("rewards");
        return rewards == null ? new JSONArray() : rewards;
    }

    static boolean rewardsSupported(JSONArray rewards) {
        for (int i = 0; i < rewards.size(); i++) {
            if (!ShopTransactionSupport.supports(rewards.getJSONObject(i).getString("type"))) return false;
        }
        return true;
    }

    static String typeName(int value) {
        return switch (value) {
            case 1 -> "MAIN";
            case 2 -> "DAILY";
            case 3 -> "WEEKLY";
            case 4 -> "GUIDE";
            case 5 -> "SUB";
            case 6 -> "ACTIVITY";
            case 7 -> "OPENSERVER";
            case 8 -> "TOWERSEASON";
            case 9 -> "RETRO";
            case 10 -> "SPECIAL_OPERATOR";
            case 11 -> "SPECIAL_OPERATOR_WEEKLY";
            default -> null;
        };
    }

    private static boolean rebuild(JSONObject user, Tables tables, JSONObject definition, JSONObject state) {
        if (definition == null || state == null || state.getIntValue("state") == FINISHED) return false;
        // Official saves accumulate counts on not-yet-taken rows too: weekly_721 sits at state 1
        // with 27/60. State only gates claiming, never the counter.
        if (state.getIntValue("state") < 1 || state.getIntValue("state") > ACTIVE) return false;
        JSONArray progress = state.getJSONArray("progress");
        if (progress == null || progress.size() != 1) return false;
        JSONObject row = progress.getJSONObject(0);
        int target = row.getIntValue("target");
        if (target <= 0) return false;

        int measured = measure(user, tables, definition, target);
        if (measured < 0) return false;
        int value = Math.min(measured, target);
        int oldValue = row.getIntValue("value");
        boolean changed = false;
        if (value > oldValue) {
            row.put("value", value);
            changed = true;
        }
        return changed;
    }

    private static int measure(JSONObject user, Tables tables, JSONObject definition, int target) {
        String template = definition.getString("template");
        JSONArray param = definition.getJSONArray("param");
        if (template == null || param == null) return -1;
        return switch (template) {
            case "CompleteMainStage" -> completedTimes(user, param.getString(1));
            case "CompleteStage" ->
                    completeStage(user, definition.getIntValue("templateType"), param, target);
            case "CompleteAnyStage" -> ratedStages(user, param.getString(1), param.getIntValue(2));
            case "UpgradePlayer" -> playerLevel(user);
            case "UpgradeChar" ->
                    upgradeChar(user, definition.getIntValue("templateType"), param, target);
            case "HasChar" -> hasChar(user, param);
            case "EvolveChar" -> evolvedChars(user, param.getIntValue(1), param.getIntValue(2));
            case "BoostPotential" -> potentialChars(user, param.getIntValue(1), param.getIntValue(2));
            case "UpgradeSkill" ->
                    upgradedSkills(user, definition.getIntValue("templateType"), param, target);
            case "HasRoom" -> rooms(user, param.getString(3), param.getIntValue(2), param.getIntValue(1));
            case "SquadFormation" -> squadFormation(user, param.getString(1));
            case "SetAssistCharList" -> configuredAssistants(user);
            case "CharIntimacy" -> definition.getIntValue("templateType") == 0
                    ? trustedOperators(user, tables.favorFrames, param.getIntValue(2)) : -1;
            default -> -1;
        };
    }

    private static int configuredAssistants(JSONObject user) {
        JSONObject social = user.getJSONObject("social");
        JSONArray slots = social == null ? null : social.getJSONArray("assistCharList");
        if (slots == null) return 0;
        int count = 0;
        for (int i = 0; i < slots.size(); i++) {
            JSONObject slot = slots.getJSONObject(i);
            if (slot != null && user.getJSONObject("troop").getJSONObject("chars").containsKey(slot.getString("charInstId"))) count++;
        }
        return count;
    }

    private static int trustedOperators(JSONObject user, JSONArray frames, int percent) {
        if (frames == null) return -1;
        int threshold = Integer.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            JSONObject data = frames.getJSONObject(i).getJSONObject("data");
            if (data.getIntValue("percent") >= percent) threshold = Math.min(threshold, data.getIntValue("favorPoint"));
        }
        int count = 0;
        JSONObject troop = user.getJSONObject("troop");
        JSONObject owned = troop == null ? null : troop.getJSONObject("chars");
        if (owned == null) return 0;
        for (Object value : owned.values())
            if (((JSONObject) value).getIntValue("favorPoint") >= threshold) count++;
        return count;
    }

    private static int playerLevel(JSONObject user) {
        JSONObject status = user.getJSONObject("status");
        return status == null ? 0 : status.getIntValue("level");
    }

    private static int completeStage(JSONObject user, int type, JSONArray param, int target) {
        if (type == 0) return completedTimes(user, param.getString(1));
        if (type == 2) return ratedStages(user, null, param.getIntValue(1));
        // practiceTimes is a prepaid practice-ticket flag, not a history of passed drills.
        if (type == 3) return -1;
        if (type == 4 && target == 1) {
            JSONObject stages = stages(user);
            if (stages == null) return 0;
            for (String stageId : stages.keySet()) {
                JSONObject stage = stages.getJSONObject(stageId);
                if (stageId.endsWith("#f#")
                        && completedTimes(user, stageId) > 0) return 1;
            }
            return 0;
        }
        return -1;
    }

    private static int completedTimes(JSONObject user, String stageId) {
        JSONObject stages = stages(user);
        JSONObject stage = stages == null ? null : stages.getJSONObject(stageId);
        if (stage == null) return 0;
        int completeTimes = stage.getIntValue("completeTimes");
        return Math.max(completeTimes, stage.getIntValue("state") >= 2 ? 1 : 0);
    }

    private static int ratedStages(JSONObject user, String stageList, int rating) {
        JSONObject stages = stages(user);
        if (stages == null) return 0;
        if (stageList == null) {
            int count = 0;
            for (String stageId : stages.keySet()) {
                JSONObject stage = stages.getJSONObject(stageId);
                if (stage != null && stage.getIntValue("state") >= rating) count++;
            }
            return count;
        }
        int count = 0;
        for (String stageId : stageList.split("\\^")) {
            JSONObject stage = stages.getJSONObject(stageId);
            if (stage != null && stage.getIntValue("state") >= rating) count++;
        }
        return count;
    }

    private static int upgradeChar(JSONObject user, int type, JSONArray param, int target) {
        if (type == 1) {
            return charsAtLeast(user, param.getIntValue(1), param.getIntValue(2),
                    param.getIntValue(3), -1, null);
        }
        if (type != 0 || target != 1) return -1;
        JSONObject troop = user.getJSONObject("troop");
        JSONObject chars = troop == null ? null : troop.getJSONObject("chars");
        if (chars == null) return 0;
        for (String instanceId : chars.keySet()) {
            if (chars.getJSONObject(instanceId).getIntValue("level") > 1) return 1;
        }
        return 0;
    }

    private static int hasChar(JSONObject user, JSONArray param) {
        int count = param.getIntValue(1);
        int phase = param.getIntValue(2);
        int level = param.getIntValue(3);
        int rarity = param.getIntValue(4);
        String profession = param.getString(5);
        return charsAtLeast(user, count, phase, level, rarity, profession);
    }

    private static int charsAtLeast(JSONObject user, int count, int phase, int level, int rarity,
                                    String profession) {
        JSONObject troop = user.getJSONObject("troop");
        JSONObject chars = troop == null ? null : troop.getJSONObject("chars");
        if (chars == null) return 0;
        JSONObject characterTable = ArknightsApplication.characterJson;
        boolean needsTable = rarity >= 0 || (profession != null && !"ALL".equals(profession));
        if (needsTable && characterTable == null) return -1;

        int matched = 0;
        for (String instanceId : chars.keySet()) {
            JSONObject owned = chars.getJSONObject(instanceId);
            if (owned == null || owned.getIntValue("evolvePhase") < phase
                    || owned.getIntValue("level") < level) continue;
            if (needsTable) {
                JSONObject data = characterTable.getJSONObject(owned.getString("charId"));
                if (data == null) continue;
                if (rarity >= 0 && rarity(data.getString("rarity")) != rarity) continue;
                if (profession != null && !"ALL".equals(profession)
                        && !profession.equals(data.getString("profession"))) continue;
            }
            matched++;
        }
        return Math.min(matched, count);
    }

    private static int evolvedChars(JSONObject user, int count, int phase) {
        return charsAtLeast(user, count, phase, 1, -1, null);
    }

    private static int potentialChars(JSONObject user, int count, int rank) {
        JSONObject troop = user.getJSONObject("troop");
        JSONObject chars = troop == null ? null : troop.getJSONObject("chars");
        if (chars == null) return 0;
        int matched = 0;
        for (String instanceId : chars.keySet()) {
            JSONObject owned = chars.getJSONObject(instanceId);
            if (owned != null && owned.getIntValue("potentialRank") >= rank) matched++;
        }
        return Math.min(matched, count);
    }

    private static int upgradedSkills(JSONObject user, int type, JSONArray param, int target) {
        if (type != 0 && type != 1) return -1;
        if (type == 0 && target != 1) return -1;
        int rank = type == 1 ? param.getIntValue(1) : 2;
        JSONObject troop = user.getJSONObject("troop");
        JSONObject chars = troop == null ? null : troop.getJSONObject("chars");
        if (chars == null) return 0;
        for (String instanceId : chars.keySet()) {
            JSONObject owned = chars.getJSONObject(instanceId);
            if (owned != null && owned.getIntValue("mainSkillLvl") >= rank) return 1;
        }
        return 0;
    }

    private static int squadFormation(JSONObject user, String professionList) {
        if (professionList == null || ArknightsApplication.characterJson == null) return -1;
        JSONObject troop = user.getJSONObject("troop");
        JSONObject squads = troop == null ? null : troop.getJSONObject("squads");
        JSONObject chars = troop == null ? null : troop.getJSONObject("chars");
        if (squads == null || chars == null) return 0;
        String[] required = professionList.split("#");
        for (String squadId : squads.keySet()) {
            JSONArray slots = squads.getJSONObject(squadId).getJSONArray("slots");
            if (slots == null) continue;
            java.util.HashSet<String> present = new java.util.HashSet<>();
            for (int i = 0; i < slots.size(); i++) {
                JSONObject slot = slots.getJSONObject(i);
                if (slot == null) continue;
                JSONObject owned = chars.getJSONObject(String.valueOf(slot.getIntValue("charInstId")));
                JSONObject data = owned == null ? null
                        : ArknightsApplication.characterJson.getJSONObject(owned.getString("charId"));
                if (data != null) present.add(data.getString("profession"));
            }
            boolean matched = true;
            for (String profession : required) {
                if (!present.contains(profession)) {
                    matched = false;
                    break;
                }
            }
            if (matched) return 1;
        }
        return 0;
    }

    private static int rooms(JSONObject user, String roomId, int level, int count) {
        JSONObject building = user.getJSONObject("building");
        JSONObject roomSlots = building == null ? null : building.getJSONObject("roomSlots");
        if (roomSlots == null) return 0;
        int matched = 0;
        for (String slot : roomSlots.keySet()) {
            JSONObject room = roomSlots.getJSONObject(slot);
            if (room != null && room.getIntValue("state") == 2 && room.getIntValue("level") >= level
                    && roomId.equals(room.getString("roomId"))) matched++;
        }
        return Math.min(matched, count);
    }

    private static boolean applyAction(JSONObject user, Tables tables, String event,
                                       JSONObject context, long ts) {
        return switch (event) {
            case "upgradeChar" -> advanceMatching(user, tables, ts,
                    definition -> "UpgradeChar".equals(definition.getString("template"))
                            && definition.getIntValue("templateType") == 0, 1);
            case "upgradeSkill" -> advanceMatching(user, tables, ts,
                    definition -> "UpgradeSkill".equals(definition.getString("template"))
                            && definition.getIntValue("templateType") == 0, 1);
            case "recruit" -> "normal".equals(context.getString("mode"))
                    && advanceMatching(user, tables, ts,
                    definition -> "NormalGacha".equals(definition.getString("template")), 1);
            case "creditCollected" -> {
                boolean changed = advanceMatching(user, tables, ts,
                        definition -> "ReceiveSocialPoint".equals(definition.getString("template"))
                                && definition.getIntValue("templateType") == 1, 1);
                changed |= advanceMatching(user, tables, ts,
                        definition -> "ReceiveSocialPoint".equals(definition.getString("template"))
                                && definition.getIntValue("templateType") == 0, context.getIntValue("amount"));
                yield changed;
            }
            case "creditPurchase" -> advanceMatching(user, tables, ts,
                    definition -> "BuyShopItem".equals(definition.getString("template"))
                            && definition.getIntValue("templateType") == 1, 1);
            case "certificatePurchase" -> advanceMatching(user, tables, ts,
                    definition -> "BuyShopItem".equals(definition.getString("template"))
                            && definition.getIntValue("templateType") == 0, 1);
            case "battleFinish" -> {
                int runs = context.containsKey("runs") ? context.getIntValue("runs") : 1;
                boolean changed = advanceMatching(user, tables, ts,
                        definition -> battleCounts(definition, context), runs);
                changed |= advanceMatching(user, tables, ts,
                        definition -> "EnemyKillInAnyStage".equals(definition.getString("template"))
                                && context.getIntValue("rating") >= definition.getJSONArray("param").getIntValue(2),
                        context.getIntValue("kills"));
                changed |= advanceMatching(user, tables, ts,
                        definition -> "StageWithEnemyKill".equals(definition.getString("template")),
                        context.getIntValue("bossKills"));
                changed |= context.getBooleanValue("friendAssist") && advanceMatching(user, tables, ts,
                        definition -> "StageWithAssistChar".equals(definition.getString("template")), runs);
                changed |= context.getBooleanValue("replay") && context.getIntValue("rating") >= 2
                        && advanceMatching(user, tables, ts,
                        definition -> "StageWithReplay".equals(definition.getString("template")), runs);
                yield changed;
            }
            case "visitFriend" -> advanceMatching(user, tables, ts,
                    definition -> "VisitBuilding".equals(definition.getString("template")), 1);
            case "manufactureSettled", "orderDelivered" -> {
                String template = "manufactureSettled".equals(event) ? "ManufactureItem" : "DeliveryOrder";
                boolean changed = advanceMatching(user, tables, ts,
                        definition -> template.equals(definition.getString("template"))
                                && definition.getIntValue("templateType") == 1, context.getIntValue("runs"));
                JSONObject items = context.getJSONObject("items");
                if (items != null) for (String itemId : items.keySet()) {
                    changed |= advanceMatching(user, tables, ts,
                            definition -> template.equals(definition.getString("template"))
                                    && definition.getIntValue("templateType") != 1
                                    && java.util.Arrays.asList(definition.getJSONArray("param").getString(2).split("#"))
                                            .contains(itemId), items.getIntValue(itemId));
                }
                yield changed;
            }
            case "intimacyGained", "dormRecovered" -> advanceMatching(user, tables, ts,
                    definition -> ("intimacyGained".equals(event) ? "GainIntimacy" : "RecoverCharBaseAp")
                            .equals(definition.getString("template")), context.getIntValue("runs"));
            case "practiceFinish" -> context.getIntValue("rating") >= 2 && advanceMatching(user, tables, ts,
                    definition -> "CompleteStage".equals(definition.getString("template"))
                            && definition.getIntValue("templateType") == 3, 1);
            case "squadChange" -> advanceFacts(user, tables, ts,
                    definition -> "SquadFormation".equals(definition.getString("template")));
            case "squadRename" -> advanceMatching(user, tables, ts,
                    definition -> "ChangeSquadName".equals(definition.getString("template")), 1);
            case "businessCardEdited" -> advanceMatching(user, tables, ts,
                    definition -> "EditBusinessCard".equals(definition.getString("template")), 1);
            case "assistConfigured" -> advanceFacts(user, tables, ts,
                    definition -> "SetAssistCharList".equals(definition.getString("template")));
            default -> false;
        };
    }

    private static boolean advanceMatching(JSONObject user, Tables tables, long ts,
                                           java.util.function.Predicate<JSONObject> matches,
                                           int amount) {
        boolean changed = false;
        for (String type : new String[]{"GUIDE", "SUB", "DAILY", "WEEKLY"}) {
            JSONObject rows = periodicRows(user, type);
            if (rows == null) continue;
            Set<String> active = activeEventMissions(tables, type, ts);
            for (String missionId : rows.keySet()) {
                if (active != null && !active.contains(missionId)) continue;
                JSONObject definition = tables.missions.getJSONObject(missionId);
                if (definition != null && matches.test(definition)) {
                    changed |= advance(rows.getJSONObject(missionId), amount);
                }
            }
        }
        return changed;
    }

    private static boolean advanceFacts(JSONObject user, Tables tables, long ts,
                                        java.util.function.Predicate<JSONObject> matches) {
        boolean changed = false;
        for (String type : new String[]{"GUIDE", "DAILY", "WEEKLY"}) {
            JSONObject rows = periodicRows(user, type);
            if (rows == null) continue;
            Set<String> active = activeEventMissions(tables, type, ts);
            for (String missionId : rows.keySet()) {
                if (active != null && !active.contains(missionId)) continue;
                JSONObject definition = tables.missions.getJSONObject(missionId);
                if (definition == null || !matches.test(definition)) continue;
                int measured = measure(user, tables, definition, 1);
                if (measured >= 1) changed |= advance(rows.getJSONObject(missionId), 1);
            }
        }
        return changed;
    }

    private static boolean battleCounts(JSONObject definition, JSONObject context) {
        JSONArray param = definition.getJSONArray("param");
        return "CompleteStageAnyType".equals(definition.getString("template"))
                && context.getIntValue("rating") >= param.getIntValue(2);
    }

    private static Set<String> activeEventMissions(Tables tables, String type, long ts) {
        return switch (type) {
            case "DAILY" -> tables.activeDailyMissionIds(ts);
            case "WEEKLY" -> tables.activeWeeklyMissionIds(ts);
            default -> null;
        };
    }

    private static JSONObject periodicRows(JSONObject user, String type) {
        JSONObject mission = user.getJSONObject("mission");
        JSONObject missions = mission == null ? null : mission.getJSONObject("missions");
        return missions == null ? null : missions.getJSONObject(type);
    }

    private static boolean advance(JSONObject state, int amount) {
        if (state == null) return false;
        int currentState = state.getIntValue("state");
        if (currentState != 1 && currentState != ACTIVE) return false;
        JSONArray progress = state.getJSONArray("progress");
        if (progress == null || progress.size() != 1) return false;
        JSONObject row = progress.getJSONObject(0);
        int target = row.getIntValue("target");
        int oldValue = row.getIntValue("value");
        if (oldValue >= target) return false;
        int value = Math.min(target, Math.max(oldValue, 0) + amount);
        if (value <= oldValue) return false;
        row.put("value", value);
        return true;
    }

    private static boolean preparePeriodic(JSONObject user, Tables tables, String type,
                                           String timestampKey, long periodTs,
                                           Set<String> missionIds) {
        if (missionIds.isEmpty()) return false;
        JSONObject missionData = ShopTransactionSupport.object(user, "mission");
        long previous = missionData.getLongValue(timestampKey);
        boolean reset = previous > 0 && previous != periodTs;
        JSONObject rows = ShopTransactionSupport.object(
                ShopTransactionSupport.object(missionData, "missions"), type);
        boolean changed = false;
        changed |= rows.keySet().removeIf(id -> !missionIds.contains(id));
        for (String missionId : missionIds) {
            if (!reset && rows.getJSONObject(missionId) != null) continue;
            JSONObject definition = tables.missions.getJSONObject(missionId);
            if (definition == null) continue;
            rows.put(missionId, initialMission(definition));
            changed = true;
        }
        if (previous != periodTs) {
            missionData.put(timestampKey, periodTs);
            changed = true;
        }
        if (reset) changed |= resetPeriodicRewards(user, type);
        JSONObject groups = ShopTransactionSupport.object(missionData, "missionGroups");
        for (String groupId : tables.groups.keySet()) {
            JSONObject group = tables.groups.getJSONObject(groupId);
            if (!type.equals(group.getString("type"))) continue;
            boolean active = missionIds.contains(group.getJSONArray("missionIds").getString(0));
            if (!active) changed |= groups.remove(groupId) != null;
            else if (reset || !groups.containsKey(groupId)) { groups.put(groupId, 0); changed = true; }
        }
        JSONObject rewards = ShopTransactionSupport.object(ShopTransactionSupport.object(
                ShopTransactionSupport.object(missionData, "missionRewards"), "rewards"), type);
        for (String rewardId : tables.periodicRewards.keySet()) {
            if (!type.equals(tables.periodicRewards.getJSONObject(rewardId).getString("type"))) continue;
            if (!activePeriodicReward(tables, rewardId, periodTs)) changed |= rewards.remove(rewardId) != null;
            else if (!rewards.containsKey(rewardId)) { rewards.put(rewardId, 0); changed = true; }
        }
        return changed;
    }

    private static boolean resetPeriodicRewards(JSONObject user, String type) {
        JSONObject missionData = ShopTransactionSupport.object(user, "mission");
        JSONObject rewards = ShopTransactionSupport.object(missionData, "missionRewards");
        String pointKey = "DAILY".equals(type) ? "dailyPoint" : "weeklyPoint";
        boolean changed = rewards.getIntValue(pointKey) != 0;
        rewards.put(pointKey, 0);
        JSONObject states = ShopTransactionSupport.object(rewards, "rewards");
        JSONObject typed = ShopTransactionSupport.object(states, type);
        for (String rewardId : typed.keySet()) {
            if (typed.getIntValue(rewardId) != 0) changed = true;
            typed.put(rewardId, 0);
        }
        return changed;
    }

    /**
     * One-way repair for saves written before this build started debiting reward costs.
     *
     * The old exchange route checked the point balance but never subtracted it, so a save can
     * still hold points that the tiers it already claims should have spent. This charges those
     * claimed tiers exactly once, from the same type's current pool, and records that it ran.
     *
     * The flag is what makes it safe to keep calling: a new save has no claimed tiers yet, so it
     * is flagged without any subtraction, and every later normalize or relogin sees the flag and
     * does nothing. Period resets keep clearing the pool and the claimed flags as before, and
     * never charge across periods.
     */
    private static boolean migrateLegacyRewardCosts(JSONObject user, Tables tables, String type,
                                                    long ts) {
        String pointKey = "DAILY".equals(type) ? "dailyPoint" : "weeklyPoint";
        String flagKey = "DAILY".equals(type) ? "dailyCostDebited" : "weeklyCostDebited";
        JSONObject missionData = user.getJSONObject("mission");
        JSONObject rewards = ShopTransactionSupport.object(missionData, "missionRewards");
        // Already migrated: never charge a second time.
        if (rewards.containsKey(flagKey)) return false;
        rewards.put(flagKey, 1);

        JSONObject typed = ShopTransactionSupport.object(
                ShopTransactionSupport.object(rewards, "rewards"), type);
        int due = 0;
        for (String rewardId : typed.keySet()) {
            if (typed.getIntValue(rewardId) != 1) continue;
            if (!activePeriodicReward(tables, rewardId, ts)) continue;
            JSONObject reward = tables.periodicRewards.getJSONObject(rewardId);
            if (reward == null) continue;
            due += reward.getIntValue("periodicalPointCost");
        }
        if (due <= 0) return true;
        int pool = Math.max(0, rewards.getIntValue(pointKey));
        rewards.put(pointKey, Math.max(0, pool - due));
        return true;
    }

    /**
     * A fresh row is active only when nothing gates it. Rows behind a {@code preMissionIds}
     * dependency stay at 1 until the previous row is claimed, which is what official saves show
     * for daily_7002 behind daily_7001 and weekly_702 behind weekly_701.
     */
    private static JSONObject initialMission(JSONObject definition) {
        JSONObject state = new JSONObject(true);
        state.put("progress", initialProgress(definition));
        JSONArray required = definition.getJSONArray("preMissionIds");
        state.put("state", required != null && !required.isEmpty() ? 1 : ACTIVE);
        return state;
    }

    private static JSONArray initialProgress(JSONObject definition) {
        JSONObject progress = new JSONObject(true);
        progress.put("value", 0);
        progress.put("target", missionTarget(definition));
        JSONArray rows = new JSONArray();
        rows.add(progress);
        return rows;
    }

    private static int missionTarget(JSONObject definition) {
        String template = definition.getString("template");
        JSONArray param = definition.getJSONArray("param");
        int type = definition.getIntValue("templateType");
        return switch (template) {
            case "CompleteMainStage" -> param.getIntValue(2);
            case "CompleteStage" -> type == 0 || type == 2
                    ? param.getIntValue(2) : param.getIntValue(1);
            case "CompleteAnyStage" -> 1;
            case "NormalGacha", "SquadFormation", "StageWithAssistChar" -> param.getIntValue(2);
            case "UpgradeSkill" -> type == 0 ? param.getIntValue(1) : 1;
            default -> param.size() > 1 ? param.getIntValue(1) : 1;
        };
    }

    private static long dailyPeriodTs(long ts) {
        return logicalDate(ts).atStartOfDay(GAME_ZONE).plusHours(RESET_HOUR).toEpochSecond();
    }

    private static long weeklyPeriodTs(long ts) {
        LocalDate monday = logicalDate(ts).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.atStartOfDay(GAME_ZONE).plusHours(RESET_HOUR).toEpochSecond();
    }

    private static LocalDate logicalDate(long ts) {
        return Instant.ofEpochSecond(ts).atZone(GAME_ZONE).minusHours(RESET_HOUR).toLocalDate();
    }

    private static JSONObject stages(JSONObject user) {
        JSONObject dungeon = user.getJSONObject("dungeon");
        return dungeon == null ? null : dungeon.getJSONObject("stages");
    }

    private static int rarity(String value) {
        if (value == null || !value.startsWith("TIER_")) return -1;
        try {
            return Integer.parseInt(value.substring("TIER_".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Tables tables(File root) {
        String key = root.getAbsolutePath();
        Tables cached = TABLE_CACHE.get(key);
        if (cached != null) return cached;
        Tables loaded = load(root);
        TABLE_CACHE.put(key, loaded);
        return loaded;
    }

    private static Tables load(File root) {
        JSONObject missionTable = readJson(new File(root, "data/excel/mission_table.json"));
        JSONObject openServerTable = readJson(new File(root, "data/excel/open_server_table.json"));
        JSONObject missions = new JSONObject(true);
        JSONObject groups = new JSONObject(true);
        JSONObject rewards = new JSONObject(true);
        JSONArray schedule = new JSONArray();
        JSONArray dailyPeriods = new JSONArray();
        JSONObject openServerData = new JSONObject(true);
        if (missionTable != null) {
            missions.putAll(missionTable.getJSONObject("missions"));
            groups.putAll(missionTable.getJSONObject("missionGroups"));
            rewards.putAll(missionTable.getJSONObject("periodicalRewards"));
            rewards.putAll(missionTable.getJSONObject("weeklyRewards"));
            JSONArray periods = missionTable.getJSONArray("dailyMissionPeriodInfo");
            if (periods != null) dailyPeriods = periods;
        }
        if (openServerTable != null) {
            schedule = openServerTable.getJSONArray("schedule");
            if (schedule == null) schedule = new JSONArray();
            JSONObject dataMap = openServerTable.getJSONObject("dataMap");
            openServerData.putAll(dataMap);
            for (String groupId : dataMap.keySet()) {
                JSONObject data = dataMap.getJSONObject(groupId);
                JSONArray missionList = data.getJSONArray("openServerMissionData");
                for (int i = 0; i < missionList.size(); i++) {
                    JSONObject mission = missionList.getJSONObject(i);
                    missions.put(mission.getString("id"), mission);
                }
                JSONObject group = data.getJSONObject("openServerMissionGroup");
                if (group != null) groups.put(group.getString("id"), group);
            }
        }
        JSONObject favor = readJson(new File(root, "data/excel/favor_table.json"));
        return new Tables(missions, groups, rewards, schedule, openServerData, dailyPeriods,
                favor.getJSONArray("favorFrames"));
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return new JSONObject(true);
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
             com.alibaba.fastjson.JSONReader jsonReader =
                     new com.alibaba.fastjson.JSONReader(reader, com.alibaba.fastjson.parser.Feature.OrderedField)) {
            return jsonReader.readObject(JSONObject.class);
        } catch (IOException | RuntimeException ignored) {
            return new JSONObject(true);
        }
    }

    private static final class Tables {
        final JSONObject missions;
        final JSONObject groups;
        final JSONObject periodicRewards;
        final JSONArray openServerSchedule;
        final JSONObject openServerData;
        final JSONArray dailyPeriods;
        final JSONArray favorFrames;

        Tables(JSONObject missions, JSONObject groups, JSONObject periodicRewards,
               JSONArray openServerSchedule, JSONObject openServerData, JSONArray dailyPeriods, JSONArray favorFrames) {
            this.missions = missions;
            this.groups = groups;
            this.periodicRewards = periodicRewards;
            this.openServerSchedule = openServerSchedule;
            this.openServerData = openServerData;
            this.dailyPeriods = dailyPeriods;
            this.favorFrames = favorFrames;
        }

        Set<String> activeOpenServerMissionIds(long ts) {
            Set<String> result = new java.util.HashSet<>();
            for (int i = 0; i < openServerSchedule.size(); i++) {
                JSONObject window = openServerSchedule.getJSONObject(i);
                if (ts < window.getLongValue("startTs") || ts > window.getLongValue("endTs")) continue;
                JSONObject data = openServerData.getJSONObject(window.getString("id"));
                JSONArray missionList = data == null ? null : data.getJSONArray("openServerMissionData");
                if (missionList == null) continue;
                for (int n = 0; n < missionList.size(); n++) {
                    result.add(missionList.getJSONObject(n).getString("id"));
                }
            }
            return result;
        }

        Set<String> activeDailyMissionIds(long ts) {
            JSONObject period = dailyPeriod(ts);
            JSONObject group = period == null ? null : groups.getJSONObject(period.getString("missionGroupId"));
            return group == null ? Set.of() : missionIds(group.getJSONArray("missionIds"));
        }

        JSONObject dailyPeriod(long ts) {
            LocalDate date = logicalDate(ts);
            int day = date.getDayOfWeek().getValue();
            for (int i = 0; i < dailyPeriods.size(); i++) {
                JSONObject window = dailyPeriods.getJSONObject(i);
                if (ts < window.getLongValue("startTime") || ts > window.getLongValue("endTime")) continue;
                JSONArray periods = window.getJSONArray("periodList");
                for (int n = 0; n < periods.size(); n++) {
                    JSONObject period = periods.getJSONObject(n);
                    if (!period.getJSONArray("period").contains(day)) continue;
                    return period;
                }
            }
            return null;
        }

        Set<String> activeWeeklyMissionIds(long ts) {
            Set<String> result = new java.util.HashSet<>();
            for (String groupId : groups.keySet()) {
                JSONObject group = groups.getJSONObject(groupId);
                if (!"WEEKLY".equals(group.getString("type"))
                        || ts < group.getLongValue("startTs") || ts > group.getLongValue("endTs")) {
                    continue;
                }
                result.addAll(missionIds(group.getJSONArray("missionIds")));
            }
            return result;
        }

        private static Set<String> missionIds(JSONArray rows) {
            Set<String> result = new java.util.HashSet<>();
            for (int i = 0; i < rows.size(); i++) result.add(rows.getString(i));
            return result;
        }
    }
}
