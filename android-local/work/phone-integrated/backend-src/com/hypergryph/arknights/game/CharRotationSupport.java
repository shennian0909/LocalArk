package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.HashSet;
import java.util.Set;

final class CharRotationSupport {
    private CharRotationSupport() {}

    static JSONObject createPreset(JSONObject user) {
        normalize(user);
        JSONObject rotation = user.getJSONObject("charRotation"), presets = rotation.getJSONObject("preset");
        JSONObject constants = CharacterBuildingSupport.table("excel/gamedata_const.json");
        require(presets.size() < constants.getIntValue("charRotationPresetMaxCnt"), "preset limit reached");
        long next = 1;
        for (String key : presets.keySet()) next = Math.max(next, Math.addExact(Long.parseLong(key), 1));
        String id = Long.toString(next);
        JSONObject preset = JSONObject.parseObject(presets.getJSONObject(rotation.getString("current")).toJSONString());
        preset.put("name", constants.getString("defaultCRPresetName"));
        validatePreset(user, preset);
        presets.put(id, preset);
        JSONObject result = result(user, false);
        result.put("instId", id);
        return result;
    }

    static JSONObject updatePreset(JSONObject user, JSONObject body) {
        String id = text(body, "instId");
        int flag = flags(body);
        require(body.get("data") instanceof JSONObject, "preset data is required");
        JSONObject data = body.getJSONObject("data");
        normalize(user);
        JSONObject rotation = user.getJSONObject("charRotation"), preset = preset(rotation, id);
        if ((flag & 1) != 0) preset.put("name", text(data, "name"));
        if ((flag & 2) != 0) {
            String background = text(data, "background");
            HomeCustomizationSupport.requireSelection(user, "background", "bgs", background);
            preset.put("background", background);
        }
        if ((flag & 4) != 0) {
            String theme = text(data, "homeTheme");
            HomeCustomizationSupport.requireSelection(user, "homeTheme", "themes", theme);
            preset.put("homeTheme", theme);
        }
        if ((flag & 8) != 0) {
            // The client's JsonProperty aliases are profile/profileInst/profileSp on the wire.
            String instanceField = data.containsKey("profileInst") ? "profileInst" : "secretaryCharInstId";
            String skinField = data.containsKey("profile") ? "profile" : "secretarySkinId";
            String specialField = data.containsKey("profileSp") ? "profileSp" : "secretaryShowSpDynIllust";
            Object instance = data.get(instanceField);
            require(instance instanceof String || instance instanceof Integer || instance instanceof Long,
                    "invalid " + instanceField);
            String skin = text(data, skinField);
            int instanceId = instanceId(String.valueOf(instance));
            JSONObject operator = ownedInstance(user, instanceId);
            boolean special = optionalBoolean(data, specialField);
            validateSkin(user, operator.getString("charId"), skin, special);
            preset.put("profile", skin);
            preset.put("profileInst", instanceId);
            preset.put("profileSp", special);
        }
        if ((flag & 16) != 0) {
            require(data.get("slots") instanceof JSONArray, "preset slots are required");
            JSONArray slots = data.getJSONArray("slots");
            validateSlots(user, slots);
            JSONArray copy = new JSONArray();
            for (int i = 0; i < slots.size(); i++) {
                JSONObject slot = slots.getJSONObject(i), clean = new JSONObject(true);
                clean.put("charId", slot.getString("charId"));
                clean.put("skinId", slot.getString("skinId"));
                clean.put("skinSp", optionalBoolean(slot, "skinSp"));
                copy.add(clean);
            }
            preset.put("slots", copy);
        }
        boolean active = id.equals(rotation.getString("current"));
        if (active) {
            // Only the requested home fields change. Renaming a preset must not reset the
            // secretary or a legacy selection that the user is still editing.
            if ((flag & 2) != 0) user.getJSONObject("background").put("selected", preset.getString("background"));
            if ((flag & 4) != 0) user.getJSONObject("homeTheme").put("selected", preset.getString("homeTheme"));
            if ((flag & 8) != 0) applySecretary(user, preset);
        }
        JSONObject result = result(user, false), modified = result.getJSONObject("playerDataDelta").getJSONObject("modified");
        if (active && (flag & 2) != 0) modified.put("background", user.getJSONObject("background"));
        if (active && (flag & 4) != 0) modified.put("homeTheme", user.getJSONObject("homeTheme"));
        if (active && (flag & 8) != 0) modified.put("status", user.getJSONObject("status"));
        return result;
    }

    static JSONObject deletePreset(JSONObject user, JSONObject body) {
        String id = text(body, "instId");
        normalize(user);
        JSONObject rotation = user.getJSONObject("charRotation");
        preset(rotation, id);
        require(!id.equals(rotation.getString("current")), "cannot delete current preset");
        rotation.getJSONObject("preset").remove(id);
        JSONObject result = result(user, false), deletedPreset = new JSONObject(true);
        deletedPreset.put(id, null);
        JSONObject deletedRotation = new JSONObject(true);
        deletedRotation.put("preset", deletedPreset);
        result.getJSONObject("playerDataDelta").getJSONObject("deleted").put("charRotation", deletedRotation);
        return result;
    }

    static JSONObject setCurrent(JSONObject user, JSONObject body) {
        String id = text(body, "instId");
        normalize(user);
        JSONObject rotation = user.getJSONObject("charRotation"), preset = preset(rotation, id);
        validatePreset(user, preset);
        rotation.put("current", id);
        user.getJSONObject("background").put("selected", preset.getString("background"));
        user.getJSONObject("homeTheme").put("selected", preset.getString("homeTheme"));
        applySecretary(user, preset);
        return result(user, true);
    }

    /** Sync may fill missing legacy fields, but never replaces saved presets or grants ownership. */
    static boolean normalize(JSONObject user) {
        JSONObject rotation = user.getJSONObject("charRotation");
        boolean changed = false;
        if (rotation == null) {
            rotation = new JSONObject(true);
            user.put("charRotation", rotation);
            changed = true;
        }
        JSONObject presets = rotation.getJSONObject("preset");
        if (presets == null) {
            presets = new JSONObject(true);
            rotation.put("preset", presets);
            changed = true;
        }
        if (presets.isEmpty()) {
            JSONObject status = user.getJSONObject("status"), initial = new JSONObject(true);
            String skin = status.getString("secretarySkinId");
            initial.put("name", CharacterBuildingSupport.table("excel/gamedata_const.json").getString("defaultCRPresetName"));
            initial.put("background", user.getJSONObject("background").getString("selected"));
            initial.put("homeTheme", user.getJSONObject("homeTheme").getString("selected"));
            initial.put("profile", skin);
            initial.put("profileInst", findInstance(user, status.getString("secretary")));
            initial.put("profileSp", false);
            JSONObject slot = new JSONObject(true);
            slot.put("charId", status.getString("secretary"));
            slot.put("skinId", skin);
            slot.put("skinSp", false);
            JSONArray slots = new JSONArray(); slots.add(slot); initial.put("slots", slots);
            validatePreset(user, initial);
            presets.put("1", initial);
            changed = true;
        }
        if (!presets.containsKey(rotation.getString("current"))) {
            rotation.put("current", presets.keySet().iterator().next());
            changed = true;
        }
        for (String key : presets.keySet()) {
            JSONObject preset = presets.getJSONObject(key);
            if (!preset.containsKey("profileSp")) { preset.put("profileSp", false); changed = true; }
            JSONObject skin = CharacterBuildingSupport.table("excel/skin_table.json")
                    .getJSONObject("charSkins").getJSONObject(preset.getString("profile"));
            JSONObject owner = user.getJSONObject("troop").getJSONObject("chars")
                    .getJSONObject(String.valueOf(preset.getIntValue("profileInst")));
            // The shipped legacy template points Amiya's skin at another operator's instance.
            if (skin != null && (owner == null || !skin.getString("charId").equals(owner.getString("charId")))) {
                int instance = findInstance(user, skin.getString("charId"));
                if (instance > 0) { preset.put("profileInst", instance); changed = true; }
            }
            JSONArray slots = preset.getJSONArray("slots");
            if (slots != null) for (int i = 0; i < slots.size(); i++) {
                JSONObject slot = slots.getJSONObject(i);
                if (!slot.containsKey("skinSp")) { slot.put("skinSp", false); changed = true; }
            }
        }
        return changed;
    }

    private static void validatePreset(JSONObject user, JSONObject preset) {
        HomeCustomizationSupport.requireSelection(user, "background", "bgs", text(preset, "background"));
        HomeCustomizationSupport.requireSelection(user, "homeTheme", "themes", text(preset, "homeTheme"));
        int instance = instanceId(String.valueOf(preset.get("profileInst")));
        validateSkin(user, ownedInstance(user, instance).getString("charId"), text(preset, "profile"), optionalBoolean(preset, "profileSp"));
        require(preset.get("slots") instanceof JSONArray, "preset slots are required");
        validateSlots(user, preset.getJSONArray("slots"));
    }

    private static void validateSlots(JSONObject user, JSONArray slots) {
        int limit = CharacterBuildingSupport.table("excel/gamedata_const.json").getIntValue("charRotationSkinListMaxCnt");
        require(!slots.isEmpty() && slots.size() <= limit, "invalid preset slot count");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            require(slots.get(i) instanceof JSONObject, "invalid preset slot");
            JSONObject slot = slots.getJSONObject(i);
            String charId = text(slot, "charId"), skin = text(slot, "skinId");
            boolean special = optionalBoolean(slot, "skinSp");
            validateSkin(user, charId, skin, special);
            require(seen.add(skin + (special ? "^sp_dyn" : "")), "duplicate preset slot");
        }
    }

    private static void validateSkin(JSONObject user, String charId, String skin, boolean special) {
        HomeCustomizationSupport.validateSkin(user, charId, skin);
        if (special) {
            JSONObject table = CharacterBuildingSupport.table("excel/skin_table.json");
            JSONObject variant = table.getJSONObject("spDynSkins").getJSONObject(skin);
            require(variant != null && skin.equals(variant.getString("skinId"))
                    && variant.getString("spDynIllustId") != null
                    && skin.equals(table.getJSONObject("spDynIllustSkinTagsMap").getString(variant.getString("spDynIllustSkinTag"))),
                    "skin has no special dynamic illustration");
        }
    }

    private static void applySecretary(JSONObject user, JSONObject preset) {
        JSONObject status = user.getJSONObject("status");
        status.put("secretary", ownedInstance(user, preset.getIntValue("profileInst")).getString("charId"));
        status.put("secretarySkinId", preset.getString("profile"));
        status.put("secretarySkinSp", preset.getBooleanValue("profileSp"));
    }

    private static JSONObject ownedInstance(JSONObject user, int instance) {
        JSONObject operator = user.getJSONObject("troop").getJSONObject("chars").getJSONObject(Integer.toString(instance));
        require(operator != null, "operator not owned");
        return operator;
    }

    private static int findInstance(JSONObject user, String charId) {
        JSONObject operators = user.getJSONObject("troop").getJSONObject("chars");
        for (String id : operators.keySet()) if (charId != null && charId.equals(operators.getJSONObject(id).getString("charId"))) return instanceId(id);
        return 0;
    }

    private static JSONObject preset(JSONObject rotation, String id) {
        JSONObject preset = rotation.getJSONObject("preset").getJSONObject(id);
        require(preset != null, "unknown preset");
        return preset;
    }

    private static int flags(JSONObject body) {
        require(body != null && (body.get("flag") instanceof Integer || body.get("flag") instanceof Long), "invalid preset flag");
        long value = body.getLongValue("flag");
        require(value > 0 && (value & ~31L) == 0, "invalid preset flag");
        return (int) value;
    }

    private static int instanceId(String id) {
        require(id.matches("[1-9][0-9]*"), "invalid operator instance");
        return Integer.parseInt(id);
    }

    private static String text(JSONObject data, String field) {
        require(data != null && data.get(field) instanceof String && !data.getString(field).trim().isEmpty(), "invalid " + field);
        return data.getString(field);
    }

    private static boolean optionalBoolean(JSONObject data, String field) {
        Object value = data.get(field);
        if (value == null || value instanceof Boolean) return Boolean.TRUE.equals(value);
        if (value instanceof Integer || value instanceof Long) {
            long flag = ((Number)value).longValue();
            require(flag == 0 || flag == 1, "invalid " + field);
            return flag == 1;
        }
        throw new IllegalArgumentException("invalid " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static JSONObject result(JSONObject user, boolean applied) {
        JSONObject modified = new JSONObject(true), delta = new JSONObject(true), result = new JSONObject(true);
        modified.put("charRotation", user.getJSONObject("charRotation"));
        if (applied) for (String section : new String[]{"status", "background", "homeTheme"}) modified.put(section, user.getJSONObject(section));
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        return result;
    }
}
