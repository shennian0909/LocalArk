package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONReader;
import com.alibaba.fastjson.parser.Feature;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only support for the activities that are actually open in the shipped official tables.
 *
 * Contract:
 * - Table driven. Every value returned here is read from data/excel/activity_table.json
 *   (plus stage_table.json for real stage ids). Nothing is hard coded, and no start/end
 *   time is ever invented or overridden.
 * - Absence is reported, never guessed. When the loaded table does not define an activity,
 *   lookup returns null / NOT_PRESENT so the caller can say so instead of faking a window.
 * - Pure helpers only. This class does not own HTTP routes, does not touch the account
 *   store, and does not write user state; the parent wires syncData, route registration
 *   and persistence.
 *
 * Memory contract (Android heap is 512 MiB and the service already sits near 190 MiB):
 * - activity_table.json is 14 MiB and costs ~45 MiB retained as a full DOM, so it is parsed
 *   at most ONCE per backend root. The full DOM is then dropped and only the fragments the
 *   three current activities need are retained: basicInfo, dynActs, activityItems,
 *   zoneToActivity, and the per-type payloads of TRACKED_IDS. Everything else, including
 *   the 5 MiB "activity" section, is released.
 * - stage_table.json is never retained either. Only a stageId -> zoneId map is kept, and
 *   when the parent injects the already-resident ArknightsApplication.stageTable no file
 *   is read at all.
 */
public final class CurrentActivitySupport {

    /**
     * Presence result for a requested activity id.
     * PRESENT means the table defines it; NOT_PRESENT means the loaded table has no such id,
     * which is a data-version gap and must not be papered over with a fabricated window.
     */
    public enum Presence { PRESENT, NOT_PRESENT }

    private static final String ACTIVITY_TABLE = "data/excel/activity_table.json";
    private static final String STAGE_TABLE = "data/excel/stage_table.json";

    /** The three activities this feature exists for; only their payloads are retained. */
    private static final Set<String> TRACKED_IDS = new HashSet<String>(
            Arrays.asList("act64sign", "act1reward", "act1museum"));

    /**
     * Wire keys for the activity containers. The C# members are
     * PlayerActivity.checkinOnlyActivityList / arcadeActivityList / rewardOnlyList, but every
     * observed real save uses the uppercase section (activity.CHECKIN_ONLY, activity.ARCADE)
     * and none uses the C# names, so the uppercase section is what gets written.
     * See CurrentActivityState for the constants the rest of the code uses.
     */
    private static final Map<String, String> CONTAINER = new HashMap<String, String>();
    static {
        CONTAINER.put("CHECKIN_ONLY", CurrentActivityState.CHECKIN_CONTAINER);
        CONTAINER.put("ARCADE", CurrentActivityState.ARCADE_CONTAINER);
        CONTAINER.put("REWARD_ONLY", CurrentActivityState.REWARD_ONLY_CONTAINER);
    }

    private static final Map<String, Tables> TABLE_CACHE = new ConcurrentHashMap<String, Tables>();
    private static volatile JSONObject injectedStageTable;
    /** Package-visible so the regression test can prove the table is loaded once, not per call. */
    static volatile int activityTableLoadCount;

    private CurrentActivitySupport() { }

    /**
     * Optional: hand over the stage map the backend already holds in
     * ArknightsApplication.stageTable so this feature never reads stage_table.json itself.
     * Without this call the helper falls back to a single parse of its own.
     */
    public static void setStageTable(JSONObject stages) {
        injectedStageTable = stages;
    }

    static JSONObject stageDefinition(String stageId) {
        return injectedStageTable == null ? null : injectedStageTable.getJSONObject(stageId);
    }

    /** Slim retained fragments of activity_table.json. */
    private static final class Tables {
        final JSONObject basicInfo;
        final JSONObject dynActs;
        final JSONObject activityItems;
        final JSONObject zoneToActivity;
        final JSONObject typePayloads;
        final Map<String, String> stageZone;

        Tables(JSONObject basicInfo, JSONObject dynActs, JSONObject activityItems,
               JSONObject zoneToActivity, JSONObject typePayloads, Map<String, String> stageZone) {
            this.basicInfo = basicInfo;
            this.dynActs = dynActs;
            this.activityItems = activityItems;
            this.zoneToActivity = zoneToActivity;
            this.typePayloads = typePayloads;
            this.stageZone = stageZone;
        }
    }

    /** One activity as defined by the loaded official table. */
    public static final class Activity {
        private final String id;
        private final String type;
        private final String name;
        private final long startTime;
        private final long endTime;
        private final long rewardEndTime;
        private final boolean displayOnHome;
        private final boolean isPageEntry;
        private final boolean hasStage;

        private Activity(JSONObject basic) {
            this.id = basic.getString("id");
            this.type = basic.getString("type");
            this.name = basic.getString("name");
            this.startTime = basic.getLongValue("startTime");
            this.endTime = basic.getLongValue("endTime");
            this.rewardEndTime = basic.getLongValue("rewardEndTime");
            this.displayOnHome = basic.getBooleanValue("displayOnHome");
            this.isPageEntry = basic.getBooleanValue("isPageEntry");
            this.hasStage = basic.getBooleanValue("hasStage");
        }

        public String id() { return id; }
        public String type() { return type; }
        public String name() { return name; }
        public long startTime() { return startTime; }
        public long endTime() { return endTime; }
        public long rewardEndTime() { return rewardEndTime; }
        public boolean displayOnHome() { return displayOnHome; }
        public boolean isPageEntry() { return isPageEntry; }
        public boolean hasStage() { return hasStage; }

        /** Open means the activity window contains now, using the table's own timestamps. */
        public boolean isOpen(long nowSeconds) {
            return startTime <= nowSeconds && nowSeconds <= endTime;
        }
    }

    /** Loaded definition plus a presence marker. */
    public static final class Lookup {
        private final Presence presence;
        private final Activity activity;

        private Lookup(Presence presence, Activity activity) {
            this.presence = presence;
            this.activity = activity;
        }

        public Presence presence() { return presence; }
        public Activity activity() { return activity; }
        public boolean present() { return presence == Presence.PRESENT; }
    }

    /**
     * Look up one activity id in the loaded official table.
     * Returns NOT_PRESENT (not a fabricated entry) when the table lacks the id.
     */
    public static Lookup lookup(File root, String activityId) {
        if (root == null) throw new IllegalArgumentException("root is required");
        if (activityId == null || activityId.trim().isEmpty()) {
            throw new IllegalArgumentException("activityId is required");
        }
        JSONObject basicInfo = tables(root).basicInfo;
        if (basicInfo == null) return new Lookup(Presence.NOT_PRESENT, null);
        JSONObject basic = basicInfo.getJSONObject(activityId);
        if (basic == null) return new Lookup(Presence.NOT_PRESENT, null);
        return new Lookup(Presence.PRESENT, new Activity(basic));
    }

    /** Every activity whose own window contains now, ordered by start time. */
    public static List<Activity> openActivities(File root, long nowSeconds) {
        List<Activity> open = new ArrayList<Activity>();
        JSONObject basicInfo = tables(root).basicInfo;
        if (basicInfo == null) return open;
        List<Activity> all = new ArrayList<Activity>();
        for (String key : basicInfo.keySet()) {
            JSONObject basic = basicInfo.getJSONObject(key);
            if (basic == null) continue;
            all.add(new Activity(basic));
        }
        all.sort((a, b) -> Long.compare(a.startTime, b.startTime));
        for (Activity activity : all) {
            if (activity.isOpen(nowSeconds)) open.add(activity);
        }
        return open;
    }

    /** Activities open now that the table declares a home entry or page entry for. */
    public static List<Activity> openHomeEntries(File root, long nowSeconds) {
        List<Activity> entries = new ArrayList<Activity>();
        for (Activity activity : openActivities(root, nowSeconds)) {
            if (activity.displayOnHome() || activity.isPageEntry()) entries.add(activity);
        }
        return entries;
    }

    /**
     * Real stage ids belonging to an activity, taken from the table's own zoneToActivity
     * mapping joined against stage_table.zoneId. Prefix matching is deliberately not used.
     */
    public static List<String> stageIds(File root, String activityId) {
        List<String> ids = new ArrayList<String>();
        if (root == null || activityId == null || activityId.trim().isEmpty()) return ids;
        Tables tables = tables(root);
        JSONObject zoneToActivity = tables.zoneToActivity;
        Map<String, String> stageZone = tables.stageZone;
        if (zoneToActivity == null || stageZone == null) return ids;

        Set<String> zones = new HashSet<String>();
        for (String zoneId : zoneToActivity.keySet()) {
            if (activityId.equals(zoneToActivity.getString(zoneId))) zones.add(zoneId);
        }
        for (Map.Entry<String, String> entry : stageZone.entrySet()) {
            String zoneId = entry.getValue();
            if (zoneId != null && zones.contains(zoneId)) ids.add(entry.getKey());
        }
        ids.sort(null);
        return ids;
    }

    /** Raw per-type payload for one id, e.g. the CHECKIN_ONLY reward list or ARCADE data. */
    public static JSONObject typePayload(File root, String type, String activityId) {
        if (root == null || type == null || activityId == null) return null;
        JSONObject typePayloads = tables(root).typePayloads;
        if (typePayloads == null) return null;
        JSONObject byType = typePayloads.getJSONObject(type);
        if (byType == null) return null;
        return byType.getJSONObject(activityId);
    }

    /** The activity's daily check-in reward entries, or null when the table has none. */
    public static JSONArray checkInRewards(File root, String activityId) {
        JSONObject payload = typePayload(root, "CHECKIN_ONLY", activityId);
        if (payload == null) return null;
        JSONObject checkInList = payload.getJSONObject("checkInList");
        if (checkInList == null) return null;
        JSONArray rewards = new JSONArray();
        List<String> keys = new ArrayList<String>(checkInList.keySet());
        keys.sort((a, b) -> Integer.compare(parseInt(a), parseInt(b)));
        for (String key : keys) {
            JSONObject entry = checkInList.getJSONObject(key);
            if (entry == null) continue;
            rewards.add(entry);
        }
        return rewards;
    }

    /**
     * The REWARD_ONLY pools the table declares for an activity, or null when it has none.
     * Keys are the real pool ids the client sends, e.g. pool_1..pool_4.
     */
    public static JSONObject rewardOnlyPools(File root, String activityId) {
        JSONObject dynAct = dynActs(root);
        if (dynAct == null) return null;
        JSONObject payload = dynAct.getJSONObject(activityId);
        if (payload == null) return null;
        return payload.getJSONObject("rewardPoolDataDict");
    }

    /**
     * Ensure the user's activity map has an entry for every activity that is open now.
     *
     * Scoped to TRACKED_IDS on purpose: only the three activities whose account container
     * and field shape are confirmed get seeded. Seeding every open activity would invent
     * empty userdata for玩法 with no agreed protocol and can perturb the client.
     *
     * Containers are the client's declared PlayerActivity members:
     * - CHECKIN_ONLY -> activity.checkinOnlyActivityList[id] = PlayerCheckinOnlyTypeActivity
     *   (history, dynOpt, extraHistory; lastTs is our own backend day marker)
     * - ARCADE       -> activity.arcadeActivityList[id] = PlayerArcadeActivity
     *   (milestone, badge, score only)
     * - REWARD_ONLY  -> activity.rewardOnlyList[id] = PlayerRewardOnlyActivity (choice, hit)
     *
     * Existing entries are never overwritten, so live progress survives a resync.
     * A tracked CHECKIN_ONLY entry also gets its day opened here, at most one per server day.
     * Returns true when anything was added.
     */
    public static boolean normalize(JSONObject user, File root, long nowSeconds) {
        if (user == null) throw new IllegalArgumentException("user is required");
        JSONObject activity = user.getJSONObject("activity");
        if (activity == null) {
            activity = new JSONObject(true);
            user.put("activity", activity);
        }
        boolean changed = false;
        for (Activity open : openActivities(root, nowSeconds)) {
            if (!TRACKED_IDS.contains(open.id())) continue;
            String container = CONTAINER.get(open.type());
            if (container == null) continue;
            JSONObject byType = activity.getJSONObject(container);
            if (byType == null) {
                byType = new JSONObject(true);
                activity.put(container, byType);
                changed = true;
            }
            if (!byType.containsKey(open.id())) {
                byType.put(open.id(), seed(open.type()));
                changed = true;
            }
            if ("CHECKIN_ONLY".equals(open.type())) {
                changed |= CurrentActivityState.refreshCheckIn(user, root, open.id(), nowSeconds);
            }
            if ("ARCADE".equals(open.type())) {
                changed |= CurrentActivityState.refreshArcadeStages(user, root, open.id(), nowSeconds);
                changed |= ArcadeBadgeSupport.normalize(user, root, open.id());
            }
        }
        return changed;
    }

    /**
     * Seeds follow the resolved .NET container types:
     * - CHECKIN_ONLY: history List<int>, dynOpt List<string>, extraHistory List<int>
     * - REWARD_ONLY: choice string, hit string
     * - ARCADE: milestone MilestoneInfo{point:int, got:List<string>},
     *   badge Dictionary<string,BadgeInfo{status,missionProgress}>,
     *   score Dictionary<string,Dictionary<string,int>>
     *
     * ARCADE starts neutral; normalization rebuilds its unlocks from the saved scores.
     */
    public static JSONObject seed(String type) {
        return CurrentActivityState.seed(type);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /** Slim activityItems fragment, for sibling helpers. */
    static JSONObject activityItems(File root) {
        return tables(root).activityItems;
    }

    /** Slim dynActs fragment, for sibling helpers. */
    static JSONObject dynActs(File root) {
        return tables(root).dynActs;
    }

    /**
     * Load once per backend root and retain only the fragments the three tracked activities
     * need. The full DOM is deliberately not kept: it costs ~45 MiB and would otherwise be
     * rebuilt on every syncData call.
     */
    private static Tables tables(File root) {
        String key = root.getAbsolutePath();
        Tables cached = TABLE_CACHE.get(key);
        if (cached != null) return cached;
        Tables loaded = load(root);
        TABLE_CACHE.put(key, loaded);
        return loaded;
    }

    private static Tables load(File root) {
        activityTableLoadCount++;
        JSONObject table = readJson(new File(root, ACTIVITY_TABLE));
        if (table == null) return new Tables(null, null, null, null, null, stageZone(root));

        // Retained by reference; the rest of the DOM becomes garbage once this method returns.
        JSONObject basicInfo = table.getJSONObject("basicInfo");
        JSONObject dynActs = table.getJSONObject("dynActs");
        JSONObject activityItems = table.getJSONObject("activityItems");
        JSONObject zoneToActivity = table.getJSONObject("zoneToActivity");
        JSONObject typePayloads = extractTrackedPayloads(table.getJSONObject("activity"));
        Map<String, String> stageZone = stageZone(root);
        return new Tables(basicInfo, dynActs, activityItems, zoneToActivity, typePayloads, stageZone);
    }

    /**
     * Keep only the three tracked activities' per-type payloads. The full "activity" section
     * is ~5 MiB and the museum is the only ARCADE entry we serve.
     */
    private static JSONObject extractTrackedPayloads(JSONObject activity) {
        if (activity == null) return new JSONObject(true);
        JSONObject kept = new JSONObject(true);
        for (String type : activity.keySet()) {
            JSONObject byType = activity.getJSONObject(type);
            if (byType == null) continue;
            JSONObject entries = new JSONObject(true);
            for (String id : byType.keySet()) {
                if (!TRACKED_IDS.contains(id)) continue;
                JSONObject payload = byType.getJSONObject(id);
                if (payload != null) entries.put(id, payload);
            }
            if (!entries.isEmpty()) kept.put(type, entries);
        }
        return kept;
    }

    /**
     * stageId -> zoneId only. Prefers the stage map the backend already holds resident, so
     * stage_table.json (19 MiB) is neither re-read nor retained here.
     */
    private static Map<String, String> stageZone(File root) {
        Map<String, String> map = new HashMap<String, String>();
        JSONObject stages = injectedStageTable;
        if (stages == null) {
            JSONObject parsed = readJson(new File(root, STAGE_TABLE));
            stages = parsed == null ? null : parsed.getJSONObject("stages");
        }
        if (stages == null) return map;
        for (String stageId : stages.keySet()) {
            JSONObject stage = stages.getJSONObject(stageId);
            if (stage == null) continue;
            String zoneId = stage.getString("zoneId");
            if (zoneId != null) map.put(stageId, zoneId);
        }
        return map;
    }

    static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
             JSONReader jsonReader = new JSONReader(reader, Feature.OrderedField)) {
            return jsonReader.readObject(JSONObject.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
