package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Staff morale rates and elapsed work, using the same AP units as the client. */
final class BaseWorkStateSupport {
    private BaseWorkStateSupport() {}

    static void advance(JSONObject user, JSONObject data, long now) {
        JSONObject base = user.getJSONObject("building"), slots = base.getJSONObject("roomSlots");
        JSONObject workers = base.getJSONObject("chars"), owned = user.getJSONObject("troop").getJSONObject("chars");
        int global = 0;
        for (String id : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(id);
            if (slot.getIntValue("state") == 2 && "CONTROL".equals(slot.getString("roomId")))
                global -= data.getJSONObject("controlData").getIntValue("basicCostBuff")
                        * occupants(slot, id, workers, owned, data.getIntValue("tiredApThreshold"));
        }
        for (String id : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(id);
            if (slot.getIntValue("state") != 2) continue;
            String type = slot.getString("roomId");
            JSONObject branch = base.getJSONObject("rooms").getJSONObject(type);
            JSONObject room = branch == null ? null : branch.getJSONObject(id);
            JSONObject definition = data.getJSONObject("rooms").getJSONObject(type);
            if (room == null || definition == null || slot.getIntValue("level") < 1) continue;
            int cost = definition.getJSONArray("phases").getJSONObject(slot.getIntValue("level") - 1).getIntValue("manpowerCost");
            JSONArray byCount = data.getJSONArray("MANUFACTURE".equals(type) ? "manufactManpowerCostByNum"
                    : "TRADING".equals(type) ? "tradingManpowerCostByNum" : "");
            if (byCount != null) cost += byCount.getIntValue(occupants(slot, id, workers, owned, -1));
            JSONObject buff = ShopTransactionSupport.object(room, "buff");
            if ("CONTROL".equals(type)) ShopTransactionSupport.object(buff, "global").put("apCost", global);
            if (cost <= 0) continue;
            Object ap = buff.get("apCost");
            int bonus = global + (ap instanceof JSONObject ? ((JSONObject)ap).getIntValue("all")
                    : ap instanceof Number ? ((Number)ap).intValue() : 0);
            room.put("apCost", -cost + bonus);
            if ("MANUFACTURE".equals(type) || "TRADING".equals(type)) {
                JSONObject display = ShopTransactionSupport.object(room, "display");
                display.put("base", -cost); display.put("buff", bonus);
            }
        }
        for (String id : owned.keySet()) {
            JSONObject worker = workers.getJSONObject(id);
            if (worker == null) continue;
            JSONObject slot = slots.getJSONObject(worker.getString("roomSlotId"));
            JSONArray ids = slot == null ? null : slot.getJSONArray("charInstIds");
            boolean stationed = slot != null && slot.getIntValue("state") == 2 && ids != null && ids.contains(Integer.valueOf(id));
            long anchor = worker.containsKey("_workStateTime") ? worker.getLongValue("_workStateTime") : now;
            long elapsed = Math.max(0L, now - anchor), oldRate = worker.getLongValue("changeScale");
            if (oldRate < 0 && elapsed > 0) worker.put("ap", Math.max(0L, worker.getLongValue("ap") + elapsed * oldRate));
            if (stationed && oldRate != 0) worker.put("workTime", worker.getLongValue("workTime") + elapsed);
            long rate = 0L;
            if (stationed) {
                String type = slot.getString("roomId");
                JSONObject branch = base.getJSONObject("rooms").getJSONObject(type);
                JSONObject room = branch == null ? null : branch.getJSONObject(worker.getString("roomSlotId"));
                worker.put("index", ids.indexOf(Integer.valueOf(id)));
                if ("DORMITORY".equals(type)) rate = dormRate(data, room, slot.getIntValue("level"), id);
                else if (worker.getLongValue("ap") > data.getIntValue("tiredApThreshold")
                        && room != null && (!room.containsKey("state") || room.getIntValue("state") == 1)) {
                    rate = Math.min(0L, room.getLongValue("apCost"));
                    Object rawAp = room.getJSONObject("buff").get("apCost");
                    JSONObject ap = rawAp instanceof JSONObject ? (JSONObject)rawAp : null;
                    if (ap != null && ap.getJSONObject("self") != null) rate += ap.getJSONObject("self").getLongValue(id);
                    if ("HIRE".equals(type)) rate += hireCost(data, owned.getJSONObject(id));
                }
            } else {
                worker.put("index", -1); worker.put("workTime", 0L);
            }
            worker.put("changeScale", rate);
            worker.put("lastApAddTime", now);
            worker.put("_workStateTime", now);
        }
    }

    static int dormRate(JSONObject data, JSONObject room, int level, String id) {
        int rate = data.getJSONObject("dormData").getJSONArray("phases").getJSONObject(level - 1).getIntValue("manpowerRecover");
        if (room == null) return rate;
        rate += room.getIntValue("comfort") / data.getIntValue("comfortManpowerRecoverFactor");
        JSONObject ap = room.getJSONObject("buff") == null ? null : room.getJSONObject("buff").getJSONObject("apCost");
        if (ap != null) {
            rate += ap.getIntValue("all");
            if (ap.getJSONObject("self") != null) rate += ap.getJSONObject("self").getIntValue(id);
        }
        return Math.max(0, rate);
    }

    private static int occupants(JSONObject slot, String slotId, JSONObject workers, JSONObject owned, int threshold) {
        int count = 0;
        JSONArray ids = slot.getJSONArray("charInstIds");
        if (ids == null) return 0;
        for (Object value : ids) {
            String id = String.valueOf(value);
            JSONObject worker = workers.getJSONObject(id);
            if (owned.containsKey(id) && worker != null && slotId.equals(worker.getString("roomSlotId"))
                    && worker.getLongValue("ap") > threshold) count++;
        }
        return count;
    }

    private static int hireCost(JSONObject data, JSONObject operator) {
        int cost = 0;
        JSONObject definition = data.getJSONObject("chars").getJSONObject(operator.getString("charId"));
        if (definition == null) return cost;
        for (Object value : definition.getJSONArray("buffChar")) {
            String id = BaseSkillRuntimeSupport.bestUnlocked((JSONObject)value, operator);
            if (id == null || !id.startsWith("hire_spd_cost[")) continue;
            String text = data.getJSONObject("buffs").getJSONObject(id).getString("description");
            Matcher match = Pattern.compile("心情每小时消耗<@cc\\.v(?:up|down)>([+-]?[0-9.]+)</>").matcher(text);
            if (match.find()) cost -= (int)Math.round(Double.parseDouble(match.group(1)) * 100D);
        }
        return cost;
    }
}
