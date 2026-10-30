package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.decrypt.Utils;
import com.hypergryph.arknights.core.pojo.Account;
import com.hypergryph.arknights.core.pojo.SearchAssistCharList;
import com.hypergryph.arknights.core.pojo.UserInfo;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.hypergryph.arknights.ArknightsApplication.mainStage;
import static com.hypergryph.arknights.ArknightsApplication.stageTable;

@RestController
@RequestMapping({"/quest"})
public class quest {

    public quest() {
    }

    @PostMapping(
            value = {"/battleStart"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject BattleStart(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        if (JsonBody == null) return BattleRepeatSupport.error("Missing battle request");
        ArknightsApplication.LOGGER.info("Received JSON: " + JsonBody.toJSONString());
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/battleStart");
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            JSONObject result = new JSONObject(true);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        } else {
            String stageId = JsonBody.getString("stageId");
            boolean isReplay = JsonBody.getBooleanValue("isReplay");
            long startTs = JsonBody.getLongValue("startTs");
            int usePracticeTicket = JsonBody.getIntValue("usePracticeTicket");
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    JSONObject stage_table = stageTable.getJSONObject(stageId);
                    if (stage_table == null) return BattleRepeatSupport.error("Unknown stage");
                    int repeatCount;
                    try {
                        repeatCount = BattleRepeatSupport.requestedRuns(JsonBody, stage_table, usePracticeTicket == 1);
                    } catch (RuntimeException invalidCount) {
                        return BattleRepeatSupport.error("Invalid multiple.battleTimes");
                    }
                    if (!BattleRepeatSupport.canAfford(UserSyncData, stage_table, repeatCount)) {
                        return BattleRepeatSupport.error("Not enough AP for multiple battle");
                    }
                    JSONObject restoredStages = MainlineProgressionSupport.normalize(UserSyncData, stageTable, mainStage);
                    restoredStages.putAll(CampaignProgressionSupport.normalize(UserSyncData, stageTable));
                    if ((MainlineProgressionSupport.isMainline(stage_table)||CampaignProgressionSupport.isExtermination(stage_table))
                            && !UserSyncData.getJSONObject("dungeon").getJSONObject("stages").containsKey(stageId))
                        return BattleRepeatSupport.error("Mainline stage is locked");
                    JSONObject battleReceipt = BattleRepeatSupport.record(UserSyncData, stageId, repeatCount, usePracticeTicket == 1);
                    battleReceipt.put("isReplay", isReplay);
                    battleReceipt.put("startTs", startTs);
                    JSONObject assistFriend = JsonBody.getJSONObject("assistFriend");
                    if (assistFriend != null) {
                        JSONArray friends = JSONObject.parseObject(Accounts.get(0).getFriend()).getJSONArray("list");
                        for (int i = 0; i < friends.size(); i++) {
                            if (friends.getJSONObject(i).getLongValue("uid") == assistFriend.getLongValue("uid")) {
                                battleReceipt.put("friendAssist", true);
                                break;
                            }
                        }
                    }
                    JSONObject result;
                    if (!UserSyncData.getJSONObject("dungeon").getJSONObject("stages").containsKey(stageId)) {
                        result = new JSONObject(true);
                        result.put("completeTimes", 0);
                        result.put("hasBattleReplay", 0);
                        result.put("noCostCnt", 1);
                        result.put("practiceTimes", 0);
                        result.put("stageId", stageId);
                        result.put("startTimes", 0);
                        result.put("state", 0);
                        UserSyncData.getJSONObject("dungeon").getJSONObject("stages").put(stageId, result);
                    }

                    if (usePracticeTicket == 1) {
                        UserSyncData.getJSONObject("status").put("practiceTicket", UserSyncData.getJSONObject("status").getIntValue("practiceTicket") - 1);
                        UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).put("practiceTimes", 1);
                    }
                    UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).put("startTimes", UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getIntValue("startTimes") + repeatCount);
                    if (userDao.setUserData(uid, UserSyncData) != 1) return BattleRepeatSupport.error("Battle start save failed");
                    result = new JSONObject(true);
                    JSONObject playerDataDelta = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    JSONObject dungeon = new JSONObject(true);
                    JSONObject stages = new JSONObject(true);
                    stages.putAll(restoredStages);
                    stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
                    dungeon.put("stages", stages);
                    modified.put("dungeon", dungeon);
                    modified.put("status", UserSyncData.getJSONObject("status"));
                    playerDataDelta.put("deleted", new JSONObject(true));
                    playerDataDelta.put("modified", modified);
                    result.put("playerDataDelta", playerDataDelta);
                    result.put("result", 0);
                    result.put("battleId", battleReceipt.getString("battleId"));
                    result.put("isApProtect", 0);
                    result.put("apFailReturn", stage_table.getIntValue("apFailReturn"));
                    if (UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getIntValue("noCostCnt") == 1) {
                        result.put("isApProtect", 1);
                        result.put("apFailReturn", stage_table.getIntValue("apCost"));
                    }

                    if (stage_table.getIntValue("apCost") == 0) {
                        result.put("isApProtect", 0);
                        result.put("apFailReturn", 0);
                    }

                    if (usePracticeTicket == 1) {
                        result.put("isApProtect", 0);
                        result.put("apFailReturn", 0);
                    }

                    return result;
                }
            }
        }
    }
    @PostMapping(
            value = {"/battleFinish"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject BattleFinish(@RequestBody JSONObject JsonBody,
                                   HttpServletResponse response,
                                   HttpServletRequest request) {
        // 1. 初始化与基础验证
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/battleFinish");
        ArknightsApplication.LOGGER.info("battleData:" + JsonBody);

        JSONObject result = new JSONObject(true);
        JSONArray firstReward = new JSONArray();

        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        }

        // 2. 验证用户
        List<Account> Accounts = userDao.queryAccountBySecret(secret);
        if (Accounts.size() != 1) {
            result.put("result", 2);
            result.put("error", "无法查询到此账户");
            return result;
        }

        Account account = Accounts.get(0);
        Long uid = account.getUid();

        if (account.getBan() == 1L) {
            response.setStatus(500);
            JSONObject banResult = new JSONObject(true);
            banResult.put("statusCode", 403);
            banResult.put("error", "Bad Request");
            banResult.put("message", "error");
            return banResult;
        }

        // 3. 获取用户数据
        JSONObject UserSyncData = JSON.parseObject(account.getUser());
        JSONObject BattleData;
        try {
            BattleData = Utils.BattleData_decrypt(JsonBody.getString("data"),
                    UserSyncData.getJSONObject("pushFlags").getString("status"));
        } catch (RuntimeException malformed) {
            return BattleRepeatSupport.error("Invalid battle data");
        }
        JSONObject battleReceipt = BattleRepeatSupport.pending(UserSyncData, BattleData);
        if (battleReceipt == null) return BattleRepeatSupport.error("Unknown or already settled battle");
        String stageId = battleReceipt.getString("stageId");
        int repeatCount = battleReceipt.getIntValue("runs");
        JSONObject stage_table = stageTable.getJSONObject(stageId);
        int reportedState = BattleData.getIntValue("completeState");
        if (stage_table == null || repeatCount < 1 || repeatCount > BattleRepeatSupport.MAX_RUNS ||
                reportedState < 1 || reportedState > 4) {
            return BattleRepeatSupport.error("Invalid battle result");
        }
        if (!BattleRepeatSupport.canAfford(UserSyncData, stage_table, repeatCount)) {
            return BattleRepeatSupport.error("Not enough AP for multiple battle");
        }

        // 5. 处理练习模式
        if (battleReceipt.getBooleanValue("practice")) {
            battleReceipt.put("settled", true);
            return handlePracticeMode(UserSyncData, uid, stageId, reportedState);
        }

        // 6. 处理正常战斗
        if (CampaignProgressionSupport.isExtermination(stage_table)) {
            try {
                JSONObject campaignResult=CampaignBattleSupport.finish(UserSyncData,battleReceipt,BattleData,stage_table);
                if(userDao.setUserData(uid,UserSyncData)!=1)return BattleRepeatSupport.error("Campaign finish save failed");
                return campaignResult;
            } catch(IllegalArgumentException invalid) { return BattleRepeatSupport.error(invalid.getMessage()); }
        }
        JSONObject chars = UserSyncData.getJSONObject("troop").getJSONObject("chars");
        JSONObject troop = new JSONObject(true);

        int DropRate = ArknightsApplication.serverConfig.getJSONObject("battle").getIntValue("dropRate");
        int completeState = BattleData.getIntValue("completeState");

        if (ArknightsApplication.serverConfig.getJSONObject("battle").getBooleanValue("debug")) {
            completeState = 3;
        }

        int apCost = stage_table.getIntValue("apCost");
        int expGain = stage_table.getIntValue("expGain");
        int goldGain = stage_table.getIntValue("goldGain");

        // 7. 处理AP消耗
        handleApConsumption(UserSyncData, apCost, completeState, stage_table);

        // 8. 战斗失败处理
        if (completeState == 1) {
            // The available protocol supplies one outcome, no per-run success list.
            // A failure settles only the attempted run and awards no successful runs.
            battleReceipt.put("settled", true);
            return handleBattleFailure(UserSyncData, uid, stage_table, stageId, firstReward);
        }

        // 9. 战斗成功处理
        updateStageState(UserSyncData, stageId, completeState);

        // 10. 首次通关奖励
        boolean FirstClear = checkFirstClear(UserSyncData, stageId);
        JSONObject recruit = new JSONObject();
        JSONObject charGet = null;
        if (FirstClear) {
            charGet = handleFirstClearRewards(UserSyncData, stageId, chars, troop, firstReward);
            if (stageId.equals("main_00-02") ) {
                recruit = handleNormalGachaUnlock(UserSyncData, uid);
            }
        }

        // 11. 关卡解锁
        JSONArray unlockStages = new JSONArray();
        JSONArray unlockStagesObject = new JSONArray();
        handleStageUnlock(UserSyncData, stageId, completeState, unlockStages, unlockStagesObject, FirstClear);

        // 12. 发放奖励
        JSONObject rewards = calculateRewards(UserSyncData, stage_table, completeState, FirstClear, DropRate, firstReward);

        // 13. 更新干员好感度
        updateOperatorFavor(UserSyncData, BattleData, stage_table, completeState);
        for (int run = 1; run < repeatCount; run++) {
            handleApConsumption(UserSyncData, apCost, completeState, stage_table);
            JSONObject nextRewards = calculateRewards(UserSyncData, stage_table, completeState, false, DropRate, new JSONArray());
            BattleRepeatSupport.mergeRewards(rewards, nextRewards);
            updateOperatorFavor(UserSyncData, BattleData, stage_table, completeState);
        }
        // Include counters in the delta after the final run, not just in persistence.
        JSONObject completedStage = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
        completedStage.put("completeTimes", completedStage.getIntValue("completeTimes") + repeatCount);
        JSONObject favorChars = new JSONObject(true);
        for (String charInstId : BattleData.getJSONObject("battleData").getJSONObject("stats").getJSONObject("charList").keySet()) {
            if (chars.containsKey(charInstId)) favorChars.put(charInstId, chars.getJSONObject(charInstId));
        }
        if (!favorChars.isEmpty()) {
            JSONObject changedChars = troop.getJSONObject("chars");
            if (changedChars == null) troop.put("chars", favorChars);
            else changedChars.putAll(favorChars);
            JSONObject favorGroups = new JSONObject(true);
            JSONObject groups = UserSyncData.getJSONObject("troop").getJSONObject("charGroup");
            for (String charInstId : favorChars.keySet()) {
                String charId = favorChars.getJSONObject(charInstId).getString("charId");
                if (groups.containsKey(charId)) favorGroups.put(charId, groups.getJSONObject(charId));
            }
            if (!favorGroups.isEmpty()) troop.put("charGroup", favorGroups);
        }

        // 14. 构建返回结果
        result.put("result", 0);
        result.put("alert", new JSONArray());
        result.put("suggestFriend", false);
        result.put("apFailReturn", 0);
        result.putAll(rewards);
        if (charGet != null) {
            result.put("charGet", charGet);
        }
        result.put("unlockStages", unlockStages);

        // 15. 构建玩家数据变更
        NewCreditSupport.recordBattle(UserSyncData,battleReceipt,completeState,System.currentTimeMillis()/1000L);
        JSONObject missionContext = BattleMissionSupport.context(battleReceipt,
                BattleData.getJSONObject("battleData").getJSONObject("stats"), stage_table, completeState);
        missionContext.put("replay", battleReceipt.getBooleanValue("isReplay"));
        JSONObject missionBefore = CharacterBuildingSupport.copy(UserSyncData.getJSONObject("mission"));
        MissionProgressSupport.onAction(UserSyncData, "battleFinish", missionContext,
                System.currentTimeMillis() / 1000L);
        JSONObject playerDataDelta = buildPlayerDataDelta(UserSyncData, unlockStagesObject, stageId, troop);
        playerDataDelta.getJSONObject("modified").put("recruit", recruit);
        MissionProgressSupport.writeDelta(UserSyncData, playerDataDelta, missionBefore);
        result.put("playerDataDelta", playerDataDelta);

        // 16. 保存数据
        battleReceipt.put("settled", true);
        if (userDao.setUserData(uid, UserSyncData) != 1) return BattleRepeatSupport.error("Battle finish save failed");
        ArknightsApplication.LOGGER.info(result.toJSONString());

        return result;
    }

    // ========== 辅助方法实现 ==========
    private JSONObject handleNormalGachaUnlock(JSONObject UserSyncData,long uid) {
        UserSyncData.getJSONObject("recruit").getJSONObject("normal").getJSONObject("slots").getJSONObject("0").put("state", 1);
        UserSyncData.getJSONObject("recruit").getJSONObject("normal").getJSONObject("slots").getJSONObject("1").put("state", 1);
        JSONObject recruit = UserSyncData.getJSONObject("recruit");
        return  recruit;
    }

    private int resolveDropType(String dropType) {
        return switch (dropType) {
            case "ONCE" -> 1;
            case "NORMAL" -> 2;
            case "RARE" -> 3;
            case "ADDITIONAL" -> 4;
            case "COMPLETE" -> 8;
            default -> 0;
        };
    }

    private int resolveOccPercent(String occPercent) {
        return switch (occPercent) {
            case "ALWAYS" -> 0;
            case "ALMOST" -> 1;
            case "SOMETIMES" -> 4;
            default -> 0;
        };
    }

    private JSONObject handlePracticeMode(JSONObject UserSyncData, Long uid, String stageId, int rating) {
        JSONObject stage = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
        stage.put("practiceTimes", 0);

        if (UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getIntValue("state") == 0) {
            UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).put("state", 1);
        }

        JSONObject missionContext = new JSONObject(true);
        missionContext.put("stageId", stageId);
        missionContext.put("rating", rating);
        MissionProgressSupport.onAction(UserSyncData, "practiceFinish", missionContext,
                System.currentTimeMillis() / 1000L);
        userDao.setUserData(uid, UserSyncData);

        JSONObject chars = new JSONObject(true);
        JSONObject troop = new JSONObject(true);
        JSONObject result = new JSONObject(true);

        JSONObject dungeon = new JSONObject(true);
        JSONObject stages = new JSONObject(true);
        stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
        dungeon.put("stages", stages);

        result.put("status", UserSyncData.getJSONObject("status"));
        result.put("dungeon", dungeon);
        result.put("mission", UserSyncData.getJSONObject("mission"));

        troop.put("deleted", new JSONObject(true));
        troop.put("modified", result);

        chars.put("playerDataDelta", troop);
        chars.put("result", 0);

        return chars;
    }

    void handleApConsumption(JSONObject UserSyncData, int apCost, int completeState, JSONObject stage_table) {
        int nowTime = (int)(new Date().getTime() / 1000L);
        int lastApAddTime = UserSyncData.getJSONObject("status").getIntValue("lastApAddTime");
        int addAp = (nowTime - lastApAddTime) / 360;

        if (UserSyncData.getJSONObject("status").getIntValue("ap") < UserSyncData.getJSONObject("status").getIntValue("maxAp")) {
            if (UserSyncData.getJSONObject("status").getIntValue("ap") + addAp >= UserSyncData.getJSONObject("status").getIntValue("maxAp")) {
                UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("maxAp"));
                UserSyncData.getJSONObject("status").put("lastApAddTime", nowTime);
            } else if (addAp != 0) {
                UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("ap") + addAp);
                UserSyncData.getJSONObject("status").put("lastApAddTime", nowTime);
            }
        }

        UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("ap") - apCost);
    }

    private JSONObject handleBattleFailure(JSONObject UserSyncData, Long uid, JSONObject stage_table, String stageId,
                                           JSONArray firstReward) {
        int apFailReturn = stage_table.getIntValue("apFailReturn");
        if (UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getIntValue("noCostCnt") == 1) {
            apFailReturn = stage_table.getIntValue("apCost");
            UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).put("noCostCnt", 0);
        }

        int nowTime = (int)(new Date().getTime() / 1000L);
        int lastApAddTime = UserSyncData.getJSONObject("status").getIntValue("lastApAddTime");
        int addAp = (nowTime - lastApAddTime) / 360;

        if (UserSyncData.getJSONObject("status").getIntValue("ap") < UserSyncData.getJSONObject("status").getIntValue("maxAp")) {
            if (UserSyncData.getJSONObject("status").getIntValue("ap") + addAp >= UserSyncData.getJSONObject("status").getIntValue("maxAp")) {
                UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("maxAp"));
                UserSyncData.getJSONObject("status").put("lastApAddTime", nowTime);
            } else {
                UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("ap") + addAp);
                UserSyncData.getJSONObject("status").put("lastApAddTime", nowTime);
            }
        }

        UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("ap") + apFailReturn);
        UserSyncData.getJSONObject("status").put("lastApAddTime", nowTime);
        userDao.setUserData(uid, UserSyncData);

        JSONObject result = new JSONObject(true);
        JSONObject playerDataDelta = new JSONObject(true);
        JSONObject modified = new JSONObject(true);

        result.put("additionalRewards", new JSONArray());
        result.put("alert", new JSONArray());
        result.put("firstRewards", firstReward);
        result.put("furnitureRewards", new JSONArray());
        result.put("unlockStages", new JSONArray());
        result.put("unusualRewards", new JSONArray());
        result.put("rewards", new JSONArray());
        result.put("expScale", 0);
        result.put("goldScale", 0);
        result.put("apFailReturn", apFailReturn);

        modified.put("status", UserSyncData.getJSONObject("status"));

        JSONObject dungeon = new JSONObject(true);
        JSONObject stages = new JSONObject(true);
        stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
        dungeon.put("stages", stages);
        modified.put("dungeon", dungeon);

        playerDataDelta.put("deleted", new JSONObject(true));
        playerDataDelta.put("modified", modified);

        result.put("playerDataDelta", playerDataDelta);
        result.put("result", 0);

        return result;
    }

    private void updateStageState(JSONObject UserSyncData, String stageId, int completeState) {
        JSONObject stages_data = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);

        if (stages_data.getIntValue("state") == 0) {
            stages_data.put("state", 1);
        }

        if (stages_data.getIntValue("state") != 3) {
            stages_data.put("state", completeState);
        }

        if (completeState == 4) {
            stages_data.put("state", completeState);
        }
    }

    private boolean checkFirstClear(JSONObject UserSyncData, String stageId) {
        int complete = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getIntValue("completeTimes");
        return complete == 0;
    }

    private JSONObject handleFirstClearRewards(JSONObject UserSyncData, String stageId,
                                               JSONObject chars, JSONObject troop, JSONArray firstReward) {
        JSONObject charGet = null;
        if (stageId.equals("main_08-16")) {
            handleAmiyaTransform(UserSyncData, chars, troop);
        }

        JSONArray displayDetailRewards = stageTable.getJSONObject(stageId).getJSONObject("stageDropInfo").getJSONArray("displayDetailRewards");
        ArknightsApplication.LOGGER.info("DDR:" + displayDetailRewards.toJSONString());

        for (int i = 0; i < displayDetailRewards.size(); i++) {
            JSONObject reward = displayDetailRewards.getJSONObject(i);
            int dropType = resolveDropType(reward.getString("dropType"));
            String reward_id = reward.getString("id");
            String reward_type = reward.getString("type");

            if (dropType == 1 || dropType == 8) {
                JSONObject rewardCharGet = handleFirstClearReward(UserSyncData, reward_id, reward_type, chars, troop);
                if (rewardCharGet != null) {
                    charGet = rewardCharGet;
                }
                if (!"CHAR".equals(reward_type)) {
                    JSONObject filteredReward = new JSONObject();
                    filteredReward.put("type", reward_type);
                    filteredReward.put("id", reward_id);
                    filteredReward.put("count", 1);
                    firstReward.add(filteredReward);
                }
            }
        }
        ArknightsApplication.LOGGER.info("First rewards extracted: " + firstReward.toJSONString());
        return charGet;
    }

    private void handleAmiyaTransform(JSONObject UserSyncData, JSONObject chars, JSONObject troop) {
        for (Map.Entry<String, Object> entry : UserSyncData.getJSONObject("troop").getJSONObject("chars").entrySet()) {
            JSONObject charData = UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(entry.getKey());
            String charId = charData.getString("charId");

            if (charId.equals("char_002_amiya")) {
                JSONArray amiya_skills = charData.getJSONArray("skills");
                String skin = charData.getString("skin");
                int defaultSkillIndex = charData.getIntValue("defaultSkillIndex");

                charData.put("skin", (Object)null);
                charData.put("defaultSkillIndex", -1);
                charData.put("skills", new JSONArray());
                charData.put("currentTmpl", "char_1001_amiya2");

                JSONObject tmpl = new JSONObject(true);
                JSONObject charList = new JSONObject(true);
                charList.put("skinId", skin);
                charList.put("defaultSkillIndex", defaultSkillIndex);
                charList.put("skills", amiya_skills);
                charList.put("currentEquip", (Object)null);
                charList.put("equip", new JSONObject(true));
                tmpl.put("char_002_amiya", charList);

                JSONArray sword_amiya_skills = new JSONArray();
                JSONObject skill1 = new JSONObject(true);
                skill1.put("skillId", "skchr_amiya2_1");
                skill1.put("unlock", 1);
                skill1.put("state", 0);
                skill1.put("specializeLevel", 0);
                skill1.put("completeUpgradeTime", -1);
                sword_amiya_skills.add(skill1);

                JSONObject skill2 = new JSONObject(true);
                skill2.put("skillId", "skchr_amiya2_1");
                skill2.put("unlock", 1);
                skill2.put("state", 0);
                skill2.put("specializeLevel", 0);
                skill2.put("completeUpgradeTime", -1);
                sword_amiya_skills.add(skill2);

                JSONObject newCharData = new JSONObject(true);
                newCharData.put("skinId", "char_1001_amiya2#2");
                newCharData.put("defaultSkillIndex", 0);
                newCharData.put("skills", sword_amiya_skills);
                newCharData.put("currentEquip", (Object)null);
                newCharData.put("equip", new JSONObject(true));
                tmpl.put("char_1001_amiya2", newCharData);

                charData.put("tmpl", tmpl);
                JSONObject charinstId = new JSONObject(true);
                charinstId.put(entry.getKey(), charData);
                troop.put("chars", charinstId);
                UserSyncData.getJSONObject("troop").getJSONObject("chars").put(entry.getKey(), charData);
                break;
            }
        }
    }

    private JSONObject handleFirstClearReward(JSONObject UserSyncData, String reward_id, String reward_type,
                                              JSONObject chars, JSONObject troop) {
        if (!reward_type.equals("CHAR")) {
            // 处理非干员奖励
            handleNonCharFirstClearReward(UserSyncData, reward_id, reward_type);
            return null;
        } else {
            // 处理干员奖励
            JSONObject charGet = handleCharFirstClearReward(UserSyncData, reward_id, chars, troop);
            ArknightsApplication.LOGGER.info("rewardSId:" + reward_id);
            return charGet;
        }
    }

    private void handleNonCharFirstClearReward(JSONObject UserSyncData, String reward_id, String reward_type) {
        switch (reward_type) {
            case "MATERIAL":
            case "CARD_EXP":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + 1);
                break;
            case "DIAMOND":
                UserSyncData.getJSONObject("status").put("androidDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("androidDiamond") + 1);
                UserSyncData.getJSONObject("status").put("iosDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("iosDiamond") + 1);
                break;
            case "GOLD":
                UserSyncData.getJSONObject("status").put("gold",
                        UserSyncData.getJSONObject("status").getIntValue("gold") + 1);
                break;
            case "TKT_RECRUIT":
                UserSyncData.getJSONObject("status").put("recruitLicense",
                        UserSyncData.getJSONObject("status").getIntValue("recruitLicense") + 1);
                break;
            case "FURN":
                if (!UserSyncData.getJSONObject("building").getJSONObject("furniture").containsKey(reward_id)) {
                    JSONObject furniture = new JSONObject(true);
                    furniture.put("count", 1);
                    furniture.put("inUse", 0);
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").put(reward_id, furniture);
                } else {
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id)
                            .put("count", UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id).getIntValue("count") + 1);
                }
                break;
        }
    }

    private JSONObject handleCharFirstClearReward(JSONObject UserSyncData, String reward_id,
                                                  JSONObject chars, JSONObject troop) {
        String randomCharId = reward_id;
        Pattern pattern = Pattern.compile("char_(\\d+)_");
        Matcher matcher = pattern.matcher(reward_id);
        ArknightsApplication.LOGGER.info("rewardSId:" + reward_id);

        int charId = 0;
        if (matcher.find()) {
            charId = Integer.parseInt(matcher.group(1));
            ArknightsApplication.LOGGER.info("提取到的数字是: " + charId);
        } else {
            ArknightsApplication.LOGGER.info("未匹配到数字");
        }

        JSONObject char_data = UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charId));
        // 检查是否已拥有该干员


        if (char_data == null) {
            // 新干员
            return handleNewCharReward(UserSyncData, randomCharId, chars, troop);
        } else {
            // 已有干员
            return handleExistingCharReward(UserSyncData, randomCharId, char_data, chars, troop);
        }
    }

    private JSONObject handleNewCharReward(JSONObject UserSyncData, String randomCharId,
                                           JSONObject chars, JSONObject troop) {
        JSONObject get_char = new JSONObject(true);
        JSONObject char_data = new JSONObject(true);
        JSONArray skilsArray = ArknightsApplication.characterJson.getJSONObject(randomCharId).getJSONArray("skills");
        JSONArray skils = new JSONArray();

        for (int i = 0; i < skilsArray.size(); i++) {
            JSONObject new_skils = new JSONObject(true);
            new_skils.put("skillId", skilsArray.getJSONObject(i).getString("skillId"));
            new_skils.put("state", 0);
            new_skils.put("specializeLevel", 0);
            new_skils.put("completeUpgradeTime", -1);

            JSONObject unlockCond = skilsArray.getJSONObject(i).getJSONObject("unlockCond");
            String phase = unlockCond != null ? unlockCond.getString("phase") : "";
            new_skils.put("unlock", "PHASE_0".equals(phase) ? 1 : 0);

            skils.add(new_skils);
        }
        //提取CharId
        Pattern pattern = Pattern.compile("char_(\\d+)_");
        Matcher matcher = pattern.matcher(randomCharId);

        int charId = 0;
        if (matcher.find()) {
            charId = Integer.parseInt(matcher.group(1));
            ArknightsApplication.LOGGER.info("提取到的数字是: " + charId);
        } else {
            ArknightsApplication.LOGGER.info("未匹配到数字");
        }

        char_data.put("instId", charId);
        char_data.put("charId", randomCharId);
        char_data.put("favorPoint", 0);
        char_data.put("potentialRank", 0);
        char_data.put("mainSkillLvl", 1);
        char_data.put("skin", randomCharId + "#1");
        char_data.put("level", 1);
        char_data.put("exp", 0);
        char_data.put("evolvePhase", 0);
        char_data.put("gainTime", new Date().getTime() / 1000L);
        char_data.put("skills", skils);
        char_data.put("voiceLan", "CN_MANDARIN");
        char_data.put("defaultSkillIndex", skils.isEmpty() ? -1 : 0);
        char_data.put("starMark", 0);
        String itemType = randomCharId.substring(randomCharId.indexOf("_") + 1);
        String itemId = itemType.substring(itemType.indexOf("_") + 1);
        JSONObject equip = new JSONObject();
        String CE = null;
        char_data.put("currentEquip", CE);
        char_data.put("equip", equip);
        ArknightsApplication.LOGGER.info("CD:"+ char_data);


        UserSyncData.getJSONObject("troop").getJSONObject("chars").put(String.valueOf(charId), char_data);
        UserSyncData.getJSONObject("building").getJSONObject("chars")
                .put(String.valueOf(charId), createBuildingCharacter(randomCharId));

        JSONObject charGroup = new JSONObject(true);
        charGroup.put("favorPoint", 0);
        UserSyncData.getJSONObject("troop").getJSONObject("charGroup").put(randomCharId, charGroup);
        JSONObject newCharGroup = new JSONObject(true);
        newCharGroup.put(randomCharId, charGroup);
        troop.put("charGroup", newCharGroup);

        get_char.put("charInstId", charId);
        get_char.put("charId", randomCharId);
        get_char.put("isNew", 1);

        JSONArray itemGet = new JSONArray();
        JSONObject hggShard = new JSONObject(true);
        hggShard.put("type", "HGG_SHD");
        hggShard.put("id", "4004");
        hggShard.put("count", 1);
        itemGet.add(hggShard);

        UserSyncData.getJSONObject("status").put("hggShard", UserSyncData.getJSONObject("status").getIntValue("hggShard") + 1);
        get_char.put("itemGet", itemGet);
        UserSyncData.getJSONObject("inventory").put("p_" + randomCharId, 0);

        JSONObject newCharInst = new JSONObject(true);
        newCharInst.put(String.valueOf(charId), char_data);
        chars.put(String.valueOf(charId), char_data);
        troop.put("chars", newCharInst);
        return get_char;
    }

    private JSONObject createBuildingCharacter(String charId) {
        JSONObject buildingChar = new JSONObject(true);
        buildingChar.put("charId", charId);
        buildingChar.put("lastApAddTime", System.currentTimeMillis() / 1000L);
        buildingChar.put("ap", 8640000);
        buildingChar.put("roomSlotId", "");
        buildingChar.put("index", -1);
        buildingChar.put("changeScale", 0);

        JSONObject bubble = new JSONObject(true);
        for (String type : Arrays.asList("normal", "assist", "private")) {
            JSONObject entry = new JSONObject(true);
            entry.put("add", -1);
            entry.put("ts", 0);
            bubble.put(type, entry);
        }
        buildingChar.put("bubble", bubble);
        buildingChar.put("workTime", 0);
        buildingChar.put("privateRooms", new JSONArray());
        return buildingChar;
    }

    private JSONObject handleExistingCharReward(JSONObject UserSyncData, String randomCharId, JSONObject char_data,
                                                JSONObject chars, JSONObject troop) {
        int charInstId = char_data.getIntValue("instId");
        JSONObject get_char = new JSONObject(true);
        get_char.put("charInstId", charInstId);
        get_char.put("charId", randomCharId);
        get_char.put("isNew", 0);

        int potentialRank = char_data.getIntValue("potentialRank");
        int reward_rarity = ArknightsApplication.getCharRarity(randomCharId);

        String itemName = null;
        String itemType = null;
        String itemId = null;
        int cur = 0;

        switch (reward_rarity) {
            case 0:
            case 1:
                itemName = "lggShard";
                itemType = "LGG_SHD";
                itemId = "4005";
                cur = reward_rarity == 0 ? 1 : 1;
                break;
            case 2:
                itemName = "lggShard";
                itemType = "LGG_SHD";
                itemId = "4005";
                cur = 5;
                break;
            case 3:
                itemName = "lggShard";
                itemType = "LGG_SHD";
                itemId = "4005";
                cur = 30;
                break;
            case 4:
                itemName = "hggShard";
                itemType = "HGG_SHD";
                itemId = "4004";
                cur = potentialRank != 5 ? 5 : 8;
                break;
            case 5:
                itemName = "hggShard";
                itemType = "HGG_SHD";
                itemId = "4004";
                cur = potentialRank != 5 ? 10 : 15;
                break;
        }

        JSONArray itemGet = new JSONArray();
        JSONObject shard = new JSONObject(true);
        shard.put("type", itemType);
        shard.put("id", itemId);
        shard.put("count", cur);
        itemGet.add(shard);

        UserSyncData.getJSONObject("status").put(itemName, UserSyncData.getJSONObject("status").getIntValue(itemName) + cur);

        JSONObject potentialItem = new JSONObject(true);
        potentialItem.put("type", "MATERIAL");
        potentialItem.put("id", "p_" + randomCharId);
        potentialItem.put("count", 1);
        itemGet.add(potentialItem);

        get_char.put("itemGet", itemGet);
        UserSyncData.getJSONObject("inventory").put("p_" + randomCharId,
                UserSyncData.getJSONObject("inventory").getIntValue("p_" + randomCharId) + 1);

        JSONObject charinstId = new JSONObject(true);
        charinstId.put(String.valueOf(charInstId), char_data);
        chars.put(String.valueOf(charInstId), char_data);
        troop.put("chars", charinstId);
        return get_char;
    }

    private void handleStageUnlock(JSONObject UserSyncData, String stageId, int completeState,
                                   JSONArray unlockStages, JSONArray unlockStagesObject, boolean firstClear) {
        JSONObject added = MainlineProgressionSupport.normalize(UserSyncData, stageTable, mainStage);
        added.putAll(CampaignProgressionSupport.normalize(UserSyncData, stageTable));
        for (String id : added.keySet()) {
            unlockStages.add(id);
            unlockStagesObject.add(added.getJSONObject(id));
        }
    }

    private JSONObject calculateRewards(JSONObject UserSyncData, JSONObject stage_table,
                                        int completeState, boolean FirstClear, int DropRate,
                                        JSONArray firstReward) {
        JSONObject result = new JSONObject();

        // 初始化奖励数组
        JSONArray additionalRewards = new JSONArray();
        JSONArray unusualRewards = new JSONArray();
        JSONArray furnitureRewards = new JSONArray();
        JSONArray firstRewards = new JSONArray();
        JSONArray rewards = new JSONArray();

        // 处理基础奖励
        int expGain = stage_table.getIntValue("expGain");
        int goldGain = stage_table.getIntValue("goldGain");

        if (completeState == 3) {
            expGain = (int)(expGain * 1.2);
            goldGain = (int)(goldGain * 1.2);
            result.put("goldScale", 1.2);
            result.put("expScale", 1.2);
        } else {
            result.put("goldScale", 1);
            result.put("expScale", 1);
        }

        // 发放金币
        if (goldGain != 0) {
            UserSyncData.getJSONObject("status").put("gold",
                    UserSyncData.getJSONObject("status").getIntValue("gold") + goldGain);

            JSONObject goldReward = new JSONObject(true);
            goldReward.put("count", goldGain);
            goldReward.put("id", 4001);
            goldReward.put("type", "GOLD");
            rewards.add(goldReward);
        }

        // 发放经验
        if (expGain != 0) {
            handleExpReward(UserSyncData, expGain);
        }

        // 处理掉落奖励
        JSONArray displayDetailRewards = stage_table.getJSONObject("stageDropInfo").getJSONArray("displayDetailRewards");

        for (int i = 0; i < displayDetailRewards.size(); i++) {
            JSONObject reward = displayDetailRewards.getJSONObject(i);
            int occPercent = resolveOccPercent(reward.getString("occPercent"));
            int dropType = resolveDropType(reward.getString("dropType"));
            int reward_count = 1 * DropRate;
            String reward_id = reward.getString("id");
            String reward_type = reward.getString("type");

            // 根据掉落类型和概率处理奖励
            handleDropReward(UserSyncData, occPercent, dropType, reward_count, reward_id, reward_type,
                    completeState, FirstClear, additionalRewards, unusualRewards,
                    furnitureRewards, firstRewards, rewards);
        }

        // 设置返回结果
        result.put("additionalRewards", additionalRewards);
        result.put("unusualRewards", unusualRewards);
        result.put("furnitureRewards", furnitureRewards);
        result.put("firstRewards", firstReward);
        result.put("rewards", rewards);

        return result;
    }

    void handleExpReward(JSONObject UserSyncData, int expGain) {
        JSONArray playerExpMap = JSON.parseArray("[500,800,1240,1320,1400,1480,1560,1640,1720,1800,1880,1960,2040,2120,2200,2280,2360,2440,2520,2600,2680,2760,2840,2920,3000,3080,3160,3240,3350,3460,3570,3680,3790,3900,4200,4500,4800,5100,5400,5700,6000,6300,6600,6900,7200,7500,7800,8100,8400,8700,9000,9500,10000,10500,11000,11500,12000,12500,13000,13500,14000,14500,15000,15500,16000,17000,18000,19000,20000,21000,22000,23000,24000,25000,26000,27000,28000,29000,30000,31000,32000,33000,34000,35000,36000,37000,38000,39000,40000,41000,42000,43000,44000,45000,46000,47000,48000,49000,50000,51000,52000,54000,56000,58000,60000,62000,64000,66000,68000,70000,73000,76000,79000,82000,85000,88000,91000,94000,97000,100000]");
        JSONArray playerApMap = JSON.parseArray("[82,84,86,88,90,91,92,93,94,95,96,97,98,99,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119,120,120,120,120,120,121,121,121,121,121,122,122,122,122,122,123,123,123,123,123,124,124,124,124,124,125,125,125,125,125,126,126,126,126,126,127,127,127,127,127,128,128,128,128,128,129,129,129,129,129,130,130,130,130,130,130,130,130,130,130,130,130,130,130,130,130,131,131,131,131,132,132,132,132,133,133,133,133,134,134,134,134,135,135,135,135]");

        int level = UserSyncData.getJSONObject("status").getIntValue("level");
        if (level >= 120) return;

        UserSyncData.getJSONObject("status").put("exp",
                UserSyncData.getJSONObject("status").getIntValue("exp") + expGain);

        for (int i = 0; i < playerExpMap.size(); i++) {
            if (level == i + 1) {
                if (playerExpMap.getIntValue(i) - UserSyncData.getJSONObject("status").getIntValue("exp") <= 0) {
                    if (i + 2 == 120) {
                        // 满级
                        UserSyncData.getJSONObject("status").put("level", 120);
                        UserSyncData.getJSONObject("status").put("exp", 0);
                        UserSyncData.getJSONObject("status").put("maxAp", playerApMap.getIntValue(i + 1));
                        UserSyncData.getJSONObject("status").put("ap", UserSyncData.getJSONObject("status").getIntValue("maxAp"));
                    } else {
                        // 升级
                        UserSyncData.getJSONObject("status").put("level", i + 2);
                        UserSyncData.getJSONObject("status").put("exp",
                                UserSyncData.getJSONObject("status").getIntValue("exp") - playerExpMap.getIntValue(i));
                        UserSyncData.getJSONObject("status").put("maxAp", playerApMap.getIntValue(i + 1));
                        UserSyncData.getJSONObject("status").put("ap",
                                UserSyncData.getJSONObject("status").getIntValue("ap") + UserSyncData.getJSONObject("status").getIntValue("maxAp"));
                    }
                    UserSyncData.getJSONObject("status").put("lastApAddTime", (int)(new Date().getTime() / 1000L));
                }
                break;
            }
        }
    }

    private void handleDropReward(JSONObject UserSyncData, int occPercent, int dropType, int reward_count,
                                  String reward_id, String reward_type, int completeState, boolean FirstClear,
                                  JSONArray additionalRewards, JSONArray unusualRewards,
                                  JSONArray furnitureRewards, JSONArray firstRewards, JSONArray rewards) {
        // 根据掉落类型和概率处理奖励
        switch (dropType) {
            case 2: // 普通掉落
                handleNormalDrop(UserSyncData, occPercent, reward_count, reward_id, reward_type,
                        completeState, FirstClear, furnitureRewards, rewards);
                break;
            case 3: // 稀有掉落
                handleRareDrop(UserSyncData, occPercent, reward_count, reward_id, reward_type,
                        unusualRewards);
                break;
            case 4: // 额外掉落
                handleExtraDrop(UserSyncData, occPercent, reward_count, reward_id, reward_type,
                        additionalRewards);
                break;
        }
    }

    private void handleNormalDrop(JSONObject UserSyncData, int occPercent, int reward_count,
                                  String reward_id, String reward_type, int completeState,
                                  boolean FirstClear, JSONArray furnitureRewards, JSONArray rewards) {
        JSONArray dropArray = new JSONArray();
        int cur = 0;

        // 根据概率初始化掉落数组
        switch (occPercent) {
            case 0: // 必定掉落
                handleGuaranteedDrop(UserSyncData, reward_count, reward_id, reward_type,
                        furnitureRewards, rewards);
                break;
            case 1: // 80%概率
                IntStream.range(0, 80).forEach(n -> dropArray.add(1));
                IntStream.range(0, 20).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, furnitureRewards, rewards);
                }
                break;
            case 2: // 50%概率
                IntStream.range(0, 50).forEach(n -> dropArray.add(1));
                IntStream.range(0, 50).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, furnitureRewards, rewards);
                }
                break;
            case 3: // 15%概率
                IntStream.range(0, 15).forEach(n -> dropArray.add(1));
                IntStream.range(0, 85).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, furnitureRewards, rewards);
                }
                break;
            case 4: // 10%概率
                IntStream.range(0, 10).forEach(n -> dropArray.add(1));
                IntStream.range(0, 90).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, furnitureRewards, rewards);
                }
                break;
        }
    }

    private void handleGuaranteedDrop(JSONObject UserSyncData, int reward_count,
                                      String reward_id, String reward_type,
                                      JSONArray furnitureRewards, JSONArray rewards) {
        switch (reward_type) {
            case "MATERIAL":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "CARD_EXP":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "DIAMOND":
                UserSyncData.getJSONObject("status").put("androidDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("androidDiamond") + reward_count);
                UserSyncData.getJSONObject("status").put("iosDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("iosDiamond") + reward_count);
                break;
            case "GOLD":
                UserSyncData.getJSONObject("status").put("gold",
                        UserSyncData.getJSONObject("status").getIntValue("gold") + reward_count);
                break;
            case "TKT_RECRUIT":
                UserSyncData.getJSONObject("status").put("recruitLicense",
                        UserSyncData.getJSONObject("status").getIntValue("recruitLicense") + reward_count);
                break;
            case "FURN":
                if (!UserSyncData.getJSONObject("building").getJSONObject("furniture").containsKey(reward_id)) {
                    JSONObject furniture = new JSONObject(true);
                    furniture.put("count", reward_count);
                    furniture.put("inUse", 0);
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").put(reward_id, furniture);
                } else {
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id)
                            .put("count", UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id).getIntValue("count") + reward_count);
                }
                break;
        }

        JSONObject reward = new JSONObject(true);
        reward.put("count", reward_count);
        reward.put("id", reward_id);
        reward.put("type", reward_type);

        if (!reward_type.equals("FURN")) {
            rewards.add(reward);
        } else {
            furnitureRewards.add(reward);
        }
    }

    private void grantReward(JSONObject UserSyncData, String reward_id, String reward_type,
                             int reward_count, JSONArray furnitureRewards, JSONArray rewards) {
        switch (reward_type) {
            case "MATERIAL":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "CARD_EXP":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "DIAMOND":
                UserSyncData.getJSONObject("status").put("androidDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("androidDiamond") + reward_count);
                UserSyncData.getJSONObject("status").put("iosDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("iosDiamond") + reward_count);
                break;
            case "GOLD":
                UserSyncData.getJSONObject("status").put("gold",
                        UserSyncData.getJSONObject("status").getIntValue("gold") + reward_count);
                break;
            case "TKT_RECRUIT":
                UserSyncData.getJSONObject("status").put("recruitLicense",
                        UserSyncData.getJSONObject("status").getIntValue("recruitLicense") + reward_count);
                break;
            case "FURN":
                if (!UserSyncData.getJSONObject("building").getJSONObject("furniture").containsKey(reward_id)) {
                    JSONObject furniture = new JSONObject(true);
                    furniture.put("count", reward_count);
                    furniture.put("inUse", 0);
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").put(reward_id, furniture);
                } else {
                    UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id)
                            .put("count", UserSyncData.getJSONObject("building").getJSONObject("furniture").getJSONObject(reward_id).getIntValue("count") + reward_count);
                }
                break;
        }

        JSONObject reward = new JSONObject(true);
        reward.put("count", reward_count);
        reward.put("id", reward_id);
        reward.put("type", reward_type);

        if (!reward_type.equals("FURN")) {
            rewards.add(reward);
        } else {
            furnitureRewards.add(reward);
        }
    }

    private void handleRareDrop(JSONObject UserSyncData, int occPercent, int reward_count,
                                String reward_id, String reward_type, JSONArray unusualRewards) {
        JSONArray dropArray = new JSONArray();
        int cur = 0;

        switch (occPercent) {
            case 0: // 必定掉落
                grantReward(UserSyncData, reward_id, reward_type, reward_count, unusualRewards);
                break;
            case 3: // 5%概率
                IntStream.range(0, 5).forEach(n -> dropArray.add(1));
                IntStream.range(0, 95).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, unusualRewards);
                }
                break;
            case 4: // 5%概率
                IntStream.range(0, 5).forEach(n -> dropArray.add(1));
                IntStream.range(0, 95).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, unusualRewards);
                }
                break;
        }
    }

    private void grantReward(JSONObject UserSyncData, String reward_id, String reward_type,
                             int reward_count, JSONArray unusualRewards) {
        switch (reward_type) {
            case "MATERIAL":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "CARD_EXP":
                UserSyncData.getJSONObject("inventory").put(reward_id,
                        UserSyncData.getJSONObject("inventory").getIntValue(reward_id) + reward_count);
                break;
            case "DIAMOND":
                UserSyncData.getJSONObject("status").put("androidDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("androidDiamond") + reward_count);
                UserSyncData.getJSONObject("status").put("iosDiamond",
                        UserSyncData.getJSONObject("status").getIntValue("iosDiamond") + reward_count);
                break;
            case "GOLD":
                UserSyncData.getJSONObject("status").put("gold",
                        UserSyncData.getJSONObject("status").getIntValue("gold") + reward_count);
                break;
            case "TKT_RECRUIT":
                UserSyncData.getJSONObject("status").put("recruitLicense",
                        UserSyncData.getJSONObject("status").getIntValue("recruitLicense") + reward_count);
                break;
        }

        JSONObject reward = new JSONObject(true);
        reward.put("count", reward_count);
        reward.put("id", reward_id);
        reward.put("type", reward_type);
        unusualRewards.add(reward);
    }

    private void handleExtraDrop(JSONObject UserSyncData, int occPercent, int reward_count,
                                 String reward_id, String reward_type, JSONArray additionalRewards) {
        JSONArray dropArray = new JSONArray();
        int cur = 0;

        switch (occPercent) {
            case 0:
                grantReward(UserSyncData, reward_id, reward_type, reward_count, additionalRewards);
                break;
            case 3: // 5%概率
                IntStream.range(0, 5).forEach(n -> dropArray.add(1));
                IntStream.range(0, 95).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, additionalRewards);
                }
                break;
            case 4: // 25%概率
                IntStream.range(0, 25).forEach(n -> dropArray.add(1));
                IntStream.range(0, 75).forEach(n -> dropArray.add(0));
                Collections.shuffle(dropArray);
                cur = dropArray.getIntValue(new Random().nextInt(dropArray.size()));
                if (cur == 1) {
                    grantReward(UserSyncData, reward_id, reward_type, reward_count, additionalRewards);
                }
                break;
        }
    }


    void updateOperatorFavor(JSONObject UserSyncData, JSONObject BattleData,
                                     JSONObject stage_table, int completeState) {
        int completeFavor = stage_table.getIntValue("completeFavor");
        int passFavor = stage_table.getIntValue("passFavor");
        JSONObject charList = BattleData.getJSONObject("battleData").getJSONObject("stats").getJSONObject("charList");

        for (Map.Entry<String, Object> entry : charList.entrySet()) {
            String charInstId = entry.getKey();
            if (!UserSyncData.getJSONObject("troop").getJSONObject("chars").containsKey(charInstId)) {
                continue;
            }

            JSONObject charData = UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(charInstId);
            String charId = charData.getString("charId");
            int currentFavor = charData.getIntValue("favorPoint");

            if (completeState != 3 && completeState != 4) {
                // 非三星通关
                charData.put("favorPoint", currentFavor + passFavor);
                if (UserSyncData.getJSONObject("troop").getJSONObject("charGroup").containsKey(charId)) {
                    UserSyncData.getJSONObject("troop").getJSONObject("charGroup").getJSONObject(charId)
                            .put("favorPoint", currentFavor + passFavor);
                }
            } else {
                // 三星通关
                charData.put("favorPoint", currentFavor + completeFavor);
                if (UserSyncData.getJSONObject("troop").getJSONObject("charGroup").containsKey(charId)) {
                    UserSyncData.getJSONObject("troop").getJSONObject("charGroup").getJSONObject(charId)
                            .put("favorPoint", currentFavor + completeFavor);
                }
            }
        }
    }

    private JSONObject buildPlayerDataDelta(JSONObject UserSyncData, JSONArray unlockStagesObject,
                                            String stageId, JSONObject troop) {
        JSONObject playerDataDelta = new JSONObject(true);
        JSONObject modified = new JSONObject(true);

        // 构建关卡数据
        JSONObject dungeon = new JSONObject(true);
        JSONObject stages = new JSONObject(true);

        for (int i = 0; i < unlockStagesObject.size(); i++) {
            String unlockedStageId = unlockStagesObject.getJSONObject(i).getString("stageId");
            stages.put(unlockedStageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(unlockedStageId));
        }

        stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
        dungeon.put("stages", stages);

        modified.put("dungeon", dungeon);
        modified.put("status", UserSyncData.getJSONObject("status"));
        modified.put("troop", troop);
        modified.put("inventory", UserSyncData.getJSONObject("inventory"));

        JSONObject troopChars = troop.getJSONObject("chars");
        if (troopChars != null) {
            JSONObject buildingChars = new JSONObject(true);
            JSONObject userBuildingChars = UserSyncData.getJSONObject("building").getJSONObject("chars");
            for (String charInstId : troopChars.keySet()) {
                if (userBuildingChars.containsKey(charInstId)) {
                    buildingChars.put(charInstId, userBuildingChars.getJSONObject(charInstId));
                }
            }
            if (!buildingChars.isEmpty()) {
                JSONObject building = new JSONObject(true);
                building.put("chars", buildingChars);
                modified.put("building", building);
            }
        }

        // Furniture drops also need to reach the client, including repeated drops.
        JSONObject changedBuilding = modified.getJSONObject("building");
        if (changedBuilding == null) {
            changedBuilding = new JSONObject(true);
            modified.put("building", changedBuilding);
        }
        changedBuilding.put("furniture", UserSyncData.getJSONObject("building").getJSONObject("furniture"));

        playerDataDelta.put("deleted", new JSONObject(true));
        playerDataDelta.put("modified", modified);

        return playerDataDelta;
    }


    @PostMapping(
            value = {"/squadFormation"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject SquadFormation(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/squadFormation");
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            JSONObject result = new JSONObject(true);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        } else {
            String squadId = JsonBody.getString("squadId");
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    UserSyncData.getJSONObject("troop").getJSONObject("squads").getJSONObject(squadId).put("slots", JsonBody.getJSONArray("slots"));
                    JSONObject missionBefore = CharacterBuildingSupport.copy(UserSyncData.getJSONObject("mission"));
                    MissionProgressSupport.onAction(UserSyncData, "squadChange", new JSONObject(),
                            System.currentTimeMillis() / 1000L);
                    userDao.setUserData(uid, UserSyncData);
                    JSONObject result = new JSONObject(true);
                    JSONObject playerDataDelta = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    JSONObject troop = new JSONObject(true);
                    JSONObject squads = new JSONObject(true);
                    JSONObject squad = UserSyncData.getJSONObject("troop").getJSONObject("squads").getJSONObject(squadId);
                    squads.put(squadId, squad);
                    troop.put("squads", squads);
                    modified.put("troop", troop);
                    
                    playerDataDelta.put("modified", modified);
                    playerDataDelta.put("deleted", new JSONObject(true));
                    MissionProgressSupport.writeDelta(UserSyncData, playerDataDelta, missionBefore);
                    result.put("playerDataDelta", playerDataDelta);
                    return result;
                }
            }
        }
    }

    @PostMapping(
            value = {"/saveBattleReplay"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject SaveBattleReplay(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/saveBattleReplay");
        JSONObject BattleData;
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            BattleData = new JSONObject(true);
            BattleData.put("statusCode", 400);
            BattleData.put("error", "Bad Request");
            BattleData.put("message", "server is close");
            return BattleData;
        } else {
            BattleData = Utils.BattleReplay_decrypt(JsonBody.getString("battleReplay"));
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    String stageId = BattleData.getJSONObject("journal").getJSONObject("metadata").getString("stageId");
                    JSONObject stages_data = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
                    stages_data.put("hasBattleReplay", 1);
                    stages_data.put("battleReplay", JsonBody.getString("battleReplay"));
                    userDao.setUserData(uid, UserSyncData);
                    JSONObject result = new JSONObject(true);
                    JSONObject playerDataDelta = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    JSONObject dungeon = new JSONObject(true);
                    JSONObject stages = new JSONObject(true);
                    stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
                    dungeon.put("stages", stages);
                    modified.put("dungeon", dungeon);
                    playerDataDelta.put("deleted", new JSONObject(true));
                    playerDataDelta.put("modified", modified);
                    result.put("playerDataDelta", playerDataDelta);
                    return result;
                }
            }
        }
    }

    @PostMapping(
            value = {"/getBattleReplay"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject GetBattleReplay(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/getBattleReplay");
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            JSONObject result = new JSONObject(true);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        } else {
            String stageId = JsonBody.getString("stageId");
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    JSONObject result = new JSONObject(true);
                    JSONObject playerDataDelta = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    playerDataDelta.put("deleted", new JSONObject(true));
                    playerDataDelta.put("modified", modified);
                    result.put("playerDataDelta", playerDataDelta);
                    result.put("battleReplay", UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId).getString("battleReplay"));
                    return result;
                }
            }
        }
    }

    @PostMapping(
            value = {"/changeSquadName"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject ChangeSquadName(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/changeSquadName");
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            JSONObject result = new JSONObject(true);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        } else {
            String squadId = JsonBody.getString("squadId");
            String name = JsonBody.getString("name");
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    UserSyncData.getJSONObject("troop").getJSONObject("squads").getJSONObject(squadId).put("name", name);
                    JSONObject missionBefore = CharacterBuildingSupport.copy(UserSyncData.getJSONObject("mission"));
                    MissionProgressSupport.onAction(UserSyncData, "squadRename", new JSONObject(), System.currentTimeMillis() / 1000L);
                    userDao.setUserData(uid, UserSyncData);
                    JSONObject result = new JSONObject(true);
                    JSONObject playerDataDelta = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    JSONObject troop = new JSONObject(true);
                    JSONObject squads = new JSONObject(true);
                    JSONObject squad = UserSyncData.getJSONObject("troop").getJSONObject("squads").getJSONObject(squadId);
                    squads.put(squadId, squad);
                    troop.put("squads", squads);
                    modified.put("troop", troop);
                    playerDataDelta.put("modified", modified);
                    playerDataDelta.put("deleted", new JSONObject(true));
                    MissionProgressSupport.writeDelta(UserSyncData, playerDataDelta, missionBefore);
                    result.put("playerDataDelta", playerDataDelta);
                    return result;
                }
            }
        }
    }

    @PostMapping(
            value = {"/getAssistList"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject getAssistList(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject result = CharacterBuildingSupport.result(new JSONObject(true));
            result.put("allowAskTs", System.currentTimeMillis() / 1000L);
            result.put("assistList", LocalAssistSupport.list(user.getJSONObject("status").getLongValue("uid"),
                    body.getString("profession")));
            result.put("starFriendAssistList", new JSONArray());
            return result;
        });
    }

    @PostMapping(
            value = {"/finishStoryStage"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject finishStoryStage(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        if (JsonBody == null) return BattleRepeatSupport.error("Missing story stage request");
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /quest/finishStoryStage");
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            JSONObject result = new JSONObject(true);
            result.put("statusCode", 400);
            result.put("error", "Bad Request");
            result.put("message", "server is close");
            return result;
        } else {
            String stageId = JsonBody.getString("stageId");
            List<Account> Accounts = userDao.queryAccountBySecret(secret);
            if (Accounts.size() != 1) {
                JSONObject result = new JSONObject(true);
                result.put("result", 2);
                result.put("error", "无法查询到此账户");
                return result;
            } else {
                Long uid = ((Account)Accounts.get(0)).getUid();
                JSONObject UserSyncData;
                if (((Account)Accounts.get(0)).getBan() == 1L) {
                    response.setStatus(500);
                    UserSyncData = new JSONObject(true);
                    UserSyncData.put("statusCode", 403);
                    UserSyncData.put("error", "Bad Request");
                    UserSyncData.put("message", "error");
                    return UserSyncData;
                } else {
                    UserSyncData = JSONObject.parseObject(((Account)Accounts.get(0)).getUser());
                    JSONObject definition = stageTable.getJSONObject(stageId);
                    if (definition == null || !definition.getBooleanValue("isStoryOnly")
                            || !MainlineProgressionSupport.isMainline(definition))
                        return BattleRepeatSupport.error("Unknown mainline story stage");
                    JSONObject restored = MainlineProgressionSupport.normalize(UserSyncData, stageTable, mainStage);
                    restored.putAll(CampaignProgressionSupport.normalize(UserSyncData, stageTable));
                    JSONObject savedStage = UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
                    if (savedStage == null) return BattleRepeatSupport.error("Story stage is locked");
                    int stage_state = savedStage.getIntValue("state");
                    JSONArray rewards = new JSONArray();
                    JSONArray unlockStages = new JSONArray();
                    JSONArray unlockStagesObject = new JSONArray();
                    for (String id : restored.keySet()) {
                        unlockStages.add(id);
                        unlockStagesObject.add(restored.getJSONObject(id));
                    }
                    JSONArray alert = new JSONArray();
                    JSONObject hard_unlockStage;
                    JSONObject reward;
                    if (stage_state != 3) {
                        savedStage.put("state", 3);
                        savedStage.put("completeTimes", savedStage.getIntValue("completeTimes") + 1);
                        reward = new JSONObject(true);
                        reward.put("type", "DIAMOND");
                        reward.put("id", "4002");
                        reward.put("count", definition.getIntValue("diamondOnceDrop"));
                        rewards.add(reward);
                        UserSyncData.getJSONObject("status").put("androidDiamond", UserSyncData.getJSONObject("status").getIntValue("androidDiamond") + definition.getIntValue("diamondOnceDrop"));
                        UserSyncData.getJSONObject("status").put("iosDiamond", UserSyncData.getJSONObject("status").getIntValue("iosDiamond") + definition.getIntValue("diamondOnceDrop"));
                    }

                    handleStageUnlock(UserSyncData, stageId, 3, unlockStages, unlockStagesObject, stage_state != 3);

                    reward = new JSONObject(true);
                    hard_unlockStage = new JSONObject(true);
                    JSONObject modified = new JSONObject(true);
                    JSONObject status = new JSONObject(true);
                    status.put("androidDiamond", UserSyncData.getJSONObject("status").getIntValue("androidDiamond"));
                    status.put("iosDiamond", UserSyncData.getJSONObject("status").getIntValue("iosDiamond"));
                    status.put("mainStageProgress", UserSyncData.getJSONObject("status").get("mainStageProgress"));
                    JSONObject dungeon = new JSONObject(true);
                    JSONObject stages = new JSONObject(true);

                    for(int i = 0; i < unlockStagesObject.size(); ++i) {
                        String unlock_stageId = unlockStagesObject.getJSONObject(i).getString("stageId");
                        stages.put(unlock_stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(unlock_stageId));
                    }

                    stages.put(stageId, UserSyncData.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId));
                    dungeon.put("stages", stages);
                    modified.put("status", status);
                    modified.put("dungeon", dungeon);
                    hard_unlockStage.put("deleted", new JSONObject(true));
                    hard_unlockStage.put("modified", modified);
                    reward.put("playerDataDelta", hard_unlockStage);
                    reward.put("rewards", rewards);
                    reward.put("unlockStages", unlockStages);
                    reward.put("alert", alert);
                    reward.put("result", 0);
                    if (userDao.setUserData(uid, UserSyncData) != 1) return BattleRepeatSupport.error("Story stage save failed");
                    return reward;
                }
            }
        }
    }
}
