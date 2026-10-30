package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import java.util.HashSet;
import java.util.Set;

final class LocalCosmeticSupport {
    private LocalCosmeticSupport() {}

    static void normalize(JSONObject user, long now) {
        JSONObject status = user.getJSONObject("status");
        if (!user.containsKey("_localOriginalRegisterTs"))
            user.put("_localOriginalRegisterTs", status.getLongValue("registerTs"));
        status.put("registerTs", ProfileSupport.DISPLAY_REGISTER_TS);

        JSONObject display = CharacterBuildingSupport.table("excel/display_meta_table.json");
        Set<String> purchasable = new HashSet<>();
        if (ArknightsApplication.GPGoodList != null) for (String group : new String[]{"levelGP", "oneTimeGP"}) {
            JSONArray packs = ArknightsApplication.GPGoodList.getJSONArray(group);
            if (packs == null) continue;
            for (Object value : packs) {
                JSONObject pack = (JSONObject) value;
                if ("DIAMOND".equals(pack.getString("currencyUnit"))
                        && pack.getLongValue("startDateTime") <= now
                        && (pack.getLongValue("endDateTime") <= 0 || pack.getLongValue("endDateTime") >= now))
                    collectRewards(pack.getJSONArray("items"), purchasable);
            }
        }
        JSONObject themes = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "homeTheme"), "themes");
        JSONArray definitions = display.getJSONObject("homeBackgroundData").getJSONArray("themeList");
        int level = status.getIntValue("level");
        for (int i = 0; i < definitions.size(); i++) {
            String id = definitions.getJSONObject(i).getString("id");
            if (purchasable.contains(id) || ("tm_rhodes_night".equals(id) && level < 50)) continue;
            JSONObject state = ShopTransactionSupport.object(themes, id);
            if (!state.containsKey("unlock")) state.put("unlock", now);
        }
        JSONObject skin = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "nameCardStyle"), "skin");
        JSONObject owned = ShopTransactionSupport.object(skin, "state");
        JSONObject templates = ShopTransactionSupport.object(skin, "tmpl");
        JSONObject cards = display.getJSONObject("nameCardV2Data").getJSONObject("skinData");
        for (String id : cards.keySet()) {
            JSONObject definition = cards.getJSONObject(id);
            if (purchasable.contains(id) || ("nc_rhodes_light".equals(id) && level < 80)) continue;
            JSONObject state = ShopTransactionSupport.object(owned, id);
            if (!state.getBooleanValue("unlock")) {
                state.put("unlock", true);
                state.put("progress", null);
                state.put("unlockTs", now);
            }
            templates.putIfAbsent(id, 0);
        }
        String defaultSkin = display.getJSONObject("nameCardV2Data").getJSONObject("consts").getString("defaultNameCardSkinId");
        String selected = skin.getString("selected");
        JSONObject selectedState = selected == null ? null : owned.getJSONObject(selected);
        if (selectedState == null || !selectedState.getBooleanValue("unlock")) {
            skin.put("selected", defaultSkin);
            templates.putIfAbsent(defaultSkin, 0);
        } else {
            templates.putIfAbsent(selected, 0);
        }
        JSONObject icons = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "avatar"), "avatar_icon");
        JSONArray avatars = display.getJSONObject("playerAvatarData").getJSONArray("avatarList");
        for (int i = 0; i < avatars.size(); i++) {
            JSONObject definition = avatars.getJSONObject(i);
            if ("ASSISTANT".equals(definition.getString("avatarType"))
                    || definition.getLongValue("avatarStartTs") > now) continue;
            String id = definition.getString("avatarId");
            if (icons.containsKey(id)) continue;
            JSONObject state = new JSONObject(true);
            state.put("ts", now);
            state.put("src", "initial");
            icons.put(id, state);
        }
    }

    static JSONObject editCard(JSONObject user, JSONObject body) {
        if (body == null || !(body.get("flag") instanceof Number) || !(body.get("content") instanceof JSONObject))
            throw new IllegalArgumentException("invalid namecard edit");
        int flags = body.getIntValue("flag");
        if (((Number) body.get("flag")).doubleValue() != flags || flags <= 0 || (flags & ~15) != 0)
            throw new IllegalArgumentException("invalid namecard flags");
        normalize(user, System.currentTimeMillis() / 1000L);
        JSONObject content = body.getJSONObject("content");
        JSONObject style = user.getJSONObject("nameCardStyle");
        JSONObject skin = style.getJSONObject("skin");
        JSONObject definitions = CharacterBuildingSupport.table("excel/display_meta_table.json").getJSONObject("nameCardV2Data");
        if ((flags & 1) != 0) {
            JSONArray order = content.getJSONArray("component");
            if (order == null || order.size() > definitions.getJSONObject("removableModuleData").size())
                throw new IllegalArgumentException("invalid namecard components");
            Set<String> used = new HashSet<>();
            for (Object id : order) {
                if (!(id instanceof String) || !definitions.getJSONObject("removableModuleData").containsKey(id) || !used.add((String) id))
                    throw new IllegalArgumentException("invalid namecard component");
            }
            style.put("componentOrder", order);
        }
        if ((flags & 2) != 0) {
            String id = content.getString("skinId");
            JSONObject state = skin.getJSONObject("state").getJSONObject(id);
            if (id == null || !definitions.getJSONObject("skinData").containsKey(id) || state == null || !state.getBooleanValue("unlock"))
                throw new IllegalArgumentException("namecard skin is not unlocked");
            skin.put("selected", id);
        }
        if ((flags & 4) != 0) {
            JSONObject misc = content.getJSONObject("misc");
            if (misc == null || !(misc.get("showDetail") instanceof Boolean) || !(misc.get("showBirthday") instanceof Boolean))
                throw new IllegalArgumentException("invalid namecard visibility");
            JSONObject value = new JSONObject(true);
            value.put("showDetail", misc.getBoolean("showDetail"));
            value.put("showBirthday", misc.getBoolean("showBirthday"));
            style.put("misc", value);
        }
        if ((flags & 8) != 0) {
            Object skinId = content.get("skinId");
            Object value = content.get("skinTmpl");
            JSONObject state = skinId instanceof String
                    ? skin.getJSONObject("state").getJSONObject((String) skinId) : null;
            JSONObject definition = skinId instanceof String
                    ? definitions.getJSONObject("skinData").getJSONObject((String) skinId) : null;
            if (!(skinId instanceof String) || state == null || !state.getBooleanValue("unlock")
                    || definition == null
                    || !definition.getBooleanValue("canChangeTmpl")
                    || !(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                    || ((Number) value).intValue() < 0 || ((Number) value).intValue() >= definition.getIntValue("skinTmplCnt"))
                throw new IllegalArgumentException("invalid namecard template");
            ShopTransactionSupport.object(skin, "tmpl").put((String) skinId, ((Number) value).intValue());
        }
        JSONObject modified = new JSONObject(true);
        modified.put("nameCardStyle", style);
        modified.put("homeTheme", user.getJSONObject("homeTheme"));
        modified.put("avatar", user.getJSONObject("avatar"));
        return CharacterBuildingSupport.result(modified);
    }

    private static void collectRewards(Object value, Set<String> ids) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if ("HOME_THEME".equals(object.getString("type")) || "NAME_CARD_SKIN".equals(object.getString("type"))) ids.add(object.getString("id"));
            for (Object child : object.values()) collectRewards(child, ids);
        } else if (value instanceof JSONArray) {
            for (Object child : (JSONArray) value) collectRewards(child, ids);
        }
    }
}
