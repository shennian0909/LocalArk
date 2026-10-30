package com.codex.arklocal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded DRAW CONTENT selection for local gacha.
 *
 * Contract:
 * - Android independent: only standard Java file IO plus Fastjson JSON.
 * - Streaming {@link JSONReader} with explicit UTF-8 decoding; never readString/readAllBytes.
 * - Remembers only the selected pool id string; persistence and UI belong to the parent.
 * - Blank/null selection means "original behavior" (no replacement).
 * - BOOT/newbee pools are never selectable and BOOT requests are never replaced.
 */
public final class LocalGachaOptions {

    private static final String TABLE_PATH = "data/excel/gacha_table.json";
    private static final String POOL_DIR = "data/gacha";
    private static final String POOL_SUFFIX = ".json";
    private static final String CHAR_TABLE_PATH = "data/excel/character_table.json";

    /** Selected DRAW CONTENT id. Blank/null means original behavior. */
    private static volatile String selectedPoolId;

    private LocalGachaOptions() { }

    // ---------------------------------------------------------------- selection

    /** Last validated selection, or null when blank (original behavior). */
    public static String getSelectedPool() {
        String id = selectedPoolId;
        return id == null || id.trim().isEmpty() ? null : id;
    }

    /**
     * Remember the requested DRAW CONTENT id. Blank/null clears the selection and restores
     * original behavior. Rejects anything not present in {@link #catalog(File)}.
     */
    public static void setSelectedPool(File root, String id) {
        if (id == null || id.trim().isEmpty()) {
            selectedPoolId = null;
            return;
        }
        if (!isValidPool(root, id.trim())) {
            throw new IllegalArgumentException("Unsupported gacha content id: " + id);
        }
        selectedPoolId = id.trim();
    }

    // ------------------------------------------------------------------ catalog

    /** [{id,name}] for every client pool that also has valid local data/gacha/<id>.json. */
    public static JSONArray catalog(File root) {
        Catalog cached = catalogFor(root);
        JSONArray out = new JSONArray(cached.ids.size());
        for (String id : cached.ids) {
            JSONObject entry = new JSONObject(true);
            entry.put("id", id);
            entry.put("name", cached.names.get(id));
            out.add(entry);
        }
        return out;
    }

    /** Catalog ids, deduped, in table order. Never returns null. */
    public static List<String> catalogIds(File root) {
        return catalogFor(root).ids;
    }

    /** True when the id is present in the catalog (table entry plus valid local pool data). */
    public static boolean isValidPool(File root, String id) {
        if (id == null) return false;
        String trimmed = id.trim();
        if (trimmed.isEmpty()) return false;
        Catalog cached = catalogFor(root);
        return cached.ids.contains(trimmed) && readSnapshot(root, trimmed, cached.knownChars, cached) != null;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Validated, fully independent snapshot of one pool file.
     * Structure is deep-parsed so callers can never mutate the on-disk/shared copy.
     */
    public static final class PoolSnapshot {
        private final String id;
        private final String name;
        private final List<AvailBucket> availList;
        private final List<UpBucket> upList;

        private PoolSnapshot(String id, String name, List<AvailBucket> availList, List<UpBucket> upList) {
            this.id = id;
            this.name = name;
            this.availList = Collections.unmodifiableList(availList);
            this.upList = Collections.unmodifiableList(upList);
        }

        public String id() { return id; }
        public String name() { return name; }
        public List<AvailBucket> availList() { return availList; }
        public List<UpBucket> upList() { return upList; }
    }

    public static final class AvailBucket {
        private final int rarityRank;
        private final double totalPercent;
        private final List<String> charIdList;

        private AvailBucket(int rarityRank, double totalPercent, List<String> charIdList) {
            this.rarityRank = rarityRank;
            this.totalPercent = totalPercent;
            this.charIdList = Collections.unmodifiableList(charIdList);
        }

        public int rarityRank() { return rarityRank; }
        public double totalPercent() { return totalPercent; }
        public List<String> charIdList() { return charIdList; }
    }

    public static final class UpBucket {
        private final int rarityRank;
        private final double percent;
        private final List<String> charIdList;

        private UpBucket(int rarityRank, double percent, List<String> charIdList) {
            this.rarityRank = rarityRank;
            this.percent = percent;
            this.charIdList = Collections.unmodifiableList(charIdList);
        }

        public int rarityRank() { return rarityRank; }
        public double percent() { return percent; }
        public List<String> charIdList() { return charIdList; }
    }

    /**
     * Read and validate one pool. Returns null for missing, DEFAULT, BOOT/newbee, traversal,
     * non-catalog ids, malformed structure, unknown character ids, or empty/zero-weight lists.
     */
    public static PoolSnapshot snapshot(File root, String id) {
        if (root == null || id == null) return null;
        String trimmed = id.trim();
        if (!isSafeId(trimmed) || isExcludedId(trimmed)) return null;
        Catalog cached = catalogFor(root);
        if (!cached.ids.contains(trimmed)) return null;
        return readSnapshot(root, trimmed, cached.knownChars, cached);
    }

    /** Rarity buckets as a JSONArray shaped like the official perAvailList, safe to pass around. */
    public static JSONArray availCharInfoArray(PoolSnapshot snapshot) {
        JSONArray out = new JSONArray();
        if (snapshot == null) return out;
        for (AvailBucket bucket : snapshot.availList()) {
            JSONObject node = new JSONObject(true);
            node.put("rarityRank", bucket.rarityRank());
            node.put("totalPercent", bucket.totalPercent());
            node.put("charIdList", stringArray(bucket.charIdList()));
            out.add(node);
        }
        return out;
    }

    /** UP buckets as a JSONArray shaped like the official perCharList. */
    public static JSONArray upCharInfoArray(PoolSnapshot snapshot) {
        JSONArray out = new JSONArray();
        if (snapshot == null) return out;
        for (UpBucket bucket : snapshot.upList()) {
            JSONObject node = new JSONObject(true);
            node.put("rarityRank", bucket.rarityRank());
            node.put("percent", bucket.percent());
            node.put("charIdList", stringArray(bucket.charIdList()));
            out.add(node);
        }
        return out;
    }

    /**
     * Draw content id to sample for one request. BOOT/newbee requests always bypass replacement.
     * A chosen-but-invalid id is surfaced through {@link ChosenPool} failure instead of DEFAULT.
     */
    public static ChosenPool chooseForRequest(File root, String incomingPoolId) {
        if (incomingPoolId == null) return ChosenPool.original();
        String incoming = incomingPoolId.trim();
        if (incoming.isEmpty()) return ChosenPool.original();
        if (isBootPool(incoming)) return ChosenPool.original();

        String chosen = getSelectedPool();
        if (chosen == null) return ChosenPool.original();
        PoolSnapshot resolved = snapshot(root, chosen);
        if (resolved == null) return ChosenPool.failedChoice();
        return ChosenPool.replaced(resolved);
    }

    /** Per-request resolution result. */
    public static final class ChosenPool {
        private final boolean replace;
        private final boolean failed;
        private final PoolSnapshot snapshot;

        private ChosenPool(boolean replace, boolean failed, PoolSnapshot snapshot) {
            this.replace = replace;
            this.failed = failed;
            this.snapshot = snapshot;
        }

        static ChosenPool original() { return new ChosenPool(false, false, null); }
        static ChosenPool replaced(PoolSnapshot snapshot) { return new ChosenPool(true, false, snapshot); }
        /** Named away from the {@link #failed()} accessor, which cannot share this signature. */
        static ChosenPool failedChoice() { return new ChosenPool(false, true, null); }

        public boolean replace() { return replace; }
        public boolean failed() { return failed; }
        public PoolSnapshot snapshot() { return snapshot; }
        public String id() { return snapshot == null ? null : snapshot.id(); }
    }

    /** True for newbee/BOOT pools, which are never selectable and never replaced. */
    public static boolean isBootPool(String id) {
        return id != null && (id.startsWith("BOOT") || id.startsWith("NEWBEE") || id.startsWith("newbee"));
    }

    // ----------------------------------------------------------------- internals

    /** One root's cached table view: ordered catalog ids, display names, known character ids. */
    private static final class Catalog {
        private final List<String> ids;
        private final Map<String, String> names;
        private final Set<String> knownChars;

        private Catalog(List<String> ids, Map<String, String> names, Set<String> knownChars) {
            this.ids = Collections.unmodifiableList(ids);
            this.names = Collections.unmodifiableMap(names);
            this.knownChars = knownChars == null ? null : Collections.unmodifiableSet(knownChars);
        }
    }

    private static final Object CACHE_LOCK = new Object();
    private static volatile File cachedRoot;
    private static volatile Catalog cachedCatalog;

    /** Table scan happens once per root; later calls reuse it instead of rereading the big table. */
    private static Catalog catalogFor(File root) {
        File resolved = root == null ? null : resolveRoot(root);
        Catalog hit = cachedCatalog;
        if (hit != null && cachedRoot != null && cachedRoot.equals(resolved)) return hit;
        Catalog built = buildCatalog(resolved);
        synchronized (CACHE_LOCK) {
            Catalog current = cachedCatalog;
            if (current != null && cachedRoot != null && cachedRoot.equals(resolved)) return current;
            cachedRoot = resolved;
            cachedCatalog = built;
            return built;
        }
    }

    private static Catalog buildCatalog(File resolved) {
        if (resolved == null) return new Catalog(new ArrayList<>(), new LinkedHashMap<>(), null);
        Set<String> known = knownCharacterIds(resolved);
        List<String> ids = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PoolRow row : clientPools(resolved)) {
            String id = row.id;
            if (id == null) continue;
            String trimmed = id.trim();
            if (!seen.add(trimmed)) continue;
            names.put(trimmed, row.name == null || row.name.trim().isEmpty() ? trimmed : row.name);
            if (!isSafeId(trimmed) || isExcludedId(trimmed)) continue;
            if (!new File(resolved, POOL_DIR + File.separator + trimmed + POOL_SUFFIX).isFile()) continue;
            ids.add(trimmed);
        }
        List<String> validated = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (readSnapshot(resolved, id, known, null) != null) validated.add(id);
        }
        return new Catalog(validated, names, known);
    }

    /**
     * Parse and validate one pool file against the official shape.
     * Returns null for any malformed content: bad buckets, unknown character ids, empty lists,
     * zero/negative weights, or a bad UP block (UP is never silently dropped).
     */
    private static PoolSnapshot readSnapshot(File resolved, String id, Set<String> known, Catalog cached) {
        JSONObject raw = readJsonObject(new File(resolved, POOL_DIR + File.separator + id + POOL_SUFFIX));
        if (raw == null) return null;

        JSONObject detail = raw.getJSONObject("detailInfo");
        if (detail == null) return null;
        JSONObject availCharInfo = detail.getJSONObject("availCharInfo");
        if (availCharInfo == null) return null;
        JSONArray perAvailList = availCharInfo.getJSONArray("perAvailList");
        if (perAvailList == null || perAvailList.isEmpty()) return null;

        List<AvailBucket> avail = new ArrayList<>();
        double totalWeight = 0.0d;
        for (int i = 0; i < perAvailList.size(); i++) {
            Object node = perAvailList.get(i);
            if (!(node instanceof JSONObject)) return null;
            JSONObject bucket = (JSONObject) node;
            JSONArray ids = bucket.getJSONArray("charIdList");
            if (ids == null || ids.isEmpty()) return null;
            int rarityRank = bucket.getIntValue("rarityRank");
            if (rarityRank <= 0) return null;
            double totalPercent = bucket.getDoubleValue("totalPercent");
            if (totalPercent <= 0.0d) return null;
            List<String> bucketIds = new ArrayList<>(ids.size());
            for (int j = 0; j < ids.size(); j++) {
                String charId = ids.getString(j);
                if (charId == null || charId.trim().isEmpty()) return null;
                if (known != null && !known.contains(charId)) return null;
                bucketIds.add(charId);
            }
            totalWeight += totalPercent;
            avail.add(new AvailBucket(rarityRank, totalPercent, bucketIds));
        }
        if (totalWeight <= 0.0d) return null;

        List<UpBucket> ups = new ArrayList<>();
        JSONObject upCharInfo = detail.getJSONObject("upCharInfo");
        if (upCharInfo != null) {
            JSONArray perCharList = upCharInfo.getJSONArray("perCharList");
            if (perCharList != null) {
                for (int i = 0; i < perCharList.size(); i++) {
                    Object node = perCharList.get(i);
                    if (!(node instanceof JSONObject)) return null;
                    JSONObject up = (JSONObject) node;
                    JSONArray ids = up.getJSONArray("charIdList");
                    if (ids == null || ids.isEmpty()) return null;
                    int rarityRank = up.getIntValue("rarityRank");
                    if (rarityRank <= 0) return null;
                    double percent = up.getDoubleValue("percent");
                    if (percent <= 0.0d) return null;
                    List<String> upIds = new ArrayList<>(ids.size());
                    for (int j = 0; j < ids.size(); j++) {
                        String charId = ids.getString(j);
                        if (charId == null || charId.trim().isEmpty()) return null;
                        if (known != null && !known.contains(charId)) return null;
                        upIds.add(charId);
                    }
                    ups.add(new UpBucket(rarityRank, percent, upIds));
                }
            }
        }
        String name = cached == null ? id : (cached.names.containsKey(id) ? cached.names.get(id) : id);
        return new PoolSnapshot(id, name, avail, ups);
    }

    private static boolean isExcludedId(String id) {
        if ("DEFAULT".equalsIgnoreCase(id)) return true;
        return isBootPool(id);
    }

    /** Rejects path traversal and anything outside a plain filename shape. */
    private static boolean isSafeId(String id) {
        if (id.isEmpty() || id.length() > 128) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return !id.contains("..") && !id.contains("/") && !id.contains("\\");
    }

    private static final class PoolRow {
        private final String id;
        private final String name;
        private PoolRow(String id, String name) { this.id = id; this.name = name; }
    }

    /** Client table rows in file order, read once per {@link #catalogFor(File)} scan. */
    private static List<PoolRow> clientPools(File resolved) {
        List<PoolRow> rows = new ArrayList<>();
        JSONObject table = readJsonObject(new File(resolved, TABLE_PATH));
        if (table == null) return rows;
        JSONArray client = table.getJSONArray("gachaPoolClient");
        if (client == null) return rows;
        for (int i = 0; i < client.size(); i++) {
            Object node = client.get(i);
            if (!(node instanceof JSONObject)) continue;
            JSONObject pool = (JSONObject) node;
            rows.add(new PoolRow(pool.getString("gachaPoolId"), pool.getString("gachaPoolName")));
        }
        return rows;
    }

    /** Character ids from character_table; null when the table is unreadable (then skip id checks). */
    private static Set<String> knownCharacterIds(File resolved) {
        JSONObject table = readJsonObject(new File(resolved, CHAR_TABLE_PATH));
        if (table == null) return null;
        Set<String> ids = new LinkedHashSet<>();
        for (String key : table.keySet()) ids.add(key);
        return ids;
    }

    private static File resolveRoot(File root) {
        return root.isAbsolute() ? root : new File(BackendPaths.rootFile(), root.getPath());
    }

    private static JSONArray stringArray(List<String> values) {
        JSONArray out = new JSONArray(values.size());
        for (String value : values) out.add(value);
        return out;
    }

    private static JSONObject readJsonObject(File file) {
        if (file == null || !file.isFile()) return null;
        try (Reader stream = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
             JSONReader reader = new JSONReader(stream, Feature.OrderedField)) {
            Object parsed = reader.readObject();
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            // Covers Fastjson's JSONException (a RuntimeException) and any other parse failure.
            return null;
        }
    }
}
