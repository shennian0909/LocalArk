package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;

final class LocalAssistSupport {
    private LocalAssistSupport() { }

    static JSONArray list(long playerUid, String profession) {
        JSONObject book = LocalFriendSupport.readFriendBook(playerUid);
        LocalFriendSupport.linkTestFriend(playerUid, book);
        JSONArray result = new JSONArray();
        JSONArray friends = book.getJSONArray("list");
        for (int n = 0; n < friends.size(); n++) {
            JSONObject entry = friends.getJSONObject(n);
            long uid = LocalFriendSupport.entryUid(entry);
            if (!LocalFriendSupport.visitable(playerUid, uid)) continue;
            JSONObject user = LocalFriendSupport.friendUser(uid);
            JSONArray chars = characters(user);
            int selected = -1;
            for (int i = 0; i < chars.size(); i++) {
                JSONObject character = chars.getJSONObject(i);
                if (character == null) continue;
                if (profession.equals(ArknightsApplication.characterJson
                        .getJSONObject(character.getString("charId")).getString("profession"))) selected = i;
            }
            if (selected < 0) continue;
            JSONObject card = LocalSocialController.friendCard(uid, entry);
            card.put("isFriend", true);
            card.put("canRequestFriend", false);
            card.put("assistCharList", chars);
            card.put("assistSlotIndex", selected);
            result.add(card);
        }
        return result;
    }

    static JSONArray characters(JSONObject user) {
        JSONArray configured = user.getJSONObject("social").getJSONArray("assistCharList");
        JSONObject roster = user.getJSONObject("troop").getJSONObject("chars");
        JSONArray result = new JSONArray();
        for (int i = 0; i < configured.size(); i++) {
            JSONObject slot = configured.getJSONObject(i);
            if (slot == null) { result.add(null); continue; }
            JSONObject owned = roster.getJSONObject(slot.getString("charInstId"));
            JSONObject character = new JSONObject(true);
            for (String key : new String[]{"charId", "skills", "mainSkillLvl", "evolvePhase",
                    "favorPoint", "potentialRank", "level", "equip"}) {
                character.put(key, owned.get(key));
            }
            character.put("skinId", owned.getString("skin"));
            character.put("skillIndex", slot.getIntValue("skillIndex"));
            character.put("currentEquip", owned.getString("currentEquip") == null ? "" : owned.getString("currentEquip"));
            character.put("crisisRecord", new JSONObject(true));
            character.put("crisisV2Record", new JSONObject(true));
            result.add(character);
        }
        while (result.size() < 3) result.add(null);
        return result;
    }
}
