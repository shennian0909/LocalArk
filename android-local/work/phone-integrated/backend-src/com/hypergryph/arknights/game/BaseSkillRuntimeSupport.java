package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Base skill speed bonuses that the local server can derive exactly from the shipped tables:
 * manufacturing productivity, control-centre global manufacturing bonuses, hire refresh speed and
 * power-station labour speed.
 *
 * <p>Every recompute replaces the values it previously wrote; a bonus disappears when its operator
 * leaves or the formula stops matching. A room is only taken over when every staffed skill that
 * affects it is understood, otherwise its stored value is left alone.
 */
public final class BaseSkillRuntimeSupport {
    /** Marks a room whose speed fields this helper owns and therefore overwrites. */
    static final String TAKEN_FIELD = "_skillRuntimeTaken";
    private static final int TAKEN_VERSION = 1;
    private static final int BUILT = 2;
    private static final int SEES_LIMIT = 4;
    private static final String SEES_TEAM = "sees";
    private static final String SEES_BUFF = "manu_prod_spd&bd[000]";
    private static final double SEES_PER_MEMBER = 5D;
    /** The table stores efficiency 0 for these; the percentage comes from their description. */
    private static final double CONTROL_ALL_SPEED = 2D;
    private static final double CONTROL_GOLD_SPEED = 1D;
    private static final double CONTROL_GOLD_SPEED_LOW = 0.5D;

    private BaseSkillRuntimeSupport() {}

    /** Recomputes every supported room bonus. Returns whether anything changed. */
    public static boolean recompute(JSONObject user, JSONObject data) {
        JSONObject building = user == null ? null : user.getJSONObject("building");
        if (building == null || data == null) return false;
        JSONObject slots = building.getJSONObject("roomSlots");
        JSONObject workers = building.getJSONObject("chars");
        JSONObject owned = owned(user);
        if (slots == null || workers == null || owned == null) return false;

        Control control = controlBonus(building, data, slots, workers, owned);
        boolean changed = manufacture(building, data, slots, workers, owned, control);
        changed |= trading(building, data, slots, workers, owned);
        changed |= hire(building, data, slots, workers, owned);
        changed |= power(building, data, slots, workers, owned);
        changed |= dormitory(building, data, slots, workers, owned);
        return changed;
    }

    private static boolean dormitory(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned) {
        JSONObject rooms = rooms(building, "DORMITORY");
        if (rooms == null) return false;
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId), room = rooms.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != BUILT) continue;
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            JSONObject self = new JSONObject(true);
            int all = 0;
            boolean known = true;
            for (Object value : staff) {
                String instance = String.valueOf(value);
                JSONObject roster = owned.getJSONObject(instance);
                JSONObject definition = data.getJSONObject("chars").getJSONObject(roster.getString("charId"));
                if (definition == null) continue;
                for (Object skill : definition.getJSONArray("buffChar")) {
                    String id = bestUnlocked((JSONObject)skill, roster);
                    if (id == null) continue;
                    JSONObject buff = data.getJSONObject("buffs").getJSONObject(id);
                    if (!"DORMITORY".equals(buff.getString("roomType"))) continue;
                    if (!"dorm_rec_all&oneself[000]".equals(id)) { known = false; break; }
                    // This table entry specifies +0.2/hour for the room and -0.1/hour for self.
                    all = Math.max(all, 20); self.put(instance, -10);
                }
            }
            if (!known || (staff.isEmpty() && !room.getBooleanValue("_dormSkillRuntimeTaken"))) continue;
            JSONObject ap = ShopTransactionSupport.object(ShopTransactionSupport.object(room, "buff"), "apCost");
            changed |= putInt(ap, "all", all);
            if (!self.equals(ap.getJSONObject("self"))) { ap.put("self", self); changed = true; }
            room.put("_dormSkillRuntimeTaken", true);
        }
        return changed;
    }

    private static boolean manufacture(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned, Control control) {
        JSONObject rooms = rooms(building, "MANUFACTURE");
        if (rooms == null) return false;
        JSONObject manufactData = data.getJSONObject("manufactData");
        JSONObject formulas = data.getJSONObject("manufactFormulas");
        if (manufactData == null || formulas == null) return false;
        double perWorker = manufactData.getDoubleValue("basicSpeedBuff");
        int sees = seesCount(building, slots, workers, owned);
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject room = rooms.getJSONObject(slotId);
            JSONObject slot = slots.getJSONObject(slotId);
            if (room == null || slot == null || slot.getIntValue("state") != BUILT
                    || !"MANUFACTURE".equals(slot.getString("roomId"))) continue;
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            String type = formulaType(formulas, room.getString("formulaId"));
            // An unclaimed room with no real worker may carry an official rate we cannot derive.
            // Keep that stored positive rate instead of zeroing a bonus we did not compute.
            if (staff.isEmpty() && preservesStoredBonus(room)) continue;
            double skill = 0D;
            boolean known = true;
            for (int i = 0; i < staff.size() && known; i++) {
                double gained = manufactureSkill(data, owned, staff.getString(i), type, sees);
                if (Double.isNaN(gained)) {
                    known = false;
                } else {
                    skill += gained;
                }
            }
            if (!known) {
                changed |= releaseTaken(room);
                continue;
            }
            changed |= writeManufacture(room, staff, perWorker, skill, type, control);
        }
        return changed;
    }

    private static boolean trading(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned) {
        JSONObject rooms = rooms(building, "TRADING");
        JSONObject tradingData = data.getJSONObject("tradingData");
        if (rooms == null || tradingData == null) return false;
        double perWorker = tradingData.getDoubleValue("basicSpeedBuff");
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject room = rooms.getJSONObject(slotId);
            JSONObject slot = slots.getJSONObject(slotId);
            if (room == null || slot == null || slot.getIntValue("state") != BUILT
                    || !"TRADING".equals(slot.getString("roomId"))) continue;
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            if (staff.isEmpty() && preservesStoredBonus(room)) continue;
            double skill = 0D;
            boolean known = true;
            for (int i = 0; i < staff.size() && known; i++) {
                double gained = tradingSkill(data, owned, staff.getString(i));
                if (Double.isNaN(gained)) {
                    known = false;
                } else {
                    skill += gained;
                }
            }
            if (!known) {
                changed |= releaseTaken(room);
                continue;
            }
            changed |= writeTrading(room, staff.size(), perWorker, skill);
        }
        return changed;
    }

    private static double tradingSkill(JSONObject data, JSONObject owned, String instId) {
        JSONObject roster = owned.getJSONObject(instId);
        JSONObject definition = data.getJSONObject("chars").getJSONObject(roster.getString("charId"));
        JSONArray buffChar = definition == null ? null : definition.getJSONArray("buffChar");
        if (buffChar == null) return 0D;
        double total = 0D;
        for (int i = 0; i < buffChar.size(); i++) {
            String buffId = bestUnlocked(buffChar.getJSONObject(i), roster);
            if (buffId == null) continue;
            JSONObject buff = data.getJSONObject("buffs").getJSONObject(buffId);
            if (buff == null || !"TRADING".equals(buff.getString("roomType"))) continue;
            if (!buffId.startsWith("trade_ord_par&per[")) return Double.NaN;
            total += buff.getDoubleValue("efficiency");
        }
        return total;
    }

    private static boolean writeTrading(JSONObject room, int workers, double perWorker, double skill) {
        JSONObject buff = room.getJSONObject("buff");
        boolean changed = false;
        if (buff == null) {
            buff = new JSONObject(true);
            room.put("buff", buff);
            changed = true;
        }
        changed |= putDouble(buff, "speed", workers * perWorker + skill / 100D);
        changed |= putInt(room, TAKEN_FIELD, TAKEN_VERSION);
        return changed;
    }

    /** True only for a room we never took over whose stored total bonus is already positive. */
    private static boolean preservesStoredBonus(JSONObject room) {
        if (room.getIntValue(TAKEN_FIELD) >= TAKEN_VERSION) return false;
        JSONObject buff = room.getJSONObject("buff");
        return buff != null && buff.getDoubleValue("speed") > 0D;
    }

    /**
     * Returns the productivity percentage one operator grants, or {@link Double#NaN} when that
     * operator has a manufacturing skill this helper does not compute.
     */
    private static double manufactureSkill(
            JSONObject data, JSONObject owned, String instId, String type, int sees) {
        JSONObject roster = owned.getJSONObject(instId);
        JSONObject definition = data.getJSONObject("chars").getJSONObject(roster.getString("charId"));
        JSONArray buffChar = definition == null ? null : definition.getJSONArray("buffChar");
        if (buffChar == null) return 0D;
        double total = 0D;
        for (int i = 0; i < buffChar.size(); i++) {
            String buffId = bestUnlocked(buffChar.getJSONObject(i), roster);
            if (buffId == null) continue;
            JSONObject buff = data.getJSONObject("buffs").getJSONObject(buffId);
            if (buff == null || !"MANUFACTURE".equals(buff.getString("roomType"))) continue;
            if (!buffId.startsWith("manu_prod_spd[") && !buffId.startsWith("manu_formula_spd[")
                    && !SEES_BUFF.equals(buffId)) return Double.NaN;
            if (!matchesTarget(buff, type)) continue;
            double efficiency = buff.getDoubleValue("efficiency");
            total += SEES_BUFF.equals(buffId)
                    ? efficiency + SEES_PER_MEMBER * sees : efficiency;
        }
        return total;
    }

    private static boolean writeManufacture(JSONObject room, JSONArray staff, double perWorker,
            double skill, String type, Control control) {
        double base = staff.size() * perWorker;
        if (type != null) skill += control.all + ("F_GOLD".equals(type) ? control.gold : 0D);
        double total = base + skill / 100D;
        JSONObject buff = room.getJSONObject("buff");
        boolean changed = false;
        if (buff == null) {
            buff = new JSONObject(true);
            room.put("buff", buff);
            changed = true;
        }
        changed |= putDouble(buff, "speed", total);
        changed |= putInt(room, TAKEN_FIELD, TAKEN_VERSION);
        return changed;
    }

    private static boolean hire(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned) {
        JSONObject rooms = rooms(building, "HIRE");
        JSONObject hireData = data.getJSONObject("hireData");
        JSONArray phases = hireData == null ? null : hireData.getJSONArray("phases");
        if (rooms == null || phases == null) return false;
        double perWorker = hireData.getDoubleValue("basicSpeedBuff");
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject room = rooms.getJSONObject(slotId);
            JSONObject slot = slots.getJSONObject(slotId);
            if (room == null || slot == null || slot.getIntValue("state") != BUILT
                    || !"HIRE".equals(slot.getString("roomId"))) continue;
            int level = slot.getIntValue("level");
            if (level <= 0 || level > phases.size()) continue;
            double resSpeed = phases.getJSONObject(level - 1).getDoubleValue("resSpeed");
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            double skill = 0D;
            boolean known = true;
            for (int i = 0; i < staff.size() && known; i++) {
                skill += hireSkill(data, owned, staff.getString(i));
                known = !Double.isNaN(skill);
            }
            if (!known) {
                changed |= releaseTaken(room);
                continue;
            }
            if (resSpeed <= 0D) continue;
            double base = staff.size() * perWorker;
            double bonus = base + skill / 100D;
            JSONObject buff = room.getJSONObject("buff");
            boolean local = false;
            if (buff == null) {
                buff = new JSONObject(true);
                room.put("buff", buff);
                local = true;
            }
            JSONObject display = room.getJSONObject("display");
            if (display == null) {
                display = new JSONObject(true);
                room.put("display", display);
                local = true;
            }
            local |= putDouble(buff, "speed", bonus);
            local |= putDouble(display, "base", base * 100D);
            local |= putDouble(display, "buff", skill);
            local |= putDouble(room, "speed", resSpeed * (1D + bonus));
            local |= putInt(room, TAKEN_FIELD, TAKEN_VERSION);
            changed |= local;
        }
        return changed;
    }

    private static double hireSkill(JSONObject data, JSONObject owned, String instId) {
        JSONObject roster = owned.getJSONObject(instId);
        JSONObject definition = data.getJSONObject("chars").getJSONObject(roster.getString("charId"));
        JSONArray buffChar = definition == null ? null : definition.getJSONArray("buffChar");
        if (buffChar == null) return 0D;
        double total = 0D;
        for (int i = 0; i < buffChar.size(); i++) {
            String buffId = bestUnlocked(buffChar.getJSONObject(i), roster);
            if (buffId == null) continue;
            JSONObject buff = data.getJSONObject("buffs").getJSONObject(buffId);
            if (buff == null || !"HIRE".equals(buff.getString("roomType"))) continue;
            // Only the flat refresh-speed-and-cost line computes exactly; clue/dorm extras do not.
            if (!buffId.startsWith("hire_spd_cost[")) return Double.NaN;
            double efficiency = buff.getDoubleValue("efficiency");
            if (efficiency == 0D) return Double.NaN;
            total += efficiency;
        }
        return total;
    }

    private static boolean power(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned) {
        JSONObject rooms = rooms(building, "POWER");
        JSONObject powerData = data.getJSONObject("powerData");
        if (rooms == null || powerData == null) return false;
        double perWorker = powerData.getDoubleValue("basicSpeedBuff");
        double total = 0D;
        boolean known = true;
        boolean changed = false;
        for (String slotId : rooms.keySet()) {
            JSONObject room = rooms.getJSONObject(slotId);
            JSONObject slot = slots.getJSONObject(slotId);
            if (room == null || slot == null || slot.getIntValue("state") != BUILT
                    || !"POWER".equals(slot.getString("roomId"))) continue;
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            double skill = 0D;
            for (int i = 0; i < staff.size(); i++) {
                skill += powerSkill(data, owned, staff.getString(i));
            }
            if (Double.isNaN(skill)) {
                known = false;
                changed |= releaseTaken(room);
                continue;
            }
            double bonus = staff.size() * perWorker + skill / 100D;
            total += bonus;
            JSONObject buff = room.getJSONObject("buff");
            boolean local = false;
            if (buff == null) {
                buff = new JSONObject(true);
                room.put("buff", buff);
                local = true;
            }
            local |= putDouble(buff, "speed", bonus);
            local |= putInt(room, TAKEN_FIELD, TAKEN_VERSION);
            changed |= local;
        }
        if (!known) return changed;
        JSONObject labor = building.getJSONObject("status") == null
                ? null : building.getJSONObject("status").getJSONObject("labor");
        if (labor == null) return changed;
        return changed | putDouble(labor, "buffSpeed", total);
    }

    private static double powerSkill(JSONObject data, JSONObject owned, String instId) {
        JSONObject roster = owned.getJSONObject(instId);
        JSONObject definition = data.getJSONObject("chars").getJSONObject(roster.getString("charId"));
        JSONArray buffChar = definition == null ? null : definition.getJSONArray("buffChar");
        if (buffChar == null) return 0D;
        double total = 0D;
        for (int i = 0; i < buffChar.size(); i++) {
            String buffId = bestUnlocked(buffChar.getJSONObject(i), roster);
            if (buffId == null) continue;
            JSONObject buff = data.getJSONObject("buffs").getJSONObject(buffId);
            if (buff == null || !"POWER".equals(buff.getString("roomType"))) continue;
            if (!buffId.startsWith("power_rec_spd[")) return Double.NaN;
            total += buff.getDoubleValue("efficiency");
        }
        return total;
    }

    private static Control controlBonus(JSONObject building, JSONObject data, JSONObject slots,
            JSONObject workers, JSONObject owned) {
        JSONObject rooms = rooms(building, "CONTROL");
        if (rooms == null) return new Control(0D, 0D);
        if (slots == null || workers == null) return new Control(0D, 0D);
        double all = 0D;
        double gold = 0D;
        for (String slotId : rooms.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != BUILT
                    || !"CONTROL".equals(slot.getString("roomId"))) continue;
            JSONArray staff = staffed(data, owned, workers, slot, slotId);
            for (int i = 0; i < staff.size(); i++) {
                JSONObject roster = owned.getJSONObject(staff.getString(i));
                JSONObject definition = data.getJSONObject("chars")
                        .getJSONObject(roster.getString("charId"));
                JSONArray buffChar = definition == null ? null : definition.getJSONArray("buffChar");
                if (buffChar == null) continue;
                for (int s = 0; s < buffChar.size(); s++) {
                    String buffId = bestUnlocked(buffChar.getJSONObject(s), roster);
                    if (buffId == null) continue;
                    JSONObject buff = data.getJSONObject("buffs").getJSONObject(buffId);
                    if (buff == null || !"CONTROL".equals(buff.getString("roomType"))) continue;
                    if (buffId.startsWith("control_prod_spd[")) {
                        all = Math.max(all, CONTROL_ALL_SPEED);
                    } else if ("control_prod_bd_spd[010]".equals(buffId)) {
                        gold = Math.max(gold, CONTROL_GOLD_SPEED);
                    } else if ("control_prod_bd_spd[000]".equals(buffId)) {
                        gold = Math.max(gold, CONTROL_GOLD_SPEED_LOW);
                    }
                }
            }
        }
        return new Control(all, gold);
    }

    /**
     * Counts distinct S.E.E.S. instances holding a real base work position. Being listed as an
     * assistant does not itself disqualify an operator; only a missing work position does.
     */
    private static int seesCount(JSONObject building, JSONObject slots, JSONObject workers,
            JSONObject owned) {
        JSONObject table = CharacterBuildingSupport.table("excel/character_table.json");
        int count = 0;
        for (String instId : workers.keySet()) {
            JSONObject worker = workers.getJSONObject(instId);
            JSONObject roster = owned.getJSONObject(instId);
            if (worker == null || roster == null) continue;
            String slotId = worker.getString("roomSlotId");
            if (slotId == null || slotId.isEmpty()) continue;
            JSONObject slot = slots.getJSONObject(slotId);
            if (slot == null || !slot.getJSONArray("charInstIds").contains(Integer.valueOf(instId))) {
                continue;
            }
            JSONObject definition = table.getJSONObject(roster.getString("charId"));
            if (definition == null || !SEES_TEAM.equals(definition.getString("teamId"))) continue;
            count++;
        }
        return Math.min(SEES_LIMIT, count);
    }

    /** Owned instances that are really stationed in this slot and still have morale. */
    private static JSONArray staffed(JSONObject data, JSONObject owned, JSONObject workers, JSONObject slot,
            String slotId) {
        JSONArray stationed = slot.getJSONArray("charInstIds");
        JSONArray staff = new JSONArray();
        if (stationed == null) return staff;
        for (int i = 0; i < stationed.size(); i++) {
            int instId = stationed.getIntValue(i);
            if (instId <= 0) continue;
            String key = String.valueOf(instId);
            JSONObject worker = workers.getJSONObject(key);
            if (worker == null || !owned.containsKey(key)) continue;
            if (!slotId.equals(worker.getString("roomSlotId"))) continue;
            if (worker.getLongValue("ap") <= data.getIntValue("tiredApThreshold")) continue;
            staff.add(key);
        }
        return staff;
    }

    /** Highest unlocked entry of one skill slot; lower tiers in the same slot never stack. */
    static String bestUnlocked(JSONObject skillSlot, JSONObject roster) {
        JSONArray buffData = skillSlot == null ? null : skillSlot.getJSONArray("buffData");
        if (buffData == null) return null;
        int phase = roster.getIntValue("evolvePhase");
        int level = roster.getIntValue("level");
        String best = null;
        int bestPhase = -1;
        int bestLevel = -1;
        for (int i = 0; i < buffData.size(); i++) {
            JSONObject entry = buffData.getJSONObject(i);
            if (entry == null || entry.getString("buffId") == null) continue;
            JSONObject cond = entry.getJSONObject("cond");
            int entryPhase = phaseIndex(cond == null ? null : cond.getString("phase"));
            int entryLevel = cond == null ? 0 : cond.getIntValue("level");
            // A reached elite phase unlocks its tier outright; the level only gates the entries
            // inside the operator's own current phase.
            if (entryPhase > phase) continue;
            if (entryPhase == phase && entryLevel > level) continue;
            if (entryPhase > bestPhase || (entryPhase == bestPhase && entryLevel >= bestLevel)) {
                best = entry.getString("buffId");
                bestPhase = entryPhase;
                bestLevel = entryLevel;
            }
        }
        return best;
    }

    private static int phaseIndex(String phase) {
        if (phase == null || !phase.startsWith("PHASE_")) return 0;
        try {
            return Integer.parseInt(phase.substring("PHASE_".length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean matchesTarget(JSONObject buff, String type) {
        JSONArray targets = buff.getJSONArray("targets");
        if (targets == null || targets.isEmpty()) return true;
        return type != null && targets.contains(type);
    }

    private static String formulaType(JSONObject formulas, String formulaId) {
        if (formulaId == null || formulaId.isEmpty()) return null;
        JSONObject formula = formulas.getJSONObject(formulaId);
        return formula == null ? null : formula.getString("formulaType");
    }

    private static JSONObject rooms(JSONObject building, String roomId) {
        JSONObject all = building.getJSONObject("rooms");
        return all == null ? null : all.getJSONObject(roomId);
    }

    private static JSONObject owned(JSONObject user) {
        JSONObject troop = user.getJSONObject("troop");
        return troop == null ? null : troop.getJSONObject("chars");
    }

    private static boolean putDouble(JSONObject object, String key, double value) {
        if (object.containsKey(key) && Double.compare(object.getDoubleValue(key), value) == 0) {
            return false;
        }
        object.put(key, value);
        return true;
    }

    private static boolean putInt(JSONObject object, String key, int value) {
        if (object.containsKey(key) && object.getIntValue(key) == value) return false;
        object.put(key, value);
        return true;
    }

    /** A room we cannot compute any more is no longer ours; its stored value stays untouched. */
    private static boolean releaseTaken(JSONObject room) {
        if (!room.containsKey(TAKEN_FIELD)) return false;
        room.remove(TAKEN_FIELD);
        return true;
    }

    private record Control(double all, double gold) {}
}
