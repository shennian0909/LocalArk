package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.decrypt.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Detached rlv2 state transitions; the controller owns persistence. */
final class RogueSupport {
    private static final String RECEIPT = "_localRogueBattle";
    private static final String NODE_NONE = "NONE";
    private static final String NODE_BATTLE_NORMAL = "BATTLE_NORMAL";
    private static final String NODE_BATTLE_ELITE = "BATTLE_ELITE";
    private static final String NODE_BATTLE_BOSS = "BATTLE_BOSS";
    private static final String NODE_FINAL = "FINAL";

    private RogueSupport() {
    }

    static JSONObject createGame(
            JSONObject user,
            JSONObject topicTable,
            JSONObject request,
            String gameId,
            long nowSeconds) {
        String theme = request.getString("theme");
        String mode = request.getString("mode");
        Object gradeValue = request.get("modeGrade");
        if (theme == null || theme.isEmpty()) throw new IllegalArgumentException("theme is required");
        if (mode == null || mode.isEmpty()) throw new IllegalArgumentException("mode is required");
        if (!(gradeValue instanceof Integer)) throw new IllegalArgumentException("modeGrade must be an integer");

        JSONObject details = topicTable == null ? null : topicTable.getJSONObject("details");
        JSONObject detail = details == null ? null : details.getJSONObject(theme);
        if (detail == null) throw new IllegalArgumentException("unknown roguelike theme");

        int modeGrade = (Integer) gradeValue;
        String predefinedId = normalizeId(request.getString("predefinedId"));
        JSONObject init = findInit(detail.getJSONArray("init"), mode, modeGrade, predefinedId);
        if (init == null) throw new IllegalArgumentException("unsupported mode or grade");

        JSONObject rlv2 = ShopTransactionSupport.object(user, "rlv2");
        if (isActive(rlv2.getJSONObject("current"))) {
            throw new IllegalArgumentException("roguelike game already active");
        }

        JSONObject outer = rlv2.getJSONObject("outer");
        JSONObject current = initialState(
                detail,
                init,
                gameId,
                theme,
                mode,
                modeGrade,
                predefinedId,
                normalizeId(request.getString("activityId")),
                nowSeconds);
        rlv2.put("current", current);
        if (outer != null) rlv2.put("outer", outer);
        user.remove(RECEIPT);
        return response(rlv2);
    }

    static JSONObject giveUpGame(JSONObject user) {
        JSONObject rlv2 = ShopTransactionSupport.object(user, "rlv2");
        if (!isActive(rlv2.getJSONObject("current"))) {
            throw new IllegalArgumentException("no active roguelike game");
        }
        JSONObject outer = rlv2.getJSONObject("outer");
        rlv2.put("current", resetState());
        if (outer != null) rlv2.put("outer", outer);
        user.remove(RECEIPT);
        return response(rlv2);
    }

    static JSONObject chooseInitialRelic(JSONObject user, String select) {
        JSONObject current = activeCurrent(user);
        JSONObject pending = requirePending(current, "GAME_INIT_RELIC");
        JSONObject items = pending.getJSONObject("content").getJSONObject("initRelic").getJSONObject("items");
        JSONObject item = items.getJSONObject(requireSelect(select));
        if (item == null || item.getString("id") == null) {
            throw new IllegalArgumentException("initial relic is not offered");
        }

        long now = System.currentTimeMillis() / 1000L;
        String relicId = item.getString("id");
        JSONObject relic = new JSONObject(true);
        relic.put("index", "r_0");
        relic.put("id", relicId);
        relic.put("count", 1);
        relic.put("ts", now);
        current.getJSONObject("inventory").getJSONObject("relic").put("r_0", relic);
        popPending(current, pending);

        JSONObject result = response(user.getJSONObject("rlv2"));
        JSONArray grants = new JSONArray();
        JSONObject grant = new JSONObject(true);
        grant.put("id", relicId);
        grant.put("type", "RELIC");
        grant.put("count", 1);
        grants.add(grant);
        result.put("items", grants);
        return result;
    }

    static JSONObject chooseInitialRecruitSet(JSONObject user, String select) {
        JSONObject current = activeCurrent(user);
        JSONObject pending = requirePending(current, "GAME_INIT_RECRUIT_SET");
        JSONObject recruitSet = pending.getJSONObject("content").getJSONObject("initRecruitSet");
        JSONArray options = recruitSet.getJSONArray("option");
        String groupId = requireSelect(select);
        if (!options.contains(groupId)) throw new IllegalArgumentException("initial recruit set is not offered");

        JSONObject detail = detailForUser(user);
        List<String> ticketDefinitionIds = ticketDefinitionIds(detail, groupId);
        JSONObject recruitInventory = current.getJSONObject("inventory").getJSONObject("recruit");
        JSONObject ownedChars = user.getJSONObject("troop").getJSONObject("chars");
        JSONArray ticketIds = new JSONArray();
        long now = System.currentTimeMillis() / 1000L;
        for (String definitionId : ticketDefinitionIds) {
            String ticketId = nextTicketId(recruitInventory);
            JSONObject ticket = new JSONObject(true);
            ticket.put("index", ticketId);
            ticket.put("id", definitionId);
            ticket.put("state", 1);
            ticket.put("list", ticketCandidates(detail, definitionId, current, ownedChars));
            ticket.put("result", null);
            ticket.put("ts", now);
            ticket.put("needAssist", false);
            recruitInventory.put(ticketId, ticket);
            ticketIds.add(ticketId);
        }
        if (ticketIds.isEmpty()) throw new IllegalArgumentException("recruit set has no available tickets");

        JSONObject recruitPending = findPending(current, "GAME_INIT_RECRUIT");
        if (recruitPending == null) throw new IllegalArgumentException("initial recruit pending is missing");
        JSONObject initialRecruit = recruitPending.getJSONObject("content").getJSONObject("initRecruit");
        initialRecruit.put("tickets", ticketIds);
        popPending(current, pending);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject chooseInitialExploreTool(JSONObject user, String select) {
        JSONObject current = activeCurrent(user);
        JSONObject pending = findPending(current, "GAME_INIT_EXPLORE_TOOL");
        if (pending == null) throw new IllegalArgumentException("no initial explore tool is pending");
        JSONObject items = pending.getJSONObject("content").getJSONObject("initExploreTool").getJSONObject("items");
        JSONObject item = items.getJSONObject(requireSelect(select));
        if (item == null || item.getString("id") == null) {
            throw new IllegalArgumentException("initial explore tool is not offered");
        }
        long now = System.currentTimeMillis() / 1000L;
        JSONObject tool = new JSONObject(true);
        tool.put("id", item.getString("id"));
        tool.put("ts", now);
        current.getJSONObject("inventory").getJSONObject("exploreTool").put(item.getString("id"), tool);
        popPending(current, pending);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject recruitChar(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        String ticketId = requireText(body, "ticketIndex");
        String optionId = requireText(body, "optionId");
        JSONObject ticket = current.getJSONObject("inventory").getJSONObject("recruit").getJSONObject(ticketId);
        if (ticket == null || ticket.getIntValue("state") != 1) {
            throw new IllegalArgumentException("recruit ticket is not active");
        }
        JSONObject selected = null;
        JSONArray options = ticket.getJSONArray("list");
        for (int index = 0; options != null && index < options.size(); index++) {
            JSONObject option = options.getJSONObject(index);
            if (optionId.equals(String.valueOf(option.get("instId")))) {
                selected = JSON.parseObject(option.toJSONString());
                break;
            }
        }
        if (selected == null) throw new IllegalArgumentException("recruit ticket offers no such option");

        JSONObject property = current.getJSONObject("player").getJSONObject("property");
        JSONObject population = property.getJSONObject("population");
        int cost = selected.getIntValue("population");
        if (cost < 0 || population.getIntValue("cost") + cost > population.getIntValue("max")) {
            throw new IllegalArgumentException("insufficient population");
        }
        population.put("cost", population.getIntValue("cost") + cost);

        JSONObject chars = current.getJSONObject("troop").getJSONObject("chars");
        String charInstId = String.valueOf(selected.get("troopInstId"));
        if (chars.containsKey(charInstId)) {
            throw new IllegalArgumentException("recruited operator is already in the squad");
        }
        JSONObject recruited = runtimeCharacter(selected);
        chars.put(charInstId, recruited);
        ticket.put("state", 2);
        ticket.put("result", selected);
        ticket.put("list", new JSONArray());
        removeRecruitedCandidate(current, ticketId, charInstId);

        JSONObject result = response(user.getJSONObject("rlv2"));
        JSONArray recruitedChars = new JSONArray();
        recruitedChars.add(recruited);
        result.put("chars", recruitedChars);
        return result;
    }

    static JSONObject closeRecruitTicket(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        String ticketId = requireText(body, "id");
        JSONObject tickets = current.getJSONObject("inventory").getJSONObject("recruit");
        JSONObject ticket = tickets == null ? null : tickets.getJSONObject(ticketId);
        if (ticket == null || ticket.getIntValue("state") != 1) {
            throw new IllegalArgumentException("recruit ticket is not active");
        }

        ticket.put("state", 2);
        ticket.put("list", new JSONArray());
        ticket.put("result", null);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject finishEvent(JSONObject user) {
        JSONObject current = activeCurrent(user);
        JSONObject player = current.getJSONObject("player");
        JSONObject pending = requirePending(current, "GAME_INIT_RECRUIT");
        if (!"INIT".equals(player.getString("state")) || player.getJSONArray("pending").size() != 1)
            throw new IllegalArgumentException("initial recruitment is not ready to finish");
        JSONArray ids = pending.getJSONObject("content").getJSONObject("initRecruit").getJSONArray("tickets");
        JSONObject tickets = current.getJSONObject("inventory").getJSONObject("recruit");
        for (int i = 0; i < ids.size(); i++) {
            JSONObject ticket = tickets.getJSONObject(ids.getString(i));
            if (ticket == null || ticket.getIntValue("state") != 2)
                throw new IllegalArgumentException("initial recruit tickets are not settled");
        }
        if (!hasSettledInitialTicket(current, pending))
            throw new IllegalArgumentException("no initial operator has been recruited");
        popPending(current, pending);
        enterFirstZone(current);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject chooseInitialRecruit(JSONObject user, String select) {
        JSONObject current = activeCurrent(user);
        JSONObject recruitPending = findPending(current, "GAME_INIT_RECRUIT");
        if (recruitPending == null) throw new IllegalArgumentException("initial recruit is not pending");
        String selectedId = requireSelect(select);
        JSONObject chars = current.getJSONObject("troop").getJSONObject("chars");
        String selectedKey = null;
        for (String key : chars.keySet()) {
            JSONObject character = chars.getJSONObject(key);
            if (selectedId.equals(key)
                    || selectedId.equals(String.valueOf(character.get("instId")))
                    || selectedId.equals(character.getString("charId"))) {
                selectedKey = key;
                break;
            }
        }
        if (selectedKey == null) throw new IllegalArgumentException("initial recruit is not in the squad");
        if (!hasSettledInitialTicket(current, recruitPending)) {
            throw new IllegalArgumentException("no initial recruit ticket has been settled");
        }
        recruitPending.getJSONObject("content").getJSONObject("initRecruit").put("team", selectedKey);
        popPending(current, recruitPending);
        enterFirstZone(current);
        JSONObject result = response(user.getJSONObject("rlv2"));
        result.put("items", new JSONArray());
        return result;
    }

    static JSONObject moveTo(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        if (!"WAIT_MOVE".equals(current.getJSONObject("player").getString("state"))) {
            throw new IllegalArgumentException("game is not waiting for a move");
        }
        JSONObject position = requirePosition(body.get("to"));
        JSONObject node = requireNextNode(current, position);
        advanceCursor(current, node);
        requireSupportedNode(node);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject moveAndBattleStart(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        JSONArray route = body.getJSONArray("route");
        JSONObject node = null;
        if (route == null || route.isEmpty()) {
            JSONObject to = body.getJSONObject("to");
            if (to == null) throw new IllegalArgumentException("route is required");
            node = nodeAt(current, requirePosition(to));
            if (node == null) throw new IllegalArgumentException("destination node does not exist");
            JSONObject cursorPosition = current.getJSONObject("player").getJSONObject("cursor")
                    .getJSONObject("position");
            if (!samePosition(cursorPosition, node.getJSONObject("pos"))) {
                node = requireNextNode(current, node.getJSONObject("pos"));
                advanceCursor(current, node);
            }
        } else {
            for (int index = 0; index < route.size(); index++) {
                String nodeKey = String.valueOf(route.get(index));
                node = currentZone(current).getJSONObject("nodes").getJSONObject(nodeKey);
                if (node == null) throw new IllegalArgumentException("route node does not exist");
                JSONObject cursorPosition = current.getJSONObject("player").getJSONObject("cursor")
                        .getJSONObject("position");
                if (cursorPosition != null && samePosition(cursorPosition, node.getJSONObject("pos"))) continue;
                node = requireNextNode(current, node.getJSONObject("pos"));
                advanceCursor(current, node);
            }
        }
        if (node == null) throw new IllegalArgumentException("route has no destination");
        requireBattleNode(node);
        String stageId = requireText(body, "stageId");
        if (!stageId.equals(node.getString("stage"))) {
            throw new IllegalArgumentException("stage does not match the destination node");
        }
        if (body.getJSONObject("squad") == null) throw new IllegalArgumentException("squad is required");
        return startPendingBattle(user, current, node, stageId, body.getJSONObject("squad"));
    }

    static JSONObject battleStart(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        JSONObject position = requirePosition(body.get("to"));
        JSONObject node = nodeAt(current, position);
        if (node == null) throw new IllegalArgumentException("destination node does not exist");
        JSONObject cursorPosition = current.getJSONObject("player").getJSONObject("cursor")
                .getJSONObject("position");
        if (!samePosition(cursorPosition, node.getJSONObject("pos"))) {
            throw new IllegalArgumentException("battle must start from the cursor node");
        }
        requireBattleNode(node);
        String stageId = requireText(body, "stageId");
        if (!stageId.equals(node.getString("stage"))) {
            throw new IllegalArgumentException("stage does not match the destination node");
        }
        if (body.getJSONObject("squad") == null) throw new IllegalArgumentException("squad is required");
        return startPendingBattle(user, current, node, stageId, body.getJSONObject("squad"));
    }

    static JSONObject battleFinish(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt == null || receipt.getBooleanValue("settled")) {
            throw new IllegalArgumentException("rogue battle is missing or already settled");
        }
        String status = user.getJSONObject("pushFlags").getString("status");
        JSONObject battleData = Utils.BattleData_decrypt(requireText(body, "data"), status);
        if (battleData == null || !receipt.getString("battleId").equals(battleData.getString("battleId"))) {
            throw new IllegalArgumentException("invalid rogue battle data");
        }
        int state = battleData.getIntValue("completeState");
        if (state < 1 || state > 4) throw new IllegalArgumentException("rogue battle has not finished");

        receipt.put("settled", true);
        JSONObject player = current.getJSONObject("player");
        player.put("pending", new JSONArray());
        if (state == 3 || state == 4) {
            JSONObject rewardPending = new JSONObject(true);
            rewardPending.put("index", "e_5");
            rewardPending.put("type", "BATTLE_REWARD");
            JSONObject content = new JSONObject(true);
            JSONObject battleReward = new JSONObject(true);
            battleReward.put("earn", emptyBattleEarn());
            // The shipped topic table names exp/gold items but does not publish battle award
            // amounts. Keep the reward step explicit and empty until captured table/server data
            // establishes those amounts.
            battleReward.put("rewards", new JSONArray());
            battleReward.put("show", "1");
            battleReward.put("state", 0);
            battleReward.put("isPerfect", state == 4 ? 1 : 0);
            content.put("battleReward", battleReward);
            rewardPending.put("content", content);
            player.getJSONArray("pending").add(rewardPending);
            player.put("state", "PENDING");
        } else {
            player.put("state", "WAIT_MOVE");
        }
        return finishBattleResponse(user.getJSONObject("rlv2"));
    }

    static JSONObject chooseBattleReward(JSONObject user, JSONObject body) {
        JSONObject current = activeCurrent(user);
        JSONObject pending = requirePending(current, "BATTLE_REWARD");
        JSONObject battleReward = pending.getJSONObject("content").getJSONObject("battleReward");
        int index = requireInteger(body, "index");
        int sub = requireInteger(body, "sub");
        JSONObject option = null;
        for (int i = 0; i < battleReward.getJSONArray("rewards").size(); i++) {
            JSONObject candidate = battleReward.getJSONArray("rewards").getJSONObject(i);
            if (candidate.getIntValue("index") == index) {
                option = candidate;
                break;
            }
        }
        if (option == null || option.getIntValue("done") != 0) {
            throw new IllegalArgumentException("battle reward is not available");
        }
        JSONObject property = current.getJSONObject("player").getJSONObject("property");
        JSONArray items = option.getJSONArray("items");
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.containsKey("sub") && item.getIntValue("sub") != sub) {
                throw new IllegalArgumentException("battle reward subtype is not available");
            }
            if (item.getString("id").equals(user.getJSONObject(RECEIPT).getString("goldItemId"))) {
                property.put("gold", property.getIntValue("gold") + item.getIntValue("count"));
            } else {
                throw new IllegalArgumentException("unsupported battle reward item");
            }
        }
        option.put("done", 1);
        return response(user.getJSONObject("rlv2"));
    }

    static JSONObject finishBattleReward(JSONObject user) {
        JSONObject current = activeCurrent(user);
        JSONObject pending = requirePending(current, "BATTLE_REWARD");
        JSONArray rewards = pending.getJSONObject("content").getJSONObject("battleReward").getJSONArray("rewards");
        boolean selected = rewards.isEmpty();
        for (int i = 0; i < rewards.size(); i++) {
            if (rewards.getJSONObject(i).getIntValue("done") == 1) {
                selected = true;
                break;
            }
        }
        if (!selected) throw new IllegalArgumentException("battle reward has not been selected");
        JSONObject node = cursorNode(current);
        node.put("fts", System.currentTimeMillis() / 1000L);
        popPending(current, pending);
        current.getJSONObject("player").put("state", "WAIT_MOVE");
        user.remove(RECEIPT);
        return response(user.getJSONObject("rlv2"));
    }

    private static JSONObject findInit(
            JSONArray records,
            String mode,
            int modeGrade,
            String predefinedId) {
        if (records == null) return null;
        for (int index = 0; index < records.size(); index++) {
            JSONObject record = records.getJSONObject(index);
            if (mode.equals(record.getString("modeId"))
                    && modeGrade == record.getIntValue("modeGrade")
                    && Objects.equals(predefinedId, normalizeId(record.getString("predefinedId")))) {
                return record;
            }
        }
        return null;
    }

    private static JSONObject initialState(
            JSONObject detail,
            JSONObject init,
            String gameId,
            String theme,
            String mode,
            int modeGrade,
            String predefinedId,
            String activityId,
            long nowSeconds) {
        JSONObject current = resetState();
        JSONObject game = current.getJSONObject("game");
        game.put("uid", gameId);
        game.put("theme", theme);
        game.put("mode", mode);
        game.put("modeGrade", modeGrade);
        game.put("equivalentGrade", modeGrade);
        game.put("predefined", predefinedId);
        game.put("difficult", init.getIntValue("initialKey"));
        game.put("start", nowSeconds);
        game.put("activity", activityId == null ? "" : activityId);

        JSONObject player = current.getJSONObject("player");
        JSONObject property = player.getJSONObject("property");
        int initialHp = init.getIntValue("initialHp");
        int initialMaxHp = init.getIntValue("initialMaxHp");
        current.getJSONObject("inventory").put(
                "stashRecruitLimit",
                detail.getJSONObject("gameConst").getIntValue("recruitStashMaxNum"));
        property.put("gold", init.getIntValue("initialGold"));
        property.put("shield", init.getIntValue("initialShield"));
        JSONObject hp = property.getJSONObject("hp");
        hp.put("current", initialHp);
        hp.put("max", initialHp + initialMaxHp);
        JSONObject population = property.getJSONObject("population");
        population.put("max", init.getIntValue("initialPopulation"));
        property.put("capacity", init.getIntValue("initialSquadCapacity"));
        property.put("maxLevel", maxPlayerLevel(detail.getJSONObject("detailConst")));
        player.put("toEnding", firstKey(detail.getJSONObject("endings")));

        JSONArray pending = player.getJSONArray("pending");
        pending.add(initialRelicPending(init.getJSONArray("initialBandRelic")));
        pending.add(initialRecruitSetPending(init.getJSONArray("initialRecruitGroup")));
        JSONObject explorePending = initialExploreToolPending(detail.getJSONObject("exploreTools"));
        if (explorePending != null) pending.add(explorePending);
        pending.add(initialRecruitPending());
        player.put("state", "INIT");
        return current;
    }

    private static JSONObject initialRelicPending(JSONArray relicIds) {
        JSONObject items = new JSONObject(true);
        if (relicIds != null) {
            for (int index = 0; index < relicIds.size(); index++) {
                JSONObject item = new JSONObject(true);
                item.put("id", relicIds.getString(index));
                item.put("count", 1);
                items.put(String.valueOf(index), item);
            }
        }
        JSONObject initRelic = new JSONObject(true);
        initRelic.put("step", jsonArray(1, 3));
        initRelic.put("items", items);
        return pendingEvent("e_0", "GAME_INIT_RELIC", "initRelic", initRelic);
    }

    private static JSONObject initialRecruitSetPending(JSONArray groups) {
        JSONObject initRecruitSet = new JSONObject(true);
        initRecruitSet.put("step", jsonArray(2, 3));
        initRecruitSet.put("option", groups == null ? new JSONArray() : groups);
        return pendingEvent("e_1", "GAME_INIT_RECRUIT_SET", "initRecruitSet", initRecruitSet);
    }

    private static JSONObject initialExploreToolPending(JSONObject tools) {
        if (tools == null || tools.isEmpty()) return null;
        JSONObject items = new JSONObject(true);
        int count = 0;
        for (String id : tools.keySet()) {
            JSONObject item = new JSONObject(true);
            item.put("id", id);
            item.put("count", 1);
            items.put(String.valueOf(count++), item);
            if (count == 3) break;
        }
        JSONObject initExploreTool = new JSONObject(true);
        initExploreTool.put("step", jsonArray(2, 3));
        initExploreTool.put("items", items);
        return pendingEvent("e_tool", "GAME_INIT_EXPLORE_TOOL", "initExploreTool", initExploreTool);
    }

    private static JSONObject initialRecruitPending() {
        JSONObject initRecruit = new JSONObject(true);
        initRecruit.put("step", jsonArray(3, 3));
        initRecruit.put("tickets", new JSONArray());
        initRecruit.put("showChar", new JSONArray());
        initRecruit.put("team", null);
        return pendingEvent("e_2", "GAME_INIT_RECRUIT", "initRecruit", initRecruit);
    }

    private static JSONObject pendingEvent(String index, String type, String key, JSONObject content) {
        JSONObject pending = new JSONObject(true);
        pending.put("index", index);
        pending.put("type", type);
        JSONObject payload = new JSONObject(true);
        payload.put(key, content);
        pending.put("content", payload);
        return pending;
    }

    private static JSONObject response(JSONObject rlv2) {
        JSONObject modified = new JSONObject(true);
        modified.put("rlv2", rlv2);
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        result.put("pushMessage", new JSONArray());
        return result;
    }

    private static JSONObject finishBattleResponse(JSONObject rlv2) {
        JSONObject result = response(rlv2);
        result.put("apFailReturn", 0);
        result.put("itemReturn", new JSONArray());
        result.put("rewards", new JSONArray());
        result.put("unusualRewards", new JSONArray());
        result.put("overrideRewards", new JSONArray());
        result.put("additionalRewards", new JSONArray());
        result.put("diamondMaterialRewards", new JSONArray());
        result.put("furnitureRewards", new JSONArray());
        return result;
    }

    private static JSONObject startPendingBattle(
            JSONObject user,
            JSONObject current,
            JSONObject node,
            String stageId,
            JSONObject squad) {
        JSONObject player = current.getJSONObject("player");
        player.put("state", "PENDING");
        JSONObject battle = new JSONObject(true);
        battle.put("state", 1);
        battle.put("chestCnt", 0);
        battle.put("goldTrapCnt", 0);
        battle.put("tmpChar", new JSONArray());
        battle.put("unKeepBuff", new JSONArray());
        battle.put("seed", stageId.hashCode() & Integer.MAX_VALUE);
        JSONObject pending = pendingEvent("e_4", "BATTLE", "battle", battle);
        JSONArray pendingList = new JSONArray();
        pendingList.add(pending);
        player.put("pending", pendingList);

        String battleId = UUID.randomUUID().toString();
        JSONObject receipt = new JSONObject(true);
        receipt.put("battleId", battleId);
        receipt.put("stageId", stageId);
        receipt.put("goldItemId", goldItemId(user));
        receipt.put("squad", squad);
        receipt.put("settled", false);
        user.put(RECEIPT, receipt);

        JSONObject result = response(user.getJSONObject("rlv2"));
        result.put("battleId", battleId);
        result.put("isApProtect", false);
        result.put("apFailReturn", 0);
        result.put("notifyPowerScoreNotEnoughIfFailed", false);
        result.put("inApProtectPeriod", false);
        return result;
    }

    private static void enterFirstZone(JSONObject current) {
        JSONObject detail = themeDetail(current);
        JSONObject zone = firstObject(detail.getJSONObject("zones"));
        JSONObject firstStage = firstStage(detail.getJSONObject("stages"));
        JSONObject secondStage = secondStage(detail.getJSONObject("stages"));
        if (zone == null || firstStage == null) {
            throw new IllegalArgumentException("theme has no usable starting zone or stage");
        }

        JSONObject start = mapNode(0, 0, NODE_NONE, null);
        JSONObject battleOne = mapNode(1, 0, NODE_BATTLE_NORMAL, firstStage.getString("id"));
        JSONObject battleTwo = mapNode(2, 0, NODE_BATTLE_NORMAL,
                secondStage == null ? firstStage.getString("id") : secondStage.getString("id"));
        JSONObject zoneEnd = mapNode(3, 0, NODE_FINAL, null);
        JSONArray links = start.getJSONArray("next");
        links.add(nodeLine(1, 0));
        battleOne.getJSONArray("next").add(nodeLine(2, 0));
        battleTwo.getJSONArray("next").add(nodeLine(3, 0));

        JSONObject nodes = new JSONObject(true);
        nodes.put("0", start);
        nodes.put("100", battleOne);
        nodes.put("200", battleTwo);
        nodes.put("300", zoneEnd);
        JSONObject generatedZone = new JSONObject(true);
        generatedZone.put("id", zone.getString("id"));
        generatedZone.put("nodes", nodes);
        generatedZone.put("variation", new JSONArray());
        generatedZone.put("type", "NORMAL");
        JSONObject zones = new JSONObject(true);
        zones.put("1", generatedZone);
        current.getJSONObject("map").put("zones", zones);

        JSONObject player = current.getJSONObject("player");
        JSONObject cursor = player.getJSONObject("cursor");
        cursor.put("zone", 1);
        cursor.put("position", position(0, 0));
        JSONArray trace = player.getJSONArray("trace");
        trace.clear();
        trace.add(traceNode(1, position(0, 0)));
        player.put("state", "WAIT_MOVE");
    }

    private static JSONObject mapNode(int x, int y, String type, String stage) {
        JSONObject node = new JSONObject(true);
        node.put("pos", position(x, y));
        node.put("next", new JSONArray());
        node.put("type", type);
        node.put("style", 0);
        node.put("realContent", null);
        node.put("attach", new JSONArray());
        node.put("shop", null);
        node.put("scenes", new JSONArray());
        if (stage != null) node.put("stage", stage);
        node.put("visibility", "NORMAL");
        node.put("refresh", null);
        return node;
    }

    private static JSONObject position(int x, int y) {
        JSONObject position = new JSONObject(true);
        position.put("x", x);
        position.put("y", y);
        return position;
    }

    private static JSONObject nodeLine(int x, int y) {
        JSONObject line = new JSONObject(true);
        line.put("x", x);
        line.put("y", y);
        line.put("hidden", "SHOW");
        line.put("key", false);
        return line;
    }

    private static JSONObject requirePosition(Object value) {
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("node position is required");
        JSONObject position = (JSONObject) value;
        if (!(position.get("x") instanceof Number) || !(position.get("y") instanceof Number)) {
            throw new IllegalArgumentException("node position is invalid");
        }
        return position;
    }

    private static JSONObject requireNextNode(JSONObject current, JSONObject target) {
        JSONObject currentPosition = current.getJSONObject("player").getJSONObject("cursor").getJSONObject("position");
        JSONObject currentNode = nodeAt(current, currentPosition);
        JSONArray next = currentNode == null ? null : currentNode.getJSONArray("next");
        boolean linked = false;
        for (int index = 0; next != null && index < next.size(); index++) {
            JSONObject link = next.getJSONObject(index);
            if (target.getIntValue("x") == link.getIntValue("x")
                    && target.getIntValue("y") == link.getIntValue("y")) {
                linked = true;
                break;
            }
        }
        if (!linked) throw new IllegalArgumentException("destination node is not directly reachable");
        JSONObject node = nodeAt(current, target);
        if (node == null) throw new IllegalArgumentException("destination node does not exist");
        return node;
    }

    private static void advanceCursor(JSONObject current, JSONObject node) {
        JSONObject player = current.getJSONObject("player");
        JSONObject nodePosition = node.getJSONObject("pos");
        player.getJSONObject("cursor").put("position",
                position(nodePosition.getIntValue("x"), nodePosition.getIntValue("y")));
        JSONArray trace = player.getJSONArray("trace");
        trace.add(traceNode(player.getJSONObject("cursor").getIntValue("zone"),
                position(nodePosition.getIntValue("x"), nodePosition.getIntValue("y"))));
    }

    private static JSONObject traceNode(int zone, JSONObject position) {
        JSONObject traceNode = new JSONObject(true);
        traceNode.put("zone", zone);
        traceNode.put("position", position);
        return traceNode;
    }

    private static void requireSupportedNode(JSONObject node) {
        if (!isBattleNode(node.getString("type")) && !NODE_FINAL.equals(node.getString("type"))) {
            throw new IllegalArgumentException("this roguelike node mechanism is not supported yet");
        }
    }

    private static void requireBattleNode(JSONObject node) {
        if (!isBattleNode(node.getString("type")) || node.getString("stage") == null) {
            throw new IllegalArgumentException("destination is not a battle node");
        }
    }

    private static boolean isBattleNode(String type) {
        return NODE_BATTLE_NORMAL.equals(type)
                || NODE_BATTLE_ELITE.equals(type)
                || NODE_BATTLE_BOSS.equals(type);
    }

    private static JSONObject nodeAt(JSONObject current, JSONObject position) {
        if (position == null) return null;
        JSONObject nodes = currentZone(current).getJSONObject("nodes");
        for (String key : nodes.keySet()) {
            JSONObject node = nodes.getJSONObject(key);
            if (samePosition(node.getJSONObject("pos"), position)) {
                return node;
            }
        }
        return null;
    }

    private static boolean samePosition(JSONObject left, JSONObject right) {
        return left != null
                && right != null
                && left.getIntValue("x") == right.getIntValue("x")
                && left.getIntValue("y") == right.getIntValue("y");
    }

    private static JSONObject cursorNode(JSONObject current) {
        JSONObject node = nodeAt(current, current.getJSONObject("player").getJSONObject("cursor").getJSONObject("position"));
        if (node == null) throw new IllegalArgumentException("cursor is not on a map node");
        return node;
    }

    private static JSONObject currentZone(JSONObject current) {
        int zone = current.getJSONObject("player").getJSONObject("cursor").getIntValue("zone");
        JSONObject value = current.getJSONObject("map").getJSONObject("zones").getJSONObject(String.valueOf(zone));
        if (value == null) throw new IllegalArgumentException("current map zone is missing");
        return value;
    }

    private static List<String> ticketDefinitionIds(JSONObject detail, String groupId) {
        String group = groupId.startsWith("recruit_group_")
                ? groupId.substring("recruit_group_".length())
                : groupId;
        List<String> professions = switch (group) {
            case "1" -> List.of("PIONEER", "SNIPER", "SPECIAL");
            case "2" -> List.of("CASTER", "CASTER", "CASTER");
            case "3" -> List.of("WARRIOR", "SUPPORT", "MEDIC");
            case "c4" -> List.of("SNIPER", "SNIPER", "CASTER", "CASTER");
            case "c5" -> List.of("WARRIOR", "WARRIOR", "TANK", "TANK");
            default -> List.of();
        };
        JSONObject definitions = detail.getJSONObject("recruitTickets");
        ArrayList<String> result = new ArrayList<>();
        if (!professions.isEmpty()) {
            for (String profession : professions) {
                String ticketId = ticketForProfession(definitions, profession);
                if (ticketId != null) result.add(ticketId);
            }
        }
        if (result.isEmpty() && definitions != null) {
            for (String id : definitions.keySet()) {
                if (definitions.getJSONObject(id).getString("profession") != null) result.add(id);
                if (result.size() == 3) break;
            }
        }
        result.removeIf(Objects::isNull);
        return result;
    }

    private static String ticketForProfession(JSONObject tickets, String profession) {
        if (tickets == null) return null;
        String conventional = "rogue_" + themeNumberFromTickets(tickets) + "_recruit_ticket_"
                + profession.toLowerCase(Locale.ROOT);
        if (tickets.containsKey(conventional)) return conventional;
        for (String id : tickets.keySet()) {
            if (profession.equals(tickets.getJSONObject(id).getString("profession"))) return id;
        }
        return null;
    }

    private static String themeNumberFromTickets(JSONObject tickets) {
        for (String id : tickets.keySet()) {
            int start = id.indexOf('_');
            int end = id.indexOf('_', start + 1);
            if (start >= 0 && end > start) return id.substring(start + 1, end);
        }
        return "";
    }

    private static JSONArray ticketCandidates(
            JSONObject detail,
            String definitionId,
            JSONObject current,
            JSONObject ownedChars) {
        JSONObject definition = detail.getJSONObject("recruitTickets").getJSONObject(definitionId);
        JSONObject characters = ArknightsApplication.characterJson;
        JSONArray result = new JSONArray();
        if (definition == null || characters == null || ownedChars == null) return result;
        JSONObject squadChars = current.getJSONObject("troop").getJSONObject("chars");
        for (String ownedKey : ownedChars.keySet()) {
            JSONObject owned = ownedChars.getJSONObject(ownedKey);
            Object instId = owned == null ? null : owned.get("instId");
            String charId = owned == null ? null : owned.getString("charId");
            JSONObject character = charId == null ? null : characters.getJSONObject(charId);
            if (instId == null || character == null
                    || squadChars.containsKey(String.valueOf(instId))
                    || !matchesProfession(definition, character.getString("profession"))
                    || !matchesRarity(definition, character.getString("rarity"))) {
                continue;
            }
            JSONObject option = JSON.parseObject(owned.toJSONString());
            applyRecruitLevelRule(character, option);
            option.put("type", "NORMAL");
            option.put("upgradePhase", 0);
            option.put("upgradeLimited", false);
            option.put("population", recruitPopulation(character.getString("rarity")));
            option.put("isUpgrade", false);
            option.put("charBuff", new JSONArray());
            option.put("troopInstId", instId);
            result.add(option);
        }
        return result;
    }

    private static boolean matchesProfession(JSONObject definition, String profession) {
        if (profession == null) return false;
        JSONArray professions = definition.getJSONArray("professionList");
        if (professions != null && !professions.isEmpty()) return professions.contains(profession);
        String fallback = definition.getString("profession");
        return fallback == null || fallback.isEmpty() || fallback.equals(profession);
    }

    private static boolean matchesRarity(JSONObject definition, String rarity) {
        if (rarity == null) return false;
        JSONArray rarities = definition.getJSONArray("rarityList");
        if (rarities != null && !rarities.isEmpty()) {
            return rarities.contains("ALL") || rarities.contains(rarity);
        }
        String fallback = definition.getString("rarity");
        return fallback == null || fallback.isEmpty() || "ALL".equals(fallback) || fallback.equals(rarity);
    }

    private static void applyRecruitLevelRule(JSONObject character, JSONObject recruited) {
        int ownedPhase = recruited.getIntValue("evolvePhase");
        if (ownedPhase < 2) return;
        JSONArray phases = character.getJSONArray("phases");
        JSONObject phase = phases == null || phases.size() < 2 ? null : phases.getJSONObject(1);
        if (phase == null) return;
        recruited.put("evolvePhase", 1);
        recruited.put("level", phase.getIntValue("maxLevel"));
        recruited.put("exp", 0);
        recruited.put("currentEquip", null);
        JSONArray skills = recruited.getJSONArray("skills");
        for (int index = 0; skills != null && index < skills.size(); index++) {
            skills.getJSONObject(index).put("specializeLevel", 0);
        }
    }

    private static JSONObject runtimeCharacter(JSONObject recruited) {
        JSONObject runtime = JSON.parseObject(recruited.toJSONString());
        runtime.remove("type");
        runtime.remove("upgradePhase");
        runtime.remove("upgradeLimited");
        runtime.remove("population");
        runtime.remove("isUpgrade");
        runtime.remove("isCure");
        runtime.remove("charBuff");
        runtime.remove("troopInstId");
        JSONArray skills = runtime.getJSONArray("skills");
        JSONArray wireSkills = new JSONArray();
        for (int index = 0; skills != null && index < skills.size(); index++) {
            JSONObject source = skills.getJSONObject(index);
            JSONObject skill = new JSONObject(true);
            skill.put("unlock", source.getBooleanValue("unlock"));
            skill.put("skillId", source.getString("skillId"));
            skill.put("specializeLevel", source.getIntValue("specializeLevel"));
            wireSkills.add(skill);
        }
        runtime.put("skills", wireSkills);
        return runtime;
    }

    private static void removeRecruitedCandidate(JSONObject current, String settledTicketId, String ownedId) {
        JSONObject tickets = current.getJSONObject("inventory").getJSONObject("recruit");
        for (String ticketId : tickets.keySet()) {
            if (ticketId.equals(settledTicketId)) continue;
            JSONArray candidates = tickets.getJSONObject(ticketId).getJSONArray("list");
            for (int index = candidates.size() - 1; index >= 0; index--) {
                if (ownedId.equals(String.valueOf(candidates.getJSONObject(index).get("instId")))) {
                    candidates.remove(index);
                }
            }
        }
    }

    private static int recruitPopulation(String rarity) {
        return switch (rarity) {
            case "TIER_6" -> 6;
            case "TIER_5" -> 4;
            case "TIER_4" -> 2;
            default -> 0;
        };
    }

    private static String nextTicketId(JSONObject tickets) {
        int index = 1;
        while (tickets.containsKey("t_" + index)) index++;
        return "t_" + index;
    }

    private static boolean hasSettledInitialTicket(JSONObject current, JSONObject pending) {
        JSONArray tickets = pending.getJSONObject("content").getJSONObject("initRecruit").getJSONArray("tickets");
        JSONObject inventory = current.getJSONObject("inventory").getJSONObject("recruit");
        for (int index = 0; index < tickets.size(); index++) {
            JSONObject ticket = inventory.getJSONObject(tickets.getString(index));
            if (ticket != null
                    && ticket.getIntValue("state") == 2
                    && ticket.getJSONObject("result") != null) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject emptyBattleEarn() {
        JSONObject earn = new JSONObject(true);
        earn.put("damage", 0);
        earn.put("hp", 0);
        earn.put("shield", 0);
        earn.put("exp", 0);
        earn.put("populationMax", 0);
        earn.put("squadCapacity", 0);
        earn.put("maxHpUp", 0);
        return earn;
    }

    private static String goldItemId(JSONObject user) {
        JSONObject detail = detailForUser(user);
        String id = detail.getJSONObject("gameConst").getString("goldItemId");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("theme has no gold item");
        return id;
    }

    private static JSONObject firstStage(JSONObject stages) {
        if (stages == null) return null;
        for (String key : stages.keySet()) {
            JSONObject stage = stages.getJSONObject(key);
            if ("NORMAL".equals(stage.getString("difficulty"))
                    && stage.getIntValue("isBoss") == 0 && stage.getIntValue("isElite") == 0) {
                return stage;
            }
        }
        return null;
    }

    private static JSONObject secondStage(JSONObject stages) {
        JSONObject first = firstStage(stages);
        boolean skipped = false;
        for (String key : stages.keySet()) {
            JSONObject stage = stages.getJSONObject(key);
            if ("NORMAL".equals(stage.getString("difficulty"))
                    && stage.getIntValue("isBoss") == 0 && stage.getIntValue("isElite") == 0) {
                if (skipped) return stage;
                skipped = true;
            }
        }
        return first;
    }

    private static JSONObject firstObject(JSONObject values) {
        if (values == null) return null;
        for (String key : values.keySet()) return values.getJSONObject(key);
        return null;
    }

    private static int maxPlayerLevel(JSONObject detailConst) {
        JSONObject levels = detailConst == null ? null : detailConst.getJSONObject("playerLevelTable");
        if (levels == null || levels.isEmpty()) return 10;
        int max = 1;
        for (String key : levels.keySet()) max = Math.max(max, Integer.parseInt(key));
        return max;
    }

    private static String firstKey(JSONObject values) {
        if (values == null) return "";
        for (String key : values.keySet()) return key;
        return "";
    }

    private static JSONObject resetState() {
        JSONObject game = new JSONObject(true);
        game.put("uid", null);
        game.put("mode", "NONE");
        game.put("outerBuff", new JSONObject(true));
        game.put("start", -1L);
        game.put("modeGrade", 0);
        game.put("theme", "");
        game.put("equivalentGrade", 0);
        game.put("predefined", null);
        game.put("difficult", 0);
        game.put("activity", null);

        JSONObject current = new JSONObject(true);
        current.put("game", game);
        current.put("module", new JSONObject(true));

        JSONObject troop = new JSONObject(true);
        troop.put("hasExpeditionReturn", false);
        troop.put("expedition", new JSONArray());
        troop.put("expeditionReturn", null);
        troop.put("chars", new JSONObject(true));
        troop.put("expeditionDetails", new JSONObject(true));
        current.put("troop", troop);

        JSONObject inventory = new JSONObject(true);
        inventory.put("exploreTool", new JSONObject(true));
        inventory.put("relic", new JSONObject(true));
        inventory.put("recruit", new JSONObject(true));
        inventory.put("stashRecruit", new JSONArray());
        inventory.put("stashRecruitLimit", 0);
        inventory.put("consumable", new JSONObject(true));
        inventory.put("trap", null);
        current.put("inventory", inventory);

        JSONObject map = new JSONObject(true);
        map.put("zones", new JSONObject(true));
        map.put("verticalCostDelta", 0);
        current.put("map", map);

        JSONObject buff = new JSONObject(true);
        buff.put("capsule", null);
        buff.put("tmpHP", 0);
        buff.put("squadBuff", new JSONArray());
        current.put("buff", buff);

        JSONObject cursor = new JSONObject(true);
        cursor.put("zone", 0);
        cursor.put("position", null);
        JSONObject property = new JSONObject(true);
        property.put("gold", 0);
        property.put("maxLevel", 0);
        property.put("shield", 0);
        property.put("level", 1);
        JSONObject hp = new JSONObject(true);
        hp.put("current", 0);
        hp.put("max", 0);
        property.put("hp", hp);
        property.put("exp", 0);
        property.put("conPerfectBattle", 0);
        property.put("capacity", 0);
        property.put("hpShowState", "NORMAL");
        JSONObject population = new JSONObject(true);
        population.put("cost", 0);
        population.put("max", 0);
        property.put("population", population);
        JSONObject status = new JSONObject(true);
        status.put("bankPut", 0);
        JSONObject player = new JSONObject(true);
        player.put("cursor", cursor);
        player.put("trace", new JSONArray());
        player.put("toEnding", "");
        player.put("pending", new JSONArray());
        player.put("chgEnding", false);
        player.put("property", property);
        player.put("state", "NONE");
        player.put("status", status);
        player.put("innerMission", new JSONArray());
        player.put("nodeMission", new JSONObject(true));
        player.put("zoneReward", new JSONObject(true));
        player.put("traderReturn", new JSONObject(true));
        current.put("player", player);
        return current;
    }

    static boolean normalizeZoneIndexes(JSONObject user) {
        JSONObject rlv2 = user.getJSONObject("rlv2");
        JSONObject current = rlv2 == null ? null : rlv2.getJSONObject("current");
        if (!isActive(current)) return false;
        JSONObject zones = current.getJSONObject("map").getJSONObject("zones");
        JSONObject player = current.getJSONObject("player");
        JSONObject cursor = player.getJSONObject("cursor");
        if (zones.size() != 1 || !zones.containsKey("0") || cursor.getIntValue("zone") != 0)
            return false;
        // Zone zero is the client's pre-dungeon sentinel, never a playable floor.
        zones.put("1", zones.remove("0"));
        cursor.put("zone", 1);
        JSONArray trace = player.getJSONArray("trace");
        for (int i = 0; i < trace.size(); i++) {
            JSONObject step = trace.getJSONObject(i);
            if (step.getIntValue("zone") == 0) step.put("zone", 1);
        }
        return true;
    }

    private static JSONObject activeCurrent(JSONObject user) {
        JSONObject rlv2 = user.getJSONObject("rlv2");
        JSONObject current = rlv2 == null ? null : rlv2.getJSONObject("current");
        if (!isActive(current)) throw new IllegalArgumentException("no active roguelike game");
        normalizeZoneIndexes(user);
        return current;
    }

    private static JSONObject detailForUser(JSONObject user) {
        return themeDetail(activeCurrent(user));
    }

    private static JSONObject themeDetail(JSONObject current) {
        String theme = current.getJSONObject("game").getString("theme");
        JSONObject table = ArknightsApplication.roguelikeTable;
        JSONObject detail = table == null ? null : table.getJSONObject("details").getJSONObject(theme);
        if (detail == null) throw new IllegalArgumentException("roguelike theme table is unavailable");
        return detail;
    }

    private static JSONObject requirePending(JSONObject current, String type) {
        JSONObject pending = findPending(current, type);
        if (pending == null) throw new IllegalArgumentException(type + " is not pending");
        return pending;
    }

    private static JSONObject findPending(JSONObject current, String type) {
        JSONArray pending = current.getJSONObject("player").getJSONArray("pending");
        for (int index = 0; pending != null && index < pending.size(); index++) {
            JSONObject event = pending.getJSONObject(index);
            if (type.equals(event.getString("type"))) return event;
        }
        return null;
    }

    private static void popPending(JSONObject current, JSONObject pending) {
        JSONArray events = current.getJSONObject("player").getJSONArray("pending");
        for (int index = 0; index < events.size(); index++) {
            if (events.getJSONObject(index) == pending) {
                events.remove(index);
                return;
            }
        }
        throw new IllegalArgumentException("pending event is missing");
    }

    private static String requireSelect(String select) {
        if (select == null || select.isEmpty()) throw new IllegalArgumentException("select is required");
        return select;
    }

    private static String requireText(JSONObject body, String key) {
        String value = body == null ? null : body.getString(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int requireInteger(JSONObject body, String key) {
        Object value = body.get(key);
        if (!(value instanceof Integer)) throw new IllegalArgumentException(key + " must be an integer");
        return (Integer) value;
    }

    private static JSONArray jsonArray(Integer... values) {
        JSONArray result = new JSONArray();
        for (Integer value : values) result.add(value);
        return result;
    }

    private static boolean isActive(JSONObject current) {
        if (current == null) return false;
        JSONObject game = current.getJSONObject("game");
        if (game == null) return false;
        String theme = game.getString("theme");
        String mode = game.getString("mode");
        return (theme != null && !theme.isEmpty())
                || (mode != null && !"NONE".equals(mode))
                || (game.containsKey("start") && game.getLongValue("start") >= 0);
    }

    private static String normalizeId(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
