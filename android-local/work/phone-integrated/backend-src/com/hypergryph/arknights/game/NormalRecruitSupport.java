package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.file.IOTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public recruitment candidate selection, read from the local tables. */
public final class NormalRecruitSupport {

    /**
     * Timer that activates specialTagRarityTable, in minutes: recruitConstants.maxRecruitTime is
     * 540 and recruitDetail states the guarantees for a 9 hour timer.
     */
    public static final int GUARANTEE_MINUTES = 540;

    public static final int MIN_DURATION_MINUTES = 10;
    public static final int MAX_DURATION_MINUTES = 540;

    private static final Map<Integer, String> TAG_NAMES = new LinkedHashMap<>();
    private static final Map<Integer, Integer> SPECIAL_TAG_RARITY = new LinkedHashMap<>();
    /** duration minutes -> {rarityStart, rarityEnd}; keys ascending. */
    private static final Map<Integer, int[]> RARITY_BY_MINUTES = new LinkedHashMap<>();
    private static final List<Integer> RARITY_MINUTE_KEYS = new ArrayList<>();
    private static final Map<String, Integer> PROFESSION_TAG = new LinkedHashMap<>();
    private static final Map<String, Integer> POSITION_TAG = new LinkedHashMap<>();
    private static final int RECRUIT_TAG_COUNT = 5;
    private static final int MIN_RECRUIT_TAG = 1;
    private static final int MAX_RECRUIT_TAG = 29;

    static {
        JSONObject table = IOTools.ReadJsonFile("data/excel/gacha_table.json");
        if (table != null) {
            JSONArray definitions = table.getJSONArray("gachaTags");
            if (definitions != null) {
                for (int i = 0; i < definitions.size(); i++) {
                    JSONObject tag = definitions.getJSONObject(i);
                    TAG_NAMES.put(tag.getIntValue("tagId"), tag.getString("tagName"));
                }
            }
            JSONObject special = table.getJSONObject("specialTagRarityTable");
            if (special != null) {
                for (String key : special.keySet()) {
                    JSONArray rarities = special.getJSONArray(key);
                    if (rarities == null || rarities.isEmpty()) continue;
                    SPECIAL_TAG_RARITY.put(Integer.parseInt(key), rarities.getIntValue(0));
                }
            }
            JSONObject recruit = table.getJSONObject("recruitRarityTable");
            if (recruit != null) {
                List<Integer> minutes = new ArrayList<>();
                for (String key : recruit.keySet()) minutes.add(Integer.parseInt(key));
                Collections.sort(minutes);
                for (int minute : minutes) {
                    JSONObject window = recruit.getJSONObject(String.valueOf(minute));
                    RARITY_BY_MINUTES.put(minute, new int[]{window.getIntValue("rarityStart"),
                            window.getIntValue("rarityEnd")});
                }
                RARITY_MINUTE_KEYS.addAll(minutes);
            }
        }

        // character_table writes professions as WARRIOR/SNIPER/... while gachaTags names them in
        // Chinese, so roguelike_table's recruit_ticket_<profession> items ("<label>招募券") bridge
        // them to the "<label>干员" tags.
        JSONObject roguelike = IOTools.ReadJsonFile("data/excel/roguelike_table.json");
        JSONObject itemTable = roguelike == null ? null : roguelike.getJSONObject("itemTable");
        JSONObject items = itemTable == null ? null : itemTable.getJSONObject("items");
        if (items != null) {
            for (String id : items.keySet()) {
                if (!id.startsWith("recruit_ticket_")) continue;
                String name = items.getJSONObject(id).getString("name");
                if (name == null || !name.endsWith("\u62db\u52df\u5238")) continue;
                Integer tagId = tagIdForName(name.substring(0, name.length() - 3) + "\u5e72\u5458");
                if (tagId != null) {
                    PROFESSION_TAG.put(id.substring("recruit_ticket_".length()).toUpperCase(), tagId);
                }
            }
        }
        POSITION_TAG.put("MELEE", tagIdForName("\u8fd1\u6218\u4f4d"));
        POSITION_TAG.put("RANGED", tagIdForName("\u8fdc\u7a0b\u4f4d"));
    }

    private NormalRecruitSupport() {}

    /**
     * The client stores public-recruitment slots at {@code recruit.normal.slots}. A level-3 HIRE
     * room has slots 0..3; the extra slot is the base slot before any construction phase.
     */
    static boolean syncRecruitSlots(JSONObject user) {
        JSONObject building = user.getJSONObject("building");
        JSONObject roomSlots = building == null ? null : building.getJSONObject("roomSlots");
        if (roomSlots == null) return false;

        for (String roomKey : roomSlots.keySet()) {
            JSONObject room = roomSlots.getJSONObject(roomKey);
            if (!"HIRE".equals(room.getString("roomId")) || room.getIntValue("level") <= 0) continue;

            int phases = IOTools.ReadJsonFile("data/excel/building_data.json")
                    .getJSONObject("rooms").getJSONObject("HIRE").getJSONArray("phases").size();
            int slotCount = phases + 1;
            int unlockedCount = Math.min(slotCount, room.getIntValue("level") + 1);
            JSONObject slots = ShopTransactionSupport.object(
                    ShopTransactionSupport.object(
                            ShopTransactionSupport.object(user, "recruit"), "normal"), "slots");
            boolean changed = false;
            for (int slotId = 0; slotId < slotCount; slotId++) {
                String key = String.valueOf(slotId);
                JSONObject slot = slots.getJSONObject(key);
                boolean unlock = slotId < unlockedCount;
                if (slot == null) {
                    slots.put(key, freshSlot(unlock));
                    changed = true;
                } else if (unlock && slot.getIntValue("state") == 0) {
                    unlockSlot(slot);
                    changed = true;
                }
            }
            return changed;
        }
        return false;
    }

    static JSONArray refreshedTags() {
        JSONArray definitions = IOTools.ReadJsonFile("data/excel/gacha_table.json").getJSONArray("gachaTags");
        List<Integer> tags = new ArrayList<>();
        // The local public catalog separates the 1012/1013 gender tags from the selectable 1..29 ids.
        for (int i = 0; i < definitions.size(); i++) {
            int id = definitions.getJSONObject(i).getIntValue("tagId");
            if (id >= MIN_RECRUIT_TAG && id <= MAX_RECRUIT_TAG) tags.add(id);
        }
        Collections.shuffle(tags);
        JSONArray result = new JSONArray();
        result.addAll(tags.subList(0, RECRUIT_TAG_COUNT));
        return result;
    }

    private static JSONObject freshSlot(boolean unlocked) {
        JSONObject slot = new JSONObject(true);
        slot.put("state", unlocked ? 1 : 0);
        slot.put("tags", refreshedTags());
        slot.put("selectTags", new JSONArray());
        slot.put("startTs", -1);
        slot.put("durationInSec", -1);
        slot.put("maxFinishTs", -1);
        slot.put("realFinishTs", -1);
        return slot;
    }

    private static void unlockSlot(JSONObject slot) {
        JSONObject idle = freshSlot(true);
        for (String key : idle.keySet()) slot.put(key, idle.get(key));
        slot.remove("specialTagId");
    }

    /**
     * Resolves one combination into the operator to recruit, plus the tags the combination kept
     * and the ones it dropped, so a caller can report the real outcome instead of a guess.
     *
     * Only perAvailList operators are candidates, each keeping the rarity its entry declares. The
     * rarity window comes from recruitRarityTable for the requested minutes; a special tag at the
     * 540 minute timer narrows that window to its pinned rarity.
     *
     * An empty intersection drops unprotected tags in selection order. At nine hours the highest
     * special rarity guarantee is protected; shorter timers keep their original rarity window,
     * so a special tag outside that window can also drop.
     * This is a declared local policy: no local capture shows how the official server settles such
     * a combination, and the reported defect is that refusing one strands a slot the player already
     * started, so the local backend resolves it instead. The timer window and the special-tag
     * highest nine-hour guarantee are never relaxed.
     * Dropping stops as soon as one candidate remains, so a combination that already works keeps
     * every tag it selected. A tag that no operator carries is still an error, and so is a
     * combination that stays empty with every ordinary tag dropped.
     *
     * No weight for the final choice ships locally (totalPercent describes the base rarity roll,
     * not who wins inside a tag match), so candidates are drawn uniformly. That is a declared
     * local policy and must not be reported as official odds.
     */
    public static Outcome select(int durationSeconds, List<Integer> selectedTags) {
        JSONArray perAvailList = ArknightsApplication.normalGachaData
                .getJSONObject("detailInfo")
                .getJSONObject("availCharInfo")
                .getJSONArray("perAvailList");
        if (perAvailList == null || perAvailList.isEmpty()) {
            throw new IllegalStateException("\u516c\u5f00\u62db\u52df\u5019\u9009\u8868\u4e0d\u53ef\u7528");
        }

        List<Integer> tags = distinct(selectedTags);
        // A tag the catalog does not define is an illegal selection, not a clash to resolve: the
        // player could never have picked it, so the finish is refused before anything is written.
        for (int tag : tags) {
            if (TAG_NAMES.get(tag) == null) {
                throw new IllegalArgumentException("\u9009\u62e9\u4e86\u672a\u77e5\u7684\u516c\u62db\u6807\u7b7e");
            }
        }
        int[] window = rarityWindow(durationSeconds);
        Integer guaranteed = guaranteedRarity(tags, durationSeconds);
        if (guaranteed != null) window = new int[]{guaranteed, guaranteed};

        JSONArray universe = universe(perAvailList);
        List<String> candidates = candidates(universe, window, tags);
        List<Integer> kept = new ArrayList<>(tags);
        // Resolve only empty intersections, preserving the duration window and highest guarantee.
        while (candidates.isEmpty()) {
            int dropped = firstDroppableTag(kept, guaranteed);
            if (dropped == NO_TAG) break;
            kept.remove(Integer.valueOf(dropped));
            candidates = candidates(universe, window, kept);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("\u6240\u9009\u6807\u7b7e\u4e0d\u5b58\u5728\u53ef\u62db\u52df\u7684\u5e72\u5458");
        }
        List<Integer> droppedTags = new ArrayList<>(tags);
        droppedTags.removeAll(kept);
        return new Outcome(pick(candidates), List.copyOf(kept), droppedTags);
    }

    /** One settled recruitment: the operator, the tags that held, and the ones that were dropped. */
    public static final class Outcome {
        private final String charId;
        private final List<Integer> keptTags;
        private final List<Integer> droppedTags;

        Outcome(String charId, List<Integer> keptTags, List<Integer> droppedTags) {
            this.charId = charId;
            this.keptTags = keptTags;
            this.droppedTags = droppedTags;
        }

        public String charId() { return charId; }

        /** Tags the resolved combination still applied, in the order they were selected. */
        public List<Integer> keptTags() { return keptTags; }

        /** Ordinary tags that had to be dropped because they clashed with the rest. */
        public List<Integer> droppedTags() { return droppedTags; }
    }

    /** Sentinel for "no ordinary tag left to drop". */
    private static final int NO_TAG = -1;

    /** Only the highest special guarantee at nine hours is protected from conflict resolution. */
    private static int firstDroppableTag(List<Integer> tags, Integer guaranteed) {
        for (int tag : tags) {
            if (guaranteed == null || !guaranteed.equals(SPECIAL_TAG_RARITY.get(tag))) return tag;
        }
        return NO_TAG;
    }

    /** Operators inside the rarity window that carry every tag in {@code tags}. */
    private static List<String> candidates(JSONArray universe, int[] window, List<Integer> tags) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < universe.size(); i++) {
            JSONObject candidate = universe.getJSONObject(i);
            int rarity = candidate.getIntValue("rarityRank");
            if (rarity < window[0] || rarity > window[1]) continue;
            if (!matchesTags(candidate, tags)) continue;
            candidates.add(candidate.getString("charId"));
        }
        return candidates;
    }

    /** The rarity perAvailList declares for one charId, or -1 when it is not recruitable. */
    public static int rarityOf(String charId) {
        JSONArray perAvailList = ArknightsApplication.normalGachaData
                .getJSONObject("detailInfo")
                .getJSONObject("availCharInfo")
                .getJSONArray("perAvailList");
        if (perAvailList == null) return -1;
        for (int i = 0; i < perAvailList.size(); i++) {
            JSONObject entry = perAvailList.getJSONObject(i);
            JSONArray ids = entry.getJSONArray("charIdList");
            if (ids == null) continue;
            for (int j = 0; j < ids.size(); j++) {
                if (charId.equals(ids.getString(j))) return entry.getIntValue("rarityRank");
            }
        }
        return -1;
    }

    private static JSONArray universe(JSONArray perAvailList) {
        JSONArray universe = new JSONArray();
        for (int i = 0; i < perAvailList.size(); i++) {
            JSONObject entry = perAvailList.getJSONObject(i);
            int rarity = entry.getIntValue("rarityRank");
            JSONArray ids = entry.getJSONArray("charIdList");
            if (ids == null) continue;
            for (int j = 0; j < ids.size(); j++) {
                JSONObject candidate = new JSONObject(true);
                candidate.put("charId", ids.getString(j));
                candidate.put("rarityRank", rarity);
                universe.add(candidate);
            }
        }
        return universe;
    }

    /** recruitRarityTable steps by 10 minutes, so the nearest lower or equal key applies. */
    private static int[] rarityWindow(int durationSeconds) {
        if (RARITY_BY_MINUTES.isEmpty()) {
            throw new IllegalStateException("\u62db\u52df\u65f6\u957f\u8868\u4e0d\u53ef\u7528");
        }
        int minutes = durationSeconds / 60;
        if (minutes < MIN_DURATION_MINUTES) minutes = MIN_DURATION_MINUTES;
        if (minutes > MAX_DURATION_MINUTES) minutes = MAX_DURATION_MINUTES;
        int[] window = RARITY_BY_MINUTES.get(RARITY_MINUTE_KEYS.get(0));
        for (int key : RARITY_MINUTE_KEYS) {
            if (key > minutes) break;
            window = RARITY_BY_MINUTES.get(key);
        }
        return window;
    }

    /** Highest pinned rarity among the selected special tags, but only at the 540 minute timer. */
    private static Integer guaranteedRarity(List<Integer> tags, int durationSeconds) {
        if (durationSeconds / 60 < GUARANTEE_MINUTES) return null;
        Integer guaranteed = null;
        for (int tag : tags) {
            Integer rarity = SPECIAL_TAG_RARITY.get(tag);
            if (rarity != null && (guaranteed == null || rarity > guaranteed)) guaranteed = rarity;
        }
        return guaranteed;
    }

    private static boolean matchesTags(JSONObject candidate, List<Integer> tags) {
        JSONObject character = ArknightsApplication.characterJson == null ? null
                : ArknightsApplication.characterJson.getJSONObject(candidate.getString("charId"));
        if (character == null) return false;
        for (int tag : tags) {
            if (!matchesTag(candidate, character, tag)) return false;
        }
        return true;
    }

    private static boolean matchesTag(JSONObject candidate, JSONObject character, int tag) {
        Integer pinned = SPECIAL_TAG_RARITY.get(tag);
        if (pinned != null) return candidate.getIntValue("rarityRank") == pinned;
        Integer professionTag = PROFESSION_TAG.get(character.getString("profession"));
        if (professionTag != null && professionTag == tag) return true;
        Integer positionTag = POSITION_TAG.get(character.getString("position"));
        if (positionTag != null && positionTag == tag) return true;
        String name = TAG_NAMES.get(tag);
        if (name == null) return false;
        JSONArray owned = character.getJSONArray("tagList");
        if (owned == null) return false;
        for (int i = 0; i < owned.size(); i++) {
            if (name.equals(owned.getString(i))) return true;
        }
        return false;
    }

    private static String pick(List<String> candidates) {
        if (candidates.size() == 1) return candidates.get(0);
        return candidates.get(new java.util.Random().nextInt(candidates.size()));
    }

    private static List<Integer> distinct(List<Integer> tags) {
        List<Integer> result = new ArrayList<>();
        if (tags == null) return result;
        for (Integer tag : tags) {
            if (tag != null && !result.contains(tag)) result.add(tag);
        }
        return result;
    }

    private static Integer tagIdForName(String name) {
        if (name == null) return null;
        for (Map.Entry<Integer, String> entry : TAG_NAMES.entrySet()) {
            if (name.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }
}
