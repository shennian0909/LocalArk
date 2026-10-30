package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Keeps the four season periods aligned with the active climb-tower table. */
final class TowerSeasonSupport {
    private static final int PERIOD_COUNT = 4;
    private static final ZoneId SEASON_ZONE = ZoneId.of("Asia/Shanghai");

    private TowerSeasonSupport() {}

    static JSONObject ensure(JSONObject user, JSONObject table) {
        return ensureAt(user, table, System.currentTimeMillis() / 1000L);
    }

    static JSONObject ensureAt(JSONObject user, JSONObject table, long now) {
        JSONObject info = activeSeason(table, now);
        CharacterBuildingSupport.require(info != null, "No active climb tower season");
        String seasonId = info.getString("id");
        long start = info.getLongValue("startTs");
        long end = info.getLongValue("endTs");
        ZonedDateTime startTime = Instant.ofEpochSecond(start).atZone(SEASON_ZONE);
        long expectedEnd = startTime.plusMonths(PERIOD_COUNT).toEpochSecond() - 1L;
        CharacterBuildingSupport.require(expectedEnd == end,
                "Invalid climb tower season month boundary");

        JSONObject tower = ShopTransactionSupport.object(user, "tower");
        JSONObject season = ShopTransactionSupport.object(tower, "season");
        boolean changed = !seasonId.equals(season.getString("id"));
        season.put("id", seasonId);
        if (!season.containsKey("finishTs")) season.put("finishTs", 0L);
        ShopTransactionSupport.object(season, "missions");
        ShopTransactionSupport.object(season, "passWithGodCard");
        ShopTransactionSupport.object(season, "towerSlotsMap");

        JSONObject period = ShopTransactionSupport.object(season, "period");
        JSONObject constant = table.getJSONObject("detailConst");
        Instant nowTime = Instant.ofEpochSecond(now);
        int current = 0;
        while (current < PERIOD_COUNT - 1
                && !nowTime.isBefore(startTime.plusMonths(current + 1).toInstant())) {
            current++;
        }
        long term = startTime.plusMonths(current + 1).toEpochSecond() - 1L;
        long previousTerm = period.getLongValue("termTs");
        if (changed || previousTerm > 0L && previousTerm < now) {
            JSONObject items = new JSONObject(true);
            items.put(constant.getString("lowerItemId"), 0);
            items.put(constant.getString("higherItemId"), 0);
            period.put("items", items);
        }
        JSONObject items = ShopTransactionSupport.object(period, "items");
        if (!items.containsKey(constant.getString("lowerItemId")))
            items.put(constant.getString("lowerItemId"), 0);
        if (!items.containsKey(constant.getString("higherItemId")))
            items.put(constant.getString("higherItemId"), 0);

        period.put("termTs", term);
        // The client save and the current metadata use two historical spellings.
        period.put("cur", current);
        period.put("len", PERIOD_COUNT);
        period.put("periodCurr", current);
        period.put("periodCount", PERIOD_COUNT);
        return info;
    }

    private static JSONObject activeSeason(JSONObject table, long now) {
        JSONObject seasons = table.getJSONObject("seasonInfos");
        if (seasons == null) return null;
        for (String id : seasons.keySet()) {
            JSONObject season = seasons.getJSONObject(id);
            if (season != null && season.getLongValue("startTs") <= now
                    && now <= season.getLongValue("endTs")) return season;
        }
        return null;
    }
}
