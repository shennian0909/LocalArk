package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;

final class HomeCustomizationSupport {
    private HomeCustomizationSupport() {}

    static JSONObject setSecretary(JSONObject user, int charInstId, String skinId) {
        JSONObject operator = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "troop"), "chars")
                .getJSONObject(String.valueOf(charInstId));
        if (operator == null) throw new IllegalArgumentException("operator not owned");
        String charId = operator.getString("charId");
        validateSkin(user, charId, skinId);
        JSONObject status = ShopTransactionSupport.object(user, "status");
        status.put("secretary", charId);
        status.put("secretarySkinId", skinId);
        // This route carries no special-dynamic-illustration request, so both representations
        // of the secretary must drop the flag. A preset left with profileSp true and a normal
        // profile later fails its own table check and cannot be applied.
        status.put("secretarySkinSp", false);
        JSONObject preset = activePreset(user);
        if (preset != null) {
            preset.put("profile", skinId);
            preset.put("profileInst", charInstId);
            preset.put("profileSp", false);
        }
        return delta(user, "status");
    }

    static JSONObject select(JSONObject user, String section, String bucket, String id) {
        requireSelection(user, section, bucket, id);
        ShopTransactionSupport.object(user, section).put("selected", id);
        JSONObject preset = activePreset(user);
        if (preset != null) preset.put(section, id);
        return delta(user, section);
    }

    static void requireSelection(JSONObject user, String section, String bucket, String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("selection id is required");
        JSONObject entry = ShopTransactionSupport.object(ShopTransactionSupport.object(user, section), bucket).getJSONObject(id);
        if (entry == null || !entry.containsKey("unlock")) throw new IllegalArgumentException("selection not unlocked");
    }

    static void validateSkin(JSONObject user, String charId, String skinId) {
        if (charId == null || skinId == null || skinId.isEmpty()) throw new IllegalArgumentException("skin id is required");
        JSONObject operators = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "troop"), "chars");
        JSONObject owned = null;
        for (String key : operators.keySet()) {
            JSONObject candidate = operators.getJSONObject(key);
            if (charId.equals(candidate.getString("charId"))) { owned = candidate; break; }
        }
        if (owned == null) throw new IllegalArgumentException("operator not owned");
        JSONObject table = CharacterBuildingSupport.table("excel/skin_table.json");
        JSONObject skin = table.getJSONObject("charSkins").getJSONObject(skinId);
        if (skin == null || !charId.equals(skin.getString("charId"))) throw new IllegalArgumentException("skin does not match operator");
        JSONObject builtins = table.getJSONObject("buildinEvolveMap").getJSONObject(charId);
        if (builtins != null) for (int phase = 0; phase <= owned.getIntValue("evolvePhase"); phase++) {
            if (skinId.equals(builtins.getString(String.valueOf(phase)))) return;
        }
        JSONObject skins = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "skin"), "characterSkins");
        if (skins.getIntValue(skinId) <= 0) throw new IllegalArgumentException("skin not unlocked");
    }

    private static JSONObject activePreset(JSONObject user) {
        JSONObject rotation = user.getJSONObject("charRotation");
        if (rotation == null || rotation.getJSONObject("preset") == null) return null;
        return rotation.getJSONObject("preset").getJSONObject(rotation.getString("current"));
    }

    private static JSONObject delta(JSONObject user, String section) {
        JSONObject result = new JSONObject(true), delta = new JSONObject(true), modified = new JSONObject(true);
        modified.put(section, user.getJSONObject(section));
        if (user.getJSONObject("charRotation") != null) modified.put("charRotation", user.getJSONObject("charRotation"));
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        return result;
    }
}
