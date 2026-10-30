package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;

/** Narrow construction/character mutations. Work on a detached snapshot; persist only on success. */
public final class CharacterBuildingSupport {
    private CharacterBuildingSupport() {}
    private static final java.util.Map<String, JSONObject> TABLES = new java.util.HashMap<>();
    static synchronized JSONObject table(String name) {
        String path = com.codex.arklocal.BackendPaths.root() + "/data/" + name;
        JSONObject data = TABLES.get(path);
        if (data == null) {
            data = IOTools.ReadJsonFile(path);
            require(data != null, "Required game table unavailable");
            TABLES.put(path, data);
        }
        return data;
    }
    public static final class Mutation {
        public final JSONObject user, response;
        Mutation(JSONObject user, JSONObject response) { this.user = user; this.response = response; }
    }
    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    static JSONObject object(JSONObject p, String key) {
        JSONObject v = p == null ? null : p.getJSONObject(key);
        require(v != null, "Missing state: " + key); return v;
    }
    static JSONObject copy(JSONObject v) {
        return JSONObject.parseObject(JSONObject.toJSONString(v, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
    }
    static void migrateCleanedEmptySlots(JSONObject slots) {
        for (String id : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(id);
            if (slot == null || !slot.containsKey("level") || !slot.containsKey("state")
                || !slot.containsKey("roomId") || !slot.containsKey("charInstIds")
                || !slot.containsKey("completeConstructTime")) continue;
            JSONArray stationed = slot.getJSONArray("charInstIds");
            // Older local builds wrote BUILT where the client expects an empty cleaned slot.
            // Keep this exact signature narrow so real CONTROL/ELEVATOR rooms remain untouched.
            if (slot.getIntValue("level") == 0 && slot.getIntValue("state") == 2
                && "NONE".equals(slot.getString("roomId"))
                && stationed != null && stationed.isEmpty()
                && slot.getLongValue("completeConstructTime") == -1L) {
                slot.put("state", 0);
            }
        }
    }
    static JSONObject result(JSONObject modified) {
        JSONObject delta = new JSONObject(true), out = new JSONObject(true);
        delta.put("modified", modified); delta.put("deleted", new JSONObject(true));
        out.put("result", 0); out.put("playerDataDelta", delta); return out;
    }
    static JSONObject error(String message) {
        JSONObject out = new JSONObject(true); out.put("result", 2); out.put("error", message);
        return out;
    }
    static int integer(JSONObject request, String key) {
        Object v = request == null ? null : request.get(key);
        require(v instanceof Number && ((Number)v).doubleValue() == ((Number)v).intValue(), "Invalid " + key);
        return ((Number)v).intValue();
    }
    static java.util.Map<String, Long> costs(JSONArray items) {
        require(items != null, "Missing item list");
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            require(item != null && item.getString("id") != null, "Invalid item");
            int count = integer(item, "count"); require(count > 0, "Invalid item count");
            String id = item.getString("id");
            long n = out.getOrDefault(id, 0L) + count;
            require(n <= Integer.MAX_VALUE, "Item count overflow"); out.put(id, n);
        }
        return out;
    }
    static void spend(JSONObject user, java.util.Map<String, Long> costs) {
        JSONObject inv = object(user, "inventory");
        for (java.util.Map.Entry<String, Long> e : costs.entrySet())
            require(inv.getLongValue(e.getKey()) >= e.getValue(), "Insufficient materials");
        for (java.util.Map.Entry<String, Long> e : costs.entrySet())
            inv.put(e.getKey(), inv.getLongValue(e.getKey()) - e.getValue());
    }
    static void gold(JSONObject user, long count) {
        JSONObject status = object(user, "status");
        require(count >= 0 && status.getLongValue("gold") >= count, "Insufficient gold");
        status.put("gold", status.getLongValue("gold") - count);
    }
    static int rarity(JSONObject definition) {
        String v = definition.getString("rarity");
        return v.startsWith("TIER_") ? Integer.parseInt(v.substring(5)) - 1 : definition.getIntValue("rarity");
    }
    public static Mutation character(JSONObject original, JSONObject request, String operation,
                                     JSONObject definitions, JSONObject constants, JSONObject items) {
        JSONObject user = copy(original);
        String inst = String.valueOf(integer(request, "charInstId"));
        JSONObject ch = object(object(object(user, "troop"), "chars"), inst);
        JSONObject def = object(definitions, ch.getString("charId"));
        EquipmentSupport.normalizeCharacter(ch, table("excel/uniequip_table.json"));
        if ("boostPotential".equals(operation)) {
            int target = integer(request, "targetRank"), rank = ch.getIntValue("potentialRank");
            require(rank >= 0 && target == rank + 1 && target <= def.getIntValue("maxPotentialLevel"), "Invalid potential rank");
            String id = request.getString("itemId"); require(id != null, "Missing potential item");
            int count = 1;
            boolean personal = id.equals(def.getString("potentialItemId"))
                || id.equals(def.getString("classicPotentialItemId"))
                || (def.getBooleanValue("canUseActivityPotentialItem") && id.equals(def.getString("activityPotentialItemId")));
            if (!personal) {
                JSONObject tier = object(object(items, "potentialItems"), String.valueOf(rarity(def)));
                require(def.getBooleanValue("canUseGeneralPotentialItem") && id.equals(tier.getString(def.getString("profession"))), "Wrong potential item");
                count = constants.getIntValue("commonPotentialLvlUpCount"); require(count > 0, "Invalid potential cost");
            }
            java.util.Map<String, Long> cost = new java.util.LinkedHashMap<>(); cost.put(id, (long)count);
            spend(user, cost); ch.put("potentialRank", target);
        } else if ("upgradeChar".equals(operation)) {
            int phase = ch.getIntValue("evolvePhase"), level = ch.getIntValue("level"), exp = ch.getIntValue("exp");
            JSONArray phases = def.getJSONArray("phases");
            require(phase >= 0 && phase < phases.size(), "Invalid character phase");
            int max = phases.getJSONObject(phase).getIntValue("maxLevel");
            require(level >= 1 && level < max && exp >= 0, "Character at level cap or invalid state");
            java.util.Map<String, Long> cost = costs(request.getJSONArray("expMats"));
            require(!cost.isEmpty(), "No experience materials"); long added = 0;
            for (java.util.Map.Entry<String, Long> e : cost.entrySet()) {
                JSONObject mat = object(object(items, "expItems"), e.getKey());
                int gain = mat.getIntValue("gainExp"); require(gain > 0, "Invalid experience item");
                added += e.getValue() * gain;
            }
            JSONArray xp = constants.getJSONArray("characterExpMap").getJSONArray(phase);
            JSONArray money = constants.getJSONArray("characterUpgradeCostMap").getJSONArray(phase);
            require(exp < xp.getIntValue(level - 1), "Invalid stored experience");
            long remaining = added, charge = 0;
            while (remaining > 0 && level < max) {
                int need = xp.getIntValue(level - 1), price = money.getIntValue(level - 1);
                require(need > 0 && price >= 0, "Invalid level cost table");
                long take = Math.min(remaining, need - exp);
                // Charge the difference of rounded cumulative costs so split requests agree.
                charge += Math.round((double)price * (exp + take) / need) - Math.round((double)price * exp / need);
                remaining -= take; exp += (int)take;
                if (exp == need) { level++; exp = 0; }
            }
            spend(user, cost); gold(user, charge);
            ch.put("level", level); ch.put("exp", exp);
        } else if ("evolveChar".equals(operation)) {
            int phase = ch.getIntValue("evolvePhase"), dest = integer(request, "destEvolvePhase");
            JSONArray phases = def.getJSONArray("phases");
            require(phase >= 0 && dest == phase + 1 && dest < phases.size(), "Invalid evolution phase");
            require(ch.getIntValue("level") == phases.getJSONObject(phase).getIntValue("maxLevel"), "Evolution requires maximum level");
            spend(user, costs(phases.getJSONObject(dest).getJSONArray("evolveCost")));
            gold(user, constants.getJSONArray("evolveGoldCost").getJSONArray(rarity(def)).getLongValue(dest - 1));
            ch.put("evolvePhase", dest); ch.put("level", 1); ch.put("exp", 0);
            JSONArray skills = ch.getJSONArray("skills"), defs = def.getJSONArray("skills");
            if (skills != null && defs != null) for (int i = 0; i < skills.size(); i++) {
                JSONObject skill = skills.getJSONObject(i);
                for (int j = 0; j < defs.size(); j++) {
                    JSONObject d = defs.getJSONObject(j), unlock = d.getJSONObject("unlockCond");
                    if (skill.getString("skillId").equals(d.getString("skillId")) && unlock != null) {
                        String p = unlock.getString("phase");
                        int required = p.startsWith("PHASE_") ? Integer.parseInt(p.substring(6)) : unlock.getIntValue("phase");
                        if (required <= dest && unlock.getIntValue("level") <= 1) skill.put("unlock", 1);
                    }
                }
            }
            if (dest == 2 && (ch.getString("charId") + "#1").equals(ch.getString("skin"))) ch.put("skin", ch.getString("charId") + "#2");
        } else if ("upgradeSkill".equals(operation)) {
            int target = integer(request, "targetLevel"); JSONArray levels = def.getJSONArray("allSkillLvlup");
            require(target == ch.getIntValue("mainSkillLvl") + 1 && target >= 2 && target - 2 < levels.size(), "Invalid skill level");
            JSONObject data = levels.getJSONObject(target - 2), cond = object(data, "unlockCond");
            String p = cond.getString("phase"); int required = p.startsWith("PHASE_") ? Integer.parseInt(p.substring(6)) : cond.getIntValue("phase");
            require(ch.getIntValue("evolvePhase") > required || (ch.getIntValue("evolvePhase") == required && ch.getIntValue("level") >= cond.getIntValue("level")), "Skill upgrade locked");
            spend(user, costs(data.getJSONArray("lvlUpCost"))); ch.put("mainSkillLvl", target);
        } else throw new IllegalArgumentException("Unsupported character operation");
        EquipmentSupport.normalizeCharacter(ch, table("excel/uniequip_table.json"));
        JSONObject chars = new JSONObject(true), troop = new JSONObject(true), modified = new JSONObject(true);
        chars.put(inst, ch); troop.put("chars", chars); modified.put("troop", troop);
        modified.put("inventory", user.getJSONObject("inventory")); modified.put("status", user.getJSONObject("status"));
        return new Mutation(user, result(modified));
    }
    public static Mutation construction(JSONObject original, JSONObject request, String operation,
                                        JSONObject data, JSONObject templates, long now) {
        JSONObject user = copy(original), base = object(user, "building"), slots = object(base, "roomSlots");
        migrateCleanedEmptySlots(slots);
        JSONObject modified = new JSONObject(true);
        BaseRoomRuntimeSupport.mutate(user, data, now);
        BaseStaffRuntimeSupport.advance(user, data, now);
        JSONArray payback = new JSONArray();
        if (!"sync".equals(operation)) {
            require(request != null, "Missing construction request");
            String slotId = request.getString("slotId"); require(slotId != null, "Missing slotId");
            JSONObject layout = object(object(data, "layouts"), "v0");
            JSONObject layoutSlot = object(object(layout, "slots"), slotId);
            JSONObject control = object(slots, data.getString("controlSlotId"));
            String storey = layoutSlot.getString("storeyId");
            if (storey != null && !storey.isEmpty())
                require(control.getIntValue("level") >= object(object(layout, "storeys"), storey).getIntValue("unlockControlLevel"), "Floor locked");
            JSONObject slot = slots.getJSONObject(slotId);
            if ("cleanRoom".equals(operation)) {
                require(slot == null, "Room already cleared");
                String costId = layoutSlot.getString("cleanCostId"); int count = 0;
                for (String key : slots.keySet()) {
                    JSONObject ls = object(layout, "slots").getJSONObject(key);
                    if (ls != null && costId.equals(ls.getString("cleanCostId")) && slots.getJSONObject(key).getIntValue("state") > 0) count++;
                }
                JSONObject clean = object(object(object(layout, "cleanCosts"), costId), "number");
                spend(user, costs(object(clean, String.valueOf(count)).getJSONArray("items")));
                JSONObject labor = object(object(base, "status"), "labor");
                int cost = layoutSlot.getIntValue("costLabor");
                require(labor.getIntValue("value") >= cost, "Insufficient drones");
                labor.put("value", labor.getIntValue("value") - cost + layoutSlot.getIntValue("provideLabor"));
                labor.put("maxValue", labor.getIntValue("maxValue") + layoutSlot.getIntValue("provideLabor"));
                // Network state 0 maps to the client's EMPTY enum. A zero level with NONE
                // distinguishes a cleaned, unbuilt slot from a BUILT room (network state 2).
                slot = new JSONObject(true); slot.put("level", 0); slot.put("state", 0);
                slot.put("roomId", "NONE"); slot.put("charInstIds", new JSONArray()); slot.put("completeConstructTime", -1);
                slots.put(slotId, slot);
                // Corridors/elevators are complete facilities as soon as the rubble is cleared.
                String category = layoutSlot.getString("category");
                if ("ELEVATOR".equals(category) || "CORRIDOR".equals(category)) {
                    slot.put("level", 1); slot.put("state", 2); slot.put("roomId", category);
                    JSONObject rooms = object(base, "rooms");
                    if (!rooms.containsKey(category)) rooms.put(category, new JSONObject(true));
                    rooms.getJSONObject(category).put(slotId, new JSONObject(true));
                }
            } else if ("degradeRoom".equals(operation)) {
                payback = degradeRoom(user, slotId, integer(request, "level"), data, now);
            } else {
                boolean upgrade = "upgradeRoom".equals(operation);
                require(upgrade || "buildRoom".equals(operation), "Unsupported construction operation");
                require(slot != null && (upgrade
                    ? slot.getIntValue("state") == 2 && slot.getIntValue("level") > 0
                    : slot.getIntValue("state") == 0 && slot.getIntValue("level") == 0
                        && "NONE".equals(slot.getString("roomId"))), "Room not ready");
                String roomId = upgrade ? slot.getString("roomId") : request.getString("roomId");
                JSONObject def = object(object(data, "rooms"), roomId);
                String category = layoutSlot.getString("category");
                require(category.equals(def.getString("category")) || ("MEETING".equals(category) && "MEETING".equals(roomId))
                    || (category.equals(roomId) && ("ELEVATOR".equals(category) || "CORRIDOR".equals(category))), "Room does not fit slot");
                require(object(layoutSlot, "size").equals(object(def, "size")), "Room size mismatch");
                int level = upgrade ? slot.getIntValue("level") + 1 : 1;
                if (request.containsKey("level")) require(integer(request, "level") == level, "Invalid target room level");
                JSONArray phases = def.getJSONArray("phases"); require(level > 0 && level <= phases.size(), "Room at level cap");
                int number = 1;
                for (String key : slots.keySet()) if (!key.equals(slotId) && roomId.equals(slots.getJSONObject(key).getString("roomId"))) number++;
                require(def.getIntValue("maxCount") < 0 || number <= def.getIntValue("maxCount"), "Room count limit");
                JSONObject phase = phases.getJSONObject(level - 1), conditions = object(data, "roomUnlockConds").getJSONObject(phase.getString("unlockCondId"));
                if (conditions != null) {
                    JSONObject condition = object(object(conditions, "number"), String.valueOf(number));
                    String type = condition.getString("type"); int found = 0;
                    for (String key : slots.keySet()) {
                        JSONObject s = slots.getJSONObject(key); String id = s.getString("roomId");
                        boolean functional = !"CONTROL".equals(id) && !"CORRIDOR".equals(id) && !"ELEVATOR".equals(id) && id != null && !id.isEmpty();
                        if (s.getIntValue("state") == 2 && s.getIntValue("level") >= condition.getIntValue("level")
                            && (type.equals(id) || ("FUNCTIONAL".equals(type) && functional))) found++;
                    }
                    require("NONE".equals(type) || found >= condition.getIntValue("count"), "Room prerequisite not met");
                }
                int electricity = phase.getIntValue("electricity");
                for (String key : slots.keySet()) if (!key.equals(slotId)) {
                    JSONObject s = slots.getJSONObject(key), d = object(data, "rooms").getJSONObject(s.getString("roomId"));
                    if (d != null && s.getIntValue("level") > 0 && s.getIntValue("state") == 2)
                        electricity += d.getJSONArray("phases").getJSONObject(s.getIntValue("level") - 1).getIntValue("electricity");
                }
                require(electricity >= 0, "Insufficient power");
                JSONObject cost = object(phase, "buildCost");
                require(cost.getIntValue("time") == 0, "Timed construction is not supported");
                spend(user, costs(cost.getJSONArray("items")));
                JSONObject labor = object(object(base, "status"), "labor"); int drones = cost.getIntValue("labor");
                require(drones >= 0 && labor.getIntValue("value") >= drones, "Insufficient drones");
                labor.put("value", labor.getIntValue("value") - drones);
                JSONObject rooms = object(base, "rooms");
                if (!rooms.containsKey(roomId)) rooms.put(roomId, new JSONObject(true));
                JSONObject room;
                if (upgrade) room = object(rooms.getJSONObject(roomId), slotId);
                else {
                    JSONObject examples = object(templates, roomId);
                    require(!examples.isEmpty(), "Room template unavailable");
                    room = copy(examples.getJSONObject(examples.keySet().iterator().next()));
                    if (room.containsKey("lastUpdateTime")) room.put("lastUpdateTime", now);
                    if (room.containsKey("completeWorkTime")) room.put("completeWorkTime", -1);
                    if (room.containsKey("comfort")) room.put("comfort", 0);
                    if ("MEETING".equals(roomId)) {
                        room.put("startApCounter", new JSONObject(true)); room.put("board", new JSONObject(true));
                        room.put("ownStock", new JSONArray()); room.put("receiveStock", new JSONArray());
                        room.put("infoShare", JSONObject.parseObject("{\"ts\":0,\"reward\":0}"));
                    }
                    rooms.getJSONObject(roomId).put(slotId, room);
                }
                if ("MANUFACTURE".equals(roomId)) room.put("capacity", data.getJSONObject("manufactData").getJSONArray("phases").getJSONObject(level - 1).getIntValue("outputCapacity"));
                if ("TRADING".equals(roomId)) room.put("stockLimit", data.getJSONObject("tradingData").getJSONArray("phases").getJSONObject(level - 1).getIntValue("orderLimit"));
                JSONArray stationed = slot.getJSONArray("charInstIds");
                while (stationed.size() < phase.getIntValue("maxStationedNum")) stationed.add(-1);
                slot.put("roomId", roomId); slot.put("level", level); slot.put("state", 2); slot.put("completeConstructTime", -1);
            }
        }
        BaseRoomRuntimeSupport.refreshStationBuffs(user, data, now);
        ManufactureRuntimeSupport.advance(user, data, now);
        BaseStaffRuntimeSupport.advance(user, data, now);
        modified.put("building", base); modified.put("inventory", user.getJSONObject("inventory"));
        // The client waits for an overdue building event before sending other mutations.
        // A reply that leaves this deadline in the past causes an immediate sync loop.
        JSONObject events=user.getJSONObject("event");
        if(events==null){events=new JSONObject(true);user.put("event",events);}
        events.put("building",now+60L);
        JSONObject eventDelta=new JSONObject(true);eventDelta.put("building",now+60L);modified.put("event",eventDelta);
        JSONObject reply=result(modified);reply.put("ts",now);
        if ("degradeRoom".equals(operation)) {
            reply.put("payback", payback);
            String id = request.getString("slotId");
            if (slots.getJSONObject(id).getIntValue("level") == 0) {
                String oldType = original.getJSONObject("building").getJSONObject("roomSlots").getJSONObject(id).getString("roomId");
                JSONObject removedRooms = new JSONObject(true), removedType = new JSONObject(true), removedBase = new JSONObject(true);
                removedType.put(id, true); removedRooms.put(oldType, removedType); removedBase.put("rooms", removedRooms);
                reply.getJSONObject("playerDataDelta").getJSONObject("deleted").put("building", removedBase);
            }
        }
        return new Mutation(user, reply);
    }

    private static JSONArray degradeRoom(JSONObject user, String slotId, int target, JSONObject data, long now) {
        JSONObject base = object(user, "building"), slots = object(base, "roomSlots");
        JSONObject slot = object(slots, slotId);
        String roomId = slot.getString("roomId");
        JSONObject def = object(object(data, "rooms"), roomId);
        int level = slot.getIntValue("level");
        require(slot.getIntValue("state") == 2 && def.getBooleanValue("canLevelDown"), "Room cannot be downgraded");
        require(target >= 0 && target < level, "Invalid downgrade target");
        JSONArray phases = def.getJSONArray("phases");
        int power = target == 0 ? 0 : phases.getJSONObject(target - 1).getIntValue("electricity");
        for (String key : slots.keySet()) {
            if (key.equals(slotId)) continue;
            JSONObject other = slots.getJSONObject(key);
            JSONObject definition = data.getJSONObject("rooms").getJSONObject(other.getString("roomId"));
            if (definition != null && other.getIntValue("state") == 2 && other.getIntValue("level") > 0)
                power += definition.getJSONArray("phases").getJSONObject(other.getIntValue("level") - 1).getIntValue("electricity");
        }
        require(power >= 0, "Downgrade would leave insufficient power");
        JSONObject rooms = object(object(base, "rooms"), roomId), room = object(rooms, slotId);
        if ("MANUFACTURE".equals(roomId)) {
            require(target > 0 || (room.getIntValue("outputSolutionCnt") == 0 && room.getIntValue("remainSolutionCnt") == 0), "Collect output and finish manufacturing before removal");
            if (target > 0) {
                int capacity = data.getJSONObject("manufactData").getJSONArray("phases").getJSONObject(target - 1).getIntValue("outputCapacity");
                JSONObject formula = data.getJSONObject("manufactFormulas").getJSONObject(room.getString("formulaId"));
                require(formula == null || (long) room.getIntValue("outputSolutionCnt") * formula.getIntValue("weight") <= capacity, "Collect output before reducing capacity");
                room.put("capacity", capacity);
            }
        }
        if ("TRADING".equals(roomId)) {
            int count = room.getJSONArray("stock") == null ? 0 : room.getJSONArray("stock").size();
            int limit = target == 0 ? 0 : data.getJSONObject("tradingData").getJSONArray("phases").getJSONObject(target - 1).getIntValue("orderLimit");
            require(count <= limit, "Deliver orders before reducing capacity");
            if (target > 0) room.put("stockLimit", limit);
        }
        if (target == 0) {
            JSONArray owners = room.getJSONArray("owners");
            if (owners != null) for (int i = 0; i < owners.size(); i++) require(owners.getIntValue(i) == -1, "Withdraw activity room owner before removal");
            JSONObject diy = room.getJSONObject("diySolution");
            if (diy != null) for (Object furniture : diy.values())
                require(furniture == null || furniture instanceof JSONArray && ((JSONArray) furniture).isEmpty(), "Remove furniture before removing room");
        }
        int capacity = target == 0 ? 0 : phases.getJSONObject(target - 1).getIntValue("maxStationedNum");
        JSONArray occupants = slot.getJSONArray("charInstIds");
        while (occupants.size() > capacity) {
            int instId = occupants.getIntValue(occupants.size() - 1);
            occupants.remove(occupants.size() - 1);
            JSONObject worker = base.getJSONObject("chars").getJSONObject(String.valueOf(instId));
            if (worker != null && slotId.equals(worker.getString("roomSlotId"))) {
                worker.put("roomSlotId", ""); worker.put("index", -1); worker.put("lastApAddTime", now);
            }
        }
        if (target > 0 && "DORMITORY".equals(roomId)) {
            int limit = data.getJSONObject("dormData").getJSONArray("phases").getJSONObject(target - 1).getIntValue("decorationLimit");
            room.put("comfort", Math.min(room.getIntValue("comfort"), limit));
        }
        if (target > 0 && "PRIVATE".equals(roomId)) {
            int limit = data.getJSONObject("privateRoomData").getJSONArray("phases").getJSONObject(target - 1).getIntValue("decorationLimit");
            room.put("comfort", Math.min(room.getIntValue("comfort"), limit));
        }
        JSONArray payback = new JSONArray();
        // Refund the exact table-listed construction items; drones are time and are not refunded.
        for (int i = target; i < level; i++) {
            JSONArray items = phases.getJSONObject(i).getJSONObject("buildCost").getJSONArray("items");
            for (int n = 0; n < items.size(); n++) ShopTransactionSupport.reward(user, items.getJSONObject(n), 1, payback);
        }
        slot.put("level", target); slot.put("completeConstructTime", -1);
        if (target == 0) { slot.put("roomId", "NONE"); slot.put("state", 0); rooms.remove(slotId); }
        return payback;
    }
}
