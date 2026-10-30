package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Read-only daily Credit settlement for dormitory Ambience and completed friend-support use. */
final class NewCreditSupport {
    private static final ZoneId RESET_ZONE = ZoneId.of("Asia/Singapore");
    private static final LocalTime RESET_TIME = LocalTime.of(4, 0);
    private static final int COMFORT_DIVISOR = 125;
    private static final int FRIEND_SUPPORT_CREDIT = 30;
    private static final String SETTLED_DAY = "creditSettlementDay";
    private static final String ASSIST_FACTS = "creditAssistFacts";

    private NewCreditSupport() { }

    static boolean settle(JSONObject user, JSONObject buildingData, long nowEpochSeconds) {
        JSONObject social = ShopTransactionSupport.object(user, "social");
        LocalDate currentDay = businessDay(nowEpochSeconds);
        if (currentDay.toString().equals(social.getString(SETTLED_DAY))) return false;

        int assist = assistAmount(social, buildingData, currentDay.minusDays(1));
        int comfort = comfortAmount(user, buildingData);
        int amount = Math.addExact(assist, comfort);

        JSONObject reward = ShopTransactionSupport.object(social, "yesterdayReward");
        reward.put("canReceive", amount > 0 ? 1 : 0);
        reward.put("assistAmount", assist);
        reward.put("comfortAmount", comfort);
        reward.put("first", amount > 0 ? 1 : 0);
        social.put(SETTLED_DAY, currentDay.toString());
        return true;
    }

    /**
     * Records one successful friend Support Unit use for the current 04:00+8 day.
     * The main quest/campaign success paths call this before saving the finished battle.
     */
    static boolean recordBattle(JSONObject user, JSONObject receipt, int rating,
                                long nowEpochSeconds) {
        if (receipt == null || rating <= 1 || receipt.getBooleanValue("practice")
                || !receipt.getBooleanValue("friendAssist")) return false;

        JSONObject social = ShopTransactionSupport.object(user, "social");
        JSONObject facts = ShopTransactionSupport.object(social, ASSIST_FACTS);
        String day = businessDay(nowEpochSeconds).toString();
        if (facts.containsKey(day)) return false;
        facts.put(day, nowEpochSeconds);
        return true;
    }

    static LocalDate businessDay(long nowEpochSeconds) {
        Instant now = Instant.ofEpochSecond(nowEpochSeconds);
        LocalDate localDay = now.atZone(RESET_ZONE).toLocalDate();
        return now.isBefore(resetAt(localDay)) ? localDay.minusDays(1) : localDay;
    }

    private static Instant resetAt(LocalDate day) {
        return day.atTime(RESET_TIME).atZone(RESET_ZONE).toInstant();
    }

    private static int comfortAmount(JSONObject user, JSONObject buildingData) {
        // The exported table supplies creditGuaranteed=10 and creditCeiling=50.
        // Divisor 125 is the compatibility inference from the shipped 4x5000 => 200
        // seed; exported creditFormula is empty, so this still needs live-game validation.
        int guaranteed = positive(buildingData, "creditGuaranteed", 0);
        int ceiling = positive(buildingData, "creditCeiling", 0);
        JSONObject building = user.getJSONObject("building");
        JSONObject rooms = building == null ? null : building.getJSONObject("rooms");
        JSONObject dormitories = rooms == null ? null : rooms.getJSONObject("DORMITORY");
        if (dormitories == null || dormitories.isEmpty() || guaranteed == 0 || ceiling == 0) return 0;

        int total = 0;
        for (Object value : dormitories.values()) {
            if (!(value instanceof JSONObject)) continue;
            Object comfort = ((JSONObject) value).get("comfort");
            if (!(comfort instanceof Number) || ((Number) comfort).intValue() < 0) continue;
            int roomCredit = Math.min(ceiling,
                    guaranteed + ((Number) comfort).intValue() / COMFORT_DIVISOR);
            total = Math.addExact(total, roomCredit);
        }
        return total;
    }

    private static int assistAmount(JSONObject social, JSONObject buildingData,
                                    LocalDate claimDay) {
        int limit = positive(buildingData, "creditInitiativeLimit", 0);
        if (limit == 0) return 0;
        JSONObject facts = social.getJSONObject(ASSIST_FACTS);
        if (facts == null || !facts.containsKey(claimDay.toString())) return 0;
        return Math.min(limit, FRIEND_SUPPORT_CREDIT);
    }

    private static int positive(JSONObject table, String key, int missing) {
        if (table == null) return missing;
        Object value = table.get(key);
        if (!(value instanceof Number) || ((Number) value).intValue() <= 0) return missing;
        return ((Number) value).intValue();
    }
}
