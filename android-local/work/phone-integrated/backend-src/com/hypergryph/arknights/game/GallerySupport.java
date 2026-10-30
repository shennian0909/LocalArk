package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.BackendPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class GallerySupport {
    private static final String THUMBNAIL_FIELD = "_localGalleryThumbnails";
    private static final Path THUMBNAIL_DIR_NAME = Paths.get("local-gallery-thumbnails");
    private static final Pattern STORED_THUMBNAIL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg)");

    private GallerySupport() {}

    static JSONObject getThumbnailUrl(JSONObject user, JSONObject body, String baseUrl) {
        Object value = body == null ? null : body.get("idList");
        if (!(value instanceof JSONArray))
            throw new IllegalArgumentException("invalid magazine leaf id list");
        JSONArray ids = (JSONArray) value;
        JSONObject thumbnails = user.getJSONObject(THUMBNAIL_FIELD);
        JSONArray urls = new JSONArray();
        for (Object id : ids) {
            if (!(id instanceof String) || ((String) id).isEmpty())
                throw new IllegalArgumentException("invalid magazine leaf id");
            urls.add(thumbnailUrl(thumbnails == null ? null : thumbnails.get(id), baseUrl));
        }
        JSONObject result = CharacterBuildingSupport.result(new JSONObject(true));
        result.put("url", urls);
        return result;
    }

    static JSONObject saveDiyMagazineV1(JSONObject user, JSONObject body) {
        Object thumbnail = body == null ? null : body.get("thumbnail");
        if (!(thumbnail instanceof String))
            throw new IllegalArgumentException("invalid magazine thumbnail");
        return saveDiyMagazine(user, body, thumbnail);
    }

    static JSONObject saveDiyMagazineV2(JSONObject user, JSONObject body,
            byte[] thumbnail, String contentType) {
        if (thumbnail == null || thumbnail.length == 0)
            throw new IllegalArgumentException("missing magazine thumbnail");
        return saveDiyMagazine(user, body, storeThumbnail(thumbnail, contentType));
    }

    static JSONObject saveDiyMagazineV2(JSONObject user, byte[] payload, String contentType) {
        return saveDiyMagazineV2(user, new String(payload, StandardCharsets.ISO_8859_1), contentType);
    }

    static JSONObject saveDiyMagazineV2(JSONObject user, String payload, String contentType) {
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            throw new IllegalArgumentException("invalid magazine multipart request");
        MultipartData data = parseMultipart(payload, contentType);
        if (data.magazine == null) throw new IllegalArgumentException("invalid magazine");
        JSONObject body = new JSONObject(true);
        body.put("magazine", data.magazine);
        byte[] thumbnail = data.jpeg != null ? data.jpeg : data.thumbnail;
        if (thumbnail == null || thumbnail.length == 0)
            throw new IllegalArgumentException("missing magazine thumbnail");
        if (!isPng(thumbnail) && !isJpeg(thumbnail))
            throw new IllegalArgumentException("invalid magazine thumbnail image");
        return saveDiyMagazine(user, body, storeThumbnail(thumbnail, contentType));
    }

    static JSONObject getFirstRewards(JSONObject user) {
        long now = System.currentTimeMillis() / 1000L;
        JSONObject gallery = normalize(user);
        if (gallery.getBooleanValue("firstRewards"))
            throw new IllegalArgumentException("first gallery rewards already claimed");

        JSONObject display = display();
        JSONArray rewards = display.getJSONObject("magazineLeafData").getJSONObject("constData")
                .getJSONArray("sysUnlockRewards");
        JSONArray granted = grantRewards(user, rewards, now);
        gallery.put("firstRewards", true);
        JSONObject result = galleryResult(user);
        result.put("rewards", granted);
        return result;
    }

    static JSONObject getCollectionRewards(JSONObject user, JSONObject body) {
        long now = System.currentTimeMillis() / 1000L;
        JSONObject gallery = normalize(user);
        LocalCosmeticSupport.normalize(user, now);
        String setId = requiredString(body, "setId");
        String missionId = requiredString(body, "missionId");
        JSONObject sets = display().getJSONObject("artGalleryCollectData").getJSONObject("collectionSets");
        JSONObject collection = sets.getJSONObject(setId);
        if (collection == null) throw new IllegalArgumentException("unknown gallery collection");
        JSONObject mission = collection.getJSONObject("missionList").getJSONObject(missionId);
        if (mission == null) throw new IllegalArgumentException("unknown collection mission");

        JSONObject claimed = gallery.getJSONObject("collectionRewards");
        if (claimed.getBooleanValue(missionId))
            throw new IllegalArgumentException("collection reward already claimed");
        int owned = ownedItemCount(user, collection.getJSONArray("items"));
        if (owned < mission.getIntValue("requireItemCount"))
            throw new IllegalArgumentException("collection mission is not complete");

        JSONArray granted = grantRewards(user, mission.getJSONArray("rewardList"), now);
        claimed.put(missionId, true);
        JSONObject result = galleryResult(user);
        result.put("rewards", granted);
        return result;
    }

    static JSONObject changeMagazineSquad(JSONObject user, JSONObject body) {
        JSONObject gallery = normalize(user);
        JSONArray squad = body == null ? null : body.getJSONArray("squad");
        if (squad == null) throw new IllegalArgumentException("invalid magazine squad");
        int max = display().getJSONObject("magazineLeafData").getJSONObject("constData")
                .getIntValue("leafDisplayMaxNum");
        if (squad.size() > max) throw new IllegalArgumentException("magazine squad is too large");
        JSONObject leafMap = gallery.getJSONObject("leafMap");
        Set<String> seen = new HashSet<>();
        JSONArray clean = new JSONArray();
        for (Object value : squad) {
            if (!(value instanceof String) || !seen.add((String) value) || !leafMap.containsKey(value))
                throw new IllegalArgumentException("invalid magazine leaf");
            clean.add(value);
        }
        gallery.put("magazineSquad", clean);
        return galleryResult(user);
    }

    static JSONArray magazineBoard(JSONObject user) {
        JSONArray board = new JSONArray();
        JSONObject gallery = user.getJSONObject("gallery");
        JSONObject leaves = gallery == null ? null : gallery.getJSONObject("leafMap");
        JSONArray squad = gallery == null ? null : gallery.getJSONArray("magazineSquad");
        if (leaves == null || squad == null) return board;
        for (Object value : squad) {
            if (!(value instanceof String)) continue;
            JSONObject leaf = leaves.getJSONObject((String) value);
            if (leaf == null) continue;
            JSONObject friendLeaf = new JSONObject(true);
            friendLeaf.put("leafId", value);
            Object decorList = leaf.get("decorList");
            friendLeaf.put("decorList", decorList instanceof JSONArray ? decorList : new JSONArray());
            friendLeaf.put("charSkin", leaf.get("charSkin"));
            board.add(friendLeaf);
        }
        return board;
    }

    static Path thumbnailFile(String fileName) {
        if (fileName == null || !STORED_THUMBNAIL.matcher(fileName).matches())
            throw new IllegalArgumentException("invalid magazine thumbnail");
        return thumbnailDirectory().resolve(fileName);
    }

    private static JSONObject normalize(JSONObject user) {
        JSONObject gallery = ShopTransactionSupport.object(user, "gallery");
        if (!gallery.containsKey("firstRewards")) gallery.put("firstRewards", false);
        JSONObject leaves = ShopTransactionSupport.object(gallery, "leafMap");
        long now = System.currentTimeMillis() / 1000L;
        for (Object leafId : new JSONArray(new ArrayList<>(leaves.keySet()))) {
            if (leafId instanceof String) normalizeLeaf(leaves, (String) leafId, now);
        }
        if (!(gallery.get("magazineSquad") instanceof JSONArray)) gallery.put("magazineSquad", new JSONArray());
        ShopTransactionSupport.object(gallery, "collectionRewards");
        ShopTransactionSupport.object(gallery, "stickerMap");
        ShopTransactionSupport.object(gallery, "offlineList");
        return gallery;
    }

    private static JSONArray grantRewards(JSONObject user, JSONArray definitions, long now) {
        if (definitions == null) throw new IllegalArgumentException("missing gallery rewards");
        JSONArray granted = new JSONArray();
        for (Object value : definitions) {
            if (!(value instanceof JSONObject)) throw new IllegalArgumentException("invalid gallery reward");
            grantReward(user, (JSONObject) value, now, granted);
        }
        return granted;
    }

    private static void grantReward(JSONObject user, JSONObject reward, long now, JSONArray granted) {
        String type = reward.getString("type");
        String id = reward.getString("id");
        int count = reward.getIntValue("count");
        if (type == null || id == null || count <= 0) throw new IllegalArgumentException("invalid gallery reward");
        if ("ITEM_PACK".equals(type)) {
            JSONObject pack = CharacterBuildingSupport.table("excel/item_table.json")
                    .getJSONObject("itemPackInfos").getJSONObject(id);
            if (pack == null) throw new IllegalArgumentException("unknown gallery item pack");
            JSONArray content = pack.getJSONArray("content");
            if (count != 1 || content == null) throw new IllegalArgumentException("invalid gallery item pack");
            for (Object value : content) grantReward(user, (JSONObject) value, now, granted);
            return;
        }

        JSONObject gallery = normalize(user);
        if ("MAGAZINE_LEAF".equals(type)) {
            if (count != 1 || !display().getJSONObject("magazineLeafData").getJSONObject("leafMap").containsKey(id))
                throw new IllegalArgumentException("invalid magazine leaf reward");
            JSONObject leaves = gallery.getJSONObject("leafMap");
            if (!leaves.containsKey(id)) {
                leaves.put(id, newLeaf(id, now, 1));
                addItem(granted, id, type, count);
            }
            return;
        }
        if ("STICKER".equals(type)) {
            if (count != 1 || !display().getJSONObject("stickerData").getJSONObject("stickerMap").containsKey(id))
                throw new IllegalArgumentException("invalid gallery sticker reward");
            JSONObject stickers = gallery.getJSONObject("stickerMap");
            if (!stickers.containsKey(id)) {
                stickers.put(id, now);
                addItem(granted, id, type, count);
            }
            return;
        }
        throw new IllegalArgumentException("unsupported gallery reward type " + type);
    }

    private static int ownedItemCount(JSONObject user, JSONArray items) {
        int owned = 0;
        for (Object value : items) {
            JSONObject item = (JSONObject) value;
            if (owns(user, item.getString("itemType"), item.getString("itemId"))) owned++;
        }
        return owned;
    }

    static boolean owns(JSONObject user, String type, String id) {
        JSONObject collection;
        switch (type) {
            case "HOME_THEME":
                collection = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "homeTheme"), "themes");
                return unlocked(collection.getJSONObject(id));
            case "HOME_BACKGROUND":
                collection = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "background"), "bgs");
                return unlocked(collection.getJSONObject(id));
            case "PLAYER_AVATAR":
                collection = ShopTransactionSupport.object(ShopTransactionSupport.object(user, "avatar"), "avatar_icon");
                return collection.containsKey(id);
            case "NAMECARD_SKIN":
            case "NAME_CARD_SKIN":
                collection = ShopTransactionSupport.object(ShopTransactionSupport.object(
                        ShopTransactionSupport.object(user, "nameCardStyle"), "skin"), "state");
                JSONObject state = collection.getJSONObject(id);
                return state != null && state.getBooleanValue("unlock");
            case "CHAR_SKIN":
                return ShopTransactionSupport.object(ShopTransactionSupport.object(user, "skin"), "characterSkins").containsKey(id);
            case "STICKER":
                return ShopTransactionSupport.object(ShopTransactionSupport.object(user, "gallery"), "stickerMap").containsKey(id);
            default:
                return false;
        }
    }

    private static boolean unlocked(JSONObject state) {
        return state != null && state.getLongValue("unlock") > 0;
    }

    private static JSONObject galleryResult(JSONObject user) {
        JSONObject modified = new JSONObject(true);
        modified.put("gallery", user.getJSONObject("gallery"));
        return CharacterBuildingSupport.result(modified);
    }

    private static void addItem(JSONArray items, String id, String type, int count) {
        JSONObject item = new JSONObject(true);
        item.put("id", id);
        item.put("type", type);
        item.put("count", count);
        items.add(item);
    }

    private static JSONObject display() {
        return CharacterBuildingSupport.table("excel/display_meta_table.json");
    }

    private static JSONObject saveDiyMagazine(JSONObject user, JSONObject body, Object thumbnail) {
        JSONObject magazine = body == null ? null : body.getJSONObject("magazine");
        if (magazine == null) throw new IllegalArgumentException("invalid magazine");
        String leafId = requiredString(magazine, "leafId");
        JSONObject display = display();
        JSONObject leafDefinitions = display.getJSONObject("magazineLeafData");
        JSONObject leafDefinition = leafDefinitions.getJSONObject("leafMap").getJSONObject(leafId);
        if (leafDefinition == null) throw new IllegalArgumentException("unknown magazine leaf");

        JSONObject gallery = normalize(user);
        JSONObject leaves = gallery.getJSONObject("leafMap");
        if (!leaves.containsKey(leafId)) throw new IllegalArgumentException("magazine leaf is not owned");

        JSONArray decorList = magazine.getJSONArray("decorList");
        if (decorList == null) throw new IllegalArgumentException("invalid magazine decorations");
        JSONObject typeDefinitions = leafDefinitions.getJSONObject("leafDecorTypeMap");
        JSONObject maximums = leafDefinition.getJSONObject("leafDecorMaxNumMap");
        JSONObject blackList = leafDefinitions.getJSONObject("blackListInDiy");
        Map<String, Integer> counts = new LinkedHashMap<>();
        JSONArray cleanDecorList = new JSONArray();
        for (Object value : decorList) {
            if (!(value instanceof JSONObject))
                throw new IllegalArgumentException("invalid magazine decoration");
            JSONObject item = (JSONObject) value;
            String type = requiredString(item, "type");
            JSONObject typeDefinition = typeDefinitions.getJSONObject(type);
            if (typeDefinition == null || "CHAR_SKIN".equals(type))
                throw new IllegalArgumentException("invalid magazine decoration type");
            JSONObject clean = validateElement(user, item, type, typeDefinition);
            String id = clean.getString("id");
            JSONObject typeBlackList = blackList.getJSONObject(type);
            if (typeBlackList != null && typeBlackList.containsKey(id))
                throw new IllegalArgumentException("magazine decoration is blacklisted");
            int count = counts.getOrDefault(type, 0) + 1;
            if (count > maximums.getIntValue(type))
                throw new IllegalArgumentException("too many magazine decorations");
            counts.put(type, count);
            cleanDecorList.add(clean);
        }

        Object charSkinValue = magazine.get("charSkin");
        if (charSkinValue != null && !(charSkinValue instanceof JSONObject))
            throw new IllegalArgumentException("invalid magazine character skin");
        JSONObject charSkin = null;
        if (charSkinValue instanceof JSONObject) {
            JSONObject definition = typeDefinitions.getJSONObject("CHAR_SKIN");
            if (definition == null) throw new IllegalArgumentException("invalid magazine character skin");
            charSkin = validateElement(user, (JSONObject) charSkinValue,
                    "CHAR_SKIN", definition);
        }

        JSONObject oldLeaf = leaves.getJSONObject(leafId);
        long now = System.currentTimeMillis() / 1000L;
        if (oldLeaf == null) oldLeaf = newLeaf(leafId, now, 0);
        JSONObject cleanLeaf = new JSONObject(true);
        cleanLeaf.put("leafId", leafId);
        cleanLeaf.put("decorList", cleanDecorList);
        cleanLeaf.put("charSkin", charSkin);
        cleanLeaf.put("getTs", oldLeaf.getLongValue("getTs"));
        cleanLeaf.put("version", Math.addExact(oldLeaf.getIntValue("version"), 1));
        leaves.put(leafId, cleanLeaf);

        JSONObject thumbnails = ShopTransactionSupport.object(user, THUMBNAIL_FIELD);
        if (thumbnail instanceof String && ((String) thumbnail).isEmpty())
            thumbnails.remove(leafId);
        else if (thumbnail != null)
            thumbnails.put(leafId, thumbnail);
        return galleryResult(user);
    }

    private static JSONObject validateElement(JSONObject user, JSONObject item,
            String expectedType, JSONObject typeDefinition) {
        String id = requiredString(item, "id");
        String type = requiredString(item, "type");
        if (!expectedType.equals(type) || !materialExists(type, id))
            throw new IllegalArgumentException("unknown magazine decoration");
        if (!owns(user, type, id))
            throw new IllegalArgumentException("magazine decoration is not owned");

        Object subValue = item.get("sub");
        if (!(subValue instanceof Number)
                || ((Number) subValue).doubleValue() != ((Number) subValue).intValue())
            throw new IllegalArgumentException("invalid magazine decoration subtype");
        JSONArray position = item.getJSONArray("pos");
        if (position == null || position.size() != 2
                || !(position.get(0) instanceof Number) || !(position.get(1) instanceof Number))
            throw new IllegalArgumentException("invalid magazine decoration position");
        for (Object coordinate : position) {
            double value = ((Number) coordinate).doubleValue();
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("invalid magazine decoration position");
        }
        Object scaleValue = item.get("scale");
        if (!(scaleValue instanceof Number))
            throw new IllegalArgumentException("invalid magazine decoration scale");
        double scale = ((Number) scaleValue).doubleValue();
        if (!Double.isFinite(scale)
                || scale < typeDefinition.getDoubleValue("minScale")
                || scale > typeDefinition.getDoubleValue("maxScale"))
            throw new IllegalArgumentException("invalid magazine decoration scale");

        JSONObject clean = new JSONObject(true);
        clean.put("id", id);
        clean.put("type", type);
        clean.put("sub", ((Number) subValue).intValue());
        clean.put("pos", position);
        clean.put("scale", scale);
        return clean;
    }

    private static boolean materialExists(String type, String id) {
        JSONObject display = display();
        switch (type) {
            case "HOME_THEME":
                return containsId(display.getJSONObject("homeBackgroundData")
                        .getJSONArray("themeList"), id);
            case "HOME_BACKGROUND":
                return containsId(display.getJSONObject("homeBackgroundData")
                        .getJSONArray("homeBgDataList"), "bgId", id);
            case "PLAYER_AVATAR":
                return containsId(display.getJSONObject("playerAvatarData")
                        .getJSONArray("avatarList"), "avatarId", id);
            case "NAME_CARD_SKIN":
                return display.getJSONObject("nameCardV2Data").getJSONObject("skinData")
                        .containsKey(id);
            case "STICKER":
                return display.getJSONObject("stickerData").getJSONObject("stickerMap")
                        .containsKey(id);
            case "CHAR_SKIN":
                return CharacterBuildingSupport.table("excel/skin_table.json")
                        .getJSONObject("charSkins").containsKey(id);
            default:
                return false;
        }
    }

    private static boolean containsId(JSONArray values, String id) {
        return containsId(values, "id", id);
    }

    private static boolean containsId(JSONArray values, String key, String id) {
        if (values == null) return false;
        for (Object value : values) {
            if (value instanceof JSONObject && id.equals(((JSONObject) value).getString(key)))
                return true;
        }
        return false;
    }

    private static void normalizeLeaf(JSONObject leaves, String leafId, long now) {
        Object value = leaves.get(leafId);
        JSONObject leaf = value instanceof JSONObject ? (JSONObject) value : null;
        if (leaf == null) {
            leaf = newLeaf(leafId, now, 1);
            leaves.put(leafId, leaf);
            return;
        }
        if (!leafId.equals(leaf.getString("leafId"))) leaf.put("leafId", leafId);
        if (!(leaf.get("decorList") instanceof JSONArray)) leaf.put("decorList", new JSONArray());
        if (!leaf.containsKey("charSkin")) leaf.put("charSkin", null);
        if (!(leaf.get("getTs") instanceof Number)) leaf.put("getTs", now);
        if (!(leaf.get("version") instanceof Number)) leaf.put("version", 1);
    }

    private static JSONObject newLeaf(String leafId, long now, int version) {
        JSONObject leaf = new JSONObject(true);
        leaf.put("leafId", leafId);
        leaf.put("decorList", new JSONArray());
        leaf.put("charSkin", null);
        leaf.put("getTs", now);
        leaf.put("version", version);
        return leaf;
    }

    private static JSONObject storeThumbnail(byte[] bytes, String contentType) {
        String extension;
        String storedContentType;
        if (isPng(bytes)) {
            extension = "png";
            storedContentType = "image/png";
        } else if (isJpeg(bytes)) {
            extension = "jpg";
            storedContentType = "image/jpeg";
        } else {
            throw new IllegalArgumentException("invalid magazine thumbnail image");
        }
        String fileName = UUID.randomUUID() + "." + extension;
        Path directory = thumbnailDirectory();
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), bytes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("cannot store magazine thumbnail", ex);
        }
        JSONObject record = new JSONObject(true);
        record.put("file", fileName);
        record.put("contentType", storedContentType);
        return record;
    }

    private static MultipartData parseMultipart(String payload, String contentType) {
        String boundary = multipartBoundary(contentType);
        String marker = "--" + boundary;
        MultipartData data = new MultipartData();
        int cursor = payload.indexOf(marker);
        if (cursor < 0) throw new IllegalArgumentException("invalid magazine multipart request");
        while (cursor >= 0) {
            int partStart = cursor + marker.length();
            if (payload.startsWith("--", partStart)) return data;
            if (!payload.startsWith("\r\n", partStart))
                throw new IllegalArgumentException("invalid magazine multipart request");
            partStart += 2;
            int partEnd = payload.indexOf(marker, partStart);
            if (partEnd < 0) throw new IllegalArgumentException("invalid magazine multipart request");
            int contentEnd = partEnd;
            if (contentEnd >= 2 && payload.startsWith("\r\n", contentEnd - 2)) contentEnd -= 2;
            String part = payload.substring(partStart, contentEnd);
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) throw new IllegalArgumentException("invalid magazine multipart request");
            String name = multipartFieldName(part.substring(0, headerEnd));
            byte[] content = part.substring(headerEnd + 4).getBytes(StandardCharsets.ISO_8859_1);
            if ("magazine".equals(name)) {
                data.magazine = JSONObject.parseObject(
                        new String(content, StandardCharsets.UTF_8));
            } else if ("thumbnail".equals(name)) {
                data.thumbnail = content;
            } else if ("thumbnail.jpg".equals(name)) {
                data.jpeg = content;
            }
            cursor = partEnd;
        }
        return data;
    }

    private static String multipartBoundary(String contentType) {
        for (String value : contentType.substring(contentType.indexOf(';') + 1).split(";")) {
            String item = value.trim();
            if (!item.toLowerCase(Locale.ROOT).startsWith("boundary=")) continue;
            String boundary = item.substring("boundary=".length()).trim();
            if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\""))
                boundary = boundary.substring(1, boundary.length() - 1);
            if (!boundary.isEmpty()) return boundary;
        }
        throw new IllegalArgumentException("invalid magazine multipart boundary");
    }

    private static String multipartFieldName(String headers) {
        String disposition = null;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition:"))
                disposition = line.substring(line.indexOf(':') + 1).trim();
        }
        if (disposition == null) return "";
        for (String value : disposition.split(";")) {
            String item = value.trim();
            if (!item.toLowerCase(Locale.ROOT).startsWith("name=")) continue;
            String name = item.substring("name=".length()).trim();
            return name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")
                    ? name.substring(1, name.length() - 1) : name;
        }
        return "";
    }

    private static String thumbnailUrl(Object value, String baseUrl) {
        if (value instanceof String) return (String) value;
        if (!(value instanceof JSONObject)) return "";
        JSONObject record = (JSONObject) value;
        String fileName = record.getString("file");
        if (fileName == null || !STORED_THUMBNAIL.matcher(fileName).matches()) return "";
        String prefix = baseUrl == null || baseUrl.isEmpty() ? "" : baseUrl.replaceAll("/+$", "");
        return prefix + "/gallery/thumbnail/" + fileName;
    }

    private static Path thumbnailDirectory() {
        return BackendPaths.rootFile().toPath().resolve(THUMBNAIL_DIR_NAME);
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }

    private static String requiredString(JSONObject body, String key) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new IllegalArgumentException("invalid " + key);
        return (String) value;
    }

    private static final class MultipartData {
        JSONObject magazine;
        byte[] thumbnail;
        byte[] jpeg;
    }
}
