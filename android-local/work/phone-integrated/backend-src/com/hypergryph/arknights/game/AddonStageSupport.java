package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** The existing charBuild routes delegate here; no additional route registration. */
public final class AddonStageSupport {
    private static final String RECEIPT = "_localAddonStageBattle";
    private AddonStageSupport() {}

    public static JSONObject battleStart(JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> {
            if (body == null) throw new IllegalArgumentException("Missing addon battle request");
            String charId = body.getString("charId"), stageId = body.getString("stageId");
            if (charId == null || stageId == null) throw new IllegalArgumentException("Missing character or addon stage");
            JSONObject definition = stageDefinition(charId, stageId);
            requireUnlocked(user, charId, definition);
            JSONObject addon = ShopTransactionSupport.object(user.getJSONObject("troop"), "addon");
            JSONObject character = ShopTransactionSupport.object(addon, charId);
            JSONObject stages = ShopTransactionSupport.object(character, "stage");
            if (!stages.containsKey(stageId))
                stages.put(stageId, JSONObject.parseObject("{fts:-1,rts:-1}"));
            JSONObject receipt = new JSONObject(true);
            receipt.put("battleId", UUID.randomUUID().toString());
            receipt.put("charId", charId);
            receipt.put("stageId", stageId);
            receipt.put("settled", false);
            user.put(RECEIPT, receipt);
            JSONObject result = result(user, new JSONArray());
            result.put("battleId", receipt.getString("battleId"));
            return result;
        });
    }

    public static JSONObject battleFinish(JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> {
            if (body == null || !(body.get("data") instanceof String) || body.getString("data").isEmpty())
                throw new IllegalArgumentException("Missing addon battle data");
            JSONObject decoded;
            try {
                decoded = Utils.BattleData_decrypt(body.getString("data"), user.getJSONObject("pushFlags").getString("status"));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid addon battle data");
            }
            JSONObject receipt = user.getJSONObject(RECEIPT);
            if (receipt == null || receipt.getBooleanValue("settled") || decoded == null
                    || !receipt.getString("battleId").equals(decoded.getString("battleId")))
                throw new IllegalArgumentException("Unknown or already settled addon battle");
            int rating = decoded.getIntValue("completeState");
            if (rating < 1 || rating > 3) throw new IllegalArgumentException("Invalid addon battle result");
            String charId = receipt.getString("charId"), stageId = receipt.getString("stageId");
            JSONObject definition = stageDefinition(charId, stageId);
            JSONObject stage = user.getJSONObject("troop").getJSONObject("addon")
                    .getJSONObject(charId).getJSONObject("stage").getJSONObject(stageId);
            JSONArray rewards = new JSONArray();
            long now = System.currentTimeMillis() / 1000L;
            if (rating >= 2) {
                // Older saves used state=3 in addition to the client GetInfo timestamps.
                // Treat that existing completion as paid so migration cannot award twice.
                boolean alreadyPaid = stage.getLongValue("rts") > 0 || stage.getIntValue("state") >= 3;
                if (stage.getLongValue("fts") <= 0) stage.put("fts", now);
                if (!alreadyPaid) {
                    JSONArray items = definition.getJSONArray("rewardItem");
                    for (int i = 0; i < items.size(); i++)
                        ShopTransactionSupport.reward(user, items.getJSONObject(i), 1, rewards);
                    stage.put("rts", now);
                }
            }
            receipt.put("settled", true);
            return result(user, rewards);
        });
    }

    private static JSONObject stageDefinition(String charId, String stageId) {
        JSONObject definition = CharacterBuildingSupport.table("excel/handbook_info_table.json")
                .getJSONObject("handbookStageData").getJSONObject(charId);
        if (definition == null || !definition.getString("stageId").equals(stageId))
            throw new IllegalArgumentException("Unknown character addon stage");
        return definition;
    }

    private static void requireUnlocked(JSONObject user, String charId, JSONObject definition) {
        JSONObject owned = null;
        JSONObject chars = user.getJSONObject("troop").getJSONObject("chars");
        for (String instId : chars.keySet()) {
            JSONObject character = chars.getJSONObject(instId);
            if (charId.equals(character.getString("charId"))) { owned = character; break; }
        }
        if (owned == null) throw new IllegalArgumentException("Character is not owned");
        JSONArray conditions = definition.getJSONArray("unlockParam");
        for (int i = 0; i < conditions.size(); i++) {
            JSONObject condition = conditions.getJSONObject(i);
            if (!"AWAKE".equals(condition.getString("unlockType")))
                throw new IllegalArgumentException("Unsupported addon unlock condition");
            int phase = Integer.parseInt(condition.getString("unlockParam1"));
            int level = Integer.parseInt(condition.getString("unlockParam2"));
            if (owned.getIntValue("evolvePhase") < phase
                    || owned.getIntValue("evolvePhase") == phase && owned.getIntValue("level") < level)
                throw new IllegalArgumentException("Addon stage is locked");
        }
    }

    private static JSONObject result(JSONObject user, JSONArray firstRewards) {
        JSONObject modified = new JSONObject(true);
        JSONObject troop = new JSONObject(true);
        troop.put("addon", user.getJSONObject("troop").getJSONObject("addon"));
        modified.put("troop", troop);
        modified.put("status", user.getJSONObject("status"));
        modified.put("inventory", user.getJSONObject("inventory"));
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        result.put("firstRewards", firstRewards);
        result.put("apFailReturn", 0);
        for (String key : new String[]{"rewards", "unusualRewards", "overrideRewards", "additionalRewards", "diamondMaterialRewards", "furnitureRewards", "itemReturn"})
            result.put(key, new JSONArray());
        return result;
    }
}
