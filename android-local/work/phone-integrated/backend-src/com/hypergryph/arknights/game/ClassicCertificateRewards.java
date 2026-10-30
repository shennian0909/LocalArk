package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;

/** Classic-pool rewards granted when an operator is acquired. */
final class ClassicCertificateRewards {
    private static final int FIRST_ACQUISITION_CERTIFICATES = 10;
    private static final int[] DUPLICATE_CERTIFICATES_BY_RARITY_RANK = {0, 0, 1, 5, 50, 100};

    private ClassicCertificateRewards() {}

    static JSONObject grant(JSONObject user, int rarityRank, boolean isNew, JSONArray items) {
        if (rarityRank < 2 || rarityRank > 5)
            throw new IllegalArgumentException("unsupported classic rarity rank " + rarityRank);
        JSONObject itemTable = IOTools.ReadJsonFile("data/excel/item_table.json");
        if (itemTable == null) throw new IllegalStateException("classic item table unavailable");

        // classicPotentialMaterialConverter is the over-cap token exchange table.
        // Acquisition certificates follow rarity and first-acquisition state instead.
        String id = "classic_normal_ticket", type = "CLASSIC_SHD";
        int count = isNew ? FIRST_ACQUISITION_CERTIFICATES
                : DUPLICATE_CERTIFICATES_BY_RARITY_RANK[rarityRank];
        JSONObject sourceItems = itemTable.getJSONObject("items");
        JSONObject itemInfo = sourceItems == null ? null : sourceItems.getJSONObject(id);
        if (itemInfo == null || !type.equals(itemInfo.getString("itemType")))
            throw new IllegalArgumentException("classic certificate item type mismatch");

        JSONObject status = user.getJSONObject("status");
        if (status == null) throw new IllegalArgumentException("missing account status");
        long total = status.getLongValue("classicShard") + count;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("reward overflow");
        status.put("classicShard", total);

        JSONObject granted = new JSONObject(true);
        granted.put("id", id);
        granted.put("type", type);
        granted.put("count", count);
        items.add(granted);
        return granted;
    }

    static JSONObject grantDuplicatePotentialItem(
            JSONObject user, JSONObject characterDefinition, JSONArray items) {
        if (characterDefinition == null)
            throw new IllegalArgumentException("unknown classic character");
        String id = characterDefinition.getString("classicPotentialItemId");
        if (id == null || id.isEmpty())
            throw new IllegalArgumentException("missing classic potential item");

        JSONObject itemTable = IOTools.ReadJsonFile("data/excel/item_table.json");
        JSONObject sourceItems = itemTable == null ? null : itemTable.getJSONObject("items");
        JSONObject itemInfo = sourceItems == null ? null : sourceItems.getJSONObject(id);
        if (itemInfo == null || !"MATERIAL".equals(itemInfo.getString("itemType")))
            throw new IllegalArgumentException("classic potential item type mismatch " + id);

        JSONObject inventory = user.getJSONObject("inventory");
        if (inventory == null) throw new IllegalArgumentException("missing account inventory");
        inventory.put(id, Math.addExact(inventory.getIntValue(id), 1));

        JSONObject granted = new JSONObject(true);
        granted.put("id", id);
        granted.put("type", "MATERIAL");
        granted.put("count", 1);
        items.add(granted);
        return granted;
    }
}
