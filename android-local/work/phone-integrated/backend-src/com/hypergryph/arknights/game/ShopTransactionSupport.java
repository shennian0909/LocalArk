package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mobile shop transactions. Work on a detached account JSON and persist only on success. */
public final class ShopTransactionSupport {
    private ShopTransactionSupport() {}
    interface Transaction { JSONObject apply(JSONObject user); }

    static synchronized JSONObject execute(HttpServletResponse response, HttpServletRequest request, Transaction tx) {
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return error("server is close");
        }
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (secret == null || secret.isEmpty()) return error("account not found");
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts == null || accounts.size() != 1) return error("account not found");
        Account account = accounts.get(0);
        if (account.getBan() == 1L) { response.setStatus(403); return error("account banned"); }
        try {
            JSONObject user = JSONObject.parseObject(account.getUser());
            JSONObject missionBefore = CharacterBuildingSupport.copy(user.getJSONObject("mission"));
            JSONObject result = tx.apply(user);
            JSONObject delta = result.getJSONObject("playerDataDelta");
            if (result.getIntValue("result") == 0 && delta != null
                    && delta.getJSONObject("modified").containsKey("mission"))
                MissionProgressSupport.writeDelta(user, delta, missionBefore);
            if (result.getIntValue("result") == 0 && userDao.setUserData(account.getUid(), user) != 1)
                return error("account update failed");
            return result;
        } catch (IllegalArgumentException | ArithmeticException e) {
            return error(e.getMessage());
        }
    }

    static JSONObject error(String message) {
        JSONObject result = new JSONObject(true);
        result.put("result", 2);
        result.put("error", message);
        result.put("playerDataDelta", JSONObject.parseObject("{\"modified\":{},\"deleted\":{}}"));
        return result;
    }

    /**
     * Applies one purchase event on the successful path only and, when the row moved, carries
     * the mission section back to the client. Callers run this after every debit and grant, so
     * a later failure can never leave progress behind.
     */
    static JSONObject withMissionEvent(JSONObject result, JSONObject user, String event) {
        boolean missionChanged = MissionProgressSupport.onAction(user, event, new JSONObject(),
                System.currentTimeMillis() / 1000L);
        if (missionChanged) MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"));
        return result;
    }

    static JSONObject object(JSONObject parent, String key) {
        JSONObject value = parent.getJSONObject(key);
        if (value == null) { value = new JSONObject(true); parent.put(key, value); }
        return value;
    }

    static JSONObject success(JSONObject user, JSONArray items) {
        JSONObject modified = new JSONObject(true);
        // nameCardStyle is included so a granted namecard is pushed to the client, not only
        // written to the stored save.
        for (String key : new String[]{"status", "inventory", "shop", "troop", "skin", "building", "consumable", "social", "homeTheme", "background", "nameCardStyle"}) {
            if (user.containsKey(key)) modified.put(key, user.get(key));
        }
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified); delta.put("deleted", new JSONObject(true));
        JSONObject result = new JSONObject(true);
        result.put("result", 0); result.put("items", items); result.put("playerDataDelta", delta);
        return result;
    }

    static int count(JSONObject body) {
        Object value = body.get("count");
        if (value == null || !value.toString().matches("[0-9]+")) throw new IllegalArgumentException("invalid count");
        int count = Integer.parseInt(value.toString());
        if (count <= 0 || count > 10000) throw new IllegalArgumentException("invalid count");
        return count;
    }

    static JSONObject find(JSONArray goods, String id) {
        if (goods != null && id != null) for (int i = 0; i < goods.size(); i++) {
            JSONObject good = goods.getJSONObject(i);
            if (id.equals(good.getString("goodId"))) return good;
        }
        throw new IllegalArgumentException("unknown good");
    }

    static JSONObject findGroup(JSONArray groups, String id) {
        if (groups != null && id != null) for (int i = 0; i < groups.size(); i++) {
            JSONObject group = groups.getJSONObject(i);
            if (id.equals(group.getString("packageId"))) return group;
        }
        throw new IllegalArgumentException("unknown furniture group");
    }

    static JSONObject record(JSONObject state, String id) {
        JSONArray info = state.getJSONArray("info");
        if (info == null) { info = new JSONArray(); state.put("info", info); }
        for (int i = 0; i < info.size(); i++) if (id.equals(info.getJSONObject(i).getString("id"))) return info.getJSONObject(i);
        JSONObject row = new JSONObject(true); row.put("id", id); row.put("count", 0); info.add(row); return row;
    }

    static void limit(JSONObject row, int limit, int count) {
        if (row.getIntValue("count") < 0 || (limit >= 0 && (long)row.getIntValue("count") + count > limit))
            throw new IllegalArgumentException("purchase limit reached");
        row.put("count", Math.addExact(row.getIntValue("count"), count));
    }

    static void debit(JSONObject balance, String key, long amount) {
        long available = balance.getLongValue(key);
        if (amount < 0 || amount > Integer.MAX_VALUE || available < amount) throw new IllegalArgumentException("insufficient " + key);
        balance.put(key, available - amount);
    }

    static void add(JSONObject balance, String key, int count) {
        long value = balance.getLongValue(key) + count;
        if (count < 0 || value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("reward overflow");
        balance.put(key, value);
    }

    static void reward(JSONObject user, JSONObject item, int multiplier, JSONArray items) {
        reward(user, item, multiplier, items, false);
    }

    /**
     * Whether the existing grant writer can actually hand out a reward type. Callers use
     * this to surface activity rewards that would otherwise throw, instead of finding out
     * at claim time.
     * Kept in step with reward(): a type listed here must be one reward() handles.
     */
    static boolean supports(String type) {
        if (type == null) return false;
        switch (type) {
            case "GOLD", "DIAMOND_SHD", "HGG_SHD", "LGG_SHD", "TKT_GACHA", "TKT_GACHA_10",
                 "TKT_RECRUIT", "TKT_INST_FIN", "SOCIAL_PT", "CLASSIC_TKT_GACHA",
                 "CLASSIC_TKT_GACHA_10", "CLASSIC_SHD", "MATERIAL", "CARD_EXP", "RENAMING_CARD",
                 "TKT_GACHA_PRSV", "AP_SUPPLY", "RETRO_COIN", "DIAMOND", "FURN", "HOME_THEME",
                 "HOME_BACKGROUND", "CHAR_SKIN", "VOUCHER_PICK", "OPTIONAL_VOUCHER_PICK",
                 "VOUCHER_CGACHA", "CHAR", "VOUCHER_MGACHA",
                 "VOUCHER_SKILL_SPECIALLEVELMAX_5", "NAME_CARD_SKIN", "EXTERMINATION_AGENT",
                 "VOUCHER_ELITE_II_5", "VOUCHER_LEVELMAX_5", "MATERIAL_ISSUE_VOUCHER" -> { return true; }
            default -> { return false; }
        }
    }

    static void reward(JSONObject user, JSONObject item, int multiplier, JSONArray items,
                       boolean classicCertificate) {
        if (item == null || item.getString("id") == null || item.getString("type") == null || item.getIntValue("count") <= 0)
            throw new IllegalArgumentException("unsupported reward");
        String id = item.getString("id"), type = item.getString("type");
        int amount = Math.multiplyExact(item.getIntValue("count"), multiplier);
        JSONObject status = object(user, "status");
        String field = switch (type) {
            case "GOLD" -> "gold"; case "DIAMOND_SHD" -> "diamondShard";
            case "HGG_SHD" -> "hggShard"; case "LGG_SHD" -> "lggShard";
            case "TKT_GACHA" -> "gachaTicket"; case "TKT_GACHA_10" -> "tenGachaTicket";
            case "TKT_RECRUIT" -> "recruitLicense"; case "TKT_INST_FIN" -> "instantFinishTicket";
            case "SOCIAL_PT" -> "socialPoint"; case "CLASSIC_TKT_GACHA" -> "classicGachaTicket";
            case "CLASSIC_TKT_GACHA_10" -> "classicTenGachaTicket";
            case "CLASSIC_SHD" -> "classicShard"; default -> null;
        };
        if (field != null) add(status, field, amount);
        else switch (type) {
            case "MATERIAL", "CARD_EXP", "RENAMING_CARD", "TKT_GACHA_PRSV", "AP_SUPPLY", "RETRO_COIN" -> add(object(user, "inventory"), id, amount);
            case "DIAMOND" -> { add(status, "androidDiamond", amount); add(status, "iosDiamond", amount); }
            case "FURN" -> {
                JSONObject furn = object(object(object(user, "building"), "furniture"), id);
                add(furn, "count", amount); if (!furn.containsKey("inUse")) furn.put("inUse", 0);
            }
            case "HOME_THEME", "HOME_BACKGROUND" -> {
                if (amount != 1) throw new IllegalArgumentException("unique reward count");
                JSONObject collection = object(object(user, type.equals("HOME_THEME") ? "homeTheme" : "background"), type.equals("HOME_THEME") ? "themes" : "bgs");
                if (collection.getJSONObject(id) != null && collection.getJSONObject(id).containsKey("unlock")) throw new IllegalArgumentException("reward already owned " + id);
                JSONObject owned = new JSONObject(true); owned.put("unlock", System.currentTimeMillis() / 1000L);
                collection.put(id, owned);
            }
            case "CHAR_SKIN" -> {
                if (amount != 1) throw new IllegalArgumentException("unique reward count");
                if (object(object(user, "skin"), "characterSkins").containsKey(id)) throw new IllegalArgumentException("reward already owned " + id);
                object(object(user, "skin"), "characterSkins").put(id, 1);
                object(object(user, "skin"), "skinTs").put(id, System.currentTimeMillis() / 1000L);
            }
            case "VOUCHER_PICK", "OPTIONAL_VOUCHER_PICK", "VOUCHER_CGACHA", "EXTERMINATION_AGENT" -> {
                JSONObject bucket = object(object(object(user, "consumable"), id), "0");
                add(bucket, "count", amount); bucket.put("ts", -1);
            }
            // Consumable vouchers stocked in the same consumable[id]["0"] slot as VOUCHER_PICK.
            // Only storage is implemented; redeeming a random-material box or spending a skill
            // voucher is a separate flow that is deliberately out of scope here.
            case "VOUCHER_MGACHA", "VOUCHER_SKILL_SPECIALLEVELMAX_5",
                 "VOUCHER_ELITE_II_5", "VOUCHER_LEVELMAX_5", "MATERIAL_ISSUE_VOUCHER" -> {
                JSONObject bucket = object(object(object(user, "consumable"), id), "0");
                add(bucket, "count", amount); bucket.put("ts", -1);
            }
            case "NAME_CARD_SKIN" -> {
                if (amount != 1) throw new IllegalArgumentException("unique reward count");
                JSONObject state = object(object(object(user, "nameCardStyle"), "skin"), "state");
                JSONObject owned = state.getJSONObject(id);
                if (owned != null && owned.getBooleanValue("unlock")) throw new IllegalArgumentException("reward already owned " + id);
                // Unlocked outright: activity namecards ship no progress track.
                JSONObject entry = new JSONObject(true);
                entry.put("unlock", true);
                entry.put("progress", null);
                entry.put("unlockTs", System.currentTimeMillis() / 1000L);
                state.put(id, entry);
            }
            case "CHAR" -> {
                if (ArknightsApplication.characterJson == null || !ArknightsApplication.characterJson.containsKey(id))
                    throw new IllegalArgumentException("unknown character reward " + id);
                for (int i = 0; i < amount; i++) character(user, id, items, classicCertificate);
                return;
            }
            default -> throw new IllegalArgumentException("unsupported reward type " + type);
        }
        JSONObject granted = new JSONObject(true); granted.put("id", id); granted.put("type", type); granted.put("count", amount); items.add(granted);
    }

    // Account saves use sparse instance IDs. The shared GM helper assumes 1..size.
    private static int tableIndex(JSONObject data, String key, String prefix) {
        String value = data.getString(key);
        if (value == null) throw new IllegalArgumentException("missing character " + key);
        return value.startsWith(prefix) ? Integer.parseInt(value.substring(prefix.length())) - (prefix.equals("TIER_") ? 1 : 0) : Integer.parseInt(value);
    }

    private static void character(JSONObject user, String id, JSONArray items,
                                  boolean classicCertificate) {
        JSONObject troop = object(user, "troop"), chars = object(troop, "chars");
        JSONObject data = ArknightsApplication.characterJson.getJSONObject(id), owned = null;
        int maxId = 0;
        for (String key : chars.keySet()) {
            maxId = Math.max(maxId, Integer.parseInt(key));
            if (id.equals(chars.getJSONObject(key).getString("charId"))) owned = chars.getJSONObject(key);
        }
        boolean isNew = owned == null;
        if (isNew) {
            int instId = Math.max(Math.addExact(maxId, 1), troop.getIntValue("curCharInstId"));
            owned = JSONObject.parseObject("{\"favorPoint\":0,\"potentialRank\":0,\"mainSkillLvl\":1,\"level\":1,\"exp\":0,\"evolvePhase\":0}");
            owned.put("instId", instId); owned.put("charId", id); owned.put("skin", id + "#1"); owned.put("gainTime", System.currentTimeMillis() / 1000L);
            JSONArray skills = new JSONArray(), definitions = data.getJSONArray("skills");
            if (definitions == null) throw new IllegalArgumentException("unsupported character skills " + id);
            for (int i = 0; i < definitions.size(); i++) {
                JSONObject definition = definitions.getJSONObject(i);
                JSONObject skill = JSONObject.parseObject("{\"state\":0,\"specializeLevel\":0,\"completeUpgradeTime\":-1}");
                skill.put("skillId", definition.getString("skillId")); skill.put("unlock", tableIndex(definition.getJSONObject("unlockCond"), "phase", "PHASE_") == 0 ? 1 : 0); skills.add(skill);
            }
            owned.put("skills", skills); owned.put("defaultSkillIndex", skills.isEmpty() ? -1 : 0);
            JSONObject voices = ArknightsApplication.charwordTable == null ? null : ArknightsApplication.charwordTable.getJSONObject("charDefaultTypeDict");
            owned.put("voiceLan", voices == null ? "CN_MANDARIN" : voices.getString(id));
            owned.put("currentEquip", null);
            if (ArknightsApplication.uniequipTable != null) {
                JSONObject equip = new JSONObject(true);
                String suffix = id.substring(id.indexOf('_', 5) + 1);
                for (String equipId : ArknightsApplication.uniequipTable.keySet()) {
                    if (equipId.startsWith("uniequip_") && equipId.endsWith("_" + suffix)) {
                        JSONObject module = JSONObject.parseObject("{\"hide\":0,\"locked\":1,\"level\":1}");
                        if (equipId.startsWith("uniequip_001_")) { module.put("locked", 0); owned.put("currentEquip", equipId); }
                        equip.put(equipId, module);
                    }
                }
                owned.put("equip", equip);
            }
            chars.put(String.valueOf(instId), owned); troop.put("curCharInstId", Math.addExact(instId, 1));
            object(troop, "charGroup").put(id, JSONObject.parseObject("{\"favorPoint\":0}"));
            JSONObject worker = JSONObject.parseObject("{\"ap\":8640000,\"roomSlotId\":\"\",\"index\":-1,\"changeScale\":0,\"workTime\":0,\"bubble\":{\"normal\":{\"add\":-1,\"ts\":0},\"assist\":{\"add\":-1,\"ts\":-1}}}");
            worker.put("charId", id); worker.put("lastApAddTime", System.currentTimeMillis() / 1000L);
            object(object(user, "building"), "chars").put(String.valueOf(instId), worker);
        }
        int rarity = tableIndex(data, "rarity", "TIER_"), potential = owned.getIntValue("potentialRank");
        JSONArray extras = new JSONArray();
        if (classicCertificate) {
            ClassicCertificateRewards.grant(user, rarity, isNew, extras);
        } else {
            boolean high = rarity >= 4;
            int certificates = isNew ? 1
                    : rarity == 5 ? (potential >= 5 ? 15 : 10) : rarity == 4 ? (potential >= 5 ? 8 : 5) : rarity == 3 ? 30 : rarity == 2 ? 5 : 1;
            JSONObject certificate = new JSONObject(true);
            certificate.put("id", high ? "4004" : "4005"); certificate.put("type", high ? "HGG_SHD" : "LGG_SHD"); certificate.put("count", certificates);
            reward(user, certificate, 1, extras);
        }
        if (!isNew) {
            if (classicCertificate) {
                ClassicCertificateRewards.grantDuplicatePotentialItem(user, data, extras);
            } else {
                JSONObject token = new JSONObject(true);
                token.put("id", "p_" + id);
                token.put("type", "MATERIAL");
                token.put("count", 1);
                reward(user, token, 1, extras);
            }
        }
        JSONObject get = new JSONObject(true); get.put("charInstId", owned.getIntValue("instId")); get.put("charId", id); get.put("isNew", isNew ? 1 : 0); get.put("itemGet", extras);
        JSONObject item = new JSONObject(true); item.put("id", id); item.put("type", "CHAR"); item.put("charGet", get); items.add(item);
    }

    static JSONObject buyHigh(JSONObject user, JSONObject body, JSONObject catalog) {
        int count = count(body);
        JSONObject good = find(catalog.getJSONArray("goodList"), body.getString("goodId"));
        JSONObject state = object(object(user, "shop"), "HS");
        JSONObject row = record(state, good.getString("goodId"));
        JSONArray items = new JSONArray();
        long cost = 0;
        if ("PROGRESS".equals(good.getString("goodType"))) {
            String progressId = good.getString("progressGoodId");
            JSONArray stages = object(catalog, "progressGoodList").getJSONArray(progressId);
            int lastOrder = lastProgressOrder(stages);
            JSONObject progress = object(object(state, "progressInfo"), progressId);
            int order = progress.containsKey("order") ? progress.getIntValue("order") : 1;
            if (count > stages.size() || order < 1 || (long)order + count - 1 > lastOrder
                    || (order == lastOrder && progress.getIntValue("count") >= 1))
                throw new IllegalArgumentException("purchase limit reached");
            for (int n = 0; n < count; n++) {
                JSONObject stage = null;
                for (int i = 0; i < stages.size(); i++) if (stages.getJSONObject(i).getIntValue("order") == order + n) stage = stages.getJSONObject(i);
                if (stage == null || stage.getIntValue("price") < 0) throw new IllegalArgumentException("invalid progress stage");
                cost += stage.getIntValue("price");
            }
            debit(object(user, "status"), "hggShard", cost);
            for (int n = 0; n < count; n++) for (int i = 0; i < stages.size(); i++)
                if (stages.getJSONObject(i).getIntValue("order") == order + n) reward(user, stages.getJSONObject(i).getJSONObject("item"), 1, items);
            // Keep the last catalog stage addressable after its one purchase is exhausted.
            progress.put("order", Math.min(order + count, lastOrder));
            progress.put("count", order + count > lastOrder ? 1 : 0);
            limit(row, -1, count);
        } else {
            limit(row, good.getIntValue("availCount"), count);
            cost = (long)good.getIntValue("price") * count;
            debit(object(user, "status"), "hggShard", cost);
            reward(user, good.getJSONObject("item"), count, items);
        }
        // High certificates are one of the three zones the type-0 BuyShopItem task names.
        return withMissionEvent(success(user, items), user, "certificatePurchase");
    }

    static JSONObject buyClassic(JSONObject user, JSONObject body, JSONObject catalog) {
        int count = count(body);
        JSONObject good = find(catalog.getJSONArray("goodList"), body.getString("goodId"));
        JSONObject state = object(object(user, "shop"), "CLASSIC");
        JSONObject row = record(state, good.getString("goodId"));
        JSONArray items = new JSONArray();
        long cost = 0;
        if ("PROGRESS".equals(good.getString("goodType"))) {
            String progressId = good.getString("progressGoodId");
            JSONArray stages = object(catalog, "progressGoodList").getJSONArray(progressId);
            int lastOrder = lastProgressOrder(stages);
            JSONObject progress = object(object(state, "progressInfo"), progressId);
            int order = progress.containsKey("order") ? progress.getIntValue("order") : 1;
            if (order < 1 || count > stages.size() || (long)order + count - 1 > lastOrder
                    || (order == lastOrder && progress.getIntValue("count") >= 1))
                throw new IllegalArgumentException("purchase limit reached");
            for (int n = 0; n < count; n++) {
                JSONObject stage = null;
                for (int i = 0; i < stages.size(); i++)
                    if (stages.getJSONObject(i).getIntValue("order") == order + n)
                        stage = stages.getJSONObject(i);
                if (stage == null || stage.getIntValue("price") < 0)
                    throw new IllegalArgumentException("invalid progress stage");
                cost = Math.addExact(cost, stage.getIntValue("price"));
            }
            debit(object(user, "status"), "classicShard", cost);
            for (int n = 0; n < count; n++) for (int i = 0; i < stages.size(); i++)
                if (stages.getJSONObject(i).getIntValue("order") == order + n)
                    reward(user, stages.getJSONObject(i).getJSONObject("item"), 1, items, true);
            progress.put("order", Math.min(order + count, lastOrder));
            progress.put("count", order + count > lastOrder ? 1 : 0);
            limit(row, -1, count);
        } else {
            limit(row, good.getIntValue("availCount"), count);
            cost = Math.multiplyExact((long)good.getIntValue("price"), count);
            debit(object(user, "status"), "classicShard", cost);
            reward(user, good.getJSONObject("item"), count, items, true);
        }
        return success(user, items);
    }

    /** Only migrate existing out-of-catalog terminal rows; balances and purchase totals stay intact. */
    static boolean repairProgressTerminals(JSONObject user, String shopType, JSONObject catalog) {
        JSONObject shops = user.getJSONObject("shop");
        JSONObject state = shops == null ? null : shops.getJSONObject(shopType);
        JSONObject progressInfo = state == null ? null : state.getJSONObject("progressInfo");
        if (progressInfo == null) return false;

        boolean changed = false;
        JSONArray goods = catalog.getJSONArray("goodList");
        JSONObject progressGoods = catalog.getJSONObject("progressGoodList");
        for (int i = 0; i < goods.size(); i++) {
            JSONObject good = goods.getJSONObject(i);
            if (!"PROGRESS".equals(good.getString("goodType"))) continue;
            String progressId = good.getString("progressGoodId");
            JSONObject progress = progressInfo.getJSONObject(progressId);
            if (progress == null) continue;
            JSONArray stages = progressGoods.getJSONArray(progressId);
            if (stages == null || stages.isEmpty()) continue;
            int lastOrder = lastProgressOrder(stages);
            if (progress.getIntValue("order") > lastOrder) {
                progress.put("order", lastOrder);
                progress.put("count", 1);
                changed = true;
            }
        }
        return changed;
    }

    private static int lastProgressOrder(JSONArray stages) {
        if (stages == null || stages.isEmpty())
            throw new IllegalArgumentException("unsupported progress good");
        int lastOrder = 0;
        for (int i = 0; i < stages.size(); i++)
            lastOrder = Math.max(lastOrder, stages.getJSONObject(i).getIntValue("order"));
        return lastOrder;
    }

    static JSONObject buySocial(JSONObject user, JSONObject body, JSONObject catalog) {
        int count = count(body);
        JSONObject good = find(catalog.getJSONArray("goodList"), body.getString("goodId"));
        JSONObject row = record(object(object(user, "shop"), "SOCIAL"), good.getString("goodId"));
        limit(row, good.getIntValue("availCount"), count);
        debit(object(user, "status"), "socialPoint", (long)good.getIntValue("price") * count);
        JSONArray items = new JSONArray();
        reward(user, good.getJSONObject("item"), count, items);
        // The credit shop is the type-1 BuyShopItem zone and never the certificate one.
        return withMissionEvent(success(user, items), user, "creditPurchase");
    }

    static JSONObject buyFurniture(JSONObject user, JSONObject body, JSONObject catalog) {
        int count = count(body);
        JSONObject good = find(catalog.getJSONArray("goods"), body.getString("goodId"));
        String currency = body.getString("currencyUnit");
        if (currency == null) currency = normalizeFurnitureCurrency(body.getString("costType"));
        if (currency == null) currency = body.containsKey("type") && body.getIntValue("type") == 1 ? "DIAMOND" : "FURNITURE";
        if (body.containsKey("type") && body.getIntValue("type") != 0 && body.getIntValue("type") != 1) throw new IllegalArgumentException("unsupported currency");
        JSONObject row = record(object(object(user, "shop"), "FURNI"), good.getString("goodId"));
        limit(row, good.getIntValue("count"), count);
        if ("FURNITURE".equals(currency)) {
            if (good.getIntValue("priceCoin") <= 0) throw new IllegalArgumentException("furniture currency unavailable");
            debit(object(user, "inventory"), "3401", (long)good.getIntValue("priceCoin") * count);
        } else if ("DIAMOND".equals(currency)) {
            if (good.getIntValue("priceDia") <= 0) throw new IllegalArgumentException("diamond currency unavailable");
            debit(object(user, "status"), "androidDiamond", (long)good.getIntValue("priceDia") * count);
        } else throw new IllegalArgumentException("unsupported currency");
        JSONObject item = new JSONObject(true); item.put("id", good.getString("furniId")); item.put("type", "FURN"); item.put("count", 1);
        JSONArray items = new JSONArray(); reward(user, item, count, items); return success(user, items);
    }

    static JSONObject buyFurnitureGroup(JSONObject user, JSONObject body, JSONObject catalog) {
        // Real client sends costType=COIN_FURN and goods[].id; FURNITURE/goodId are legacy.
        String currency = normalizeFurnitureCurrency(body.getString("costType"));
        if (currency == null) throw new IllegalArgumentException("unsupported currency");
        JSONObject group = findGroup(catalog.getJSONArray("groups"), body.getString("groupId"));
        JSONArray groupLines = group.getJSONArray("goodList"), requestedLines = body.getJSONArray("goods");
        if (groupLines == null || groupLines.isEmpty() || requestedLines == null || requestedLines.isEmpty())
            throw new IllegalArgumentException("invalid furniture group goods");

        Map<String, Integer> expected = new LinkedHashMap<>();
        for (int i = 0; i < groupLines.size(); i++) {
            JSONObject line = groupLines.getJSONObject(i);
            String goodId = line.getString("goodId");
            if (goodId == null || expected.put(goodId, count(line)) != null)
                throw new IllegalArgumentException("invalid furniture group catalog");
        }
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (int i = 0; i < requestedLines.size(); i++) {
            JSONObject line = requestedLines.getJSONObject(i);
            // Real client sends {"count":1,"id":"event2_1"}; legacy/assumed shape was {"goodId":..}.
            String goodId = line.getString("id");
            if (goodId == null) goodId = line.getString("goodId");
            if (goodId == null || requested.put(goodId, count(line)) != null)
                throw new IllegalArgumentException("duplicate good");
        }
        if (!expected.equals(requested)) throw new IllegalArgumentException("furniture group goods mismatch");

        // Validate every line and total balance before the per-line helper mutates the detached user.
        long total = 0;
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            JSONObject good = find(catalog.getJSONArray("goods"), entry.getKey());
            int price = "FURNITURE".equals(currency) ? good.getIntValue("priceCoin") : good.getIntValue("priceDia");
            if (price <= 0) throw new IllegalArgumentException("furniture currency unavailable");
            total = Math.addExact(total, Math.multiplyExact((long)price, entry.getValue()));
        }
        String balanceKey = "FURNITURE".equals(currency) ? "3401" : "androidDiamond";
        JSONObject balance = "FURNITURE".equals(currency) ? object(user, "inventory") : object(user, "status");
        if (total > Integer.MAX_VALUE || balance.getLongValue(balanceKey) < total)
            throw new IllegalArgumentException("insufficient " + balanceKey);

        JSONArray items = new JSONArray();
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            JSONObject line = new JSONObject(true);
            line.put("goodId", entry.getKey());
            line.put("count", entry.getValue());
            line.put("currencyUnit", currency);
            items.addAll(buyFurniture(user, line, catalog).getJSONArray("items"));
        }
        return success(user, items);
    }

    /** Client CostType enum members are COIN_FURN and DIAMOND; FURNITURE is the pre-capture legacy spelling. */
    static String normalizeFurnitureCurrency(String costType) {
        if (costType == null) return null;
        switch (costType) {
            case "COIN_FURN":
            case "FURNITURE":
                return "FURNITURE";
            case "DIAMOND":
                return "DIAMOND";
            default:
                return null;
        }
    }

    static JSONObject buyPack(JSONObject user, JSONObject body, JSONObject catalog) {
        int count = body.containsKey("count") ? count(body) : 1;
        String id = body.getString("goodId");
        JSONObject good = null; String group = null;
        for (String key : new String[]{"levelGP", "oneTimeGP"}) {
            try { good = find(catalog.getJSONArray(key), id); group = key.equals("levelGP") ? "level" : "oneTime"; break; }
            catch (IllegalArgumentException ignored) { }
        }
        if (good == null) throw new IllegalArgumentException("unsupported pack: only diamond level/one-time packs");
        if (!"DIAMOND".equals(good.getString("currencyUnit")) || (body.containsKey("currencyUnit") && !"DIAMOND".equals(body.getString("currencyUnit"))))
            throw new IllegalArgumentException("cash payments are unsupported");
        if (good.getIntValue("playerLevel") > object(user, "status").getIntValue("level")) throw new IllegalArgumentException("player level too low");
        JSONObject row = record(object(object(object(user, "shop"), "GP"), group), id);
        limit(row, good.getIntValue("availCount"), count);
        JSONArray rewards = good.getJSONArray("items"), items = new JSONArray();
        if (rewards == null || rewards.isEmpty()) throw new IllegalArgumentException("unsupported pack rewards");
        debit(object(user, "status"), "androidDiamond", (long)good.getIntValue("price") * count);
        for (int i = 0; i < rewards.size(); i++) reward(user, rewards.getJSONObject(i), count, items);
        JSONObject result = success(user, items);
        JSONObject received = new JSONObject(true); received.put("items", items); result.put("receiveItems", received);
        return result;
    }

    static JSONObject receiveCredit(JSONObject user) {
        JSONObject yesterday = object(object(user, "social"), "yesterdayReward");
        if (yesterday.getIntValue("canReceive") != 1) throw new IllegalArgumentException("credit already collected");
        int assist = yesterday.getIntValue("assistAmount"), comfort = yesterday.getIntValue("comfortAmount");
        if (assist < 0 || comfort < 0) throw new IllegalArgumentException("invalid credit reward");
        int amount = Math.addExact(assist, comfort);
        add(object(user, "status"), "socialPoint", amount);
        yesterday.put("canReceive", 0); yesterday.put("first", 0);
        JSONArray items = new JSONArray();
        JSONObject item = new JSONObject(true); item.put("id", "4009"); item.put("type", "SOCIAL_PT"); item.put("count", amount); items.add(item);
        // The credit tasks split by templateType: type 1 counts collections, type 0 counts the
        // credited amount, so the event carries the real amount the player was granted.
        JSONObject context = new JSONObject(true);
        context.put("amount", amount);
        boolean missionChanged = amount > 0 && MissionProgressSupport.onAction(user, "creditCollected", context,
                System.currentTimeMillis() / 1000L);
        JSONObject result = success(user, items);
        result.put("credit", amount);
        if (missionChanged) MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"));
        return result;
    }
}
