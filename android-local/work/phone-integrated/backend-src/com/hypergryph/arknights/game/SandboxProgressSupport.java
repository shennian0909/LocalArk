package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One story-map V3 progression lane backed by the client's sandbox tables. */
final class SandboxProgressSupport {
    private static final String RECEIPT = "_localSandboxV3Battle";
    private static final int BAND_SELECT = 0;
    private static final int INIT_GAP = 1;
    private static final int IN_BATTLE = 2;
    private static final int NORM_GAP = 5;
    private static final int GAME_FINISH = 6;
    private static final int NODE_NOT_COMPLETE = 1;
    private static final int NODE_COMPLETED = 2;
    private static final int PLAYER_REPO = 0;

    private SandboxProgressSupport() { }

    static JSONObject createGame(JSONObject user, String topicId, String nodeId,
                                 String difficultyId, long now) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject table = table();
        JSONObject node = requireNode(table, topicId, nodeId);
        refreshProgress(topic.state, topicTable(table, topicId));
        if (!topic.state.getJSONObject("map").getJSONObject("node").containsKey(nodeId))
            throw new IllegalArgumentException("sandbox node is locked");
        String nodeType = node.getString("nodeType");
        if ("EXPLORE".equals(nodeType) && difficultyId == null)
            throw new IllegalArgumentException("difficultyId is required for EXPLORE nodes");
        if ("HOME".equals(nodeType))
            throw new IllegalArgumentException("HOME uses the basement entry, not createGame");
        if (!"STORY".equals(nodeType))
            throw new IllegalArgumentException("only STORY stages are supported");
        // STORY omits this request field, but the native random-task pool requires a key.
        if (difficultyId == null && "STORY".equals(nodeType))
            difficultyId = defaultDifficulty(topicTable(table, topicId));
        if (difficultyId != null) {
            JSONObject difficulties = topic.state.getJSONObject("dungeon")
                    .getJSONObject("difficulty");
            JSONObject difficulty = difficulties == null ? null : difficulties.getJSONObject(difficultyId);
            if (difficulty == null || difficulty.getIntValue("state") == 0)
                throw new IllegalArgumentException("sandbox difficulty is locked");
        }
        JSONObject current = topic.state.getJSONObject("current");
        if (current != null) {
            JSONObject game = current.getJSONObject("game");
            if (nodeId.equals(current.getString("nodeId"))
                    && java.util.Objects.equals(difficultyId,
                            game == null ? null : game.getString("difficultyId")))
                return SandboxPermSupport.result(user);
            throw new IllegalArgumentException("a different sandbox run is already active");
        }
        if (topic.summary.getBooleanValue("inCurrent"))
            throw new IllegalArgumentException("sandbox summary is already in a run");

        ensureModeId(topic.state, table, topicId);
        String stageId = required(node.getString("stageId"), "sandbox node stage");
        JSONObject stage = requireStage(table, topicId, stageId);
        JSONObject map = storyMap(table, topicId, stageId);
        current = new JSONObject(true);
        current.put("nodeId", nodeId);
        current.put("state", BAND_SELECT);
        current.put("game", initialGame(stage, difficultyId, now, topicId, table));
        current.put("map", map);
        current.put("band", objectWith("id", "", "level", 0));
        current.put("troop", emptyTroop(stage, table, topicId));
        current.put("shop", emptyShop());
        current.put("dailyReport", null);
        current.put("bag", initialBag(stage, table));
        current.put("event", null);
        current.put("effect", emptyEffect());
        // A missing first snapshot lets the client initialize resources from each level.
        current.put("save", null);
        topic.state.put("current", current);
        topic.summary.put("inCurrent", true);
        JSONObject progress = nodeState(topic.state, nodeId);
        if (progress.getIntValue("state") != NODE_COMPLETED)
            progress.put("state", NODE_NOT_COMPLETE);
        return SandboxPermSupport.result(user);
    }

    static JSONObject chooseBand(JSONObject user, String topicId, String bandId) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, BAND_SELECT, "choose a band");
        JSONObject node = requireNode(table(), topicId, current.getString("nodeId"));
        JSONArray allowed = table().getJSONObject("detail").getJSONObject("SANDBOX_V3")
                .getJSONObject(topicId).getJSONObject("modeType2BandIdListMap")
                .getJSONArray(node.getString("nodeType"));
        if (allowed == null || !allowed.contains(bandId))
            throw new IllegalArgumentException("band is not available for this node");
        JSONObject unlocked = topic.state.getJSONObject("band").getJSONObject(bandId);
        if (unlocked == null || unlocked.getIntValue("level") <= 0)
            throw new IllegalArgumentException("band is not unlocked");
        JSONObject band = current.getJSONObject("band");
        band.put("id", bandId);
        band.put("level", unlocked.getIntValue("level"));
        current.put("state", INIT_GAP);
        return SandboxPermSupport.result(user);
    }

    static JSONObject switchMode(JSONObject user, String topicId, String modeId) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject modes = topicTable(table(), topicId).getJSONObject("modeDatas");
        if (modes == null || !modes.containsKey(modeId))
            throw new IllegalArgumentException("unknown sandbox game mode");
        JSONObject current = topic.state.getJSONObject("current");
        if (current != null && current.getIntValue("state") == IN_BATTLE)
            throw new IllegalArgumentException("cannot switch sandbox mode in battle");
        modeState(topic.state).put("modeId", modeId);
        return SandboxPermSupport.result(user);
    }

    static JSONObject initRecruit(JSONObject user, String topicId, JSONObject body) {
        Object assist = body.get("assistFriend");
        JSONArray requested = body.getJSONArray("ownChars");
        if (requested == null || requested.isEmpty())
            throw new IllegalArgumentException("ownChars must not be empty");
        return recruit(user, topicId, requested, assist);
    }

    static JSONObject getDailyRecruitList(JSONObject user, String topicId) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, INIT_GAP, "get the daily recruit list");

        JSONObject result = SandboxPermSupport.result(user);
        result.put("subProfessionList", ownedSubProfessions(user));
        result.put("tempCharList", new JSONArray());
        result.put("rookieCharList", new JSONArray());
        return result;
    }

    static JSONObject dailyRecruit(JSONObject user, String topicId, JSONObject body) {
        Object thirdChar = body.get("thirdChar");
        if (thirdChar != null)
            throw new IllegalArgumentException("thirdChar is not supported");
        Object ownChar = body.get("ownChar");
        if (!(ownChar instanceof JSONObject))
            throw new IllegalArgumentException("ownChar is required");
        JSONArray requested = new JSONArray();
        requested.add(ownChar);
        return recruit(user, topicId, requested, null);
    }

    private static JSONObject recruit(JSONObject user, String topicId,
                                      JSONArray requested, Object assist) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, INIT_GAP, "recruit sandbox characters");
        if (assist instanceof JSONObject && !((JSONObject) assist).isEmpty())
            throw new IllegalArgumentException("assistFriend is not supported");
        if (assist != null && !(assist instanceof JSONObject))
            throw new IllegalArgumentException("invalid assistFriend");

        JSONObject table = table();
        JSONObject stage = requireStage(table, topicId, currentStage(topic, current));
        JSONObject troop = current.getJSONObject("troop");
        JSONObject existing = troop.getJSONObject("chars");
        int limit = existing.isEmpty()
                ? Math.min(stage.getIntValue("initialRecruitNum"),
                        stage.getIntValue("squadMax"))
                : Math.min(troop.getIntValue("canRecruit"),
                        stage.getIntValue("dayPassRecruitNum"));
        if (limit <= 0 || requested.size() > limit
                || existing.size() + requested.size() > stage.getIntValue("squadMax"))
            throw new IllegalArgumentException("sandbox recruit limit exceeded");

        JSONObject ownedChars = user.getJSONObject("troop").getJSONObject("chars");
        JSONObject sandboxChars = troop.getJSONObject("chars");
        JSONArray slots = troop.getJSONArray("slots");
        JSONArray currentRecruit = troop.getJSONArray("currentRecruit");
        Set<Integer> seen = new HashSet<>();
        int nextLocalInstId = 1;
        for (String key : existing.keySet())
            nextLocalInstId = Math.max(nextLocalInstId,
                    existing.getJSONObject(key).getIntValue("instId") + 1);
        for (int i = 0; i < requested.size(); i++) {
            JSONObject request = requested.getJSONObject(i);
            if (request == null) throw new IllegalArgumentException("invalid ownChars entry");
            int sourceInstId = integer(request, "charInstId");
            if (sourceInstId <= 0 || !seen.add(sourceInstId))
                throw new IllegalArgumentException("duplicate or invalid charInstId");
            JSONObject owned = ownedChars.getJSONObject(String.valueOf(sourceInstId));
            if (owned == null) throw new IllegalArgumentException("sandbox character is not owned");
            for (String key : existing.keySet()) {
                if (owned.getString("charId").equals(
                        existing.getJSONObject(key).getString("charId")))
                    throw new IllegalArgumentException("sandbox character is already recruited");
            }
            int skillIndex = integer(request, "skillIndex");
            requireSkill(owned, skillIndex);
            Object rawEquip = request.get("currentEquip");
            if (rawEquip != null && !(rawEquip instanceof String))
                throw new IllegalArgumentException("invalid currentEquip");
            String equip = (String) rawEquip;
            requireEquip(owned, equip);

            int localInstId = nextLocalInstId + i;
            JSONObject slot = objectWith("charInstId", localInstId,
                    "skillIndex", skillIndex, "currentEquip", equip);
            slots.add(slot);
            JSONObject local = copy(owned);
            JSONArray runtimeSkills = new JSONArray();
            for (Object value : local.getJSONArray("skills")) {
                JSONObject sourceSkill = (JSONObject) value;
                JSONObject runtimeSkill = new JSONObject(true);
                for (String key : new String[]{"unlock", "skillId", "specializeLevel"})
                    if (sourceSkill.containsKey(key)) runtimeSkill.put(key, sourceSkill.get(key));
                runtimeSkills.add(runtimeSkill);
            }
            local.put("skills", runtimeSkills);
            local.put("instId", localInstId);
            local.put("charType", PLAYER_REPO);
            sandboxChars.put(String.valueOf(localInstId), local);
            currentRecruit.add(String.valueOf(localInstId));
        }
        troop.put("canRecruit", Math.max(0, troop.getIntValue("canRecruit") - requested.size()));
        return SandboxPermSupport.result(user);
    }

    static JSONObject battleStart(JSONObject user, String topicId) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, INIT_GAP, "start a sandbox battle");
        ensureModeId(topic.state, table(), topicId);
        String nodeId = current.getString("nodeId");
        String stageId = currentStage(topic, current);
        JSONObject troop = current.getJSONObject("troop");
        if (troop.getJSONObject("chars").isEmpty())
            throw new IllegalArgumentException("sandbox squad is not recruited");
        JSONObject old = user.getJSONObject(RECEIPT);
        if (old != null && !old.getBooleanValue("settled"))
            throw new IllegalArgumentException("sandbox battle is already pending");

        JSONObject receipt = new JSONObject(true);
        receipt.put("battleId", UUID.randomUUID().toString());
        receipt.put("topicId", topicId);
        receipt.put("nodeId", nodeId);
        receipt.put("stageId", stageId);
        receipt.put("settled", false);
        user.put(RECEIPT, receipt);
        troop.put("currentRecruit", new JSONArray());
        troop.put("canRecruit", 0);
        current.put("state", IN_BATTLE);

        JSONObject result = SandboxPermSupport.result(user);
        result.put("battleId", receipt.getString("battleId"));
        result.put("battleShopId", battleShopId(table(), topicId, stageId));
        return result;
    }

    static JSONObject battleFinish(JSONObject user, String topicId, JSONObject body) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, IN_BATTLE, "finish a sandbox battle");
        JSONObject receipt = user.getJSONObject(RECEIPT);
        // Utils throws on inputs shorter than the IV suffix instead of returning null.
        JSONObject decoded;
        try {
            decoded = Utils.BattleData_decrypt(body.getString("data"),
                    user.getJSONObject("pushFlags").getString("status"));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("invalid sandbox battle data");
        }
        if (decoded == null || receipt == null || receipt.getBooleanValue("settled")
                || !topicId.equals(receipt.getString("topicId"))
                || !receipt.getString("battleId").equals(decoded.getString("battleId")))
            throw new IllegalArgumentException("unknown, changed or already settled sandbox battle");
        int completeState = decoded.getIntValue("completeState");
        if (completeState < 1 || completeState > 4)
            throw new IllegalArgumentException("invalid sandbox battle result");
        if (!receipt.getString("nodeId").equals(current.getString("nodeId"))
                || !receipt.getString("stageId").equals(currentStage(topic, current)))
            throw new IllegalArgumentException("sandbox battle no longer matches the active run");

        JSONObject sandboxData = body.getJSONObject("sandboxV3Data");
        JSONObject save = sandboxData.getJSONObject("saveData");
        boolean gaveUp = sandboxData.getIntValue("giveUp") != 0;
        if (save != null) {
            requireSave(save);
            JSONObject table = table();
            current.put("save", persistedSave(save));
            current.put("bag", bagFromSave(save, table));
            JSONObject game = current.getJSONObject("game");
            game.put("day", save.getIntValue("day"));
            game.put("power", save.getIntValue("powerValue"));
            game.put("pros", save.getIntValue("prosperity"));
            game.put("aesth", save.getIntValue("aesthetics"));
            JSONArray unlock = save.getJSONArray("unlockIndex");
            if (unlock != null) current.getJSONObject("map").put("unlockIndex", copy(unlock));
        } else if (!gaveUp) {
            requireSave(null);
        }
        boolean completed = !gaveUp
                && (completeState == 2 || completeState == 3);
        receipt.put("completed", completed);
        if (gaveUp) current.getJSONObject("game").put("success", false);
        current.put("state", gaveUp ? GAME_FINISH : NORM_GAP);
        receipt.put("settled", true);
        return finishResult(user);
    }

    static JSONObject nextDay(JSONObject user, String topicId) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, NORM_GAP, "advance the sandbox day");
        JSONObject stage = requireStage(table(), topicId, currentStage(topic, current));

        JSONObject game = current.getJSONObject("game");
        JSONObject save = current.getJSONObject("save");
        int day = game.getIntValue("day");
        int maxDay = stage.getIntValue("maxDay");
        if (day > maxDay)
            throw new IllegalArgumentException("sandbox day exceeds the stage limit");
        if (day == maxDay) {
            current.put("state", GAME_FINISH);
            return SandboxPermSupport.result(user);
        }
        int nextDay = day + 1;
        game.put("day", nextDay);
        save.put("day", nextDay);
        JSONObject troop = current.getJSONObject("troop");
        troop.put("canRecruit", Math.max(0, Math.min(
                stage.getIntValue("dayPassRecruitNum"),
                stage.getIntValue("squadMax") - troop.getJSONObject("chars").size())));
        JSONArray currentRecruit = new JSONArray();
        for (String instId : troop.getJSONObject("chars").keySet())
            currentRecruit.add(instId);
        troop.put("currentRecruit", currentRecruit);
        current.put("state", INIT_GAP);
        return SandboxPermSupport.result(user);
    }

    static JSONObject giveUpGame(JSONObject user, String topicId) {
        TopicRun topic = requireTopic(user, topicId);
        requireCurrent(topic);
        user.remove(RECEIPT);
        topic.state.put("current", null);
        topic.summary.put("inCurrent", false);
        return SandboxPermSupport.result(user);
    }

    static JSONObject settleGame(JSONObject user, String topicId, long now) {
        TopicRun topic = requireTopic(user, topicId);
        JSONObject current = requireCurrent(topic);
        requireState(current, GAME_FINISH, "settle the sandbox game");

        JSONObject game = current.getJSONObject("game");
        JSONObject troop = current.getJSONObject("troop");
        JSONObject bag = current.getJSONObject("bag");
        JSONObject stage = requireStage(table(), topicId, currentStage(topic, current));

        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt != null && receipt.getBooleanValue("completed")
                && game.getIntValue("power") >= stage.getIntValue("targetPowerValue")) {
            nodeState(topic.state, current.getString("nodeId")).put("state", NODE_COMPLETED);
            String difficultyId = game.getString("difficultyId");
            JSONObject node = requireNode(table(), topicId, current.getString("nodeId"));
            if ("EXPLORE".equals(node.getString("nodeType")) && difficultyId != null)
                topic.state.getJSONObject("dungeon").getJSONObject("difficulty")
                        .getJSONObject(difficultyId).put("state", 2);
        }
        refreshProgress(topic.state, topicTable(table(), topicId));

        JSONObject score = new JSONObject(true);
        score.put("power", game.getIntValue("power"));
        score.put("powerScore", 0);
        score.put("quest", new JSONObject(true));
        score.put("enemy", new JSONObject(true));
        score.put("difficultyId", game.getString("difficultyId"));
        score.put("scoreFactor", 0.0);
        score.put("totalScore", 0);

        JSONObject reward = new JSONObject(true);
        reward.put("first", new JSONArray());
        reward.put("score", new JSONArray());
        reward.put("extra", new JSONArray());
        reward.put("shopCoinMax", false);

        JSONObject detail = new JSONObject(true);
        detail.put("nodeId", current.getString("nodeId"));
        detail.put("bandId", current.getJSONObject("band").getString("id"));
        detail.put("settleTs", now);
        detail.put("duration", (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, now - game.getLongValue("openTs"))));
        detail.put("gameDay", game.getIntValue("day"));
        detail.put("weatherList", new JSONArray());
        detail.put("allSubStage", false);
        detail.put("slots", copy(troop.getJSONArray("slots")));
        detail.put("chars", copy(troop.getJSONObject("chars")));
        detail.put("power", game.getIntValue("power"));
        detail.put("powerTarget", stage.getIntValue("targetPowerValue"));
        detail.put("aesth", game.getIntValue("aesth"));
        detail.put("pros", game.getIntValue("pros"));
        detail.put("material", copy(bag.getJSONObject("material")));
        detail.put("relic", copy(bag.getJSONArray("relic")));
        detail.put("recipe", copy(bag.getJSONArray("recipe")));

        user.remove(RECEIPT);
        topic.state.put("current", null);
        topic.summary.put("inCurrent", false);

        JSONObject result = SandboxPermSupport.result(user);
        result.put("score", score);
        result.put("reward", reward);
        result.put("detail", detail);
        return result;
    }

    private static TopicRun requireTopic(JSONObject user, String topicId) {
        JSONObject root = SandboxPermSupport.requiredRoot(user);
        SandboxPermSupport.requireEnterableTopic(root, topicId);
        if (!topicId.equals(root.getString("topic")))
            throw new IllegalArgumentException("sandbox topic has not been selected");
        JSONObject state = root.getJSONObject("template").getJSONObject("SANDBOX_V3")
                .getJSONObject(topicId);
        JSONObject summary = root.getJSONObject("summary").getJSONObject("SANDBOX_V3")
                .getJSONObject(topicId);
        if (state == null || summary == null)
            throw new IllegalArgumentException("sandbox V3 state is not initialized");
        return new TopicRun(topicId, state, summary);
    }

    static void refreshHomeProgress(JSONObject user, String topicId) {
        TopicRun topic = requireTopic(user, topicId);
        refreshProgress(topic.state, topicTable(table(), topicId));
    }

    static boolean normalizeStoryDifficulty(JSONObject user) {
        JSONObject root = user.getJSONObject("sandboxPerm");
        JSONObject templates = root == null ? null : root.getJSONObject("template");
        JSONObject topics = templates == null ? null : templates.getJSONObject("SANDBOX_V3");
        if (topics == null) return false;
        boolean changed = false;
        for (String topicId : topics.keySet()) {
            JSONObject state = topics.getJSONObject(topicId);
            JSONObject current = state == null ? null : state.getJSONObject("current");
            JSONObject game = current == null ? null : current.getJSONObject("game");
            if (game == null || game.getString("difficultyId") != null) continue;
            JSONObject data = topicTable(table(), topicId);
            JSONObject node = data.getJSONObject("mainMapData").getJSONObject("nodes")
                    .getJSONObject(current.getString("nodeId"));
            if (node != null && "STORY".equals(node.getString("nodeType"))) {
                game.put("difficultyId", defaultDifficulty(data));
                changed = true;
            }
        }
        return changed;
    }

    private static String defaultDifficulty(JSONObject data) {
        return required(data.getJSONObject("gameConst").getString("defaultDifficultyId"),
                "default sandbox difficulty");
    }

    private static void refreshProgress(JSONObject state, JSONObject data) {
        JSONObject map = state.getJSONObject("map");
        JSONObject nodes = map.getJSONObject("node");
        JSONObject zones = map.getJSONObject("zone");
        JSONObject catalog = data.getJSONObject("mainMapData").getJSONObject("nodes");
        for (String id : catalog.keySet()) {
            JSONObject node = catalog.getJSONObject(id);
            JSONObject previous = nodes.getJSONObject(node.getString("frontNodeId"));
            if (previous == null || previous.getIntValue("state") != NODE_COMPLETED) continue;
            if (!nodes.containsKey(id)) nodes.put(id, objectWith("state", 0));
            String zoneId = node.getString("zoneId");
            if (!zones.containsKey(zoneId))
                zones.put(zoneId, objectWith("defend", objectWith("main", -1, "sub", new JSONArray())));
        }

        JSONObject difficulties = state.getJSONObject("dungeon").getJSONObject("difficulty");
        JSONObject definitions = data.getJSONObject("exploreStageDifficultyData");
        // These are the six difficulty conditions shipped for sandbox_2, not the
        // maximum difficulty offered by each individual exploration node.
        String[] requiredZones = {null, "z_2_2", "z_2_2", "z_2_3", "z_2_3", "z_2_4"};
        for (String id : definitions.keySet()) {
            int level = definitions.getJSONObject(id).getIntValue("difficultyLevel");
            JSONObject difficulty = difficulties.getJSONObject(id);
            if (difficulty.getIntValue("state") == 2 || level == 0) continue;
            boolean passedPrevious = false;
            for (String previousId : definitions.keySet()) {
                if (definitions.getJSONObject(previousId).getIntValue("difficultyLevel") == level - 1
                        && difficulties.getJSONObject(previousId).getIntValue("state") == 2)
                    passedPrevious = true;
            }
            boolean zoneUnlocked = level < requiredZones.length && zones.containsKey(requiredZones[level]);
            JSONArray conditions = new JSONArray();
            conditions.add(passedPrevious);
            conditions.add(zoneUnlocked);
            difficulty.put("cond", conditions);
            if (passedPrevious && zoneUnlocked) difficulty.put("state", 1);
        }
    }

    private static JSONObject requireCurrent(TopicRun topic) {
        JSONObject current = topic.state.getJSONObject("current");
        if (current == null || !topic.summary.getBooleanValue("inCurrent"))
            throw new IllegalArgumentException("no active sandbox run");
        return current;
    }

    private static JSONObject modeState(JSONObject topicState) {
        JSONObject game = topicState.getJSONObject("game");
        if (game == null) {
            game = new JSONObject(true);
            topicState.put("game", game);
        }
        return game;
    }

    private static String ensureModeId(JSONObject topicState, JSONObject table, String topicId) {
        JSONObject game = modeState(topicState);
        String modeId = game.getString("modeId");
        if (modeId == null || modeId.isEmpty()) {
            modeId = required(topicTable(table, topicId).getJSONObject("gameConst")
                    .getString("defaultModeId"), "default sandbox game mode");
        }
        JSONObject modes = topicTable(table, topicId).getJSONObject("modeDatas");
        if (modes == null || !modes.containsKey(modeId))
            throw new IllegalArgumentException("invalid sandbox game mode");
        game.put("modeId", modeId);
        return modeId;
    }

    private static void requireState(JSONObject current, int expected, String action) {
        if (current.getIntValue("state") != expected)
            throw new IllegalArgumentException("cannot " + action + " in the current sandbox state");
    }

    private static JSONObject table() {
        return CharacterBuildingSupport.table("excel/sandbox_perm_table.json");
    }

    private static JSONObject topicTable(JSONObject table, String topicId) {
        JSONObject details = table.getJSONObject("detail");
        JSONObject kinds = details == null ? null : details.getJSONObject("SANDBOX_V3");
        JSONObject topic = kinds == null ? null : kinds.getJSONObject(topicId);
        if (topic == null) throw new IllegalArgumentException("sandbox topic tables are unavailable");
        return topic;
    }

    private static JSONArray ownedSubProfessions(JSONObject user) {
        JSONObject ownedChars = user.getJSONObject("troop").getJSONObject("chars");
        JSONObject characterTable = CharacterBuildingSupport.table("excel/character_table.json");
        Set<String> subProfessions = new LinkedHashSet<>();
        for (String key : ownedChars.keySet()) {
            JSONObject owned = ownedChars.getJSONObject(key);
            JSONObject character = owned == null ? null
                    : characterTable.getJSONObject(owned.getString("charId"));
            String subProfession = character == null
                    ? null : character.getString("subProfessionId");
            if (subProfession != null && !subProfession.isEmpty())
                subProfessions.add(subProfession);
        }
        return new JSONArray(new java.util.ArrayList<>(subProfessions));
    }

    private static JSONObject requireNode(JSONObject table, String topicId, String nodeId) {
        JSONObject data = topicTable(table, topicId);
        JSONObject map = data.getJSONObject("mainMapData");
        JSONObject node = map == null || map.getJSONObject("nodes") == null
                ? null : map.getJSONObject("nodes").getJSONObject(nodeId);
        if (node == null) throw new IllegalArgumentException("unknown sandbox node");
        return node;
    }

    private static JSONObject requireStage(JSONObject table, String topicId, String stageId) {
        JSONObject stage = topicTable(table, topicId).getJSONObject("stageData")
                .getJSONObject(stageId);
        if (stage == null) throw new IllegalArgumentException("sandbox stage is unavailable");
        return stage;
    }

    private static String currentStage(TopicRun topic, JSONObject current) {
        return requireNode(table(), topic.topicId, current.getString("nodeId"))
                .getString("stageId");
    }

    private static JSONObject storyMap(JSONObject table, String topicId, String stageId) {
        JSONObject story = topicTable(table, topicId).getJSONObject("storyStageData")
                .getJSONObject(stageId);
        if (story == null) throw new IllegalArgumentException("only story sandbox maps are supported");
        JSONArray initial = story.getJSONArray("initialSubStageIndexList");
        if (initial == null || initial.isEmpty())
            throw new IllegalArgumentException("sandbox story map has no initial index");
        JSONObject map = new JSONObject(true);
        map.put("subStage", copy(story.getJSONArray("subStageIdList")));
        map.put("unlockIndex", copy(initial));
        map.put("initIndex", copy(initial));
        return map;
    }

    private static JSONObject initialGame(JSONObject stage, String difficultyId, long now,
                                          String topicId, JSONObject table) {
        JSONObject game = new JSONObject(true);
        game.put("idx", 1);
        game.put("openTs", now);
        game.put("difficultyId", difficultyId);
        game.put("npcInstId", -1);
        game.put("day", 1);
        game.put("weather", "weather_normal");
        game.put("windDir", 0);
        game.put("power", 0);
        game.put("pros", 0);
        game.put("aesth", 0);
        return game;
    }

    private static JSONObject emptyTroop(JSONObject stage, JSONObject table, String topicId) {
        JSONObject constants = topicTable(table, topicId).getJSONObject("gameConst");
        JSONObject troop = new JSONObject(true);
        troop.put("slots", new JSONArray());
        troop.put("chars", new JSONObject(true));
        troop.put("food", new JSONObject(true));
        troop.put("maxRecruit", constants.getIntValue("characterLimit"));
        troop.put("canRecruit", stage.getIntValue("initialRecruitNum"));
        troop.put("currentRecruit", new JSONArray());
        troop.put("removeChar", new JSONArray());
        troop.put("defend", objectWith("mainIds", new JSONArray(), "otherIds", new JSONArray()));
        troop.put("refreshPrice", constants.getIntValue("recruitStartPrice"));
        return troop;
    }

    private static JSONObject emptyShop() {
        JSONObject recruit = new JSONObject(true);
        recruit.put("show", false);
        recruit.put("price", 0);
        JSONObject shop = new JSONObject(true);
        shop.put("shopId", "");
        shop.put("slots", new JSONArray());
        shop.put("recruit", recruit);
        shop.put("refreshPrice", 0);
        shop.put("sellPrice", new JSONObject(true));
        shop.put("showBattleShop", false);
        return shop;
    }

    private static JSONObject emptyEffect() {
        JSONObject effect = new JSONObject(true);
        effect.put("rune", new JSONArray());
        effect.put("shopRefreshDiscount", 0);
        effect.put("shopRefreshFree", 0);
        effect.put("shopSlotAdd", 0);
        effect.put("shopStockAdd", new JSONObject(true));
        effect.put("shopDiscountRate", 0);
        effect.put("gapGainItem", new JSONObject(true));
        effect.put("recipeRefreshDiscount", 0);
        effect.put("productAdd", new JSONObject(true));
        effect.put("trapDrop", new JSONObject(true));
        effect.put("buildReturn", new JSONObject(true));
        effect.put("taskRefreshAdd", 0);
        return effect;
    }

    private static JSONObject initialBag(JSONObject stage, JSONObject table) {
        JSONObject bag = emptyBag();
        JSONArray items = stage.getJSONArray("initialItemData");
        if (items == null) return bag;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            addItem(bag, item.getString("itemId"), item.getIntValue("count"), table);
        }
        return bag;
    }

    private static JSONObject emptyBag() {
        JSONObject bag = new JSONObject(true);
        bag.put("coin", new JSONObject(true));
        bag.put("material", new JSONObject(true));
        bag.put("relic", new JSONArray());
        bag.put("recipe", new JSONArray());
        bag.put("trap", new JSONObject(true));
        return bag;
    }

    private static JSONObject bagFromSave(JSONObject save, JSONObject table) {
        JSONArray items = save.getJSONArray("itemSave");
        if (items == null) throw new IllegalArgumentException("saveData.itemSave is required");
        JSONObject bag = emptyBag();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) throw new IllegalArgumentException("invalid saveData.itemSave");
            addItem(bag, item.getString("itemId"), item.getIntValue("cnt"), table);
        }
        return bag;
    }

    private static void addItem(JSONObject bag, String itemId, int count, JSONObject table) {
        if (itemId == null || count <= 0)
            throw new IllegalArgumentException("invalid sandbox item");
        JSONObject item = table.getJSONObject("itemData").getJSONObject(itemId);
        String type = item == null ? null : item.getString("itemType");
        if (type == null) throw new IllegalArgumentException("unknown sandbox item " + itemId);
        switch (type) {
            case "COIN" -> putCount(bag.getJSONObject("coin"), itemId, count);
            case "FOODMAT", "SPECIALMAT", "BUILDINGMAT", "PRODUCT" ->
                    putCount(bag.getJSONObject("material"), itemId, count);
            case "RELIC" -> addUnique(bag.getJSONArray("relic"), itemId);
            case "RECIPE" -> addUnique(bag.getJSONArray("recipe"), itemId);
            case "BUILDING", "ANIMAL", "TACTICAL" ->
                    putCount(bag.getJSONObject("trap"), itemId, count);
            default -> throw new IllegalArgumentException("unsupported sandbox item type " + type);
        }
    }

    private static void putCount(JSONObject target, String id, int count) {
        int total = Math.addExact(target.getIntValue(id), count);
        target.put(id, total);
    }

    private static void addUnique(JSONArray target, String id) {
        if (!target.contains(id)) target.add(id);
    }

    private static void requireSave(JSONObject save) {
        if (save == null) throw new IllegalArgumentException("sandboxV3Data.saveData is required");
        for (String key : new String[]{"roomSave", "itemSave", "day", "powerValue",
                "prosperity", "aesthetics"}) {
            if (!save.containsKey(key))
                throw new IllegalArgumentException("saveData." + key + " is required");
        }
        if (!(save.get("roomSave") instanceof JSONArray)
                || !(save.get("itemSave") instanceof JSONArray))
            throw new IllegalArgumentException("saveData arrays are malformed");
        if (save.getIntValue("day") < 1 || save.getIntValue("powerValue") < 0
                || save.getIntValue("prosperity") < 0 || save.getIntValue("aesthetics") < 0)
            throw new IllegalArgumentException("saveData counters are invalid");
    }

    private static JSONObject persistedSave(JSONObject source) {
        JSONObject save = new JSONObject(true);
        for (String key : new String[]{"charSaves", "resSaves", "predefineLikeSave",
                "roomSave", "enemyDeathSave", "importantEnemySave", "processorSaves",
                "levelRandomSaves", "animalSaves", "powerValue", "decimalPowerValue",
                "day", "milestoneSave", "taskSave", "summonEnemiesSaves", "relicSaves",
                "serviceSaves", "showedPredefinedAlias", "prosperity", "aesthetics",
                "enemyDeathCountByLevel", "enemyDeathCountByTag", "shopEntryPos"}) {
            if (source.containsKey(key)) save.put(key, copy(source.get(key)));
        }
        return save;
    }

    private static void requireSkill(JSONObject owned, int index) {
        JSONArray skills = owned.getJSONArray("skills");
        if (skills == null || index < 0 || index >= skills.size())
            throw new IllegalArgumentException("invalid sandbox skill");
        JSONObject skill = skills.getJSONObject(index);
        if (skill == null || skill.getIntValue("unlock") != 1)
            throw new IllegalArgumentException("sandbox skill is locked");
    }

    private static void requireEquip(JSONObject owned, String equip) {
        if (equip == null || equip.isEmpty()) return;
        JSONObject equips = owned.getJSONObject("equip");
        JSONObject module = equips == null ? null : equips.getJSONObject(equip);
        if (module == null || module.getIntValue("locked") != 0
                || module.getIntValue("level") < 1)
            throw new IllegalArgumentException("sandbox equipment is locked");
    }

    private static int integer(JSONObject value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Number))
            throw new IllegalArgumentException("invalid " + key);
        double number = ((Number) raw).doubleValue();
        int result = ((Number) raw).intValue();
        if (number != result) throw new IllegalArgumentException("invalid " + key);
        return result;
    }

    private static JSONObject nodeState(JSONObject state, String nodeId) {
        JSONObject nodes = state.getJSONObject("map").getJSONObject("node");
        JSONObject node = nodes.getJSONObject(nodeId);
        if (node == null) {
            node = new JSONObject(true);
            node.put("state", 0);
            nodes.put(nodeId, node);
        }
        return node;
    }

    private static String battleShopId(JSONObject table, String topicId, String stageId) {
        JSONArray shops = topicTable(table, topicId).getJSONObject("stageShopListData")
                .getJSONArray(stageId);
        if (shops == null) return "";
        for (int i = 0; i < shops.size(); i++) {
            JSONObject shop = shops.getJSONObject(i);
            if ("BATTLE".equals(shop.getString("shopType"))) {
                return required(shop.getString("shopId"), "battle shop");
            }
        }
        return "";
    }

    private static JSONObject finishResult(JSONObject user) {
        JSONObject result = SandboxPermSupport.result(user);
        result.put("apFailReturn", 0);
        for (String key : new String[]{"itemReturn", "rewards", "unusualRewards",
                "overrideRewards", "additionalRewards", "diamondMaterialRewards",
                "furnitureRewards"})
            result.put(key, new JSONArray());
        return result;
    }

    private static JSONObject objectWith(Object... values) {
        JSONObject result = new JSONObject(true);
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static JSONObject copy(JSONObject value) {
        return value == null ? null : JSON.parseObject(value.toJSONString());
    }

    private static JSONArray copy(JSONArray value) {
        return value == null ? null : JSON.parseArray(value.toJSONString());
    }

    private static Object copy(Object value) {
        if (value instanceof JSONObject) return copy((JSONObject) value);
        if (value instanceof JSONArray) return copy((JSONArray) value);
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException(label + " is unavailable");
        return value;
    }

    private record TopicRun(String topicId, JSONObject state, JSONObject summary) { }
}
