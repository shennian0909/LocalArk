package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Card-driven NORMAL climb towers tower_n_10 and tower_n_21. */
final class TowerNormalSupport {
    static final String RECEIPT = "_localTowerNormalBattle";
    static final String INIT_GOD_CARD = "INIT_GOD_CARD";
    static final String INIT_BUFF = "INIT_BUFF";
    static final String INIT_CARD = "INIT_CARD";
    static final String STANDBY = "STANDBY";
    static final String SUB_GOD_CARD_RECRUIT = "SUB_GOD_CARD_RECRUIT";
    static final String END = "END";
    static final String NONE = "NONE";
    static final String[] PROFESSIONS = {
            "PIONEER", "WARRIOR", "TANK", "SNIPER", "CASTER", "SUPPORT", "MEDIC", "SPECIAL"
    };

    private TowerNormalSupport() {}

    static boolean supports(JSONObject table, String id) {
        return ("tower_n_10".equals(id) || "tower_n_21".equals(id))
                && table.getJSONObject("towers").getJSONObject(id) != null;
    }

    static boolean isActive(JSONObject user) {
        JSONObject current = TowerTrainingSupport.normalize(user);
        String id = current.getJSONObject("status").getString("tower");
        return supports(CharacterBuildingSupport.table("excel/climb_tower_table.json"), id);
    }

    static JSONObject create(JSONObject user, JSONObject body) {
        CharacterBuildingSupport.require(body != null, "Missing tower request");
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        String id = body.getString("tower");
        JSONObject definition = definition(table, id);
        requireAccess(user, table, definition);
        TowerSeasonSupport.ensure(user, table);
        JSONObject current = TowerTrainingSupport.normalize(user);
        CharacterBuildingSupport.require(NONE.equals(current.getJSONObject("status").getString("state")),
                "Settle the current tower first");

        boolean hard = difficulty(body);
        current = TowerTrainingSupport.emptyCurrent();
        JSONObject status = current.getJSONObject("status");
        status.put("state", INIT_GOD_CARD);
        status.put("tower", id);
        status.put("coord", 0);
        status.put("start", System.currentTimeMillis() / 1000L);
        status.put("isHard", hard);
        JSONArray ladder = hard ? definition.getJSONArray("hardLevels") : definition.getJSONArray("levels");
        CharacterBuildingSupport.require(ladder != null && !ladder.isEmpty(), "Tower ladder is unavailable");
        for (int i = 0; i < ladder.size(); i++) {
            JSONObject layer = new JSONObject(true);
            layer.put("id", ladder.getString(i));
            layer.put("try", 0);
            layer.put("pass", false);
            current.getJSONArray("layer").add(layer);
        }
        current.put("trap", curseTraps(table, definition));
        user.getJSONObject("tower").put("current", current);
        return success(user);
    }

    static JSONObject initGodCard(JSONObject user, JSONObject body) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = requireState(user, table, INIT_GOD_CARD);
        CharacterBuildingSupport.require(body != null, "Missing tower request");
        Object rawChoice = body.get("godCardId");
        CharacterBuildingSupport.require(rawChoice instanceof String
                && !((String) rawChoice).isEmpty(), "Missing godCardId");
        String chosen = (String) rawChoice;
        JSONObject definition = definition(table, current.getJSONObject("status").getString("tower"));
        String seasonId = user.getJSONObject("tower").getJSONObject("season").getString("id");
        JSONObject season = seasonId == null ? null
                : table.getJSONObject("seasonInfos").getJSONObject(seasonId);
        JSONObject card = selectableGodCard(table, definition, season, chosen);
        CharacterBuildingSupport.require(card != null,
                "God card does not belong to this tower");
        current.getJSONObject("godCard").put("id", chosen);
        current.getJSONObject("status").put("state", INIT_BUFF);
        return success(user);
    }

    static JSONObject initGame(JSONObject user, JSONObject body) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = requireState(user, table, INIT_BUFF);
        CharacterBuildingSupport.require(body != null, "Missing tower request");
        JSONObject tactical = body.getJSONObject("tactical");
        CharacterBuildingSupport.require(tactical != null, "Missing tactical selection");
        JSONObject target = current.getJSONObject("status").getJSONObject("tactical");
        for (String profession : PROFESSIONS) {
            String buff = tactical.getString(profession);
            if (buff == null)
                buff = tactical.getString(profession.toLowerCase(Locale.ROOT));
            CharacterBuildingSupport.require(buff != null && !buff.isEmpty(),
                    "Missing tactical buff: " + profession);
            JSONObject definition = table.getJSONObject("tacticalBuffs").getJSONObject(buff);
            CharacterBuildingSupport.require(definition != null
                    && profession.equals(definition.getString("profession")),
                    "Tactical buff does not match " + profession);
            target.put(profession, buff);
        }
        String strategy = body.getString("strategy");
        CharacterBuildingSupport.require("NONE".equals(strategy) || "OPTIMIZE".equals(strategy),
                "Invalid tower strategy");
        JSONObject outer = user.getJSONObject("tower").getJSONObject("outer");
        outer.put("tactical", target);
        outer.put("strategy", strategy);
        current.getJSONObject("status").put("strategy", strategy);
        current.getJSONObject("status").put("state", INIT_CARD);
        return success(user);
    }

    static JSONObject initCard(JSONObject user, JSONObject body) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = requireState(user, table, INIT_CARD);
        CharacterBuildingSupport.require(body != null, "Missing tower card request");
        JSONArray assist = body.getJSONArray("assist");
        CharacterBuildingSupport.require(assist == null || assist.isEmpty(),
                "Tower assist cards are not implemented");
        JSONObject godCard = current.getJSONObject("godCard");
        CharacterBuildingSupport.require(!godCard.getString("id").isEmpty(), "God card is not selected");
        JSONObject main = table.getJSONObject("mainCards").getJSONObject(godCard.getString("id"));
        JSONObject owned = user.getJSONObject("troop").getJSONObject("chars");
        CharacterBuildingSupport.require(owned != null && !owned.isEmpty(),
                "No owned operators are available for the tower card deck");

        int cardLimit = table.getJSONObject("detailConst").getIntValue("initCharCount");
        JSONArray requested = body.getJSONArray("slots");
        JSONArray selected = requested == null || requested.isEmpty()
                ? defaultCardIds(owned, cardLimit) : requestedCardIds(owned, requested, cardLimit);
        CharacterBuildingSupport.require(!selected.isEmpty(),
                "No tower cards were selected");
        putCards(current, owned, selected);
        addTraps(current.getJSONArray("trap"), main.getJSONArray("trapIds"), main.getString("id"));
        current.getJSONObject("status").put("state", STANDBY);
        return success(user);
    }

    static JSONObject chooseSubGodCard(JSONObject user, JSONObject body) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = requireState(user, table, SUB_GOD_CARD_RECRUIT);
        String chosen = required(body, "subGodCardId");
        JSONObject main = table.getJSONObject("mainCards")
                .getJSONObject(current.getJSONObject("godCard").getString("id"));
        JSONObject sub = table.getJSONObject("subCards").getJSONObject(chosen);
        CharacterBuildingSupport.require(sub != null
                && main.getString("id").equals(sub.getString("mainCardId"))
                && main.getJSONArray("subCardIds").contains(chosen),
                "Sub god card is not offered by the selected god card");
        current.getJSONObject("godCard").put("subGodCardId", chosen);
        addTraps(current.getJSONArray("trap"), sub.getJSONArray("trapIds"), chosen);
        JSONObject outer = user.getJSONObject("tower").getJSONObject("outer");
        rememberSubCard(outer, "pickedCardMap", main.getString("id"), chosen);
        rememberSubCard(outer, "pickedGodCard", main.getString("id"), chosen);
        current.getJSONObject("status").put("state", STANDBY);
        return success(user);
    }

    static JSONObject start(JSONObject user, JSONObject body) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = requireState(user, table, STANDBY);
        JSONObject definition = definition(table, current.getJSONObject("status").getString("tower"));
        JSONObject layer = pendingLayer(current);
        JSONObject level = table.getJSONObject("levels").getJSONObject(layer.getString("id"));
        CharacterBuildingSupport.require(body != null && level != null, "Missing tower battle request");
        String stageId = body.getString("stageId");
        CharacterBuildingSupport.require(stageId != null && (stageId.equals(level.getString("levelId"))
                || stageId.equals(level.getString("id"))), "Wrong tower layer");
        JSONArray squad = normalizeSquad(user, current, body);

        JSONObject receipt = new JSONObject(true);
        receipt.put("battleId", UUID.randomUUID().toString());
        receipt.put("tower", definition.getString("id"));
        receipt.put("stageId", layer.getString("id"));
        receipt.put("levelId", level.getString("levelId"));
        receipt.put("settled", false);
        user.put(RECEIPT, receipt);
        user.getJSONObject("tower").getJSONObject("outer").put("squad", squad);
        layer.put("try", layer.getIntValue("try") + 1);

        JSONObject result = success(user);
        result.put("battleId", receipt.getString("battleId"));
        result.put("stageId", level.getString("levelId"));
        result.put("isApProtect", false);
        result.put("apFailReturn", 0);
        result.put("inApProtectPeriod", false);
        result.put("notifyPowerScoreNotEnoughIfFailed", false);
        result.put("layerNum", layerPosition(current, layer));
        return result;
    }

    static JSONObject finish(JSONObject user, JSONObject body) {
        CharacterBuildingSupport.require(body != null && body.get("data") instanceof String
                && !body.getString("data").isEmpty(), "Missing tower battle data");
        JSONObject decoded;
        try {
            decoded = Utils.BattleData_decrypt(body.getString("data"),
                    user.getJSONObject("pushFlags").getString("status"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid tower battle data");
        }
        JSONObject receipt = user.getJSONObject(RECEIPT);
        CharacterBuildingSupport.require(receipt != null && !receipt.getBooleanValue("settled")
                && decoded != null && receipt.getString("battleId").equals(decoded.getString("battleId")),
                "Unknown or already settled tower battle");
        int rating = decoded.getIntValue("completeState");
        CharacterBuildingSupport.require(rating >= 1 && rating <= 3, "Invalid tower result");
        if (rating == 1) return failed(user, receipt);

        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = TowerTrainingSupport.normalize(user);
        JSONObject definition = definition(table, receipt.getString("tower"));
        JSONObject layer = layer(current, receipt.getString("stageId"));
        CharacterBuildingSupport.require(layer != null && !layer.getBooleanValue("pass"),
                "Tower layer is missing or already cleared");
        int reached = layerPosition(current, layer);
        JSONObject record = towerRecord(user, definition);
        boolean hard = current.getJSONObject("status").getBooleanValue("isHard");
        boolean isNewRecord = reached > record.getIntValue(hard ? "hardBest" : "best");
        boolean firstTaskReward = !record.getJSONArray("reward").contains(reached);
        JSONObject period = user.getJSONObject("tower").getJSONObject("season").getJSONObject("period");
        JSONObject constant = table.getJSONObject("detailConst");
        int lowerBefore = period.getJSONObject("items").getIntValue(constant.getString("lowerItemId"));
        int higherBefore = period.getJSONObject("items").getIntValue(constant.getString("higherItemId"));

        JSONArray firstRewards = new JSONArray();
        JSONArray drop = grantDrop(user, table, layer.getString("id"), firstRewards);
        if (firstTaskReward)
            grantTaskReward(user, table, definition, reached, firstRewards);

        layer.put("pass", true);
        current.getJSONObject("status").put("coord", reached);
        updateRecord(record, definition, current, reached, hard);
        if (firstTaskReward) record.getJSONArray("reward").add(reached);
        boolean last = current.getJSONArray("layer").size() == reached;
        if (last) {
            current.getJSONObject("status").put("state", END);
            recordPassWithGodCard(user, current);
        } else if (reached == constant.getIntValue("subcardStageSort") - 1
                && current.getJSONObject("godCard").getString("subGodCardId").isEmpty()) {
            current.getJSONObject("status").put("state", SUB_GOD_CARD_RECRUIT);
        } else {
            current.getJSONObject("status").put("state", STANDBY);
        }

        receipt.put("settled", true);
        JSONObject result = success(user);
        result.put("isNewRecord", isNewRecord);
        result.put("isPerfect", rating == 3);
        result.put("stageId", layer.getString("id"));
        result.put("drop", drop);
        result.put("offer", new JSONArray());
        result.put("show", "");
        result.put("trap", current.getJSONArray("trap"));
        result.put("firstRewards", firstRewards);
        result.put("rewards", new JSONArray());
        result.put("additionalRewards", new JSONArray());
        result.put("unusualRewards", new JSONArray());
        result.put("overrideRewards", new JSONArray());
        result.put("diamondMaterialRewards", new JSONArray());
        result.put("furnitureRewards", new JSONArray());
        result.put("itemReturn", new JSONArray());
        result.put("unlockStages", new JSONArray());
        result.put("alert", new JSONArray());
        result.put("reward", seasonReward(period, constant, lowerBefore, higherBefore));
        JSONObject progress = new JSONObject(true);
        progress.put("layer", reached);
        progress.put("total", current.getJSONArray("layer").size());
        progress.put("state", current.getJSONObject("status").getString("state"));
        result.put("progress", progress);
        return result;
    }

    static JSONObject settle(JSONObject user) {
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        JSONObject current = TowerTrainingSupport.normalize(user);
        JSONObject status = current.getJSONObject("status");
        definition(table, status.getString("tower"));
        CharacterBuildingSupport.require(!NONE.equals(status.getString("state")), "No tower run to settle");
        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt != null) receipt.put("settled", true);
        TowerSeasonSupport.ensure(user, table);
        user.getJSONObject("tower").put("current", TowerTrainingSupport.emptyCurrent());
        JSONObject result = success(user);
        result.put("finishTs", System.currentTimeMillis() / 1000L);
        return result;
    }

    private static JSONObject definition(JSONObject table, String id) {
        JSONObject definition = id == null ? null : table.getJSONObject("towers").getJSONObject(id);
        CharacterBuildingSupport.require(definition != null
                && supports(table, id), "Only tower_n_10 and tower_n_21 are supported");
        CharacterBuildingSupport.require("NORMAL".equals(definition.getString("towerType"))
                && definition.getJSONArray("levels").size() == 6
                && definition.getJSONArray("hardLevels") != null
                && definition.getJSONArray("hardLevels").size() == 6,
                "Normal tower table is incomplete");
        return definition;
    }

    private static void requireAccess(JSONObject user, JSONObject table, JSONObject definition) {
        String unlockId = table.getJSONObject("detailConst").getString("unlockLevelId");
        JSONObject stage = user.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(unlockId);
        CharacterBuildingSupport.require(stage != null && stage.getIntValue("state") >= 2,
                "Climb tower is locked");
        String previous = definition.getString("preTowerId");
        if (previous != null) {
            JSONObject tower = user.getJSONObject("tower").getJSONObject("outer")
                    .getJSONObject("towers").getJSONObject(previous);
            CharacterBuildingSupport.require(tower != null
                    && tower.getIntValue("best") >= definition.getJSONArray("levels").size(),
                    "Previous tower is not cleared");
        }
    }

    private static boolean difficulty(JSONObject body) {
        Object asked = body.get("isHard");
        if (asked == null || Boolean.FALSE.equals(asked) || "0".equals(asked)
                || asked instanceof Number && ((Number) asked).doubleValue() == 0D) return false;
        CharacterBuildingSupport.require(Boolean.TRUE.equals(asked) || "1".equals(asked)
                || asked instanceof Number && ((Number) asked).doubleValue() == 1D,
                "Invalid tower difficulty");
        return true;
    }

    private static JSONObject requireState(JSONObject user, JSONObject table, String state) {
        JSONObject current = TowerTrainingSupport.normalize(user);
        definition(table, current.getJSONObject("status").getString("tower"));
        CharacterBuildingSupport.require(state.equals(current.getJSONObject("status").getString("state")),
                "Tower is not in " + state);
        return current;
    }

    private static String required(JSONObject body, String field) {
        CharacterBuildingSupport.require(body != null, "Missing tower request");
        String value = body.getString(field);
        CharacterBuildingSupport.require(value != null && !value.isEmpty(), "Missing " + field);
        return value;
    }

    private static JSONObject selectableGodCard(JSONObject table, JSONObject tower,
                                                JSONObject season, String chosen) {
        JSONObject card = table.getJSONObject("mainCards").getJSONObject(chosen);
        if (card == null || !validSubGodCardBindings(table, card)) return null;
        String towerId = tower.getString("id");
        String type = card.getString("type");
        if ("TOWER".equals(type)) {
            return chosen.equals(tower.getString("cardId"))
                    && towerId.equals(card.getString("linkedTowerId")) ? card : null;
        }
        if (!"SEASON".equals(type) || card.get("linkedTowerId") != null || season == null) return null;
        return contains(season.getJSONArray("towers"), towerId)
                && contains(season.getJSONArray("seasonCards"), chosen) ? card : null;
    }

    private static boolean validSubGodCardBindings(JSONObject table, JSONObject main) {
        JSONArray ids = main.getJSONArray("subCardIds");
        if (ids == null || ids.isEmpty()) return true;
        JSONObject subCards = table.getJSONObject("subCards");
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.getString(i);
            JSONObject sub = id == null || subCards == null ? null : subCards.getJSONObject(id);
            if (sub == null || !main.getString("id").equals(sub.getString("mainCardId"))) return false;
        }
        return true;
    }

    private static boolean contains(JSONArray values, String expected) {
        return values != null && values.contains(expected);
    }

    private static JSONObject pendingLayer(JSONObject current) {
        JSONArray layers = current.getJSONArray("layer");
        for (int i = 0; i < layers.size(); i++) {
            if (!layers.getJSONObject(i).getBooleanValue("pass")) return layers.getJSONObject(i);
        }
        throw new IllegalArgumentException("Tower ladder is already cleared");
    }

    private static JSONObject layer(JSONObject current, String id) {
        JSONArray layers = current.getJSONArray("layer");
        for (int i = 0; i < layers.size(); i++) {
            if (id.equals(layers.getJSONObject(i).getString("id"))) return layers.getJSONObject(i);
        }
        return null;
    }

    private static int layerPosition(JSONObject current, JSONObject target) {
        JSONArray layers = current.getJSONArray("layer");
        for (int i = 0; i < layers.size(); i++) if (layers.getJSONObject(i) == target) return i + 1;
        throw new IllegalArgumentException("Tower layer is missing");
    }

    private static JSONArray curseTraps(JSONObject table, JSONObject definition) {
        JSONArray traps = new JSONArray();
        addTraps(traps, definition.getJSONArray("curseCardIds"), definition.getString("id"));
        return traps;
    }

    private static void addTraps(JSONArray traps, JSONArray source, String alias) {
        if (source == null) return;
        JSONObject table = CharacterBuildingSupport.table("excel/climb_tower_table.json");
        for (int i = 0; i < source.size(); i++) {
            String id = source.getString(i);
            JSONObject card = table.getJSONObject("curseCards") == null ? null
                    : table.getJSONObject("curseCards").getJSONObject(id);
            String trapId = card == null ? id : card.getString("trapId");
            boolean exists = false;
            for (int j = 0; j < traps.size(); j++)
                if (trapId.equals(traps.getJSONObject(j).getString("id"))) exists = true;
            if (!exists) {
                JSONObject trap = new JSONObject(true);
                trap.put("id", trapId);
                trap.put("alias", alias);
                traps.add(trap);
            }
        }
    }

    private static JSONArray grantDrop(JSONObject user, JSONObject table, String layerId,
                                       JSONArray firstRewards) {
        JSONObject level = table.getJSONObject("levels").getJSONObject(layerId);
        JSONObject sheet = level == null ? null
                : value(level.getJSONObject("dropInfo"), "displayDropInfo");
        CharacterBuildingSupport.require(sheet != null, "Tower layer drop table is unavailable");
        JSONArray drop = new JSONArray();
        JSONObject constant = table.getJSONObject("detailConst");
        for (String id : sheet.keySet()) {
            JSONObject entry = sheet.getJSONObject(id);
            int count = entry.getIntValue("minCount");
            CharacterBuildingSupport.require(count > 0 && count == entry.getIntValue("maxCount"),
                    "Tower layer has an unsupported drop range");
            JSONObject inventory = ShopTransactionSupport.object(user, "inventory");
            int before = inventory.getIntValue(id);
            JSONObject item = new JSONObject(true);
            item.put("id", id);
            item.put("type", entry.getString("type"));
            item.put("count", count);
            grantSeasonReward(user, table, item, firstRewards);
            int after = inventory.getIntValue(id);
            JSONObject change = new JSONObject(true);
            change.put("itemId", id);
            change.put("before", before);
            change.put("after", after);
            change.put("max", id.equals(constant.getString("lowerItemId"))
                    ? constant.getIntValue("lowerItemLimit")
                    : id.equals(constant.getString("higherItemId"))
                    ? constant.getIntValue("higherItemLimit") : 0);
            drop.add(change);
        }
        return drop;
    }

    private static void grantTaskReward(JSONObject user, JSONObject table, JSONObject definition, int layerNum,
                                        JSONArray rewards) {
        JSONArray tasks = definition.getJSONArray("taskInfo");
        if (tasks == null) return;
        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (task.getIntValue("levelNum") != layerNum) continue;
            JSONArray listed = task.getJSONArray("rewards");
            for (int j = 0; j < listed.size(); j++)
                grantSeasonReward(user, table, listed.getJSONObject(j), rewards);
            return;
        }
    }

    private static void grantSeasonReward(JSONObject user, JSONObject table, JSONObject item,
                                          JSONArray rewards) {
        JSONObject constant = table.getJSONObject("detailConst");
        String id = item.getString("id");
        int limit = itemLimit(constant, id);
        if (limit <= 0) {
            ShopTransactionSupport.reward(user, item, 1, rewards);
            return;
        }
        JSONObject items = user.getJSONObject("tower").getJSONObject("season")
                .getJSONObject("period").getJSONObject("items");
        int used = items.getIntValue(id);
        int available = Math.max(0, limit - used);
        int granted = Math.min(item.getIntValue("count"), available);
        if (granted == 0) return;
        JSONObject actual = JSONObject.parseObject(item.toJSONString());
        actual.put("count", granted);
        ShopTransactionSupport.reward(user, actual, 1, rewards);
        items.put(id, used + granted);
    }

    private static int itemLimit(JSONObject constant, String id) {
        if (id.equals(constant.getString("lowerItemId")))
            return constant.getIntValue("lowerItemLimit");
        if (id.equals(constant.getString("higherItemId")))
            return constant.getIntValue("higherItemLimit");
        return 0;
    }

    private static JSONArray defaultCardIds(JSONObject owned, int limit) {
        JSONArray selected = new JSONArray();
        for (String key : owned.keySet()) {
            JSONObject operator = owned.getJSONObject(key);
            if (operator != null) selected.add(operator.getIntValue("instId"));
            if (selected.size() == limit) break;
        }
        return selected;
    }

    private static JSONArray requestedCardIds(JSONObject owned, JSONArray requested, int limit) {
        CharacterBuildingSupport.require(requested.size() <= limit,
                "Too many tower cards were selected");
        JSONArray selected = new JSONArray();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            JSONObject slot = requested.getJSONObject(i);
            CharacterBuildingSupport.require(slot != null, "Invalid tower card slot");
            int instId = slot.getIntValue("charInstId");
            CharacterBuildingSupport.require(instId > 0 && owned.getJSONObject(Integer.toString(instId)) != null,
                    "Tower card is not owned");
            CharacterBuildingSupport.require(seen.add(instId), "Duplicate tower card");
            selected.add(instId);
        }
        return selected;
    }

    private static JSONArray normalizeSquad(JSONObject user, JSONObject current, JSONObject body) {
        Object raw = body.get("squad");
        CharacterBuildingSupport.require(raw instanceof JSONObject, "Missing tower squad");
        JSONObject source = (JSONObject) raw;
        JSONArray requested = source.getJSONArray("slots");
        CharacterBuildingSupport.require(requested != null && !requested.isEmpty(),
                "Missing tower squad slots");
        JSONObject owned = user.getJSONObject("troop").getJSONObject("chars");
        JSONObject cards = current.getJSONObject("cards");
        CharacterBuildingSupport.require(owned != null && !owned.isEmpty(), "No owned operators are available");
        if (cards == null) {
            cards = new JSONObject(true);
            current.put("cards", cards);
        }
        boolean cardsWereEmpty = cards.isEmpty();

        JSONArray slots = new JSONArray();
        JSONArray selected = new JSONArray();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            JSONObject slot = requested.getJSONObject(i);
            if (slot == null) {
                slots.add(null);
                continue;
            }
            int instId = CharacterBuildingSupport.integer(slot, "charInstId");
            String key = Integer.toString(instId);
            JSONObject operator = instId <= 0 ? null : owned.getJSONObject(key);
            CharacterBuildingSupport.require(operator != null
                            && (cardsWereEmpty || cards.getJSONObject(key) != null),
                    "Tower squad operator is not owned or initialized");
            CharacterBuildingSupport.require(seen.add(instId), "Duplicate tower squad operator");

            String templateKey = slot.containsKey("currentTmpl") ? "currentTmpl"
                    : slot.containsKey("S_currentTmpl") ? "S_currentTmpl" : null;
            Object templateValue = templateKey == null ? null : slot.get(templateKey);
            CharacterBuildingSupport.require(templateValue == null || templateValue instanceof String,
                    "Invalid tower squad template");
            String template = (String) templateValue;
            String effectiveTemplate = template == null || template.isEmpty()
                    ? operator.getString("currentTmpl") : template;
            JSONObject selectedForm = formForTemplate(operator, effectiveTemplate);

            JSONArray skills = selectedForm.getJSONArray("skills");
            String skillKey = slot.containsKey("skillIndex") ? "skillIndex"
                    : slot.containsKey("S_skillIndex") ? "S_skillIndex" : null;
            int skill = skillKey == null ? selectedForm.getIntValue("defaultSkillIndex")
                    : CharacterBuildingSupport.integer(slot, skillKey);
            if (skills == null || skills.isEmpty()) {
                CharacterBuildingSupport.require(skill == -1 || skill == 0,
                        "Invalid tower squad skill");
                skill = -1;
            } else {
                CharacterBuildingSupport.require(skill >= 0 && skill < skills.size(),
                        "Invalid tower squad skill");
                JSONObject skillDefinition = skills.getJSONObject(skill);
                CharacterBuildingSupport.require(skillDefinition != null
                                && skillDefinition.getIntValue("unlock") == 1,
                        "Tower squad skill is locked");
            }

            String equipKey = slot.containsKey("currentEquip") ? "currentEquip"
                    : slot.containsKey("S_currentEquip") ? "S_currentEquip" : null;
            Object equipValue = equipKey == null ? null : slot.get(equipKey);
            CharacterBuildingSupport.require(equipValue == null || equipValue instanceof String,
                    "Invalid tower squad equipment");
            if (equipValue != null) requireEquip(selectedForm, (String) equipValue);

            JSONObject clean = new JSONObject(true);
            clean.put("charInstId", instId);
            clean.put("skillIndex", skill);
            if (templateKey != null) clean.put("currentTmpl", templateValue);
            if (equipKey != null) clean.put("currentEquip", equipValue);
            String templatePatchKey = slot.containsKey("tmpl") ? "tmpl"
                    : slot.containsKey("S_tmpl") ? "S_tmpl" : null;
            if (templatePatchKey != null) {
                Object templatePatch = slot.get(templatePatchKey);
                CharacterBuildingSupport.require(templatePatch == null || templatePatch instanceof JSONObject,
                        "Invalid tower squad template patch");
                clean.put("tmpl", templatePatch == null ? null
                        : JSONObject.parseObject(((JSONObject) templatePatch).toJSONString()));
            }
            slots.add(clean);
            selected.add(instId);
        }
        CharacterBuildingSupport.require(!selected.isEmpty(), "Tower squad has no operators");
        if (cardsWereEmpty) putCards(current, owned, selected);
        return slots;
    }

    private static JSONObject formForTemplate(JSONObject operator, String template) {
        if (template == null || template.isEmpty() || template.equals(operator.getString("charId")))
            return operator;
        JSONObject forms = operator.getJSONObject("tmpl");
        JSONObject form = forms == null ? null : forms.getJSONObject(template);
        CharacterBuildingSupport.require(form != null, "Tower squad character template is not owned");
        return form;
    }

    private static void requireEquip(JSONObject form, String equip) {
        if (equip.isEmpty()) return;
        JSONObject modules = form.getJSONObject("equip");
        JSONObject module = modules == null ? null : modules.getJSONObject(equip);
        CharacterBuildingSupport.require(module != null
                        && module.getIntValue("locked") == 0
                        && module.getIntValue("level") >= 1,
                "Tower squad equipment is locked");
    }
    private static void putCards(JSONObject current, JSONObject owned, JSONArray selected) {
        JSONObject cards = new JSONObject(true);
        for (int i = 0; i < selected.size(); i++) {
            int instId = selected.getIntValue(i);
            JSONObject source = owned.getJSONObject(Integer.toString(instId));
            JSONObject card = JSONObject.parseObject(source.toJSONString());
            card.put("instId", instId);
            card.put("relation", "");
            card.put("type", "CHAR");
            if (!card.containsKey("skills")) card.put("skills", new JSONArray());
            if (!card.containsKey("equip")) card.put("equip", new JSONObject(true));
            if (!card.containsKey("currentTmpl")) card.put("currentTmpl", "");
            if (!card.containsKey("tmpl")) card.put("tmpl", new JSONObject(true));
            if (!card.containsKey("master")) card.put("master", new JSONObject(true));
            cards.put(Integer.toString(instId), card);
        }
        current.put("cards", cards);
    }

    private static JSONObject towerRecord(JSONObject user, JSONObject definition) {
        JSONObject records = user.getJSONObject("tower").getJSONObject("outer").getJSONObject("towers");
        String id = definition.getString("id");
        JSONObject record = records.getJSONObject(id);
        if (record == null) {
            record = new JSONObject(true);
            record.put("best", 0);
            record.put("reward", new JSONArray());
            record.put("unlockHard", false);
            record.put("hardBest", 0);
            record.put("isHardValid", false);
            record.put("canSweep", false);
            record.put("canSweepHard", false);
            records.put(id, record);
        }
        if (!record.containsKey("reward")) record.put("reward", new JSONArray());
        if (!record.containsKey("isHardValid")) record.put("isHardValid", false);
        return record;
    }

    private static void updateRecord(JSONObject record, JSONObject definition,
                                     JSONObject current, int reached, boolean hard) {
        String field = hard ? "hardBest" : "best";
        record.put(field, Math.max(record.getIntValue(field), reached));
        if (!hard && reached >= definition.getJSONArray("levels").size()) {
            record.put("unlockHard", true);
            record.put("isHardValid", true);
        }
    }

    private static void rememberSubCard(JSONObject outer, String field, String mainId, String subId) {
        JSONObject cards = ShopTransactionSupport.object(outer, field);
        JSONArray picked = cards.getJSONArray(mainId);
        if (picked == null) {
            picked = new JSONArray();
            cards.put(mainId, picked);
        }
        if (!picked.contains(subId)) picked.add(subId);
    }

    private static void recordPassWithGodCard(JSONObject user, JSONObject current) {
        JSONObject outer = user.getJSONObject("tower").getJSONObject("outer");
        outer.put("hasTowerPass", outer.getIntValue("hasTowerPass") + 1);
        JSONObject progress = user.getJSONObject("tower").getJSONObject("season")
                .getJSONObject("passWithGodCard");
        String cardId = current.getJSONObject("godCard").getString("id");
        JSONArray towers = progress.getJSONArray(cardId);
        if (towers == null) {
            towers = new JSONArray();
            progress.put(cardId, towers);
        }
        String towerId = current.getJSONObject("status").getString("tower");
        if (!towers.contains(towerId)) towers.add(towerId);
    }

    private static JSONObject seasonReward(JSONObject period, JSONObject constant,
                                           int lowerBefore, int higherBefore) {
        JSONObject reward = new JSONObject(true);
        reward.put("lowerItem", delta(lowerBefore,
                period.getJSONObject("items").getIntValue(constant.getString("lowerItemId"))));
        reward.put("higherItem", delta(higherBefore,
                period.getJSONObject("items").getIntValue(constant.getString("higherItemId"))));
        return reward;
    }

    private static JSONObject delta(int before, int after) {
        JSONObject delta = new JSONObject(true);
        delta.put("from", before);
        delta.put("to", after);
        return delta;
    }

    private static JSONObject failed(JSONObject user, JSONObject receipt) {
        JSONObject current = TowerTrainingSupport.normalize(user);
        current.getJSONObject("status").put("state", STANDBY);
        receipt.put("settled", true);
        JSONObject result = success(user);
        result.put("isNewRecord", false);
        result.put("stageId", receipt.getString("stageId"));
        for (String key : new String[]{"drop", "offer", "trap", "firstRewards", "rewards",
                "additionalRewards", "unusualRewards", "overrideRewards", "diamondMaterialRewards",
                "furnitureRewards", "itemReturn", "unlockStages", "alert"})
            result.put(key, new JSONArray());
        result.put("show", "");
        result.put("reward", new JSONObject(true));
        return result;
    }

    private static JSONObject success(JSONObject user) {
        JSONObject result = TowerTrainingSupport.result(user);
        JSONObject modified = result.getJSONObject("playerDataDelta").getJSONObject("modified");
        if (user.containsKey("inventory")) modified.put("inventory", user.getJSONObject("inventory"));
        if (user.containsKey("status")) modified.put("status", user.getJSONObject("status"));
        return result;
    }

    private static JSONObject value(JSONObject parent, String key) {
        return parent == null ? null : parent.getJSONObject(key);
    }
}
