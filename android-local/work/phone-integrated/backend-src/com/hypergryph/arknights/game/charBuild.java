package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.decrypt.Utils;
import com.hypergryph.arknights.core.pojo.Account;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.hypergryph.arknights.ArknightsApplication.getCharRarity;
import static com.hypergryph.arknights.core.work.checkAccount.checkAccount;
import static com.hypergryph.arknights.core.work.checkAccount.createError;

@RestController
@RequestMapping({"/charBuild"})
public class charBuild {
    public charBuild() {
    }

    private JSONObject characterMutation(JSONObject body, HttpServletResponse response,
                                         HttpServletRequest request, String operation) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        synchronized (CharacterBuildingSupport.class) {
            if (!checkAccount(secret)) return createError(secret, response);
            List<Account> accounts = userDao.queryAccountBySecret(secret);
            if (accounts.size() != 1) return CharacterBuildingSupport.error("Account unavailable");
            Account account = accounts.get(0);
            try {
                CharacterBuildingSupport.Mutation change = CharacterBuildingSupport.character(
                    JSONObject.parseObject(account.getUser()), body, operation, ArknightsApplication.characterJson,
                    CharacterBuildingSupport.table("excel/gamedata_const.json"),
                    CharacterBuildingSupport.table("excel/item_table.json"));
                JSONObject missionBefore = CharacterBuildingSupport.copy(change.user.getJSONObject("mission"));
                MissionProgressSupport.onAction(change.user, operation, new JSONObject(),
                        System.currentTimeMillis() / 1000L);
                MissionProgressSupport.writeDelta(change.user, change.response.getJSONObject("playerDataDelta"), missionBefore);
                if (userDao.setUserData(account.getUid(), change.user) != 1)
                    return CharacterBuildingSupport.error("Save failed");
                // This endpoint's result is the training outcome (1=success), not
                // the usual zero status code. The live client shows a failure at 0.
                if("boostPotential".equals(operation))change.response.put("result",1);
                return change.response;
            } catch (IllegalArgumentException ex) {
                return CharacterBuildingSupport.error(ex.getMessage());
            }
        }
    }

    @PostMapping(
            value = {"/upgradeChar"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject UpgradeChar(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return characterMutation(JsonBody, response, request, "upgradeChar");
    }

    @PostMapping(
            value = {"/upgradeSkill"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject UpgradeSkill(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return characterMutation(JsonBody, response, request, "upgradeSkill");
    }

    @PostMapping(
            value = {"/setDefaultSkill"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject SetDefaultSkill(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] 设置技能 /charBuild/setDefaultSkill");
        int charInstId = JsonBody.getIntValue("charInstId");
        int defaultSkillIndex = JsonBody.getInteger("defaultSkillIndex");
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
                UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)).put("defaultSkillIndex", defaultSkillIndex);
                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                JSONObject chars = new JSONObject(true);
                chars.put(String.valueOf(charInstId), UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)));
                troop.put("chars", chars);
                modified.put("troop", troop);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                return result;
            }
        }
    }

    @PostMapping(
            value = {"/boostPotential"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject BoostPotential(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return characterMutation(JsonBody, response, request, "boostPotential");
    }

    @PostMapping(
            value = {"/evolveChar"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject EvolveChar(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return characterMutation(JsonBody, response, request, "evolveChar");
    }

    @PostMapping(
            value = {"/changeCharSkin"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject ChangeCharSkin(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/changeCharSkin");
        int charInstId = JsonBody.getIntValue("charInstId");
        String skinId = JsonBody.getString("skinId");
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
                UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)).put("skin", skinId);
                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                JSONObject chars = new JSONObject(true);
                chars.put(String.valueOf(charInstId), UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)));
                troop.put("chars", chars);
                modified.put("troop", troop);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                return result;
            }
        }
    }

    @PostMapping(
            value = {"/setCharVoiceLan"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject setCharVoiceLan(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/setCharVoiceLan");
        JSONArray charList = JsonBody.getJSONArray("charList");
        String voiceLan = JsonBody.getString("voiceLan");
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

                for(int i = 0; i < charList.size(); ++i) {
                    UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(charList.getString(i)).put("voiceLan", voiceLan);
                }

                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                troop.put("chars", UserSyncData.getJSONObject("troop").getJSONObject("chars"));
                modified.put("troop", troop);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                return result;
            }
        }
    }

    @PostMapping(
            value = {"/batchSetCharVoiceLan"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject batchSetCharVoiceLan(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/batchSetCharVoiceLan");
        String voiceLan = JsonBody.getString("voiceLan");
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
                JSONObject voiceLangDict = ArknightsApplication.charwordTable.getJSONObject("voiceLangDict");
                JSONObject chars = UserSyncData.getJSONObject("troop").getJSONObject("chars");
                Iterator var12 = chars.entrySet().iterator();

                JSONObject cvDictionary;
                while(var12.hasNext()) {
                    Map.Entry entry = (Map.Entry)var12.next();
                    String charId = chars.getJSONObject(entry.getKey().toString()).getString("charId");
                    if (voiceLangDict.containsKey(charId)) {
                        cvDictionary = voiceLangDict.getJSONObject(charId).getJSONObject("dict");
                        if (cvDictionary.containsKey(voiceLan)) {
                            chars.getJSONObject(entry.getKey().toString()).put("voiceLan", voiceLan);
                        }
                    }
                }

                UserSyncData.getJSONObject("status").put("globalVoiceLan", voiceLan);
                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                cvDictionary = new JSONObject(true);
                cvDictionary.put("chars", chars);
                modified.put("troop", cvDictionary);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                result.put("result", 0);
                return result;
            }
        }
    }

    @PostMapping(
            value = {"/unlockEquipment"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject unlockEquipment(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/unlockEquipment");
        int charInstId = JsonBody.getIntValue("charInstId");
        String templateId = JsonBody.getString("templateId");
        String equipId = JsonBody.getString("equipId");
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
                UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)).getJSONObject("equip").getJSONObject(equipId).put("locked", 0);
                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                JSONObject chars = new JSONObject(true);
                chars.put(String.valueOf(charInstId), UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)));
                troop.put("chars", chars);
                modified.put("troop", troop);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                return result;
            }
        }
    }

    @PostMapping(
            value = {"/setEquipment"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject setEquipment(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> EquipmentSupport.select(user, JsonBody));
    }

    @PostMapping(
            value = {"/addonStory/unlock"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject addonStoryUnlock(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/addonStory/unlock");
        String charId = JsonBody.getString("charId");
        String storyId = JsonBody.getString("storyId");
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
                JSONObject story = new JSONObject(true);
                story.put("fts", (new Date()).getTime() / 1000L);
                story.put("rts", (new Date()).getTime() / 1000L);
                JSONObject addon = UserSyncData.getJSONObject("troop").getJSONObject("addon");
                JSONObject char_data = new JSONObject(true);
                JSONObject story_1;
                if (addon.containsKey(charId)) {
                    if (addon.getJSONObject(charId).containsKey("story")) {
                        addon.getJSONObject(charId).getJSONObject("story").put(storyId, story);
                        UserSyncData.getJSONObject("troop").put("addon", addon);
                    } else {
                        story_1 = new JSONObject(true);
                        story_1.put(storyId, story);
                        char_data = addon.getJSONObject(charId);
                        char_data.put("story", story_1);
                        addon.put(charId, char_data);
                        UserSyncData.getJSONObject("troop").put("addon", addon);
                    }
                } else {
                    story_1 = new JSONObject(true);
                    story_1.put(storyId, story);
                    char_data.put("story", story_1);
                    addon.put(charId, char_data);
                    UserSyncData.getJSONObject("troop").put("addon", addon);
                }

                userDao.setUserData(uid, UserSyncData);
                story_1 = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                troop.put("addon", addon);
                modified.put("troop", troop);
                playerDataDelta.put("modified", modified);
                story_1.put("playerDataDelta", playerDataDelta);
                return story_1;
            }
        }
    }

    @PostMapping(
            value = {"/addonStage/battleStart"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject addonStageBattleStart(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return AddonStageSupport.battleStart(JsonBody, response, request);
    }

    @PostMapping(
            value = {"/addonStage/battleFinish"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject addonStageBattleFinish(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        return AddonStageSupport.battleFinish(JsonBody, response, request);
    }

    @PostMapping(
            value = {"/changeCharTemplate"},
            produces = {"application/json;charset=UTF-8"}
    )
    public JSONObject changeCharTemplate(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /charBuild/changeCharTemplate");
        int charInstId = JsonBody.getIntValue("charInstId");
        String templateId = JsonBody.getString("templateId");
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
                UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)).put("currentTmpl", templateId);
                userDao.setUserData(uid, UserSyncData);
                JSONObject result = new JSONObject(true);
                JSONObject modified = new JSONObject(true);
                JSONObject playerDataDelta = new JSONObject(true);
                JSONObject troop = new JSONObject(true);
                JSONObject chars = new JSONObject(true);
                chars.put(String.valueOf(charInstId), UserSyncData.getJSONObject("troop").getJSONObject("chars").getJSONObject(String.valueOf(charInstId)));
                troop.put("chars", chars);
                modified.put("troop", troop);
                playerDataDelta.put("deleted", new JSONObject(true));
                playerDataDelta.put("modified", modified);
                result.put("playerDataDelta", playerDataDelta);
                return result;
            }
        }
    }
}
