package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;

/** Crisis V2 shop state and the verified #5 local compatibility catalog. */
final class CrisisV2Support {
    private static final String CATALOG_PATH = "data/crisisv2/r32_shop_catalog.json";
    private static volatile JSONObject catalog;

    private CrisisV2Support() { }

    static JSONObject catalog() {
        JSONObject value = catalog;
        if (value == null) {
            synchronized (CrisisV2Support.class) {
                value = catalog;
                if (value == null) {
                    value = IOTools.ReadJsonFile(CATALOG_PATH);
                    if (value == null || value.getJSONArray("goods") == null)
                        throw new IllegalStateException("crisis shop catalog unavailable");
                    catalog = value;
                }
            }
        }
        return value;
    }

    static JSONObject goodList(JSONObject user) {
        JSONObject crisis = state(user);
        JSONArray permanent = new JSONArray(), season = new JSONArray();
        JSONArray goods = catalog().getJSONArray("goods");
        for (int i = 0; i < goods.size(); i++) {
            JSONObject good = goods.getJSONObject(i);
            JSONObject view = commonView(good);
            if (good.getIntValue("perm") == 1) addLongTermView(view, good);
            else addSeasonView(view, good);
            (good.getIntValue("perm") == 1 ? permanent : season).add(view);
        }

        JSONObject result = response(crisis);
        result.put("permanent", permanent);
        result.put("season", season);
        result.put("progressGoodList", new JSONObject(true));
        return result;
    }

    static JSONObject info(JSONObject user) {
        JSONObject crisis = state(user);
        JSONObject result = response(crisis);
        result.put("ts", System.currentTimeMillis() / 1000L);
        result.put("info", null);
        return result;
    }

    /**
     * The current account state has no open Crisis V2 season. Reward-page access does not
     * require synthesizing a season or reusing legacy cc1..cc3 map data.
     */
    static JSONObject snapshot(JSONObject user) {
        JSONObject result = response(state(user));
        result.put("simple", new JSONObject(true));
        result.put("detail", new JSONObject(true));
        return result;
    }

    static JSONObject buy(JSONObject user, JSONObject body) {
        if (body == null) throw new IllegalArgumentException("missing shop request");
        JSONObject good = ShopTransactionSupport.find(
                catalog().getJSONArray("goods"), body.getString("goodId"));
        if (!body.containsKey("perm") || body.getIntValue("perm") != good.getIntValue("perm"))
            throw new IllegalArgumentException("shop view mismatch");

        int count = ShopTransactionSupport.count(body);
        JSONObject crisis = state(user);
        JSONObject shop = crisis.getJSONObject("shop");
        JSONObject row = ShopTransactionSupport.record(shop, good.getString("goodId"));
        ShopTransactionSupport.limit(row, good.getIntValue("stock"), count);
        long cost = Math.multiplyExact((long) good.getIntValue("price"), count);
        ShopTransactionSupport.debit(shop, "coin", cost);

        JSONArray items = new JSONArray();
        ShopTransactionSupport.reward(user, good.getJSONObject("item"), count, items);
        JSONObject result = ShopTransactionSupport.success(user, items);
        result.getJSONObject("playerDataDelta").getJSONObject("modified")
                .put("crisisV2", crisis);
        return result;
    }

    private static JSONObject state(JSONObject user) {
        JSONObject crisis = ShopTransactionSupport.object(user, "crisisV2");
        if (!crisis.containsKey("current")) crisis.put("current", "");
        if (!crisis.containsKey("seasons")) crisis.put("seasons", new JSONObject(true));
        if (!crisis.containsKey("newRecordTs")) crisis.put("newRecordTs", -1L);
        if (!crisis.containsKey("nst")) crisis.put("nst", -1L);
        JSONObject shop = ShopTransactionSupport.object(crisis, "shop");
        if (!shop.containsKey("coin")) shop.put("coin", 0);
        if (!shop.containsKey("info")) shop.put("info", new JSONArray());
        if (!shop.containsKey("progressInfo")) shop.put("progressInfo", new JSONObject(true));
        return crisis;
    }

    private static JSONObject commonView(JSONObject good) {
        JSONObject view = new JSONObject(true);
        view.put("goodId", good.getString("goodId"));
        view.put("displayName", good.getString("displayName"));
        view.put("item", good.getJSONObject("item"));
        view.put("progressGoodId", good.getString("progressGoodId"));
        view.put("price", good.getIntValue("price"));
        view.put("availCount", good.getIntValue("stock"));
        view.put("itemSupplyTime", -1L);
        return view;
    }

    private static void addLongTermView(JSONObject view, JSONObject good) {
        JSONObject detail = good.getJSONObject("longTerm");
        view.put("isSingle", detail.getIntValue("isSingle"));
        view.put("slotId1", detail.getIntValue("slotId1"));
        view.put("slotId2", detail.getIntValue("slotId2"));
        view.put("seasonId", detail.getString("seasonId"));
        view.put("title", detail.getString("title"));
    }

    private static void addSeasonView(JSONObject view, JSONObject good) {
        JSONObject detail = good.getJSONObject("season");
        view.put("slotId", detail.getIntValue("slotId"));
        view.put("rarity", detail.getString("rarity"));
    }

    private static JSONObject response(JSONObject crisis) {
        JSONObject modified = new JSONObject(true);
        modified.put("crisisV2", crisis);
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("playerDataDelta", delta);
        return result;
    }
}
