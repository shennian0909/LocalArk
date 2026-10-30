package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Production mutations: collecting output, collecting trust, stationing, resting morale. */
public final class BaseProductionSupport {
    private static final int BUILT = 2;
    private static final int MAX_FAVOR = 25_570;
    private static final String[] BUBBLE_BRANCHES = {"normal", "assist", "private"};

    private BaseProductionSupport() {}

    /** Outcome of one settle pass: slots that paid out and the real granted item totals. */
    static final class Settlement {
        int slots;
        final JSONObject items = new JSONObject(true);
    }

    /**
     * Delivers one already generated gold order. The saved delivery list is the complete
     * material cost, and the saved gain is the complete payout.
     */
    static JSONObject deliverOrder(JSONObject user, String slotId, int orderId) {
        JSONObject slot = roomSlots(user).getJSONObject(slotId);
        CharacterBuildingSupport.require(slot != null, "Unknown room slot");
        CharacterBuildingSupport.require(slot.getIntValue("state") == BUILT, "Room is not built");
        CharacterBuildingSupport.require("TRADING".equals(slot.getString("roomId")),
                "Room is not a trading post");

        JSONObject tradingRooms = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "building"), "rooms").getJSONObject("TRADING");
        JSONObject room = tradingRooms == null ? null : tradingRooms.getJSONObject(slotId);
        CharacterBuildingSupport.require(room != null, "Trading room unavailable");
        JSONArray stock = room.getJSONArray("stock");
        CharacterBuildingSupport.require(stock != null, "Missing trading orders");

        int found = -1;
        for (int i = 0; i < stock.size(); i++) {
            JSONObject candidate = stock.getJSONObject(i);
            if (candidate == null || candidate.getIntValue("instId") != orderId) continue;
            CharacterBuildingSupport.require(found < 0, "Duplicate trading order");
            found = i;
        }
        CharacterBuildingSupport.require(found >= 0, "Trading order not found");

        JSONObject order = stock.getJSONObject(found);
        CharacterBuildingSupport.require("O_GOLD".equals(order.getString("type")),
                "Unsupported trading order");
        CharacterBuildingSupport.require(order.get("extraCost") == null,
                "Unsupported trading order extra cost");
        JSONArray delivery = order.getJSONArray("delivery");
        CharacterBuildingSupport.require(delivery != null && !delivery.isEmpty(),
                "Missing trading delivery");
        for (int i = 0; i < delivery.size(); i++) {
            JSONObject item = delivery.getJSONObject(i);
            CharacterBuildingSupport.require(item != null
                            && "MATERIAL".equals(item.getString("type")),
                    "Unsupported trading delivery");
        }

        JSONObject gain = order.getJSONObject("gain");
        CharacterBuildingSupport.require(gain != null && gain.getString("id") != null
                        && "GOLD".equals(gain.getString("type")),
                "Unsupported trading gain");
        CharacterBuildingSupport.require(CharacterBuildingSupport.integer(gain, "count") > 0,
                "Invalid trading gain");

        CharacterBuildingSupport.spend(user, CharacterBuildingSupport.costs(delivery));
        ShopTransactionSupport.reward(user, gain, 1, new JSONArray());
        stock.remove(found);
        return gain;
    }

    /**
     * Collects output a factory has already finished. Quantities come only from the room's
     * own {@code outputSolutionCnt}; elapsed time is never converted into output.
     */
    static Settlement settleManufacture(JSONObject user, JSONArray slotIds, long now) {
        JSONObject slots = roomSlots(user);
        JSONObject rooms = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "building"), "rooms")
                .getJSONObject("MANUFACTURE");
        CharacterBuildingSupport.require(rooms != null, "No factory rooms");
        JSONObject formulas = CharacterBuildingSupport.object(
                CharacterBuildingSupport.table("excel/building_data.json"), "manufactFormulas");
        JSONObject items = CharacterBuildingSupport.table("excel/item_table.json")
                .getJSONObject("items");
        JSONArray granted = new JSONArray();
        Settlement result = new Settlement();
        CharacterBuildingSupport.require(slotIds != null && !slotIds.isEmpty(), "Missing room slots");
        for (int i = 0; i < slotIds.size(); i++) {
            String slotId = slotIds.getString(i);
            CharacterBuildingSupport.require(slotId != null, "Missing slot id");
            JSONObject slot = slots.getJSONObject(slotId);
            CharacterBuildingSupport.require(slot != null, "Unknown room slot");
            if (slot.getIntValue("state") != BUILT || !"MANUFACTURE".equals(slot.getString("roomId"))) {
                throw new IllegalArgumentException("Room is not a built factory");
            }
            JSONObject room = rooms.getJSONObject(slotId);
            if (room == null) continue;
            String formulaId = room.getString("formulaId");
            if (formulaId == null || formulaId.isEmpty()) continue;
            int output = room.getIntValue("outputSolutionCnt");
            if (output <= 0) continue;
            JSONObject formula = formulas.getJSONObject(formulaId);
            CharacterBuildingSupport.require(formula != null, "Unknown manufacturing formula");
            String itemId = formula.getString("itemId");
            CharacterBuildingSupport.require(itemId != null, "Manufacturing formula has no output");
            JSONObject definition = items.getJSONObject(itemId);
            CharacterBuildingSupport.require(definition != null, "Unknown manufactured item");
            String type = definition.getString("itemType");
            CharacterBuildingSupport.require(ShopTransactionSupport.supports(type),
                    "Unsupported manufactured item type");
            int perRun = Math.max(1, formula.getIntValue("count"));
            JSONObject reward = new JSONObject(true);
            reward.put("id", itemId);
            reward.put("type", type);
            reward.put("count", perRun);
            ShopTransactionSupport.reward(user, reward, output, granted);
            room.put("outputSolutionCnt", 0);
            room.put("lastUpdateTime", now);
            result.slots++;
            result.items.put(itemId, result.items.getIntValue(itemId) + output * perRun);
        }
        return result;
    }

    static final class Intimacy {
        int normal, assist, privateRelated, operators;
    }

    /** Only owned operators can consume saved bubbles; template-only workers stay untouched. */
    static Intimacy gainAllIntimacy(JSONObject user, long now) {
        JSONObject chars = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "building"), "chars");
        JSONObject troop = CharacterBuildingSupport.object(user, "troop");
        JSONObject owned = CharacterBuildingSupport.object(troop, "chars");
        JSONObject groups = CharacterBuildingSupport.object(troop, "charGroup");
        Intimacy credited = new Intimacy();
        for (String instId : chars.keySet()) {
            JSONObject worker = chars.getJSONObject(instId);
            JSONObject roster = owned.getJSONObject(instId);
            if (worker == null || roster == null) continue;
            collectIntimacy(worker, roster, groups, BUBBLE_BRANCHES, now, credited);
        }
        CharacterBuildingSupport.require(credited.operators > 0, "No claimable intimacy bubbles");
        return credited;
    }

    static Intimacy confirmPrivateDormIntimacy(JSONObject user, int instId, long now) {
        String key = String.valueOf(instId);
        JSONObject troop = CharacterBuildingSupport.object(user, "troop");
        JSONObject roster = CharacterBuildingSupport.object(troop, "chars").getJSONObject(key);
        CharacterBuildingSupport.require(roster != null, "Operator is not owned");
        JSONObject worker = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "building"), "chars").getJSONObject(key);
        CharacterBuildingSupport.require(worker != null, "Unknown building operator");
        Intimacy credited = new Intimacy();
        collectIntimacy(worker, roster, CharacterBuildingSupport.object(troop, "charGroup"),
                new String[]{"private"}, now, credited);
        CharacterBuildingSupport.require(credited.operators > 0, "No claimable private intimacy bubble");
        return credited;
    }

    static Intimacy gainIntimacy(JSONObject user, int instId, String branch, long now) {
        JSONObject troop = CharacterBuildingSupport.object(user, "troop");
        JSONObject roster = CharacterBuildingSupport.object(troop, "chars").getJSONObject(String.valueOf(instId));
        CharacterBuildingSupport.require(roster != null, "Operator is not owned");
        JSONObject worker = CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "building"), "chars")
                .getJSONObject(String.valueOf(instId));
        CharacterBuildingSupport.require(worker != null, "Unknown building operator");
        Intimacy credited = new Intimacy();
        collectIntimacy(worker, roster, CharacterBuildingSupport.object(troop, "charGroup"),
                new String[]{branch}, now, credited);
        CharacterBuildingSupport.require(credited.operators > 0, "No claimable intimacy bubble");
        return credited;
    }

    static void setAssist(JSONObject user, int type, int instId, long now) {
        JSONObject base = CharacterBuildingSupport.object(user, "building");
        JSONArray assistants = base.getJSONArray("assist");
        JSONObject data = CharacterBuildingSupport.table("excel/building_data.json");
        JSONArray unlock = data.getJSONArray("assistantUnlock");
        CharacterBuildingSupport.require(type >= 0 && type < unlock.size(), "Invalid assistant slot");
        JSONObject control = CharacterBuildingSupport.object(base, "roomSlots").getJSONObject(data.getString("controlSlotId"));
        CharacterBuildingSupport.require(control != null && control.getIntValue("state") == BUILT
                && control.getIntValue("level") >= unlock.getIntValue(type), "Assistant slot locked");
        CharacterBuildingSupport.require(instId == -1 || CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "troop"), "chars").containsKey(String.valueOf(instId)), "Operator is not owned");
        if (assistants == null) { assistants = new JSONArray(); base.put("assist", assistants); }
        while (assistants.size() < unlock.size()) assistants.add(-1);
        if (instId != -1) for (int i = 0; i < assistants.size(); i++) {
            if (assistants.getIntValue(i) == instId) assistants.set(i, -1);
        }
        assistants.set(type, instId);
    }

    static void setPrivateOwner(JSONObject user, String slotId, int instId, long now) {
        JSONObject base = CharacterBuildingSupport.object(user, "building");
        JSONObject slot = CharacterBuildingSupport.object(base, "roomSlots").getJSONObject(slotId);
        CharacterBuildingSupport.require(slot != null && slot.getIntValue("state") == BUILT
                && "PRIVATE".equals(slot.getString("roomId")), "Not a built activity room");
        CharacterBuildingSupport.require(instId == -1 || CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "troop"), "chars").containsKey(String.valueOf(instId)), "Operator is not owned");
        JSONObject rooms = CharacterBuildingSupport.object(CharacterBuildingSupport.object(base, "rooms"), "PRIVATE");
        JSONObject room = CharacterBuildingSupport.object(rooms, slotId);
        if (instId != -1) for (String id : rooms.keySet()) {
            JSONArray owners = rooms.getJSONObject(id).getJSONArray("owners");
            if (owners != null) for (int i = 0; i < owners.size(); i++) if (owners.getIntValue(i) == instId) owners.set(i, -1);
        }
        JSONArray owners = new JSONArray(); owners.add(instId); room.put("owners", owners);
        // Private owners are a separate relationship; ordinary work/rest placement stays intact.
        JSONObject workers = CharacterBuildingSupport.object(base, "chars");
        for (String key : workers.keySet()) {
            JSONObject worker = workers.getJSONObject(key);
            JSONArray privateRooms = worker.getJSONArray("privateRooms");
            if (privateRooms != null) privateRooms.remove(slotId);
        }
        if (instId != -1) {
            JSONObject worker = workers.getJSONObject(String.valueOf(instId));
            if (worker != null) {
                JSONArray privateRooms = worker.getJSONArray("privateRooms");
                if (privateRooms == null) { privateRooms = new JSONArray(); worker.put("privateRooms", privateRooms); }
                privateRooms.clear(); privateRooms.add(slotId);
            }
        }
    }

    private static void collectIntimacy(JSONObject worker, JSONObject roster, JSONObject groups,
                                        String[] branches, long now, Intimacy credited) {
        JSONObject bubble = worker.getJSONObject("bubble");
        if (bubble == null) return;
        int gained = 0;
        for (String branch : branches) {
            JSONObject entry = bubble.getJSONObject(branch);
            if (entry == null || entry.getIntValue("add") <= 0) continue;
            gained += entry.getIntValue("add");
            entry.put("add", -1);
            entry.put("ts", now);
            switch (branch) {
                case "normal" -> credited.normal++;
                case "assist" -> credited.assist++;
                case "private" -> credited.privateRelated++;
            }
        }
        if (gained <= 0) return;
        addFavor(roster, gained);
        JSONObject group = groups.getJSONObject(roster.getString("charId"));
        if (group != null) addFavor(group, gained);
        credited.operators++;
    }

    static JSONObject assistReport(JSONObject user) {
        JSONArray reports = user.getJSONArray("_localBuildingReports");
        JSONObject result = new JSONObject(true);
        // The native type table confirms List<BuildingDailyReport>; assignment alone is no history.
        result.put("reports", reports == null ? new JSONArray() : reports);
        return result;
    }

    private static void addFavor(JSONObject target, int gained) {
        int current = target.getIntValue("favorPoint");
        target.put("favorPoint", Math.min(MAX_FAVOR, current + gained));
    }

    /** Converts account AP into drones using the shipped building exchange ratio. */
    static void buyLabor(JSONObject user, int costAp, long now) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject accountStatus = CharacterBuildingSupport.object(user, "status");
        JSONObject labor = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(building, "status"), "labor");
        JSONObject data = CharacterBuildingSupport.table("excel/building_data.json");
        int ratio = data.getIntValue("apToLaborRatio");
        int unlockLevel = data.getIntValue("apToLaborUnlockLevel");
        CharacterBuildingSupport.require(ratio > 0 && unlockLevel > 0,
                "Invalid drone exchange table");
        JSONObject control = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(building, "roomSlots"), data.getString("controlSlotId"));
        CharacterBuildingSupport.require("CONTROL".equals(control.getString("roomId"))
                        && control.getIntValue("level") >= unlockLevel,
                "Drone exchange locked");

        int availableAp = accountStatus.getIntValue("ap");
        int value = labor.getIntValue("value");
        int max = labor.getIntValue("maxValue");
        long added = (long) costAp * ratio;
        CharacterBuildingSupport.require(costAp > 0 && availableAp >= costAp, "Insufficient AP");
        CharacterBuildingSupport.require(value >= 0 && max >= value && added <= max - value,
                "Insufficient drone capacity");

        accountStatus.put("ap", availableAp - costAp);
        accountStatus.put("lastApAddTime", now);
        labor.put("value", value + (int) added);
        if (value + added == max) labor.put("processPoint", 0D);
        labor.put("lastUpdateTime", now);
    }

    /**
     * Stations operators, treating the request as the complete final roster for that slot:
     * anyone previously there but absent from the list is withdrawn, and an empty list
     * empties the room. Every id must already exist in both building and troop state.
     */
    static void assignChar(JSONObject user, String slotId, JSONArray instIds, long now) {
        CharacterBuildingSupport.require(slotId != null, "Missing room slot");
        CharacterBuildingSupport.require(instIds != null, "Missing operator list");
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        JSONObject workers = CharacterBuildingSupport.object(building, "chars");
        JSONObject roster = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "troop"), "chars");
        JSONObject slot = slots.getJSONObject(slotId);
        CharacterBuildingSupport.require(slot != null, "Unknown room slot");
        CharacterBuildingSupport.require(slot.getIntValue("state") == BUILT, "Room is not built");
        String roomId = slot.getString("roomId");
        CharacterBuildingSupport.require(roomId != null && !"NONE".equals(roomId), "Room slot is empty");
        int capacity = stationCapacity(roomId, slot.getIntValue("level"));
        CharacterBuildingSupport.require(instIds.size() <= capacity, "Room is full");

        Set<Integer> requested = new LinkedHashSet<>();
        for (int i = 0; i < instIds.size(); i++) {
            Object raw = instIds.get(i);
            CharacterBuildingSupport.require(raw instanceof Number && ((Number) raw).doubleValue() == ((Number) raw).intValue(), "Invalid operator id");
            int instId = ((Number) raw).intValue();
            if (instId == -1) continue;
            String key = String.valueOf(instId);
            CharacterBuildingSupport.require(workers.containsKey(key), "Unknown building operator");
            CharacterBuildingSupport.require(roster.containsKey(key), "Unknown operator");
            CharacterBuildingSupport.require(requested.add(instId), "Duplicate operator");
        }

        BaseStaffRuntimeSupport.advance(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
        // Settle the morale these operators really earned before their placement changes,
        // then rebase the anchor so a stale seed timestamp cannot backdate years of rest.
        recoverDormitoryAp(user, now);
        JSONArray stationed = slot.getJSONArray("charInstIds");
        if (stationed == null) {
            stationed = new JSONArray();
            slot.put("charInstIds", stationed);
        }
        Set<String> removedFromTraining = new LinkedHashSet<>();
        for (int i = 0; i < stationed.size(); i++) {
            int previous = stationed.getIntValue(i);
            if (previous == -1 || requested.contains(previous)) continue;
            JSONObject leaving = workers.getJSONObject(String.valueOf(previous));
            if (leaving == null) continue;
            leaving.put("roomSlotId", "");
            leaving.put("index", -1);
            leaving.put("lastApAddTime", now);
        }
        for (int instId : requested) {
            // Clean all old slot references, including inconsistent legacy seed placements.
            for (String oldId : slots.keySet()) {
                if (oldId.equals(slotId)) continue;
                JSONArray occupants = slots.getJSONObject(oldId).getJSONArray("charInstIds");
                if (occupants != null) for (int n = 0; n < occupants.size(); n++) {
                    if (occupants.getIntValue(n) != instId) continue;
                    occupants.set(n, -1);
                    if ("TRAINING".equals(slots.getJSONObject(oldId).getString("roomId")))
                        removedFromTraining.add(oldId);
                }
            }
        }
        stationed.clear();
        for (int index = 0; index < instIds.size(); index++) {
            int instId = instIds.getIntValue(index);
            stationed.add(instId);
            if (instId == -1) continue;
            JSONObject worker = workers.getJSONObject(String.valueOf(instId));
            worker.put("roomSlotId", slotId);
            worker.put("index", index);
            worker.put("lastApAddTime", now);
        }
        while (stationed.size() < capacity) stationed.add(-1);
        mirrorTraining(user, slotId, stationed, now);
        for (String oldId : removedFromTraining)
            mirrorTraining(user, oldId, slots.getJSONObject(oldId).getJSONArray("charInstIds"), now);
    }

    private static void mirrorTraining(JSONObject user, String slotId, JSONArray stationed, long now) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        if (!"TRAINING".equals(slots.getJSONObject(slotId).getString("roomId"))) return;

        JSONObject rooms = CharacterBuildingSupport.object(building, "rooms");
        JSONObject allTraining = rooms.getJSONObject("TRAINING");
        if (allTraining == null) {
            allTraining = new JSONObject(true);
            rooms.put("TRAINING", allTraining);
        }
        JSONObject room = allTraining.getJSONObject(slotId);
        if (room == null) {
            JSONObject templates = CharacterBuildingSupport.table("defaultSyncData_1.json")
                    .getJSONObject("building").getJSONObject("rooms").getJSONObject("TRAINING");
            CharacterBuildingSupport.require(templates != null && !templates.isEmpty(),
                    "Training room template unavailable");
            room = CharacterBuildingSupport.copy(templates.getJSONObject(templates.keySet().iterator().next()));
            allTraining.put(slotId, room);
        }

        int trainerId = stationed.getIntValue(0);
        int traineeId = stationed.getIntValue(1);
        boolean trainerChanged = mirrorTrainer(
                room.getJSONObject("trainer"), trainerId, room.getIntValue("state"));
        if (mirrorTrainee(room.getJSONObject("trainee"), traineeId)) {
            room.put("state", 0);
            room.put("lastUpdateTime", now);
            room.put("completeWorkTime", -1L);
            JSONObject trainer = room.getJSONObject("trainer");
            if (trainer.getIntValue("charInstId") > 0) trainer.put("state", 3);
        } else if (trainerChanged && trainerId > 0 && room.getIntValue("state") == 1) {
            room.put("lastUpdateTime", now);
            room.getJSONObject("trainer").put("state", 1);
        }
    }

    private static boolean mirrorTrainer(JSONObject trainer, int instId, int roomState) {
        if (trainer == null) throw new IllegalArgumentException("Missing training trainer state");
        if (trainer.getIntValue("charInstId") == instId) return false;
        trainer.put("charInstId", instId);
        int state = instId == -1 ? 0 : roomState == 1 ? 1 : roomState == 2 ? 2 : 3;
        trainer.put("state", state);
        return true;
    }

    private static boolean mirrorTrainee(JSONObject trainee, int instId) {
        if (trainee == null) throw new IllegalArgumentException("Missing training trainee state");
        if (trainee.getIntValue("charInstId") == instId) return false;
        trainee.put("charInstId", instId);
        trainee.put("state", instId == -1 ? 0 : 3);
        trainee.put("targetSkill", -1);
        trainee.put("processPoint", -1D);
        trainee.put("speed", 1D);
        return true;
    }

    /** Places idle operators into free dormitory bunks, rebasing each morale anchor to now. */
    static void batchRestChar(JSONObject user, long now) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        JSONObject workers = CharacterBuildingSupport.object(building, "chars");
        BaseStaffRuntimeSupport.advance(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
        recoverDormitoryAp(user, now);
        for (String slotId : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != BUILT
                    || !"DORMITORY".equals(slot.getString("roomId"))) continue;
            int capacity = stationCapacity("DORMITORY", slot.getIntValue("level"));
            JSONArray stationed = slot.getJSONArray("charInstIds");
            if (stationed == null) {
                stationed = new JSONArray();
                slot.put("charInstIds", stationed);
            }
            while (stationed.size() < capacity) stationed.add(-1);
            for (int i = 0; i < capacity; i++) {
                if (stationed.getIntValue(i) != -1) continue;
                Integer free = firstIdle(workers, CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "troop"), "chars"));
                if (free == null) return;
                JSONObject worker = workers.getJSONObject(String.valueOf(free));
                worker.put("roomSlotId", slotId);
                worker.put("index", i);
                worker.put("lastApAddTime", now);
                stationed.set(i, free);
            }
        }
    }

    private static Integer firstIdle(JSONObject workers, JSONObject owned) {
        for (String instId : workers.keySet()) {
            JSONObject worker = workers.getJSONObject(instId);
            if (worker == null || !owned.containsKey(instId)) continue;
            String current = worker.getString("roomSlotId");
            if (current == null || current.isEmpty()) return Integer.parseInt(instId);
        }
        return null;
    }

    private static int stationCapacity(String roomId, int level) {
        JSONObject definition = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(
                        CharacterBuildingSupport.table("excel/building_data.json"), "rooms"), roomId);
        JSONArray phases = definition.getJSONArray("phases");
        CharacterBuildingSupport.require(level > 0 && phases != null && level <= phases.size(),
                "Invalid room level");
        return phases.getJSONObject(level - 1).getIntValue("maxStationedNum");
    }

    /**
     * Advances morale for operators actually resting in a built dormitory and rebases each
     * anchor after settlement, so repeated calls never re-credit the same elapsed time.
     * Returns only the operators whose morale really increased.
     */
    static int recoverDormitoryAp(JSONObject user, long now) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        JSONObject workers = CharacterBuildingSupport.object(building, "chars");
        JSONObject owned = CharacterBuildingSupport.object(CharacterBuildingSupport.object(user, "troop"), "chars");
        JSONObject data = CharacterBuildingSupport.table("excel/building_data.json");
        JSONObject dormRooms = CharacterBuildingSupport.object(building, "rooms")
                .getJSONObject("DORMITORY");
        JSONArray phases = CharacterBuildingSupport.object(data, "dormData").getJSONArray("phases");
        JSONObject charTable = CharacterBuildingSupport.object(data, "chars");
        int recovered = 0;
        for (String instId : workers.keySet()) {
            JSONObject worker = workers.getJSONObject(instId);
            if (worker == null || !owned.containsKey(instId)) continue;
            String slotId = worker.getString("roomSlotId");
            if (slotId == null || slotId.isEmpty()) continue;
            JSONObject slot = slots.getJSONObject(slotId);
            if (slot == null || slot.getIntValue("state") != BUILT
                    || !"DORMITORY".equals(slot.getString("roomId"))
                    || slot.getJSONArray("charInstIds") == null
                    || !slot.getJSONArray("charInstIds").contains(Integer.valueOf(instId))) continue;
            int level = slot.getIntValue("level");
            CharacterBuildingSupport.require(phases != null && level > 0 && level <= phases.size(),
                    "Invalid dormitory level");
            JSONObject definition = charTable.getJSONObject(worker.getString("charId"));
            int max = definition == null ? 0 : definition.getIntValue("maxManpower");
            if (max <= 0) continue;
            int before = worker.getIntValue("ap");
            if (before >= max) {
                worker.put("lastApAddTime", now);
                continue;
            }
            long anchor = worker.getLongValue("lastApAddTime");
            if (anchor <= 0 || anchor > now) {
                worker.put("lastApAddTime", now);
                continue;
            }
            long elapsed = now - anchor;
            if (elapsed <= 0) continue;
            JSONObject dorm = dormRooms == null ? null : dormRooms.getJSONObject(slotId);
            int rate = BaseWorkStateSupport.dormRate(data, dorm, level, instId);
            // manpowerRecover is AP per second; laborRecoverTime belongs to drone recovery.
            int after = (int) Math.min(max, (long) Math.max(before, 0) + elapsed * rate);
            worker.put("ap", after);
            worker.put("lastApAddTime", now);
            if (after > before) recovered++;
        }
        return recovered;
    }

    private static JSONObject roomSlots(JSONObject user) {
        return CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "building"), "roomSlots");
    }
}
