package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import java.util.UUID;

/** Single-stage training only. Normal towers require their separate card/recruitment rules. */
final class TowerTrainingSupport {
    private static final String RECEIPT = "_localTowerTrainingBattle";
    private TowerTrainingSupport() {}

    static JSONObject create(JSONObject user, JSONObject body) {
        if (body == null) throw new IllegalArgumentException("Missing tower request");
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        String requestedId = body.getString("tower");
        if (TowerNormalSupport.supports(table, requestedId)) return TowerNormalSupport.create(user, body);
        Object difficulty = body.get("isHard");
        boolean hard;
        if (difficulty == null || Boolean.FALSE.equals(difficulty) || "0".equals(difficulty)
                || (difficulty instanceof Number && ((Number) difficulty).doubleValue() == 0D)) hard = false;
        else if (Boolean.TRUE.equals(difficulty) || "1".equals(difficulty)
                || (difficulty instanceof Number && ((Number) difficulty).doubleValue() == 1D)) hard = true;
        else throw new IllegalArgumentException("Invalid tower difficulty");
        String id = body.getString("tower");
        JSONObject definition = trainingDefinition(table, id);
        if (hard) throw new IllegalArgumentException("Training has no hard mode");
        String unlockId = table.getJSONObject("detailConst").getString("unlockLevelId");
        JSONObject clear = user.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(unlockId);
        if (clear == null || clear.getIntValue("state") < 2) throw new IllegalArgumentException("Tower training is locked");
        JSONObject current = normalize(user);
        JSONObject tower = user.getJSONObject("tower");
        JSONObject status = current.getJSONObject("status");
        if (!"NONE".equals(status.getString("state"))) throw new IllegalArgumentException("Settle the current tower first");
        String previous = definition.getString("preTowerId");
        if (previous != null && tower.getJSONObject("outer").getJSONObject("training").getIntValue(previous) < 1)
            throw new IllegalArgumentException("Previous tower training is not cleared");
        current = emptyCurrent();
        status = current.getJSONObject("status");
        status.put("state", "STANDBY");
        status.put("tower", id);
        status.put("start", System.currentTimeMillis() / 1000L);
        JSONArray levels = definition.getJSONArray("levels");
        for (int i = 0; i < levels.size(); i++) {
            JSONObject layer = new JSONObject(true);
            layer.put("id", levels.getString(i));
            layer.put("try", 0);
            layer.put("pass", false);
            current.getJSONArray("layer").add(layer);
        }
        tower.put("current", current);
        return result(user);
    }

    static JSONObject initGodCard(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.initGodCard(user, body);
        throw new IllegalArgumentException("God card selection is not pending");
    }

    static JSONObject chooseSubGodCard(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.chooseSubGodCard(user, body);
        throw new IllegalArgumentException("Sub god card selection is not pending");
    }

    static JSONObject initGame(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.initGame(user, body);
        throw new IllegalArgumentException("Tower game initialization is not pending");
    }

    static JSONObject initCard(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.initCard(user, body);
        throw new IllegalArgumentException("Tower card initialization is not pending");
    }

    static JSONObject start(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.start(user, body);
        if (body == null) throw new IllegalArgumentException("Missing tower battle request");
        JSONObject current = normalize(user);
        JSONObject status = current.getJSONObject("status");
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        trainingDefinition(table, status.getString("tower"));
        if (!"STANDBY".equals(status.getString("state"))) throw new IllegalArgumentException("Tower training is not ready");
        JSONObject layer = current.getJSONArray("layer").getJSONObject(0);
        if (!layer.getString("id").equals(body.getString("stageId")) || layer.getBooleanValue("pass"))
            throw new IllegalArgumentException("Wrong training stage");
        JSONObject old = user.getJSONObject(RECEIPT);
        if (old != null && !old.getBooleanValue("settled")) throw new IllegalArgumentException("Tower battle is already pending");
        JSONObject receipt = new JSONObject(true);
        receipt.put("battleId", UUID.randomUUID().toString());
        receipt.put("tower", status.getString("tower"));
        receipt.put("stageId", layer.getString("id"));
        receipt.put("settled", false);
        user.put(RECEIPT, receipt);
        layer.put("try", layer.getIntValue("try") + 1);
        JSONObject result = result(user);
        result.put("battleId", receipt.getString("battleId"));
        result.put("isApProtect", false);
        result.put("apFailReturn", 0);
        result.put("inApProtectPeriod", false);
        result.put("notifyPowerScoreNotEnoughIfFailed", false);
        return result;
    }

    static JSONObject finish(JSONObject user, JSONObject body) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.finish(user, body);
        if (body == null || !(body.get("data") instanceof String) || body.getString("data").isEmpty())
            throw new IllegalArgumentException("Missing tower battle data");
        JSONObject decoded;
        try {
            decoded = Utils.BattleData_decrypt(body.getString("data"), user.getJSONObject("pushFlags").getString("status"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid tower battle data");
        }
        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt == null || receipt.getBooleanValue("settled") || decoded == null
                || !receipt.getString("battleId").equals(decoded.getString("battleId")))
            throw new IllegalArgumentException("Unknown or already settled tower battle");
        int rating = decoded.getIntValue("completeState");
        if (rating < 1 || rating > 3) throw new IllegalArgumentException("Invalid tower training result");
        JSONObject current = normalize(user), tower = user.getJSONObject("tower");
        JSONObject layer = current.getJSONArray("layer").getJSONObject(0);
        if (!receipt.getString("tower").equals(current.getJSONObject("status").getString("tower"))
                || !receipt.getString("stageId").equals(layer.getString("id")))
            throw new IllegalArgumentException("Tower training changed");
        if (rating >= 2) {
            layer.put("pass", true);
            current.getJSONObject("status").put("state", "END");
            tower.getJSONObject("outer").getJSONObject("training").put(receipt.getString("tower"), 1);
            recordTowerClear(tower, receipt.getString("tower"));
        }
        receipt.put("settled", true);
        JSONObject result = result(user);
        for (String key : new String[]{"drop", "offer", "trap", "firstRewards", "rewards", "additionalRewards", "unusualRewards", "overrideRewards", "diamondMaterialRewards", "furnitureRewards", "itemReturn", "unlockStages", "alert"})
            result.put(key, new JSONArray());
        result.put("isNewRecord", false);
        result.put("show", "");
        result.put("apFailReturn", 0);
        JSONObject reward = new JSONObject(true);
        JSONObject items = tower.getJSONObject("season").getJSONObject("period").getJSONObject("items");
        JSONObject constant = CharacterBuildingSupport.table("excel/climb_tower_table.json").getJSONObject("detailConst");
        for (String key : new String[]{"lowerItem", "higherItem"}) {
            int count = items.getIntValue(constant.getString(key + "Id"));
            JSONObject delta = new JSONObject(true);
            delta.put("from", count);
            delta.put("to", count);
            reward.put(key, delta);
        }
        result.put("reward", reward);
        return result;
    }

    static JSONObject settle(JSONObject user) {
        if (TowerNormalSupport.isActive(user)) return TowerNormalSupport.settle(user);
        JSONObject current = normalize(user), tower = user.getJSONObject("tower");
        trainingDefinition(CharacterBuildingSupport.table("excel/climb_tower_table.json"),
                current.getJSONObject("status").getString("tower"));
        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt != null) receipt.put("settled", true);
        tower.put("current", emptyCurrent());
        JSONObject result = result(user);
        result.put("finishTs", System.currentTimeMillis() / 1000L);
        return result;
    }

    static JSONObject normalize(JSONObject user) {
        JSONObject tower = ShopTransactionSupport.object(user, "tower");
        JSONObject current = tower.getJSONObject("current");
        if (current == null) {
            current = emptyCurrent();
            tower.put("current", current);
        }
        JSONObject outer = ShopTransactionSupport.object(tower, "outer");
        ShopTransactionSupport.object(outer, "training");
        ShopTransactionSupport.object(outer, "towers");
        normalizeSquad(outer);
        ShopTransactionSupport.object(ShopTransactionSupport.object(ShopTransactionSupport.object(tower, "season"), "period"), "items");
        return current;
    }

    private static void normalizeSquad(JSONObject outer) {
        Object stored = outer.get("squad");
        Object source = stored instanceof JSONObject ? ((JSONObject) stored).get("slots") : stored;
        JSONArray migrated = new JSONArray();
        if (source instanceof JSONArray) {
            JSONArray slots = (JSONArray) source;
            for (Object value : slots) {
                migrated.add(migrateSquadSlot(value));
            }
        }
        outer.put("squad", migrated);
    }

    private static JSONObject migrateSquadSlot(Object value) {
        if (!(value instanceof JSONObject)) return null;
        JSONObject source = (JSONObject) value;
        Integer instId = integerValue(source, "charInstId");
        Integer skill = integerValue(source, "skillIndex", "S_skillIndex");
        if (instId == null || instId <= 0 || skill == null || skill < -1) return null;
        JSONObject clean = new JSONObject(true);
        clean.put("charInstId", instId);
        clean.put("skillIndex", skill);
        copyString(source, clean, "currentTmpl", "S_currentTmpl");
        copyString(source, clean, "currentEquip", "S_currentEquip");
        String patchKey = source.containsKey("tmpl") ? "tmpl"
                : source.containsKey("S_tmpl") ? "S_tmpl" : null;
        if (patchKey != null) {
            Object patch = source.get(patchKey);
            if (patch == null) clean.put("tmpl", null);
            else if (patch instanceof JSONObject)
                clean.put("tmpl", JSONObject.parseObject(((JSONObject) patch).toJSONString()));
        }
        return clean;
    }

    private static Integer integerValue(JSONObject source, String... keys) {
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            Object raw = source.get(key);
            if (!(raw instanceof Number)) return null;
            double value = ((Number) raw).doubleValue();
            int result = ((Number) raw).intValue();
            return value == result ? result : null;
        }
        return null;
    }

    private static void copyString(JSONObject source, JSONObject target, String... keys) {
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            Object value = source.get(key);
            if (value == null || value instanceof String) target.put(targetKey(key), value);
            return;
        }
    }

    private static String targetKey(String key) {
        return key.startsWith("S_") ? key.substring(2) : key;
    }

    /**
     * The client reads outer.towers[<towerId>].best/.reward/.unlockHard/.hardBest for the
     * campaign list; outer.training only gates whether the next training tower can start.
     * The read-only official capture confirms all four keys are present per cleared tower.
     */
    private static void recordTowerClear(JSONObject tower, String towerId) {
        JSONObject towers = tower.getJSONObject("outer").getJSONObject("towers");
        int layerCount = CharacterBuildingSupport.table("excel/climb_tower_table.json")
                .getJSONObject("towers").getJSONObject(towerId).getJSONArray("levels").size();
        JSONObject record = new JSONObject(true);
        record.put("best", layerCount);
        record.put("reward", new JSONArray());
        record.put("unlockHard", false);
        record.put("hardBest", 0);
        towers.put(towerId, record);
    }

    private static JSONObject trainingDefinition(JSONObject table, String id) {
        JSONObject definition = table.getJSONObject("towers").getJSONObject(id);
        if (definition == null || !"TRAINING".equals(definition.getString("towerType"))
                || definition.getJSONArray("levels").size() != 1)
            throw new IllegalArgumentException("Only tower training is currently supported");
        return definition;
    }

    static JSONObject emptyCurrent() {
        return JSON.parseObject("{status:{state:'NONE',tower:'',coord:0,tactical:{PIONEER:'',WARRIOR:'',TANK:'',SNIPER:'',CASTER:'',SUPPORT:'',MEDIC:'',SPECIAL:''},start:0,isHard:false},"
                + "layer:[],cards:{},godCard:{id:'',subGodCardId:''},halftime:{count:0,candidate:[],canGiveUp:false},trap:[]}");
    }

    static JSONObject result(JSONObject user) {
        JSONObject modified = new JSONObject(true);
        modified.put("tower", user.getJSONObject("tower"));
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        return result;
    }
}
