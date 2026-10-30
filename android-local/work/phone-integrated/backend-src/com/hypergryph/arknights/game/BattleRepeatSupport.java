package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.math.BigDecimal;
import java.util.UUID;

/** Account-local, persisted start receipt. Never trust a multiplier supplied at finish. */
final class BattleRepeatSupport {
    static final String RECEIPT = "_localQuestBattle";
    // Shipped excel/gamedata_const.json: defaultMaxMultipleBattleTimes = 10.
    static final int MAX_RUNS = 10;

    private BattleRepeatSupport() { }

    static int requestedRuns(JSONObject request, JSONObject stage, boolean practice) {
        int runs = 1;
        if (request.get("multiple") != null) {
            JSONObject multiple = request.getJSONObject("multiple");
            runs = new BigDecimal(String.valueOf(multiple.get("battleTimes"))).intValueExact();
        }
        if (runs < 1 || runs > MAX_RUNS || (runs > 1 &&
                (practice || !stage.getBooleanValue("canMultipleBattle")))) {
            throw new IllegalArgumentException("Invalid multiple.battleTimes");
        }
        return runs;
    }

    static JSONObject record(JSONObject user, String stageId, int runs, boolean practice) {
        JSONObject receipt = new JSONObject(true);
        receipt.put("battleId", UUID.randomUUID().toString());
        receipt.put("stageId", stageId);
        receipt.put("runs", runs);
        receipt.put("practice", practice);
        receipt.put("settled", false);
        user.put(RECEIPT, receipt);
        return receipt;
    }

    static JSONObject pending(JSONObject user, JSONObject finish) {
        JSONObject receipt = user.getJSONObject(RECEIPT);
        if (receipt == null || receipt.getBooleanValue("settled") || finish == null ||
                !receipt.getString("battleId").equals(finish.getString("battleId"))) {
            return null;
        }
        return receipt;
    }

    static boolean canAfford(JSONObject user, JSONObject stage, int runs) {
        if (runs == 1) return true; // Preserve legacy single-run AP behavior.
        JSONObject status = user.getJSONObject("status");
        long ap = status.getLongValue("ap");
        long maxAp = status.getLongValue("maxAp");
        if (ap < maxAp) {
            long elapsed = Math.max(0, System.currentTimeMillis() / 1000L - status.getLongValue("lastApAddTime"));
            ap = Math.min(maxAp, ap + elapsed / 360L);
        }
        return ap >= (long) stage.getIntValue("apCost") * runs;
    }

    static JSONObject error(String message) {
        JSONObject result = new JSONObject(true);
        result.put("result", 2);
        result.put("error", message);
        return result;
    }

    static void mergeRewards(JSONObject total, JSONObject next) {
        for (String key : new String[]{"rewards", "additionalRewards", "unusualRewards", "furnitureRewards"}) {
            JSONArray target = total.getJSONArray(key);
            for (Object value : next.getJSONArray(key)) {
                JSONObject incoming = (JSONObject) value;
                JSONObject match = null;
                for (Object prior : target) {
                    JSONObject item = (JSONObject) prior;
                    if (item.getString("id").equals(incoming.getString("id")) &&
                            item.getString("type").equals(incoming.getString("type"))) {
                        match = item;
                        break;
                    }
                }
                if (match == null) target.add(incoming);
                else match.put("count", match.getIntValue("count") + incoming.getIntValue("count"));
            }
        }
    }
}
