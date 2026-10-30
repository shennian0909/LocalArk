package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Offline elapsed-time settlement for staffed rooms; caller persists the detached account. */
public final class BaseStaffRuntimeSupport {
    private BaseStaffRuntimeSupport() {}

    public static void advance(JSONObject user, JSONObject data, long now) {
        JSONObject base = CharacterBuildingSupport.object(user, "building");
        ensureOwnedWorkers(user, base, data, now);
        BaseProductionSupport.recoverDormitoryAp(user, now);
        settleMeeting(user, base, data, now);
        settleBasicTrust(user, base, data, now);
        BaseWorkStateSupport.advance(user, data, now);
    }

    private static void ensureOwnedWorkers(JSONObject user, JSONObject base, JSONObject data, long now) {
        JSONObject owned = CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "troop"), "chars");
        JSONObject workers = CharacterBuildingSupport.object(base, "chars");
        for (String id : owned.keySet()) {
            JSONObject worker = workers.getJSONObject(id);
            if (worker == null) {
                String charId = owned.getJSONObject(id).getString("charId");
                JSONObject definition = data.getJSONObject("chars").getJSONObject(charId);
                if (definition == null) continue;
                // Old seeds indexed some template workers by character number instead of owned instance ID.
                worker = new JSONObject(true);
                worker.put("charId", charId);
                worker.put("roomSlotId", "");
                worker.put("index", -1);
                worker.put("changeScale", 0);
                worker.put("workTime", 0);
                worker.put("ap", definition.getLongValue("maxManpower"));
                worker.put("lastApAddTime", now);
                workers.put(id, worker);
            }
            ensureWorkerCollections(worker);
        }
    }

    private static void ensureWorkerCollections(JSONObject worker) {
        if (!worker.containsKey("privateRooms")) worker.put("privateRooms", new JSONArray());
        JSONObject bubbles = worker.getJSONObject("bubble");
        if (bubbles == null) {
            bubbles = new JSONObject(true);
            worker.put("bubble", bubbles);
        }
        ensureBubble(bubbles, "normal");
        ensureBubble(bubbles, "assist");
        ensureBubble(bubbles, "private");
    }

    private static void ensureBubble(JSONObject bubbles, String kind) {
        JSONObject bubble = bubbles.getJSONObject(kind);
        if (bubble == null) {
            bubble = new JSONObject(true);
            bubbles.put(kind, bubble);
            bubble.put("add", -1);
            bubble.put("ts", 0L);
        }
        if (!bubble.containsKey("add")) bubble.put("add", -1);
    }

    private static void settleMeeting(JSONObject user, JSONObject base, JSONObject data, long now) {
        JSONObject rooms = CharacterBuildingSupport.object(base, "rooms").getJSONObject("MEETING");
        if (rooms == null) return;
        JSONObject slots = CharacterBuildingSupport.object(base, "roomSlots");
        JSONObject owned = CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "troop"), "chars");
        JSONObject workers = CharacterBuildingSupport.object(base, "chars");
        double limit = data.getDoubleValue("socialResourceLimit");
        JSONArray phases = CharacterBuildingSupport.object(data, "meetingData").getJSONArray("phases");
        for (String slotId : rooms.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId), room = rooms.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != 2 || !"MEETING".equals(slot.getString("roomId"))) continue;
            boolean staffed = false;
            JSONArray stationed = slot.getJSONArray("charInstIds");
            if (stationed != null) for (int i = 0; i < stationed.size(); i++) {
                String id = stationed.getString(i);
                JSONObject worker = workers.getJSONObject(id);
                if (owned.containsKey(id) && worker != null && slotId.equals(worker.getString("roomSlotId"))
                        && worker.getLongValue("ap") > 0) staffed = true;
            }
            double point = room.containsKey("_meetingPoint") ? room.getDoubleValue("_meetingPoint") : room.getDoubleValue("processPoint");
            long anchor = room.getLongValue("lastUpdateTime");
            // First observation keeps saved progress but never turns a template epoch into years of production.
            if (room.getBooleanValue("_meetingRuntime") && anchor > 0 && anchor <= now && room.getIntValue("state") == 1) {
                point += (now - anchor) * room.getDoubleValue("speed");
            }
            point = Math.max(0D, Math.min(limit, point));
            double buff = room.getJSONObject("buff") == null ? 0D : room.getJSONObject("buff").getDoubleValue("speed");
            double speed = staffed ? phases.getJSONObject(slot.getIntValue("level") - 1).getDoubleValue("gatheringSpeed") * Math.max(0D, 1D + buff) : 0D;
            room.put("_meetingRuntime", true);
            room.put("_meetingPoint", point);
            room.put("processPoint", (int) point);
            room.put("speed", speed);
            room.put("state", speed > 0 && point < limit ? 1 : 0);
            room.put("lastUpdateTime", now);
            room.put("completeWorkTime", speed > 0 && point < limit ? now + (long) Math.ceil((limit - point) / speed) : -1L);
        }
    }

    private static void settleBasicTrust(JSONObject user, JSONObject base, JSONObject data, long now) {
        JSONObject owned = CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "troop"), "chars");
        JSONObject slots = CharacterBuildingSupport.object(base, "roomSlots");
        JSONObject workers = CharacterBuildingSupport.object(base, "chars");
        double perDay = data.getDoubleValue("basicFavorPerDay");
        for (String id : owned.keySet()) {
            JSONObject worker = workers.getJSONObject(id);
            if (worker == null) continue;
            JSONObject slot = slots.getJSONObject(worker.getString("roomSlotId"));
            boolean stationed = slot != null && slot.getIntValue("state") == 2
                    && slot.getJSONArray("charInstIds") != null && slot.getJSONArray("charInstIds").contains(Integer.valueOf(id));
            long anchor = worker.getLongValue("_basicTrustTime");
            double fraction = worker.getDoubleValue("_basicTrustFraction");
            JSONObject bubbles = worker.getJSONObject("bubble");
            if (bubbles == null) { bubbles = new JSONObject(true); worker.put("bubble", bubbles); }
            JSONObject normal = bubbles.getJSONObject("normal");
            if (normal == null) { normal = new JSONObject(true); normal.put("add", -1); bubbles.put("normal", normal); }
            if (stationed && anchor > 0 && anchor <= now) {
                double total = fraction + (now - anchor) * perDay / 86400D;
                int whole = (int) Math.min(25570D, Math.floor(total));
                int pending = Math.max(0, normal.getIntValue("add"));
                if (whole > 0) normal.put("add", Math.min(25570, pending + whole));
                fraction = total - Math.floor(total);
            }
            worker.put("_basicTrustTime", now);
            worker.put("_basicTrustFraction", fraction);
        }
    }
}
