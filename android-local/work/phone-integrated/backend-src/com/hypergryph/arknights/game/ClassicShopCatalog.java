package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;

/**
 * Universal-certificate shop catalog. This data is kept out of the global
 * application registry because it is specific to the CLASSIC store route.
 *
 * KS_6001..KS_6029 IDs and their order come from
 * payload/data/defaultSyncData.json. The associations below are the local
 * catalog mapping; payload/data/shop/HighGoodList.json provides direct item
 * mappings for its first ten skins, while the remaining sixteen follow the
 * account-state order because no local getClassicGoodList response is shipped.
 * The client consumes the good list returned by the server.
 * KS_6101..KS_6104 are local IDs for OLD1..OLD4, whose item chains come from
 * payload/data/shop/HighGoodList.json.
 */
final class ClassicShopCatalog {
    static final String FEATURED_SIX = "KS_6001";
    static final String FEATURED_FIVE = "KS_6002";
    static final String TICKET_PROGRESS = "CLASSIC_TICKETS";

    private static final String[][] SKINS = {
            {"KS_6018", "char_120_hibisc@nian#1"},
            {"KS_6005", "char_002_amiya@winter#1"},
            {"KS_6007", "char_112_siege@wild#2"},
            {"KS_6010", "char_215_mantic@epoque#4"},
            {"KS_6014", "char_117_myrrh@wild#1"},
            {"KS_6017", "char_222_bpipe@race#1"},
            {"KS_6019", "char_181_flower@daily#1"},
            {"KS_6020", "char_129_bluep@marthe#3"},
            {"KS_6021", "char_225_haak@nian#5"},
            {"KS_6022", "char_109_fmout@epoque#2"},
            {"KS_6023", "char_171_bldsk@summer#7"},
            {"KS_6024", "char_378_asbest@whirlwind#3"},
            {"KS_6025", "char_356_broca@marthe#4"},
            {"KS_6026", "char_002_amiya@epoque#4"},
            {"KS_6027", "char_366_acdrop@whirlwind#4"},
            {"KS_6028", "char_208_melan@epoque#1"},
            {"KS_6029", "char_274_astesi@epoque#23"},
            {"KS_6016", "char_236_rope@witch#1"},
            {"KS_6015", "char_209_ardign@snow#1"},
            {"KS_6013", "char_235_jesica@nian#2"},
            {"KS_6012", "char_253_greyy@epoque#8"},
            {"KS_6009", "char_122_beagle@boc#1"},
            {"KS_6011", "char_164_nightm@epoque#5"},
            {"KS_6008", "char_436_whispr@nian#4"},
            {"KS_6006", "char_355_ethan@epoque#7"},
            {"KS_6004", "char_440_pinecn@shining#1"}
    };

    private static final String[][] CHAINS = {
            {"KS_6104", "OLD4", "char_4043_erato"},
            {"KS_6103", "OLD3", "char_402_tuye"},
            {"KS_6102", "OLD2", "char_336_folivo"},
            {"KS_6101", "OLD1", "char_252_bibeak"}
    };

    private ClassicShopCatalog() {}

    static JSONObject catalog() {
        JSONObject itemTable = IOTools.ReadJsonFile("data/excel/item_table.json");
        JSONObject skinTable = IOTools.ReadJsonFile("data/excel/skin_table.json");
        JSONObject characterTable = IOTools.ReadJsonFile("data/excel/character_table.json");
        if (itemTable == null || skinTable == null || characterTable == null)
            throw new IllegalStateException("classic shop tables unavailable");

        JSONObject items = itemTable.getJSONObject("items");
        JSONObject skins = skinTable.getJSONObject("charSkins");
        JSONArray goods = new JSONArray();
        JSONObject progress = new JSONObject(true);

        goods.add(good(FEATURED_SIX, "中坚甄选6星干员", 1, "NORMAL",
                item("char_293_thorns", "CHAR", 1), null, 1800, 1));
        goods.add(good(FEATURED_FIVE, "中坚甄选5星干员", 2, "NORMAL",
                item("char_367_swllow", "CHAR", 1), null, 450, 1));
        goods.add(good("KS_6003", "中坚寻访凭证", 3, "PROGRESS",
                null, TICKET_PROGRESS, 0, 0));
        progress.put(TICKET_PROGRESS, ticketStages(items));

        int number = 100;
        for (String[] chain : CHAINS) {
            JSONObject character = characterTable.getJSONObject(chain[2]);
            if (character == null) throw new IllegalArgumentException("unknown character " + chain[2]);
            goods.add(good(chain[0], character.getString("name"), number++, "PROGRESS",
                    null, chain[1], 0, 0));
            progress.put(chain[1], characterStages(character.getString("name"), chain[2]));
        }

        for (String[] skin : SKINS) {
            JSONObject skinInfo = skins.getJSONObject(skin[1]);
            if (skinInfo == null)
                throw new IllegalArgumentException("unknown skin " + skin[1]);
            String displayName = skinInfo.getJSONObject("displaySkin").getString("skinName");
            goods.add(good(skin[0], displayName, number++, "NORMAL",
                    item(skin[1], "CHAR_SKIN", 1), null, 400, 1));
        }

        JSONObject delta = new JSONObject(true);
        delta.put("modified", new JSONObject(true));
        delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("goodList", goods);
        result.put("progressGoodList", progress);
        result.put("newFlag", new JSONArray());
        result.put("playerDataDelta", delta);
        return result;
    }

    private static JSONArray ticketStages(JSONObject items) {
        requireItemType(items, "classic_gacha", "CLASSIC_TKT_GACHA");
        requireItemType(items, "classic_gacha_10", "CLASSIC_TKT_GACHA_10");
        JSONArray stages = new JSONArray();
        stages.add(stage(1, 100, "中坚寻访凭证",
                item("classic_gacha", "CLASSIC_TKT_GACHA", 1)));
        stages.add(stage(2, 180, "中坚寻访凭证",
                item("classic_gacha", "CLASSIC_TKT_GACHA", 2)));
        stages.add(stage(3, 400, "中坚寻访凭证",
                item("classic_gacha", "CLASSIC_TKT_GACHA", 5)));
        stages.add(stage(4, 700, "批量中坚寻访凭证",
                item("classic_gacha_10", "CLASSIC_TKT_GACHA_10", 1)));
        stages.add(stage(5, 1200, "批量中坚寻访凭证",
                item("classic_gacha_10", "CLASSIC_TKT_GACHA_10", 2)));
        return stages;
    }

    private static JSONArray characterStages(String name, String charId) {
        JSONArray stages = new JSONArray();
        stages.add(stage(1, 400, name, item(charId, "CHAR", 1)));
        int[] prices = {30, 40, 50, 60, 70};
        for (int i = 0; i < prices.length; i++) {
            stages.add(stage(i + 2, prices[i], name + "的信物",
                    item("p_" + charId, "MATERIAL", 1)));
        }
        return stages;
    }

    private static void requireItemType(JSONObject items, String id, String type) {
        JSONObject item = items.getJSONObject(id);
        if (item == null || !type.equals(item.getString("itemType")))
            throw new IllegalArgumentException("classic shop item type mismatch " + id);
    }

    private static JSONObject item(String id, String type, int count) {
        JSONObject item = new JSONObject(true);
        item.put("id", id);
        item.put("type", type);
        item.put("count", count);
        return item;
    }

    private static JSONObject stage(int order, int price, String displayName, JSONObject item) {
        JSONObject stage = new JSONObject(true);
        stage.put("order", order);
        stage.put("price", price);
        stage.put("displayName", displayName);
        stage.put("item", item);
        return stage;
    }

    private static JSONObject good(String id, String displayName, int number,
                                   String goodType, JSONObject item,
                                   String progressGoodId, int price, int availCount) {
        JSONObject good = new JSONObject(true);
        good.put("goodId", id);
        good.put("displayName", displayName);
        good.put("priority", number <= 3 ? 1 : 2);
        good.put("number", number);
        good.put("goodType", goodType);
        good.put("item", item);
        good.put("progressGoodId", progressGoodId);
        good.put("price", price);
        good.put("originPrice", price);
        good.put("discount", 0);
        good.put("availCount", availCount);
        good.put("goodStartTime", -1);
        good.put("goodEndTime", -1);
        return good;
    }
}
