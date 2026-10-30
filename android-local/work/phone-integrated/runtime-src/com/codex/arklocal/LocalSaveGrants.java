package com.codex.arklocal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Applies the local save grants to a detached copy, then commits it as one in-memory update. */
public final class LocalSaveGrants {
    static final int MATERIAL_FLOOR = 100000;
    static final int STATUS_GOLD_FLOOR = 200000000;
    static final int STATUS_FLOOR = 100000;

    private LocalSaveGrants() { }

    public static JSONObject apply(
            JSONObject user,
            JSONObject items,
            JSONObject skins,
            boolean materials,
            boolean costumes,
            long timestampSeconds) {
        if (user == null) throw invalid("user is required");

        JSONObject working = detachedCopy(user);
        requireObject(working, "inventory");
        requireObject(working, "status");

        List<String> inventoryToAdd = new ArrayList<String>();
        List<String> inventoryToRaise = new ArrayList<String>();
        List<String> statusToRaise = new ArrayList<String>();
        List<String> ownershipToAdd = new ArrayList<String>();
        List<String> timestampsToAdd = new ArrayList<String>();
        int materialItems = 0;
        int costumeItems = 0;

        if (materials) {
            if (items == null) throw invalid("item catalog is required");
            JSONObject inventory = working.getJSONObject("inventory");
            for (Map.Entry<String, Object> entry : items.entrySet()) {
                String id = entry.getKey();
                Object definition = entry.getValue();
                if (!(definition instanceof JSONObject)) {
                    throw invalid("item catalog entry " + id + " must be an object");
                }
                Object itemType = ((JSONObject) definition).get("itemType");
                if (!(itemType instanceof String)) {
                    throw invalid("item catalog itemType must be a string for " + id);
                }
                if (!"MATERIAL".equals(itemType) && !"CARD_EXP".equals(itemType)) continue;

                materialItems++;
                if (!inventory.containsKey(id)) {
                    inventoryToAdd.add(id);
                } else {
                    int current = existingCount(inventory, id, "inventory." + id);
                    if (current < MATERIAL_FLOOR) inventoryToRaise.add(id);
                }
            }

            JSONObject status = working.getJSONObject("status");
            validateStatusCounter(status, "gold", STATUS_GOLD_FLOOR, statusToRaise);
            validateStatusCounter(status, "hggShard", STATUS_FLOOR, statusToRaise);
            validateStatusCounter(status, "lggShard", STATUS_FLOOR, statusToRaise);
            validateStatusCounter(status, "socialPoint", STATUS_FLOOR, statusToRaise);
        }

        if (costumes) {
            if (skins == null) throw invalid("skin catalog is required");
            if (timestampSeconds < 0) throw invalid("timestampSeconds must be nonnegative");
            JSONObject characterSkins = optionalObject(working, "skin", "characterSkins");
            JSONObject skinTimestamps = optionalObject(working, "skin", "skinTs");

            for (Map.Entry<String, Object> entry : skins.entrySet()) {
                if (!(entry.getValue() instanceof JSONObject)) {
                    throw invalid("skin catalog entry " + entry.getKey() + " must be an object");
                }
                Object buySkin = ((JSONObject) entry.getValue()).get("isBuySkin");
                if (!(buySkin instanceof Boolean)) {
                    throw invalid("skin catalog isBuySkin must be boolean for " + entry.getKey());
                }
                if (Boolean.FALSE.equals(buySkin)) continue;

                costumeItems++;
                String skinId = entry.getKey();
                if (characterSkins == null || !characterSkins.containsKey(skinId)) {
                    ownershipToAdd.add(skinId);
                } else {
                    existingCount(characterSkins, skinId, "skin.characterSkins." + skinId);
                }
                if (skinTimestamps == null || !skinTimestamps.containsKey(skinId)) {
                    timestampsToAdd.add(skinId);
                }
            }
        }

        JSONObject inventory = working.getJSONObject("inventory");
        for (String id : inventoryToAdd) inventory.put(id, MATERIAL_FLOOR);
        for (String id : inventoryToRaise) inventory.put(id, MATERIAL_FLOOR);

        JSONObject status = working.getJSONObject("status");
        for (String name : statusToRaise) {
            status.put(name, "gold".equals(name) ? STATUS_GOLD_FLOOR : STATUS_FLOOR);
        }

        if (!ownershipToAdd.isEmpty() || !timestampsToAdd.isEmpty()) {
            JSONObject skin = requiredChild(working, "skin");
            if (!ownershipToAdd.isEmpty()) {
                JSONObject characterSkins = requiredChild(skin, "characterSkins");
                for (String id : ownershipToAdd) characterSkins.put(id, 1);
            }
            if (!timestampsToAdd.isEmpty()) {
                JSONObject skinTs = requiredChild(skin, "skinTs");
                for (String id : timestampsToAdd) skinTs.put(id, timestampSeconds);
            }
        }

        JSONObject summary = new JSONObject(true);
        summary.put("materialItems", materialItems);
        summary.put("inventoryAdded", inventoryToAdd.size());
        summary.put("inventoryRaised", inventoryToRaise.size());
        summary.put("statusCounters", materials ? 4 : 0);
        summary.put("statusRaised", statusToRaise.size());
        summary.put("costumeItems", costumeItems);
        summary.put("ownershipAdded", ownershipToAdd.size());
        summary.put("timestampsAdded", timestampsToAdd.size());
        summary.put("timestampSeconds", costumes ? timestampSeconds : 0L);

        commit(user, working);
        return summary;
    }

    private static void validateStatusCounter(
            JSONObject status, String name, int floor, List<String> statusToRaise) {
        int current = existingCount(status, name, "status." + name);
        if (current < floor) statusToRaise.add(name);
    }

    private static int existingCount(JSONObject object, String key, String path) {
        if (!object.containsKey(key)) return 0;
        BigInteger value = integralValue(object.get(key), path);
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw invalid(path + " must be an integer from 0 through " + Integer.MAX_VALUE);
        }
        return value.intValue();
    }

    private static BigInteger integralValue(Object value, String path) {
        try {
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                return BigInteger.valueOf(((Number) value).longValue());
            }
            if (value instanceof BigInteger) return (BigInteger) value;
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).toBigIntegerExact();
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) throw new ArithmeticException();
                return BigDecimal.valueOf(number).toBigIntegerExact();
            }
        } catch (ArithmeticException ex) {
            throw invalid(path + " must be an integer");
        }
        throw invalid(path + " must be an integer");
    }

    private static JSONObject requireObject(JSONObject parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof JSONObject)) throw invalid(name + " must be an object");
        return (JSONObject) value;
    }

    private static JSONObject requiredChild(JSONObject parent, String name) {
        if (!parent.containsKey(name)) {
            JSONObject child = new JSONObject(true);
            parent.put(name, child);
            return child;
        }
        Object value = parent.get(name);
        if (!(value instanceof JSONObject)) throw invalid("skin." + name + " must be an object");
        return (JSONObject) value;
    }

    private static JSONObject optionalObject(JSONObject parent, String first, String second) {
        if (!parent.containsKey(first)) return null;
        Object skinValue = parent.get(first);
        if (!(skinValue instanceof JSONObject)) throw invalid(first + " must be an object");
        JSONObject skin = (JSONObject) skinValue;
        if (!skin.containsKey(second)) return null;
        Object value = skin.get(second);
        if (!(value instanceof JSONObject)) {
            throw invalid(first + "." + second + " must be an object");
        }
        return (JSONObject) value;
    }

    private static JSONObject detachedCopy(JSONObject source) {
        return copyObject(source, new IdentityHashMap<Object, Boolean>());
    }

    private static JSONObject copyObject(
            JSONObject source, IdentityHashMap<Object, Boolean> visiting) {
        if (source == null || visiting.put(source, Boolean.TRUE) != null) {
            throw invalid("user must be a non-circular JSON object");
        }
        JSONObject copy = new JSONObject(true);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue(), visiting));
        }
        visiting.remove(source);
        return copy;
    }

    private static JSONArray copyArray(
            JSONArray source, IdentityHashMap<Object, Boolean> visiting) {
        if (visiting.put(source, Boolean.TRUE) != null) {
            throw invalid("user must be a non-circular JSON object");
        }
        JSONArray copy = new JSONArray(source.size());
        for (Object value : source) copy.add(copyValue(value, visiting));
        visiting.remove(source);
        return copy;
    }

    private static Object copyValue(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof JSONObject) return copyObject((JSONObject) value, visiting);
        if (value instanceof JSONArray) return copyArray((JSONArray) value, visiting);
        throw invalid("user contains a non-JSON value");
    }

    private static void commit(JSONObject target, JSONObject source) {
        target.putAll(source);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Local save grants rejected: " + message);
    }
}
