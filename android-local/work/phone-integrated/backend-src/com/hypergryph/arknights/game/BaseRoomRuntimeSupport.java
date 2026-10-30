package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Mutates a detached save; the caller owns persistence and response deltas. */
public final class BaseRoomRuntimeSupport {
    static final String MIGRATION_FIELD = "_baseRoomRuntimeMigration";
    private static final int MIGRATION_VERSION = 1;

    private BaseRoomRuntimeSupport() {}

    public static boolean mutate(JSONObject user, JSONObject buildingData, long now) {
        validateInput(user, buildingData, now);
        JSONObject building = user.getJSONObject("building");
        boolean changed = settleHire(building, buildingData, now);
        changed |= settleLabor(building, buildingData.getIntValue("laborRecoverTime"), now);
        changed |= ManufactureRuntimeSupport.advance(user, buildingData, now);
        changed |= refreshStationBuffs(user, buildingData, now);
        return changed;
    }

    public static boolean refreshStationBuffs(JSONObject user, JSONObject data, long now) {
        boolean changed = BaseSkillRuntimeSupport.recompute(user, data);
        if (changed) {
            // The old rate was settled before a staff/skill change; only future work uses this rate.
            settleHire(user.getJSONObject("building"), data, now);
            ManufactureRuntimeSupport.advance(user, data, now);
        }
        return changed;
    }

    private static boolean settleHire(JSONObject building, JSONObject table, long now) {
        JSONObject hireRooms = building.getJSONObject("rooms").getJSONObject("HIRE");
        if (hireRooms == null) return false;
        JSONObject slots = building.getJSONObject("roomSlots");
        JSONObject hireData = table.getJSONObject("hireData");
        JSONArray phases = hireData.getJSONArray("phases");
        double limit = table.getDoubleValue("humanResourceLimit");
        boolean changed = false;
        for (String slotId : hireRooms.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != 2
                    || !"HIRE".equals(slot.getString("roomId"))) continue;
            int level = slot.getIntValue("level");
            if (level <= 0 || level > phases.size()) {
                throw new IllegalArgumentException("Invalid hire level");
            }
            JSONObject phase = phases.getJSONObject(level - 1);
            JSONObject room = hireRooms.getJSONObject(slotId);
            double speed = effectiveHireSpeed(building, slotId, slot, room,
                    hireData, phase.getDoubleValue("resSpeed"));
            changed |= settleHireRoom(room, now, limit, speed, phase.getIntValue("refreshTimes"));
        }
        return changed;
    }

    private static boolean settleHireRoom(
            JSONObject room, long now, double limit, double speed, int cap) {
        int count = room.getIntValue("refreshCount");
        double point = room.getDoubleValue("processPoint");
        long anchor = room.getLongValue("lastUpdateTime");
        boolean migrated = room.getIntValue(MIGRATION_FIELD) >= MIGRATION_VERSION;
        boolean changed = false;

        if (count >= cap) {
            point = limit;
        } else if (point >= limit && room.getIntValue("state") == 0) {
            // Only EMPTY uses full point as a sentinel; HIRING at the limit has earned a count.
            point = 0D;
        } else if (migrated && anchor > 0 && anchor <= now) {
            double total = point + (now - anchor) * speed;
            int gained = (int) Math.min(cap - count, Math.floor(total / limit));
            count += gained;
            point = count >= cap ? limit : total % limit;
        }

        // Legacy seeds have anchors such as 1700000000; first observation credits no elapsed time.
        changed |= putLong(room, MIGRATION_FIELD, MIGRATION_VERSION);
        changed |= putLong(room, "refreshCount", count);
        changed |= putDouble(room, "processPoint", point);
        changed |= putDouble(room, "speed", speed);
        changed |= putLong(room, "state", count >= cap ? 0 : 1);
        changed |= putLong(room, "completeWorkTime", count >= cap ? -1L
                : now + (long) Math.ceil((limit - point) / speed));
        changed |= putLong(room, "lastUpdateTime", now);
        return changed;
    }

    private static boolean settleLabor(JSONObject building, int interval, long now) {
        JSONObject labor = building.getJSONObject("status").getJSONObject("labor");
        if (labor == null) return false;
        if (labor.getIntValue(MIGRATION_FIELD) < MIGRATION_VERSION) {
            labor.put(MIGRATION_FIELD, MIGRATION_VERSION);
            labor.put("lastUpdateTime", now);
            return true;
        }

        long anchor = labor.getLongValue("lastUpdateTime");
        if (anchor <= 0 || anchor > now) return putLong(labor, "lastUpdateTime", now);
        int value = labor.getIntValue("value");
        int max = labor.getIntValue("maxValue");
        double point = labor.getDoubleValue("processPoint");
        if (value >= max) {
            point = 0D;
        } else {
            double total = point + (now - anchor) * (1D + labor.getDoubleValue("buffSpeed"));
            value += (int) Math.min(max - value, Math.floor(total / interval));
            point = value >= max ? 0D : total % interval;
        }
        boolean changed = putLong(labor, "value", value);
        changed |= putDouble(labor, "processPoint", point);
        changed |= putLong(labor, "lastUpdateTime", now);
        return changed;
    }

    private static double effectiveHireSpeed(JSONObject building, String slotId,
            JSONObject slot, JSONObject room, JSONObject hireData, double baseSpeed) {
        double speed = room.getDoubleValue("speed");
        if (speed > 0D) return speed;
        JSONObject buff = room.getJSONObject("buff");
        double speedBuff = buff == null ? 0D : buff.getDoubleValue("speed");
        if (speedBuff == 0D && hasStationedWorker(building, slotId, slot)) {
            speedBuff = hireData.getDoubleValue("basicSpeedBuff");
        }
        speed = baseSpeed * (1D + speedBuff);
        if (!Double.isFinite(speed) || speed <= 0D) {
            throw new IllegalArgumentException("Invalid hire speed");
        }
        return speed;
    }

    private static boolean hasStationedWorker(JSONObject building, String slotId, JSONObject slot) {
        JSONObject workers = building.getJSONObject("chars");
        JSONArray stationed = slot.getJSONArray("charInstIds");
        for (int i = 0; i < stationed.size(); i++) {
            JSONObject worker = workers.getJSONObject(String.valueOf(stationed.getIntValue(i)));
            if (worker != null && slotId.equals(worker.getString("roomSlotId"))) return true;
        }
        return false;
    }

    private static void validateInput(JSONObject user, JSONObject table, long now) {
        if (user == null || table == null || now <= 0) {
            throw new IllegalArgumentException("Missing user/table or invalid current time");
        }
        JSONObject building = user.getJSONObject("building");
        if (building == null || building.getJSONObject("roomSlots") == null
                || building.getJSONObject("rooms") == null || building.getJSONObject("chars") == null
                || building.getJSONObject("status") == null) {
            throw new IllegalArgumentException("Missing building state");
        }
        JSONObject hireData = table.getJSONObject("hireData");
        double limit = table.getDoubleValue("humanResourceLimit");
        if (!Double.isFinite(limit) || limit <= 0D
                || table.getIntValue("laborRecoverTime") <= 0 || hireData == null
                || hireData.getJSONArray("phases") == null) {
            throw new IllegalArgumentException("Missing room timing table");
        }
        JSONArray phases = hireData.getJSONArray("phases");
        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            if (phase.getIntValue("refreshTimes") <= 0 || phase.getDoubleValue("resSpeed") <= 0D) {
                throw new IllegalArgumentException("Invalid hire phase capacity or speed");
            }
        }
    }

    private static boolean putLong(JSONObject object, String key, long value) {
        if (object.containsKey(key) && object.getLongValue(key) == value) return false;
        object.put(key, value);
        return true;
    }

    private static boolean putDouble(JSONObject object, String key, double value) {
        if (object.containsKey(key) && Double.compare(object.getDoubleValue(key), value) == 0) return false;
        object.put(key, value);
        return true;
    }
}
