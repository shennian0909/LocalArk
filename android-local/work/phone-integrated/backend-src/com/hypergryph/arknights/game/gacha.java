package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.file.IOTools;
import com.hypergryph.arknights.core.pojo.Account;
import com.codex.arklocal.BackendPaths;
import com.codex.arklocal.LocalGachaOptions;
import com.codex.arklocal.LocalGachaOptions.ChosenPool;

import java.util.*;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.*;

import static com.hypergryph.arknights.core.work.checkAccount.checkAccount;
import static com.hypergryph.arknights.core.work.checkAccount.createError;
import static com.hypergryph.arknights.core.work.consumableNumberG.getInnerNumber;
import static com.hypergryph.arknights.core.work.consumableNumberG.getInnerObject;
import static com.hypergryph.arknights.core.work.processArray.handleTenGachaCould;
import static com.hypergryph.arknights.core.work.processArray.handleTenGachaItem;

@RestController
@RequestMapping("/gacha")
public class gacha {
    private final ArknightsApplication arknightsApplication;

    /** Reflection-friendly construction without a Spring container. */
    public gacha() { this(new ArknightsApplication()); }

    public gacha(ArknightsApplication arknightsApplication) {
        this.arknightsApplication = arknightsApplication;
    }

    // 配置文件路径


    @PostMapping("/syncNormalGacha")
    public JSONObject syncNormalGacha(@RequestBody JSONObject JsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/syncNormalGacha");
        ArknightsApplication.LOGGER.info("syncNG" + JsonBody);
        String secret = ArknightsApplication.getSecretByIP(clientIp);

        if (checkAccount(secret)) {
            List<Account> accounts = userDao.queryAccountBySecret(secret);

            Account account = accounts.get(0);
            JSONObject userData = JSONObject.parseObject(account.getUser());
            if (NormalRecruitSupport.syncRecruitSlots(userData)
                    && userDao.setUserData(account.getUid(), userData) != 1) {
                return ShopTransactionSupport.error("account update failed");
            }
            JSONObject result = new JSONObject();
            JSONObject playerDataDelta = new JSONObject();
            JSONObject modified = new JSONObject();
            modified.put("recruit", userData.get("recruit"));
            playerDataDelta.put("modified", modified);
            playerDataDelta.put("deleted", new JSONObject());
            result.put("playerDataDelta", playerDataDelta);
            return result;
        }
        else {
            createError(secret, response);
        }

        return new JSONObject();
    }

    @PostMapping("/cancelNormalGacha")
    public JSONObject cancelNormalGacha(@RequestBody JSONObject body, HttpServletRequest request,
                                        HttpServletResponse response) {
        if (body == null || !body.containsKey("slotId")) {
            response.setStatus(400);
            return ShopTransactionSupport.error("slotId is required");
        }
        int slotId = body.getIntValue("slotId");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject slot = user.getJSONObject("recruit").getJSONObject("normal")
                    .getJSONObject("slots").getJSONObject(String.valueOf(slotId));
            if (slot == null || slot.getIntValue("state") != 2) {
                throw new IllegalArgumentException("招募位当前没有进行中的招募");
            }
            slot.put("state", 1);
            for (String key : new String[]{"durationInSec", "startTs", "realFinishTs", "maxFinishTs", "specialTagId"}) {
                slot.put(key, -1);
            }
            slot.put("selectTags", new JSONArray());
            // The client's confirmation says cancellation forfeits resources and refreshes tags.
            slot.put("tags", NormalRecruitSupport.refreshedTags());
            JSONObject modified = new JSONObject(true);
            modified.put("recruit", user.getJSONObject("recruit"));
            JSONObject delta = new JSONObject(true);
            delta.put("modified", modified);
            delta.put("deleted", new JSONObject(true));
            JSONObject result = new JSONObject(true);
            result.put("result", 0);
            result.put("playerDataDelta", delta);
            return result;
        });
    }

    @PostMapping("/refreshTags")
    public JSONObject refreshTags(@RequestBody JSONObject body, HttpServletRequest request,
                                  HttpServletResponse response) {
        if (body == null || !body.containsKey("slotId")) {
            response.setStatus(400);
            return ShopTransactionSupport.error("slotId is required");
        }
        int slotId = body.getIntValue("slotId");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject slot = user.getJSONObject("recruit").getJSONObject("normal")
                    .getJSONObject("slots").getJSONObject(String.valueOf(slotId));
            if (slot == null || slot.getIntValue("state") != 1) {
                throw new IllegalArgumentException("招募位当前不可刷新");
            }
            JSONObject hireRooms = user.getJSONObject("building").getJSONObject("rooms")
                    .getJSONObject("HIRE");
            if (hireRooms == null || hireRooms.isEmpty()) {
                throw new IllegalArgumentException("请先建造人力办公室");
            }
            JSONObject office = hireRooms.getJSONObject(hireRooms.keySet().iterator().next());
            int remaining = office.getIntValue("refreshCount");
            if (remaining <= 0) throw new IllegalArgumentException("人脉联络次数不足");

            slot.put("tags", NormalRecruitSupport.refreshedTags());
            slot.put("selectTags", new JSONArray());
            office.put("refreshCount", remaining - 1);
            double limit = CharacterBuildingSupport.table("excel/building_data.json").getDoubleValue("humanResourceLimit");
            if (office.getDoubleValue("processPoint") >= limit) {
                long now = System.currentTimeMillis() / 1000L;
                office.put("processPoint", 0D);
                office.put("lastUpdateTime", now);
                office.put("state", 1);
                office.put("completeWorkTime", now + (long) Math.ceil(limit / office.getDoubleValue("speed")));
            }

            JSONObject modified = new JSONObject(true);
            modified.put("recruit", user.getJSONObject("recruit"));
            JSONObject rooms = new JSONObject(true);
            rooms.put("HIRE", hireRooms);
            JSONObject building = new JSONObject(true);
            building.put("rooms", rooms);
            modified.put("building", building);
            JSONObject delta = new JSONObject(true);
            delta.put("modified", modified);
            delta.put("deleted", new JSONObject(true));
            JSONObject result = new JSONObject(true);
            result.put("result", 0);
            result.put("playerDataDelta", delta);
            return result;
        });
    }

    @PostMapping("/normalGacha")
    public JSONObject normalGacha(@RequestBody JSONObject jsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/normalGacha");
        ArknightsApplication.LOGGER.info("normalG:" + jsonBody);
        String secret = ArknightsApplication.getSecretByIP(clientIp);

        if (checkAccount(secret)) {
            List<Account> accounts = userDao.queryAccountBySecret(secret);
            Account account = accounts.get(0);

            // 获取请求参数
            int slotId = jsonBody.getIntValue("slotId");
            JSONArray tagList = jsonBody.getJSONArray("tagList");
            int duration = jsonBody.getIntValue("duration");
            int specialTagId = jsonBody.getIntValue("specialTagId");

            // 读取用户数据
            JSONObject userData = JSONObject.parseObject(account.getUser());
            NormalRecruitSupport.syncRecruitSlots(userData);
            JSONObject recruitSlot = userData.getJSONObject("recruit")
                    .getJSONObject("normal")
                    .getJSONObject("slots")
                    .getJSONObject(String.valueOf(slotId));

            // 检查招募许可
            int recruitLicense = userData.getJSONObject("status").getIntValue("recruitLicense");
            if (recruitLicense <= 0) {
                response.setStatus(400);
                return createErrorResponse(400, "招募许可不足");
            }

            // 更新招募信息
            long currentTime = System.currentTimeMillis() / 1000;
            recruitSlot.put("state", 2);
            recruitSlot.put("selectTags", createSelectTags(tagList));
            recruitSlot.put("startTs", currentTime);
            recruitSlot.put("durationInSec", duration);
            recruitSlot.put("maxFinishTs", currentTime + duration);
            recruitSlot.put("realFinishTs", currentTime + duration);
            recruitSlot.put("specialTagId", specialTagId);

            // 更新招募许可数量
            userData.getJSONObject("status").put("recruitLicense", recruitLicense - 1);

            // 保存用户数据
            userDao.setUserData(account.getUid(), userData);

            // 构建响应
            JSONObject result = new JSONObject();
            JSONObject playerDataDelta = new JSONObject();
            JSONObject modified = new JSONObject();
            modified.put("recruit", userData.getJSONObject("recruit"));
            modified.put("status", userData.getJSONObject("status"));
            playerDataDelta.put("modified", modified);
            playerDataDelta.put("deleted", new JSONObject());
            result.put("playerDataDelta", playerDataDelta);

            return result;
        }
        else {
            createError(secret, response);
        }
        return null;
    }

    @PostMapping("/finishNormalGacha")
    public JSONObject finishNormalGacha(@RequestBody JSONObject jsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/finishNormalGacha");
        ArknightsApplication.LOGGER.info("finishNG:" + jsonBody);

        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return createErrorResponse(400, "服务器维护中");
        }

        String secret = ArknightsApplication.getSecretByIP(clientIp);
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts.size() != 1) {
            response.setStatus(401);
            return createErrorResponse(2, "账户验证失败");
        }

        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return createErrorResponse(403, "账号已被封禁");
        }

        int slotId = jsonBody.getIntValue("slotId");
        JSONObject userData = JSONObject.parseObject(account.getUser());
        NormalRecruitSupport.syncRecruitSlots(userData);

        // Only a running slot may be settled. An idle slot holds no selected tags and no timer, so
        // reaching the selection would hand out a free operator from the shortest rarity window,
        // which is not a recruitment at all.
        JSONObject runningSlot = recruitSlot(userData, slotId);
        if (runningSlot == null || runningSlot.getIntValue("state") != 2) {
            response.setStatus(400);
            return createErrorResponse(400, "招募位当前没有进行中的招募");
        }

        // The mission block is snapshotted before any progress is applied, so the delta can name
        // only the rows this recruitment really removed. The client rejects deleting a key it
        // never held, which it logs as "Trying to delete invalid field" and which used to flood
        // the protocol window on every finish.
        JSONObject missionBefore = CharacterBuildingSupport.copy(userData.getJSONObject("mission"));

        JSONObject gachaResult;
        try {
            gachaResult = performNormalGacha(userData, slotId);
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            ArknightsApplication.LOGGER.info("finishNG rejected: " + e.getMessage());
            return createErrorResponse(400, e.getMessage());
        }

        // 更新用户数据
        updateUserAfterNormalGacha(userData, gachaResult, slotId);
        JSONObject missionContext = new JSONObject(true);
        missionContext.put("mode", "normal");
        MissionProgressSupport.onAction(userData, "recruit", missionContext,
                System.currentTimeMillis() / 1000L);
        userDao.setUserData(account.getUid(), userData);

        // 构建响应
        JSONObject result = new JSONObject();
        JSONObject playerDataDelta = new JSONObject();
        JSONObject modified = new JSONObject();
        modified.put("recruit", userData.getJSONObject("recruit"));
        modified.put("status", userData.getJSONObject("status"));
        modified.put("troop", userData.getJSONObject("troop"));
        
        modified.put("inventory", userData.getJSONObject("inventory"));
        if (gachaResult.getIntValue("isNew") == 1) {
            String instId = String.valueOf(gachaResult.getIntValue("charInstId"));
            JSONObject newBuildingChars = new JSONObject(true);
            newBuildingChars.put(instId,
                    userData.getJSONObject("building").getJSONObject("chars").getJSONObject(instId));
            JSONObject buildingDelta = new JSONObject(true);
            buildingDelta.put("chars", newBuildingChars);
            modified.put("building", buildingDelta);
        }
        playerDataDelta.put("modified", modified);
        playerDataDelta.put("deleted", new JSONObject());
        MissionProgressSupport.writeDelta(userData, playerDataDelta, missionBefore);
        result.put("playerDataDelta", playerDataDelta);
        result.put("charGet", gachaResult);

        return result;
    }

    /**
     * Spends one instantFinishTicket to pull a running slot's deadline to now. It deliberately
     * awards nobody and leaves the recruitment mission alone: finishing is still a separate
     * finishNormalGacha call. The client currently only toasts 加急许可不足 and never sends this.
     */
    @PostMapping("/boostNormalGacha")
    public JSONObject boostNormalGacha(@RequestBody JSONObject jsonBody, HttpServletRequest request,
                                       HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/boostNormalGacha");
        ArknightsApplication.LOGGER.info("boostNG:" + jsonBody);

        if (jsonBody == null || !jsonBody.containsKey("slotId")) {
            response.setStatus(400);
            return createErrorResponse(400, "slotId is required");
        }
        int slotId = jsonBody.getIntValue("slotId");
        // Only the ticket path is supported: no expedite price ships locally, so buy is refused
        // rather than guessed at.
        int buy = jsonBody.getIntValue("buy");
        if (buy != 0) {
            response.setStatus(400);
            return createErrorResponse(400, "不支持的加急方式");
        }

        String secret = ArknightsApplication.getSecretByIP(clientIp);
        if (!checkAccount(secret)) {
            createError(secret, response);
            return createErrorResponse(2, "账户验证失败");
        }

        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts.size() != 1) {
            response.setStatus(401);
            return createErrorResponse(2, "账户验证失败");
        }
        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return createErrorResponse(403, "账号已被封禁");
        }

        JSONObject userData = JSONObject.parseObject(account.getUser());
        JSONObject slot = recruitSlot(userData, slotId);
        long now = System.currentTimeMillis() / 1000;
        if (slot == null || slot.getIntValue("state") != 2) {
            response.setStatus(400);
            return createErrorResponse(400, "招募位当前没有进行中的招募");
        }
        if (slot.getLongValue("realFinishTs") <= now) {
            response.setStatus(400);
            return createErrorResponse(400, "该招募已完成，无需加急");
        }
        int tickets = userData.getJSONObject("status").getIntValue("instantFinishTicket");
        if (tickets <= 0) {
            response.setStatus(400);
            return createErrorResponse(400, "加急许可不足");
        }

        // Only the deadline moves.
        slot.put("realFinishTs", now);
        userData.getJSONObject("status").put("instantFinishTicket", tickets - 1);
        userDao.setUserData(account.getUid(), userData);

        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        JSONObject modified = new JSONObject(true);
        modified.put("recruit", userData.getJSONObject("recruit"));
        modified.put("status", userData.getJSONObject("status"));
        JSONObject delta = new JSONObject(true);
        delta.put("modified", modified);
        delta.put("deleted", new JSONObject(true));
        result.put("playerDataDelta", delta);
        return result;
    }

    @PostMapping("/getPoolDetail")
    public JSONObject getPoolDetail(@RequestBody JSONObject jsonBody, HttpServletResponse response) {
        String poolId = jsonBody.getString("poolId");
        String poolPath = "data/gacha/" + poolId + ".json";
        ArknightsApplication.LOGGER.info("getPoolD:" + jsonBody);


        JSONObject poolData = IOTools.ReadJsonFile(poolPath);
        if (poolData == null) {
            response.setStatus(500);
            return createErrorResponse(500, "卡池数据读取失败");
        }

        return poolData;
    }

    @PostMapping("/advancedGacha")
    public JSONObject advancedGacha(@RequestBody JSONObject jsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/advancedGacha");
        ArknightsApplication.LOGGER.info("advanceG:" + jsonBody);

        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return createErrorResponse(400, "服务器维护中");
        }

        String secret = ArknightsApplication.getSecretByIP(clientIp);
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts.size() != 1) {
            response.setStatus(401);
            return createErrorResponse(2, "账户验证失败");
        }

        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return createErrorResponse(403, "账号已被封禁");
        }

        String poolId = jsonBody.getString("poolId");
        int useTkt = jsonBody.getIntValue("useTkt");
        int diamondCost = poolId.startsWith("BOOT") ? 380 : 600;
        String itemId = "gachaTicket";
        if (jsonBody.getString("itemId") != null) {
            itemId = jsonBody.getString("itemId");
        }
        return performAdvancedGacha(account, poolId, itemId, diamondCost, 1, useTkt, response, new JSONArray());
    }

    @PostMapping("/tenAdvancedGacha")
    public JSONObject tenAdvancedGacha(@RequestBody JSONObject jsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /gacha/tenAdvancedGacha");
        ArknightsApplication.LOGGER.info("TAG:" + jsonBody);

        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return createErrorResponse(400, "服务器维护中");
        }

        String secret = ArknightsApplication.getSecretByIP(clientIp);
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts.size() != 1) {
            response.setStatus(401);
            return createErrorResponse(2, "账户验证失败");
        }

        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return createErrorResponse(403, "账号已被封禁");
        }

        String poolId = jsonBody.getString("poolId");
        int useTkt = jsonBody.getIntValue("useTkt");
        JSONArray itemList = jsonBody.getJSONArray("itemList");
        int diamondCost = poolId.startsWith("BOOT") ? 380 : 600;

        return performAdvancedGacha(account, poolId, "tenGachaTicket", diamondCost, 10, useTkt, response, itemList);
    }

    @GetMapping("/activity")
    public JSONObject activity(@RequestBody JSONObject jsonBody, HttpServletRequest request, HttpServletResponse response) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /activity");
        ArknightsApplication.LOGGER.info("/gache/activity:" + jsonBody);
        return new JSONObject();
    }

    private JSONObject createErrorResponse(int code, String message) {
        JSONObject result = new JSONObject();
        result.put("result", code);
        result.put("msg", message);
        result.put("message", "server error");
        return result;
    }

    private JSONArray createSelectTags(JSONArray tagList) {
        JSONArray selectTags = new JSONArray();
        for (int i = 0; i < tagList.size(); i++) {
            JSONObject tag = new JSONObject();
            tag.put("pick", 1);
            tag.put("tagId", tagList.getString(i));
            selectTags.add(tag);
        }
        return selectTags;
    }

    private JSONObject performNormalGacha(JSONObject userData, int slotId) {
        JSONObject chars = userData.getJSONObject("troop").getJSONObject("chars");
        JSONObject buildingChars = userData.getJSONObject("building").getJSONObject("chars");

        // Tags and timer pick the operator. An ordinary tag that clashes with the rest is dropped
        // so the recruitment settles instead of stranding the slot; a tag no operator carries
        // still throws before any reward, mission or write can happen.
        NormalRecruitSupport.Outcome outcome = NormalRecruitSupport.select(
                recruitDurationSeconds(userData, slotId), recruitSelectedTags(userData, slotId));
        String charId = outcome.charId();
        int rarityRank = NormalRecruitSupport.rarityOf(charId);

        // 检查是否已有该角色
        int charInstId = 0;
        for (String key : chars.keySet()) {
            if (chars.getJSONObject(key).getString("charId").equals(charId)) {
                charInstId = Integer.parseInt(key);
                break;
            }
        }

        JSONObject result = new JSONObject();
        JSONArray itemGet = new JSONArray();
        boolean isNew = charInstId == 0;

        if (isNew) {
            // 新角色处理
            Pattern pattern = Pattern.compile("char_(\\d+)_");
            Matcher matcher = pattern.matcher(charId);
            ArknightsApplication.LOGGER.info("rewardSId:" + charId);

            int CharId = 0;
            if (matcher.find()) {
                CharId = Integer.parseInt(matcher.group(1));
                ArknightsApplication.LOGGER.info("提取到的数字是: " + CharId);
            } else {
                ArknightsApplication.LOGGER.info("未匹配到数字");
            }
            charInstId = CharId;
            JSONObject newChar = createNewCharacter(charId, charInstId);
            chars.put(String.valueOf(charInstId), newChar);

            // 添加基建数据
            JSONObject buildingChar = createBuildingCharacter(charId);
            buildingChars.put(String.valueOf(charInstId), buildingChar);

            // 添加获得物品
            JSONObject hggShard = new JSONObject();
            hggShard.put("type", "HGG_SHD");
            hggShard.put("id", "4004");
            hggShard.put("count", 1);
            itemGet.add(hggShard);

            // 更新库存
            userData.getJSONObject("status").put("hggShard",
                    userData.getJSONObject("status").getIntValue("hggShard") + 1);

            // 更新角色组
            JSONObject charGroup = new JSONObject();
            charGroup.put("favorPoint", 0);
            userData.getJSONObject("troop").getJSONObject("charGroup").put(charId, charGroup);
        } else {
            // 重复角色处理
            JSONObject existingChar = chars.getJSONObject(String.valueOf(charInstId));
            int rarity = rarityRank;
            int potentialRank = existingChar.getIntValue("potentialRank");

            // 根据稀有度添加物品
            JSONObject shardItem = new JSONObject();
            if (rarity <= 3) {
                shardItem.put("type", "LGG_SHD");
                shardItem.put("id", "4005");
                shardItem.put("count", rarity == 3 ? 30 : (rarity == 2 ? 5 : 1));
                userData.getJSONObject("status").put("lggShard",
                        userData.getJSONObject("status").getIntValue("lggShard") +
                                shardItem.getIntValue("count"));
            } else {
                shardItem.put("type", "HGG_SHD");
                shardItem.put("id", "4004");
                shardItem.put("count", rarity == 5 ?
                        (potentialRank < 5 ? 10 : 15) :
                        (potentialRank < 5 ? 5 : 8));
                userData.getJSONObject("status").put("hggShard",
                        userData.getJSONObject("status").getIntValue("hggShard") +
                                shardItem.getIntValue("count"));
            }
            itemGet.add(shardItem);

            // 添加潜能
            JSONObject potentialItem = new JSONObject();
            potentialItem.put("type", "MATERIAL");
            potentialItem.put("id", "p_" + charId);
            potentialItem.put("count", 1);
            itemGet.add(potentialItem);
            userData.getJSONObject("inventory").put("p_" + charId,
                    userData.getJSONObject("inventory").getIntValue("p_" + charId) + 1);
        }

        result.put("charId", charId);
        result.put("charInstId", charInstId);
        result.put("isNew", isNew ? 1 : 0);
        result.put("itemGet", itemGet);
        return result;
    }

    /** Tag ids the client selected on one slot. */
    private static List<Integer> recruitSelectedTags(JSONObject userData, int slotId) {
        List<Integer> tags = new ArrayList<>();
        JSONObject slot = recruitSlot(userData, slotId);
        JSONArray selectTags = slot == null ? null : slot.getJSONArray("selectTags");
        if (selectTags == null) return tags;
        for (int i = 0; i < selectTags.size(); i++) {
            Object entry = selectTags.get(i);
            int tagId;
            if (entry instanceof JSONObject) {
                tagId = ((JSONObject) entry).getIntValue("tagId");
            } else if (entry instanceof Number) {
                tagId = ((Number) entry).intValue();
            } else {
                continue;
            }
            if (!tags.contains(tagId)) tags.add(tagId);
        }
        return tags;
    }

    /** The timer one slot was started with, in seconds. */
    private static int recruitDurationSeconds(JSONObject userData, int slotId) {
        JSONObject slot = recruitSlot(userData, slotId);
        int duration = slot == null ? 0 : slot.getIntValue("durationInSec");
        // A slot without a timer still has to resolve through a real recruitRarityTable lookup.
        return duration > 0 ? duration : NormalRecruitSupport.MIN_DURATION_MINUTES * 60;
    }

    private static JSONObject recruitSlot(JSONObject userData, int slotId) {
        return userData.getJSONObject("recruit")
                .getJSONObject("normal")
                .getJSONObject("slots")
                .getJSONObject(String.valueOf(slotId));
    }

    private JSONObject createNewCharacter(String charId, int instId) {

        JSONArray skilsArray = ArknightsApplication.characterJson.getJSONObject(charId).getJSONArray("skills");
        JSONArray skils = new JSONArray();
        for (int i = 0; i < skilsArray.size(); i++) {
            JSONObject new_skils = new JSONObject(true);
            new_skils.put("skillId", skilsArray.getJSONObject(i).getString("skillId"));
            new_skils.put("state", 0);
            new_skils.put("specializeLevel", 0);
            new_skils.put("completeUpgradeTime", -1);

            if (skilsArray.getJSONObject(i).getJSONObject("unlockCond").getString("phase").equals("PHASE_0")) {
                new_skils.put("unlock", 1);
            } else {
                new_skils.put("unlock", 0);
            }

            skils.add(new_skils);
        }
        JSONObject charData = new JSONObject();
        charData.put("charId", charId);
        charData.put("instId", instId);
        charData.put("level", 1);
        charData.put("exp", 0);
        charData.put("evolvePhase", 0);
        charData.put("favorPoint", 0);
        charData.put("potentialRank", 0);
        charData.put("mainSkillLvl", 1);
        charData.put("skin", charId + "#1");
        charData.put("gainTime", System.currentTimeMillis() / 1000);

        // 初始化技能
        JSONArray skills = new JSONArray();
        JSONObject charInfo = ArknightsApplication.characterJson.getJSONObject(charId);
        for (Object skillObj : charInfo.getJSONArray("skills")) {
            JSONObject skill = (JSONObject) skillObj;
            JSONObject newSkill = new JSONObject();
            JSONObject unlockCond = skill.getJSONObject("unlockCond");

            String phase = unlockCond != null ? unlockCond.getString("phase") : "";
            int unlock = "PHASE_0".equals(phase) ? 1 : 0;
            newSkill.put("skillId", skill.getString("skillId"));
            newSkill.put("state", 0);
            newSkill.put("specializeLevel", 0);
            newSkill.put("completeUpgradeTime", -1);
            newSkill.put("unlock", unlock);
            skills.add(newSkill);
        }
        charData.put("skills", skills);
        charData.put("currentEquip", null);
        JSONObject equip = new JSONObject();
        charData.put("equip", equip);
        // 设置语音语言
        charData.put("voiceLan", "JP");
        charData.put("defaultSkillIndex", -1);
        charData.put("starMark", skils.isEmpty() ? -1 : 0);

        return charData;
    }

    private JSONObject createBuildingCharacter(String charId) {
        JSONObject buildingChar = new JSONObject();
        buildingChar.put("charId", charId);
        buildingChar.put("lastApAddTime", System.currentTimeMillis() / 1000);
        buildingChar.put("ap", 8640000);
        buildingChar.put("roomSlotId", "");
        buildingChar.put("index", -1);
        buildingChar.put("changeScale", 0);
        JSONObject bubble = new JSONObject();
        JSONObject normal = new JSONObject();
        normal.put("add", -1);
        normal.put("ts", 0);
        bubble.put("normal", normal);

        JSONObject assist = new JSONObject();
        assist.put("add", -1);
        assist.put("ts", 0);
        bubble.put("assist", assist);

        buildingChar.put("bubble", bubble);
        buildingChar.put("workTime", 0);

        return buildingChar;
    }

    private void updateUserAfterNormalGacha(JSONObject userData, JSONObject gachaResult, int slotId) {
        // 重置招募位
        JSONObject recruitSlot = userData.getJSONObject("recruit")
                .getJSONObject("normal")
                .getJSONObject("slots")
                .getJSONObject(String.valueOf(slotId));
        recruitSlot.put("state", 1);
        recruitSlot.put("selectTags", new JSONArray());
        recruitSlot.put("tags", NormalRecruitSupport.refreshedTags());
    }

    private JSONObject createEmptyPoolResponse() {
        JSONObject result = new JSONObject();
        result.put("result", 1);
        result.put("errMsg", "卡池数据不存在");
        return result;
    }

    private JSONObject performAdvancedGacha(Account account, String poolId,
                                            String ticketType, int diamondCost,
                                            int drawCount, int useTkt,
                                            HttpServletResponse response, JSONArray itemList) {
        JSONObject userData = JSONObject.parseObject(account.getUser());
        JSONObject status = userData.getJSONObject("status");

        // DRAW CONTENT replacement: resolved and validated here, before any counter/branch or
        // currency mutation, so a stale or deleted choice can never charge a failing draw.
        // Incoming poolId still owns cost, ticket/currency/itemList, gacha branch and pity bucket.
        ChosenPool chosen = LocalGachaOptions.chooseForRequest(BackendPaths.rootFile(), poolId);
        if (chosen.failed()) {
            response.setStatus(500);
            return createErrorResponse(500, "所选寻访内容不可用");
        }
        LocalGachaOptions.PoolSnapshot chosenData = chosen.replace() ? chosen.snapshot() : null;
        JSONObject userGacha=userData.getJSONObject("gacha");
        if(poolId.startsWith("BOOT")&&(userGacha==null||userGacha.getJSONObject("newbee")==null||userGacha.getJSONObject("newbee").getIntValue("cnt")<drawCount))
            return createErrorResponse(400,"新手池抽卡数不足");

        if (drawCount == 1) {
            if (useTkt == 1 || useTkt == 2) {
                if (status.getIntValue(ticketType) < drawCount) {
                    return createErrorResponse(5771, "gacha_tkt_state_error");
                }
            } else if (useTkt == 4) {
                JSONObject TKT = userData.getJSONObject("consumable").getJSONObject(ticketType);
                JSONObject A = getInnerObject(TKT);
                if (A.getInteger("count") < drawCount) {
                    return createErrorResponse(5771, "gacha_tkt_state_error");
                }
            }
            else {
                if (status.getIntValue("diamondShard") < diamondCost * drawCount) {
                    return createErrorResponse(5647, "not enough diamond shard");
                }
            }
        }
        else if (drawCount == 10) {
            ArknightsApplication.LOGGER.info("10抽");
            if (!handleTenGachaCould(itemList, userData)){
                return createErrorResponse(5771, "gacha_tkt_state_error");
            }
        }

        // 读取卡池数据
        String poolPath = "data/gacha/" + poolId + ".json";
        JSONObject poolData = IOTools.ReadJsonFile(poolPath);
        ArknightsApplication.LOGGER.info("PoolData" + poolData);
        if (poolData == null) {
            poolData = IOTools.ReadJsonFile("data/gacha/DEFAULT.json");
            if (poolData == null) {
                response.setStatus(500);
                return createErrorResponse(500, "卡池数据加载失败");
            }
        }

        // 获取保底数据
        // 保底数据必须挂在最终会被保存的 userData 实例上，否则改动不会落盘
        JSONObject exGachaData = userData.getJSONObject("exgachadata");
        if (exGachaData == null) {
            exGachaData = new JSONObject();
            userData.put("exgachadata", exGachaData);
        }

        String gachaType = poolId.startsWith("CLASSIC") ? "CLASSIC" :
                poolId.startsWith("BOOT") ? "BOOT" : "NORM";
        int gachaCount = exGachaData.getIntValue(gachaType);

        JSONArray gachaResults = new JSONArray();
        JSONObject chars = userData.getJSONObject("troop").getJSONObject("chars");
        JSONObject buildingChars = userData.getJSONObject("building").getJSONObject("chars");
        JSONObject newBuildingChars = new JSONObject(true);
        // 替换只换内容：保底概率、权重累加与抽样算法保持原样，仅数据源换成校验过的选中池快照
        JSONArray availCharInfo = chosenData != null
                ? LocalGachaOptions.availCharInfoArray(chosenData)
                : poolData.getJSONObject("detailInfo")
                .getJSONObject("availCharInfo")
                .getJSONArray("perAvailList");
        JSONArray chosenUpCharInfo = chosenData != null
                ? LocalGachaOptions.upCharInfoArray(chosenData)
                : null;

        for (int i = 0; i < drawCount; i++) {
            // 执行单次抽卡
            JSONObject gachaResult = performSingleGacha(userData, chars, buildingChars,
                    availCharInfo, gachaCount, poolId, chosenUpCharInfo, newBuildingChars);
            gachaResults.add(gachaResult);

            // 更新保底计数
            if (gachaResult.getIntValue("rarity") == 5) {
                gachaCount = 0;
            } else {
                gachaCount++;
            }
        }
        Long uid = account.getUid();

        // 保存保底数据
        exGachaData.put(gachaType, gachaCount);

        // 扣除资源
        if (drawCount == 1) {
            if (useTkt == 1 || useTkt == 2) {
                status.put(ticketType, status.getIntValue(ticketType) - drawCount);
            }
            else if (useTkt == 4) {
                JSONObject TKT = userData.getJSONObject("consumable").getJSONObject(ticketType);
                JSONObject A = getInnerObject(TKT);
                JSONObject TKY = new JSONObject();
                int me = getInnerNumber(TKT);
                int o_count = A.getIntValue("count");
                A.put("count", o_count - 1);
                TKY.put(String.valueOf(me), A);
                userData.getJSONObject("consumable").put(ticketType, TKY);
            }
            else {
                status.put("diamondShard", status.getIntValue("diamondShard") - diamondCost * drawCount);
            }
        }
        else if(drawCount == 10) {
            userData = handleTenGachaItem(itemList, userData);
            status = userData.getJSONObject("status");
        }

        //处理 dexNav
        JSONObject dexNav0 = performDexNav(gachaResults, userData);
        JSONObject dexNav = dexNav0.getJSONObject("dexNav");

        // 构建响应
        JSONObject result = new JSONObject();
        result.put("result", 0);

        if (drawCount == 1) {
            result.put("charGet", gachaResults.getJSONObject(0));
        } else {
            result.put("gachaResultList", gachaResults);
        }
        JSONObject gacha = userData.getJSONObject("gacha");
        if(poolId.startsWith("BOOT")) {
            int cnt = gacha.getJSONObject("newbee").getIntValue("cnt");
            if(cnt - drawCount < 0){
                return createErrorResponse(400, "新手池抽卡数不足");
            }
            else {
                gacha.getJSONObject("newbee").put("cnt", gacha.getJSONObject("newbee").getIntValue("cnt") - drawCount);
            }
        }
        if(userDao.setUserData(account.getUid(), userData)!=1)return createErrorResponse(500,"保存失败");

        JSONObject playerDataDelta = new JSONObject();
        JSONObject modified = new JSONObject();
        modified.put("status", status);
        modified.put("troop", userData.getJSONObject("troop"));
        modified.put("inventory", userData.getJSONObject("inventory"));
        modified.put("consumable", userData.getJSONObject("consumable"));
        modified.put("gacha", gacha);
        modified.put("dexNav", dexNav);
        if (!newBuildingChars.isEmpty()) {
            JSONObject buildingDelta = new JSONObject(true);
            buildingDelta.put("chars", newBuildingChars);
            modified.put("building", buildingDelta);
        }
        playerDataDelta.put("modified", modified);
        playerDataDelta.put("deleted", new JSONObject());
        result.put("playerDataDelta", playerDataDelta);
        ArknightsApplication.LOGGER.info("result" + result);
        return result;
    }

    private JSONObject performSingleGacha(JSONObject userData, JSONObject chars,
                                          JSONObject buildingChars, JSONArray availCharInfo,
                                          int gachaCount, String poolId,
                                          JSONArray chosenUpCharInfo,
                                          JSONObject newBuildingChars) {
        // 构建概率数组
        List<JSONObject> randomRankList = new ArrayList<>();
        for (int i = 0; i < availCharInfo.size(); i++) {
            JSONObject charInfo = availCharInfo.getJSONObject(i);
            int totalPercent = (int)(charInfo.getDoubleValue("totalPercent") * 100);
            int rarityRank = charInfo.getIntValue("rarityRank");

            // 保底加成
            if (rarityRank == 5) {
                totalPercent += (gachaCount + 50) / 50 * 2;
            }

            JSONObject rankObj = new JSONObject();
            rankObj.put("rarityRank", rarityRank);
            rankObj.put("index", i);

            for (int j = 0; j < totalPercent; j++) {
                randomRankList.add(rankObj);
            }
        }

        // 随机选择
        Collections.shuffle(randomRankList);
        JSONObject selectedRank = randomRankList.get(new Random().nextInt(randomRankList.size()));
        // 每次抽样都复制一份：原实现直接复用池内 charIdList，十连时 UP 加权会逐抽累积
        JSONArray charIdList = new JSONArray();
        charIdList.addAll(availCharInfo.getJSONObject(selectedRank.getIntValue("index"))
                .getJSONArray("charIdList"));
        JSONObject poolData = IOTools.ReadJsonFile("data/gacha/" + poolId + ".json");

        // 处理UP角色
        if (!poolId.startsWith("BOOT")) {
            try {
                // 选中池时必须使用同一份选中快照的 UP 数据，不能回读原始 poolId 文件
                JSONArray upCharInfo = chosenUpCharInfo != null
                        ? chosenUpCharInfo
                        : poolData.getJSONObject("detailInfo")
                        .getJSONObject("upCharInfo")
                        .getJSONArray("perCharList");
                for (int i = 0; i < upCharInfo.size(); i++) {
                    JSONObject upChar = upCharInfo.getJSONObject(i);
                    if (upChar.getIntValue("rarityRank") == selectedRank.getIntValue("rarityRank")) {
                        int upRate = (int)(upChar.getDoubleValue("percent") * 100) - 15;
                        JSONArray upCharIds = upChar.getJSONArray("charIdList");
                        for (int j = 0; j < upCharIds.size(); j++) {
                            String upCharId = upCharIds.getString(j);
                            for (int k = 0; k < upRate; k++) {
                                charIdList.add(upCharId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                ArknightsApplication.LOGGER.error("处理UP角色异常", e);
            }
        }

        Collections.shuffle(charIdList);
        String charId = charIdList.getString(new Random().nextInt(charIdList.size()));

        // 检查是否已有角色
        Pattern pattern = Pattern.compile("char_(\\d+)_");
        Matcher matcher = pattern.matcher(charId);
        ArknightsApplication.LOGGER.info("rewardSId:" + charId);

        int CharId = 0;
        if (matcher.find()) {
            CharId = Integer.parseInt(matcher.group(1));
            ArknightsApplication.LOGGER.info("提取到的数字是: " + CharId);
        } else {
            ArknightsApplication.LOGGER.info("未匹配到数字");
        }
        boolean isNew = true;
        int charInstId = CharId;
        for (String key : chars.keySet()) {
            if (chars.getJSONObject(key).getString("charId").equals(charId)) {
                isNew = false;
                charInstId = Integer.parseInt(key);
                break;
            }
        }

        // 构建结果
        JSONObject result = new JSONObject();
        result.put("charId", charId);
        result.put("rarity", selectedRank.getIntValue("rarityRank"));
        JSONArray itemGet = new JSONArray();
        boolean classicPool = poolId.startsWith("CLASSIC");

        if (isNew) {
            // 新角色处理
            charInstId = CharId;
            JSONObject newChar = createNewCharacter(charId, charInstId);
            chars.put(String.valueOf(charInstId), newChar);

            // 添加基建数据
            JSONObject buildingChar = createBuildingCharacter(charId);
            buildingChars.put(String.valueOf(charInstId), buildingChar);

            // 添加角色组
            newBuildingChars.put(String.valueOf(charInstId),buildingChar);
            JSONObject charGroup = new JSONObject();
            charGroup.put("favorPoint", 0);
            userData.getJSONObject("troop").getJSONObject("charGroup").put(charId, charGroup);

            if (classicPool) {
                ClassicCertificateRewards.grant(
                        userData, selectedRank.getIntValue("rarityRank"), true, itemGet);
            } else {
                JSONObject hggShard = new JSONObject();
                hggShard.put("type", "HGG_SHD");
                hggShard.put("id", "4004");
                hggShard.put("count", 1);
                itemGet.add(hggShard);
                userData.getJSONObject("status").put("hggShard",
                        userData.getJSONObject("status").getIntValue("hggShard") + 1);
            }

            // 初始化潜能
            userData.getJSONObject("inventory").put("p_" + charId, 0);
        } else {
            // 重复角色处理
            JSONObject existingChar = chars.getJSONObject(String.valueOf(charInstId));
            int rarity = selectedRank.getIntValue("rarityRank");
            int potentialRank = existingChar.getIntValue("potentialRank");

            if (classicPool) {
                ClassicCertificateRewards.grant(userData, rarity, false, itemGet);
            } else {
                // 根据稀有度添加物品
                JSONObject shardItem = new JSONObject();
                if (rarity <= 3) {
                    shardItem.put("type", "LGG_SHD");
                    shardItem.put("id", "4005");
                    shardItem.put("count", rarity == 3 ? 30 : (rarity == 2 ? 5 : 1));
                    userData.getJSONObject("status").put("lggShard",
                            userData.getJSONObject("status").getIntValue("lggShard") +
                                    shardItem.getIntValue("count"));
                } else {
                    shardItem.put("type", "HGG_SHD");
                    shardItem.put("id", "4004");
                    shardItem.put("count", rarity == 5 ?
                            (potentialRank < 5 ? 10 : 15) :
                            (potentialRank < 5 ? 5 : 8));
                    userData.getJSONObject("status").put("hggShard",
                            userData.getJSONObject("status").getIntValue("hggShard") +
                                    shardItem.getIntValue("count"));
                }
                itemGet.add(shardItem);
            }

            // 添加潜能
            if (classicPool) {
                ClassicCertificateRewards.grantDuplicatePotentialItem(
                        userData, ArknightsApplication.characterJson.getJSONObject(charId), itemGet);
            } else {
                JSONObject potentialItem = new JSONObject();
                potentialItem.put("type", "MATERIAL");
                potentialItem.put("id", "p_" + charId);
                potentialItem.put("count", 1);
                itemGet.add(potentialItem);
                userData.getJSONObject("inventory").put("p_" + charId,
                        userData.getJSONObject("inventory").getIntValue("p_" + charId) + 1);
            }
        }

        result.put("charInstId", charInstId);
        result.put("isNew", isNew ? 1 : 0);
        result.put("itemGet", itemGet);

        return result;
    }

    private JSONObject performDexNav(JSONArray gachaResultList,JSONObject userData) {
        JSONObject result = new JSONObject();
        JSONObject dexNav = new JSONObject();
        JSONObject character = new JSONObject();
        JSONObject id = new JSONObject();
        JSONObject add = new JSONObject();
        if (gachaResultList != null && !gachaResultList.isEmpty()) {
            for (int i = 0; i < gachaResultList.size(); i++) {
                if (gachaResultList.get(i) instanceof JSONObject item) {
                    String charId = item.getString("charId");
                    int isNew = item.getIntValue("isNew");
                    int charInstId  = item.getIntValue("charInstId");
                    if  (isNew == 1) {
                        add.put("count", 1);
                        add.put("charInstId", charInstId);
                        add.put("classicCount", 0);
                        id.put(charId, add);
                        character.put("character", id);
                        dexNav.put("dexNav", character);
                        userData.getJSONObject("dexNav").getJSONObject("character").put(charId, add);
                        result.put("dexNav", dexNav);
                    } else if (isNew == 0) {
                        int count = userData.getJSONObject("dexNav").getJSONObject("character").getJSONObject(charId).getIntValue("count");
                        add.put("count", count);
                        add.put("charInstId", charInstId);
                        add.put("classicCount", 0);
                        id.put(charId, add);
                        character.put("character", id);
                        dexNav.put("dexNav", character);
                        userData.getJSONObject("dexNav").getJSONObject("character").getJSONObject(charId).put("count", count + 1);
                        result.put("dexNav", dexNav);
                    }
                }
            }
        }
        return result;
    }
}
