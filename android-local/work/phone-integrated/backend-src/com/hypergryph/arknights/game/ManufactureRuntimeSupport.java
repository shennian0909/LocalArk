package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.BackendPaths;
import com.hypergryph.arknights.core.file.IOTools;

/** Free factory plan changes and elapsed-time settlement on a detached account save. */
public final class ManufactureRuntimeSupport {
    static final String MIGRATION_FIELD = "_manufactureRuntimeMigration";
    private static final int MIGRATION_VERSION = 1;
    private static final int BUILT = 2;
    private static final int STOP = 0;
    private static final int RUN = 1;
    private static volatile JSONObject cachedBuildingData;

    private ManufactureRuntimeSupport() {}

    public static JSONObject change(JSONObject user, JSONObject body, long now) {
        validateUser(user, now);
        ChangeRequest request = parseRequest(body);
        JSONObject building = user.getJSONObject("building");
        JSONObject slot = building.getJSONObject("roomSlots").getJSONObject(request.roomSlotId);
        require(slot != null, "Unknown room slot");
        require(slot.getIntValue("state") == BUILT
                        && "MANUFACTURE".equals(slot.getString("roomId")),
                "Room is not a built factory");

        JSONObject rooms = building.getJSONObject("rooms").getJSONObject("MANUFACTURE");
        require(rooms != null, "No factory rooms");
        JSONObject room = rooms.getJSONObject(request.roomSlotId);
        require(room != null, "Missing factory room state");

        JSONObject table = buildingData();
        advanceRoom(user, slot, room, table, now);
        require(room.getIntValue("outputSolutionCnt") <= 0,
                "Collect manufacturing output before changing formula");

        JSONObject formulas = table.getJSONObject("manufactFormulas");
        String oldFormulaId = room.getString("formulaId");
        JSONObject oldFormula = oldFormulaId == null || oldFormulaId.isEmpty()
                ? null : formulas.getJSONObject(oldFormulaId);
        if (oldFormulaId != null && !oldFormulaId.isEmpty()
                && !isSupportedFormula(oldFormulaId, oldFormula)) {
            boolean active = room.getIntValue("remainSolutionCnt") > 0
                    || room.getDoubleValue("processPoint") != 0D
                    || room.getLongValue("saveTime") != 0L
                    || room.getIntValue("outputSolutionCnt") > 0;
            require(!active, "Cannot cancel an active paid manufacturing plan");
        }

        JSONObject target = formulas.getJSONObject(request.targetFormulaId);
        require(target != null, "Unknown manufacturing formula");
        require(isSupportedFormula(request.targetFormulaId, target),
                "Only free EXP/GOLD manufacturing formulas are supported");
        require(requirementsMet(user, slot, target),
                "Manufacturing formula requirements are not met");
        require(hasOnlyScalarSpeedBuff(room),
                "Unsupported extra manufacturing speed fields");
        require(Double.isFinite(factorySpeed(room)) && factorySpeed(room) > 0D, "Invalid manufacturing speed");
        room.put("formulaId", request.targetFormulaId);
        BaseSkillRuntimeSupport.recompute(user, table);
        double speed = factorySpeed(room);
        require(Double.isFinite(speed) && speed > 0D, "Invalid manufacturing speed");
        require(Double.isFinite(target.getDoubleValue("costPoint"))
                        && target.getDoubleValue("costPoint") > 0D
                        && target.getIntValue("weight") > 0,
                "Invalid manufacturing formula");

        putInt(room, "outputSolutionCnt", 0);
        putInt(room, "remainSolutionCnt", request.solutionCount);
        putDouble(room, "processPoint", 0D);
        putLong(room, "saveTime", 0L);
        putLong(room, "lastUpdateTime", now);
        putLong(room, MIGRATION_FIELD, MIGRATION_VERSION);
        publishSchedule(room, target, now, 0D, speed);

        JSONObject modified = new JSONObject(true);
        modified.put("building", building);
        JSONObject deleted = new JSONObject(true);
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", deleted);
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("playerDataDelta", delta);
        // The DTO field exists; its official scalar meaning has not been captured.
        result.put("change", true);
        return result;
    }

    public static boolean advance(JSONObject user, JSONObject buildingData, long now) {
        validateUser(user, now);
        require(buildingData != null && buildingData.getJSONObject("manufactFormulas") != null,
                "Missing manufacturing table");
        JSONObject building = user.getJSONObject("building");
        JSONObject rooms = building.getJSONObject("rooms").getJSONObject("MANUFACTURE");
        if (rooms == null) return false;
        JSONObject slots = building.getJSONObject("roomSlots");
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject room = rooms.getJSONObject(slotId);
            JSONObject slot = slots.getJSONObject(slotId);
            if (room == null || slot == null || slot.getIntValue("state") != BUILT
                    || !"MANUFACTURE".equals(slot.getString("roomId"))) continue;
            changed |= advanceRoom(user, slot, room, buildingData, now);
        }
        return changed;
    }

    private static boolean advanceRoom(
            JSONObject user, JSONObject slot, JSONObject room, JSONObject table, long now) {
        String formulaId = room.getString("formulaId");
        if (formulaId == null || formulaId.isEmpty()) return false;
        JSONObject formula = table.getJSONObject("manufactFormulas").getJSONObject(formulaId);
        if (!isSupportedFormula(formulaId, formula)
                || !requirementsMet(user, slot, formula)
                || !hasOnlyScalarSpeedBuff(room)) return false;

        double speed = factorySpeed(room);
        double costPoint = formula.getDoubleValue("costPoint");
        int weight = formula.getIntValue("weight");
        int remain = room.getIntValue("remainSolutionCnt");
        int output = room.getIntValue("outputSolutionCnt");
        double point = room.getDoubleValue("processPoint");
        if (!Double.isFinite(speed) || speed <= 0D || !Double.isFinite(costPoint)
                || costPoint <= 0D || weight <= 0 || remain < 0 || output < 0
                || !Double.isFinite(point) || point < 0D) return false;

        boolean migrated = room.getIntValue(MIGRATION_FIELD) >= MIGRATION_VERSION;
        long anchor = room.getLongValue("lastUpdateTime");
        if (!migrated || anchor <= 0L || anchor > now) {
            boolean changed = false;
            if (!migrated) changed |= putLong(room, MIGRATION_FIELD, MIGRATION_VERSION);
            changed |= putLong(room, "lastUpdateTime", now);
            changed |= publishSchedule(room, formula, now, point, speed);
            return changed;
        }

        int capacityCount = capacityCount(room, weight, output);
        if (remain == 0 || capacityCount == 0) {
            boolean changed = putLong(room, "state", STOP);
            changed |= putLong(room, "completeWorkTime", -1L);
            if (remain == 0) changed |= putDouble(room, "processPoint", 0D);
            return changed;
        }
        if (now == anchor) {
            return publishSchedule(room, formula, now, point, speed);
        }

        double progress = point + (now - anchor) * speed;
        if (!Double.isFinite(progress)) return false;
        int scheduled = Math.min(remain, capacityCount);
        long finishedLong = (long) Math.floor(progress / costPoint);
        int finished = (int) Math.min(scheduled, finishedLong);

        if (finished == 0) {
            boolean changed = putDouble(room, "processPoint", progress);
            changed |= putLong(room, "lastUpdateTime", now);
            changed |= publishSchedule(room, formula, now, progress, speed);
            return changed;
        }

        int nextOutput = output + finished;
        int nextRemain = remain - finished;
        boolean changed = putInt(room, "outputSolutionCnt", nextOutput);
        changed |= putInt(room, "remainSolutionCnt", nextRemain);
        if (nextRemain == 0 || capacityCount(room, weight, nextOutput) == 0) {
            double worked = finished * costPoint - point;
            long stoppedAt = addCeiling(anchor, Math.max(0D, worked / speed));
            changed |= putLong(room, "lastUpdateTime", stoppedAt);
            if (nextRemain == 0) {
                changed |= putDouble(room, "processPoint", 0D);
            } else {
                // Keep only the overshoot at the stop second, never later warehouse downtime.
                double overshoot = Math.max(0D,
                        point + (stoppedAt - anchor) * speed - finished * costPoint);
                changed |= putDouble(room, "processPoint", overshoot);
            }
            changed |= putLong(room, "state", STOP);
            changed |= putLong(room, "completeWorkTime", -1L);
            return changed;
        }

        double remainder = progress - finished * costPoint;
        changed |= putDouble(room, "processPoint", remainder);
        changed |= putLong(room, "lastUpdateTime", now);
        changed |= publishSchedule(room, formula, now, remainder, speed);
        return changed;
    }

    private static boolean publishSchedule(
            JSONObject room, JSONObject formula, long anchor, double point, double speed) {
        int remain = room.getIntValue("remainSolutionCnt");
        int output = room.getIntValue("outputSolutionCnt");
        int weight = formula.getIntValue("weight");
        int count = Math.min(remain, capacityCount(room, weight, output));
        boolean changed;
        if (count <= 0) {
            changed = putLong(room, "state", STOP);
            changed |= putLong(room, "completeWorkTime", -1L);
            return changed;
        }
        double seconds = Math.max(1D, (count * formula.getDoubleValue("costPoint") - point) / speed);
        long deadline = addCeiling(anchor, seconds);
        changed = putLong(room, "state", RUN);
        changed |= putLong(room, "completeWorkTime", deadline);
        return changed;
    }

    private static int capacityCount(JSONObject room, int weight, int output) {
        double available = room.getDoubleValue("capacity") - (double) output * weight;
        if (available <= 0D) return 0;
        long count = (long) Math.floor(available / weight);
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    private static double factorySpeed(JSONObject room) {
        JSONObject buff = room.getJSONObject("buff");
        double buffSpeed = buff == null ? 0D : buff.getDoubleValue("speed");
        // Old/default saves use exactly -1 for an uncomputed bonus; use the base rate.
        return buffSpeed == -1D ? 1D : 1D + buffSpeed;
    }

    private static boolean hasOnlyScalarSpeedBuff(JSONObject room) {
        JSONObject buff = room.getJSONObject("buff");
        if (buff == null) return true;
        return neutralSpeedField(buff.get("tSpeed"))
                && neutralSpeedField(buff.get("cSpeed"))
                && neutralSpeedField(buff.get("sSpeed"));
    }

    private static boolean neutralSpeedField(Object value) {
        if (value == null) return true;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && number == 0D;
        }
        if (value instanceof JSONObject) return ((JSONObject) value).isEmpty();
        if (value instanceof JSONArray) return ((JSONArray) value).isEmpty();
        if (value instanceof String) return ((String) value).isEmpty();
        return false;
    }

    private static boolean requirementsMet(JSONObject user, JSONObject slot, JSONObject formula) {
        JSONArray requiredRooms = formula.getJSONArray("requireRooms");
        if (requiredRooms == null) return false;
        boolean roomMatched = false;
        int level = slot.getIntValue("level");
        for (int i = 0; i < requiredRooms.size(); i++) {
            JSONObject required = requiredRooms.getJSONObject(i);
            if (required == null || !"MANUFACTURE".equals(required.getString("roomId"))) continue;
            if (level >= required.getIntValue("roomLevel")) roomMatched = true;
        }
        if (!roomMatched) return false;

        JSONArray requiredStages = formula.getJSONArray("requireStages");
        if (requiredStages == null || requiredStages.isEmpty()) return true;
        JSONObject dungeon = user.getJSONObject("dungeon");
        JSONObject stages = dungeon == null ? null : dungeon.getJSONObject("stages");
        for (int i = 0; i < requiredStages.size(); i++) {
            JSONObject required = requiredStages.getJSONObject(i);
            if (required == null || required.getString("stageId") == null) return false;
            JSONObject stage = stages == null ? null : stages.getJSONObject(required.getString("stageId"));
            if (stage == null || stage.getIntValue("state") < required.getIntValue("rank")) return false;
        }
        return true;
    }

    private static boolean isSupportedFormula(String formulaId, JSONObject formula) {
        if (formula == null || !("1".equals(formulaId) || "2".equals(formulaId)
                || "3".equals(formulaId) || "4".equals(formulaId))) return false;
        JSONArray costs = formula.getJSONArray("costs");
        if (costs == null || !costs.isEmpty()) return false;
        String type = formula.getString("formulaType");
        return "F_EXP".equals(type) || "F_GOLD".equals(type);
    }

    private static ChangeRequest parseRequest(JSONObject body) {
        require(body != null, "Missing manufacturing request");
        Object roomSlotId = body.get("roomSlotId");
        Object targetFormulaId = body.get("targetFormulaId");
        Object solutionCount = body.get("solutionCount");
        require(roomSlotId instanceof String && !((String) roomSlotId).isEmpty(),
                "Missing roomSlotId");
        require(targetFormulaId instanceof String && !((String) targetFormulaId).isEmpty(),
                "Missing targetFormulaId");
        require(solutionCount instanceof Number, "Invalid solutionCount");
        double value = ((Number) solutionCount).doubleValue();
        require(Double.isFinite(value) && value == Math.rint(value),
                "Invalid solutionCount");
        require(value >= 1D && value <= 99D, "solutionCount must be between 1 and 99");
        return new ChangeRequest((String) roomSlotId, (String) targetFormulaId, (int) value);
    }

    private static JSONObject buildingData() {
        JSONObject data = cachedBuildingData;
        if (data != null) return data;
        synchronized (ManufactureRuntimeSupport.class) {
            data = cachedBuildingData;
            if (data == null) {
                data = IOTools.ReadJsonFile(BackendPaths.root() + "/data/excel/building_data.json");
                require(data != null, "Required game table unavailable");
                cachedBuildingData = data;
            }
            return data;
        }
    }

    private static void validateUser(JSONObject user, long now) {
        require(user != null && now > 0L, "Missing user or invalid current time");
        JSONObject building = user.getJSONObject("building");
        require(building != null && building.getJSONObject("roomSlots") != null
                && building.getJSONObject("rooms") != null, "Missing building state");
    }

    private static long addCeiling(long base, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0D || seconds > Long.MAX_VALUE) {
            throw new ArithmeticException("Invalid manufacturing duration");
        }
        long whole = (long) Math.ceil(seconds);
        try {
            return Math.addExact(base, whole);
        } catch (ArithmeticException ex) {
            throw new ArithmeticException("Manufacturing deadline overflow");
        }
    }

    private static boolean putLong(JSONObject object, String key, long value) {
        if (object.containsKey(key) && object.getLongValue(key) == value) return false;
        object.put(key, value);
        return true;
    }

    private static boolean putInt(JSONObject object, String key, int value) {
        if (object.containsKey(key) && object.getIntValue(key) == value) return false;
        object.put(key, value);
        return true;
    }

    private static boolean putDouble(JSONObject object, String key, double value) {
        if (object.containsKey(key) && Double.compare(object.getDoubleValue(key), value) == 0) {
            return false;
        }
        object.put(key, value);
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record ChangeRequest(String roomSlotId, String targetFormulaId, int solutionCount) {}
}
