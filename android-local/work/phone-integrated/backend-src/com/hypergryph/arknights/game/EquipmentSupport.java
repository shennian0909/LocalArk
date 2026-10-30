package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Module visibility and selection; reaching elite two never purchases an advanced module. */
public final class EquipmentSupport {
    private EquipmentSupport() {}

    public static boolean normalize(JSONObject user) {
        JSONObject troop = user.getJSONObject("troop");
        if (troop == null || troop.getJSONObject("chars") == null) return false;
        JSONObject table = CharacterBuildingSupport.table("excel/uniequip_table.json");
        boolean changed = false;
        for (Object value : troop.getJSONObject("chars").values()) changed |= normalizeCharacter((JSONObject)value, table);
        return changed;
    }

    public static void initializeNewCharacter(JSONObject character) {
        normalizeCharacter(character, CharacterBuildingSupport.table("excel/uniequip_table.json"));
    }

    static boolean normalizeCharacter(JSONObject character, JSONObject table) {
        JSONObject forms = character.getJSONObject("tmpl");
        if (forms == null) return normalizeForm(character, character, character.getString("charId"), table);
        boolean changed = false;
        for (String template : forms.keySet()) changed |= normalizeForm(character, forms.getJSONObject(template), template, table);
        return changed;
    }

    private static boolean normalizeForm(JSONObject character, JSONObject form, String template, JSONObject table) {
        JSONArray ids = table.getJSONObject("charEquip").getJSONArray(template);
        if (ids == null) return false;
        JSONObject modules = ShopTransactionSupport.object(form, "equip");
        boolean changed = false;
        String initial = null;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.getString(i);
            JSONObject definition = table.getJSONObject("equipDict").getJSONObject(id);
            boolean basic = "INITIAL".equals(definition.getString("type"));
            JSONObject module = modules.getJSONObject(id);
            if (module == null) {
                module = JSONObject.parseObject("{hide:1,locked:1,level:1}");
                if (basic) module.put("locked", 0);
                modules.put(id, module); changed = true;
            }
            boolean shown = reached(character, definition.getString("showEvolvePhase"), definition.getIntValue("showLevel"));
            changed |= put(module, "hide", shown ? 0 : 1);
            // An operator cannot legitimately own an advanced unlock below its required phase/level.
            // Eligibility later becoming true does not itself unlock it or consume its material cost.
            if (!basic && !reached(character, definition.getString("unlockEvolvePhase"), definition.getIntValue("unlockLevel")))
                changed |= put(module, "locked", 1);
            if (basic && shown) { changed |= put(module, "locked", 0); initial = id; }
        }
        String current = form.getString("currentEquip");
        JSONObject selected = current == null ? null : modules.getJSONObject(current);
        if (selected == null || selected.getIntValue("locked") != 0 || selected.getIntValue("hide") != 0) {
            if (!java.util.Objects.equals(current, initial) || !form.containsKey("currentEquip")) {
                form.put("currentEquip", initial); changed = true;
            }
        }
        return changed;
    }

    static JSONObject select(JSONObject user, JSONObject body) {
        CharacterBuildingSupport.require(body != null, "Missing module selection");
        int instance = CharacterBuildingSupport.integer(body, "charInstId");
        JSONObject character = user.getJSONObject("troop").getJSONObject("chars").getJSONObject(Integer.toString(instance));
        CharacterBuildingSupport.require(character != null, "Operator is not owned");
        JSONObject table = CharacterBuildingSupport.table("excel/uniequip_table.json");
        normalizeCharacter(character, table);
        String template = body.getString("templateId");
        if (template == null || template.isEmpty()) template = character.getString("currentTmpl");
        if (template == null || template.isEmpty()) template = character.getString("charId");
        JSONObject forms = character.getJSONObject("tmpl");
        JSONObject form;
        if (forms == null) {
            CharacterBuildingSupport.require(template.equals(character.getString("charId")), "Wrong character template");
            form = character;
        } else {
            form = forms.getJSONObject(template);
            CharacterBuildingSupport.require(form != null, "Unknown character template");
        }
        String id = body.getString("equipId");
        JSONArray ids = table.getJSONObject("charEquip").getJSONArray(template);
        CharacterBuildingSupport.require(ids != null, "Operator has no module");
        if (id == null || id.isEmpty()) {
            for (int i = 0; i < ids.size(); i++) {
                String candidate = ids.getString(i);
                if ("INITIAL".equals(table.getJSONObject("equipDict").getJSONObject(candidate).getString("type"))) { id = candidate; break; }
            }
        }
        CharacterBuildingSupport.require(id != null && ids.contains(id), "Module does not belong to this template");
        JSONObject module = form.getJSONObject("equip").getJSONObject(id);
        CharacterBuildingSupport.require(module != null && module.getIntValue("locked") == 0 && module.getIntValue("hide") == 0, "Module is locked");
        form.put("currentEquip", id);
        JSONObject chars = new JSONObject(true), troop = new JSONObject(true), modified = new JSONObject(true);
        chars.put(Integer.toString(instance), character); troop.put("chars", chars); modified.put("troop", troop);
        return CharacterBuildingSupport.result(modified);
    }

    private static boolean reached(JSONObject character, String phase, int level) {
        int required = phase == null ? 0 : Integer.parseInt(phase.substring("PHASE_".length()));
        int current = character.getIntValue("evolvePhase");
        return current > required || (current == required && character.getIntValue("level") >= level);
    }

    private static boolean put(JSONObject object, String key, int value) {
        if (object.containsKey(key) && object.getIntValue(key) == value) return false;
        object.put(key, value); return true;
    }
}
