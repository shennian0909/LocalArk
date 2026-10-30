package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import static com.hypergryph.arknights.core.work.checkAccount.checkAccount;
import static com.hypergryph.arknights.core.work.checkAccount.createError;

@RestController
@RequestMapping("/building")
public class building {
    /** Test-only clock override; zero means real server time. */
    static long TEST_NOW;

    @PostMapping(value={"/getRecentVisitors"},produces="application/json;charset=UTF-8")
    public JSONObject getRecentVisitors(HttpServletRequest request,HttpServletResponse response){
        return offlineVisitors(request,response,true);
    }
    @PostMapping(value={"/getInfoShareVisitorsNum"},produces="application/json;charset=UTF-8")
    public JSONObject getInfoShareVisitorsNum(HttpServletRequest request,HttpServletResponse response){
        return offlineVisitors(request,response,false);
    }
    private JSONObject offlineVisitors(HttpServletRequest request,HttpServletResponse response,boolean list){
        String secret=ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if(!checkAccount(secret))return createError(secret,response);
        JSONObject result=new JSONObject(true);
        // An isolated phone save has no visiting remote accounts or shared clue sessions.
        if(list)result.put("visitors",new com.alibaba.fastjson.JSONArray());
        else result.put("num",0);
        JSONObject delta=new JSONObject(true);delta.put("modified",new JSONObject(true));delta.put("deleted",new JSONObject(true));
        result.put("playerDataDelta",delta);result.put("ts",System.currentTimeMillis()/1000L);
        return result;
    }
    @PostMapping(value = {"/sync"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject sync(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return mutate(body, request, response, "sync");
    }
    @PostMapping(value = {"/cleanRoom", "/cleanRoomSlot"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject cleanRoom(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return mutate(body, request, response, "cleanRoom");
    }
    @PostMapping(value = {"/buildRoom"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject buildRoom(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return mutate(body, request, response, "buildRoom");
    }
    @PostMapping(value = {"/upgradeRoom"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject upgradeRoom(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return mutate(body, request, response, "upgradeRoom");
    }
    @PostMapping(value = {"/degradeRoom"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject degradeRoom(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return mutate(body, request, response, "degradeRoom");
    }
    @PostMapping(value = {"/setBuildingAssist"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject setBuildingAssist(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "setBuildingAssist");
    }
    @PostMapping(value = {"/setPrivateDormOwner"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject setPrivateDormOwner(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "setPrivateDormOwner");
    }
    @PostMapping(value = {"/gainIntimacy"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject gainIntimacy(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "gainIntimacy");
    }
    @PostMapping(value = {"/gainAssistIntimacy"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject gainAssistIntimacy(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "gainAssistIntimacy");
    }
    @PostMapping(value = {"/settleManufacture"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject settleManufacture(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "settleManufacture");
    }
    @PostMapping(value = {"/deliveryOrder"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject deliveryOrder(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        synchronized (CharacterBuildingSupport.class) {
            if (!checkAccount(secret)) return createError(secret, response);
            List<Account> accounts = userDao.queryAccountBySecret(secret);
            if (accounts.size() != 1) return CharacterBuildingSupport.error("Account unavailable");
            Account account = accounts.get(0);
            try {
                CharacterBuildingSupport.require(body != null, "Missing delivery request");
                String slotId = body.getString("slotId");
                CharacterBuildingSupport.require(slotId != null && !slotId.isEmpty(), "Missing slotId");
                int orderId = CharacterBuildingSupport.integer(body, "orderId");
                JSONObject user = CharacterBuildingSupport.copy(
                        JSONObject.parseObject(account.getUser()));
                JSONObject missionBefore = CharacterBuildingSupport.copy(user.getJSONObject("mission"));
                long now = TEST_NOW > 0 ? TEST_NOW : System.currentTimeMillis() / 1000L;
                JSONObject gain = BaseProductionSupport.deliverOrder(user, slotId, orderId);
                JSONObject context = new JSONObject(true);
                context.put("runs", 1);
                JSONObject items = new JSONObject(true);
                items.put(gain.getString("id"), 1);
                context.put("items", items);
                MissionProgressSupport.onAction(user, "orderDelivered", context, now);
                JSONObject modified = pushed(user);
                
                if (userDao.setUserData(account.getUid(), user) != 1)
                    return CharacterBuildingSupport.error("Save failed");
                JSONObject result = CharacterBuildingSupport.result(modified);
                MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"), missionBefore);
                return result;
            } catch (IllegalArgumentException ex) {
                return CharacterBuildingSupport.error(ex.getMessage());
            }
        }
    }
    @PostMapping(value = {"/deliveryBatchOrder"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject deliveryBatchOrder(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (!checkAccount(secret)) return createError(secret, response);
        return CharacterBuildingSupport.error("batch trading order delivery is not backed by verified response data");
    }
    @PostMapping(value = {"/gainAllIntimacy"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject gainAllIntimacy(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "gainAllIntimacy");
    }
    @PostMapping(value = {"/confirmPrivateDormIntimacy"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject confirmPrivateDormIntimacy(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "confirmPrivateDormIntimacy");
    }
    @PostMapping(value = {"/getAssistReport"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject getAssistReport(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (!checkAccount(secret)) return createError(secret, response);
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts.size() != 1) return CharacterBuildingSupport.error("Account unavailable");
        try {
            return BaseProductionSupport.assistReport(JSONObject.parseObject(accounts.get(0).getUser()));
        } catch (IllegalArgumentException ex) {
            return CharacterBuildingSupport.error(ex.getMessage());
        }
    }
    @PostMapping(value = {"/changeManufactureSolution"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject changeManufactureSolution(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> {
            long now = TEST_NOW > 0 ? TEST_NOW : System.currentTimeMillis() / 1000L;
            JSONObject result = ManufactureRuntimeSupport.change(user, body, now);
            publishBuildingEvent(user, result, now);
            return result;
        });
    }
    @PostMapping(value = {"/assignChar"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject assignChar(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "assignChar");
    }
    @PostMapping(value = {"/buyLabor"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject buyLabor(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "buyLabor");
    }
    @PostMapping(value = {"/upgradeSpecialization"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject upgradeSpecialization(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "upgradeSpecialization");
    }
    @PostMapping(value = {"/completeUpgradeSpecialization"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject completeUpgradeSpecialization(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "completeUpgradeSpecialization");
    }
    // batchChangeWorkChar swaps workers into production posts; that is the opposite of
    // one-key resting and has no verified contract here, so it stays unmapped.
    @PostMapping(value = {"/batchRestChar"}, produces = {"application/json;charset=UTF-8"})
    public JSONObject batchRestChar(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return produce(body, request, response, "batchRestChar");
    }
    private JSONObject produce(JSONObject body, HttpServletRequest request, HttpServletResponse response, String operation) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        synchronized (CharacterBuildingSupport.class) {
            if (!checkAccount(secret)) return createError(secret, response);
            List<Account> accounts = userDao.queryAccountBySecret(secret);
            if (accounts.size() != 1) return CharacterBuildingSupport.error("Account unavailable");
            Account account = accounts.get(0);
            try {
                JSONObject user = CharacterBuildingSupport.copy(JSONObject.parseObject(account.getUser()));
                JSONObject missionBefore = CharacterBuildingSupport.copy(user.getJSONObject("mission"));
                CharacterBuildingSupport.require(body != null, "Missing building request");
                long now = TEST_NOW > 0 ? TEST_NOW : System.currentTimeMillis() / 1000L;
                BaseRoomRuntimeSupport.mutate(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
                TrainingSkillSupport.advance(user, now);
                BaseStaffRuntimeSupport.advance(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
                JSONObject result = switch (operation) {
                    case "settleManufacture" -> settle(user, body, now);
                    case "gainAllIntimacy" -> intimacy(user, now);
                    case "confirmPrivateDormIntimacy" -> privateIntimacy(user, body, now);
                    case "assignChar" -> assign(user, body, now);
                    case "buyLabor" -> buyLabor(user, body, now);
                    case "upgradeSpecialization" -> upgradeSpecialization(user, body, now);
                    case "completeUpgradeSpecialization" -> completeUpgradeSpecialization(user, now);
                    case "gainIntimacy", "gainAssistIntimacy" -> singleIntimacy(user, body, operation, now);
                    case "setBuildingAssist" -> setAssist(user, body, now);
                    case "setPrivateDormOwner" -> setPrivateOwner(user, body, now);
                    default -> batchRest(user, now);
                };
                BaseRoomRuntimeSupport.refreshStationBuffs(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
                if (!"gainAllIntimacy".equals(operation) && !"confirmPrivateDormIntimacy".equals(operation))
                    BaseProductionSupport.recoverDormitoryAp(user, now);
                TrainingSkillSupport.advance(user, now);
                BaseStaffRuntimeSupport.advance(user, CharacterBuildingSupport.table("excel/building_data.json"), now);
                result.getJSONObject("playerDataDelta").getJSONObject("modified").put("building", user.getJSONObject("building"));
                MissionProgressSupport.normalize(user, now);
                MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"), missionBefore);
                if (operation.startsWith("gain")) publishBuildingEvent(user, result, now);
                if (userDao.setUserData(account.getUid(), user) != 1)
                    return CharacterBuildingSupport.error("Save failed");
                return result;
            } catch (IllegalArgumentException ex) {
                return CharacterBuildingSupport.error(ex.getMessage());
            }
        }
    }
    private static void publishBuildingEvent(JSONObject user, JSONObject result, long now) {
        JSONObject event = ShopTransactionSupport.object(user, "event");
        event.put("building", now + 60L);
        JSONObject eventDelta = new JSONObject(true);
        eventDelta.put("building", now + 60L);
        result.getJSONObject("playerDataDelta").getJSONObject("modified").put("event", eventDelta);
        result.put("ts", now);
    }
    private static JSONObject settle(JSONObject user, JSONObject body, long now) {
        JSONArray slotIds = body.getJSONArray("roomSlotIdList");
        if (slotIds == null || slotIds.isEmpty()) {
            String single = body.getString("roomSlotId");
            if (single == null) single = body.getString("slotId");
            CharacterBuildingSupport.require(single != null, "Missing room slots");
            slotIds = new JSONArray();
            slotIds.add(single);
        }
        JSONObject data = CharacterBuildingSupport.table("excel/building_data.json");
        ManufactureRuntimeSupport.advance(user, data, now);
        BaseProductionSupport.Settlement settlement = BaseProductionSupport.settleManufacture(
                user, slotIds, now);
        ManufactureRuntimeSupport.advance(user, data, now);
        JSONObject context = new JSONObject(true);
        context.put("runs", settlement.slots);
        context.put("items", settlement.items);
        MissionProgressSupport.onAction(user, "manufactureSettled", context, now);
        JSONObject reply = CharacterBuildingSupport.result(pushed(user));
        reply.put("supplement", 0);
        return reply;
    }
    private static JSONObject intimacy(JSONObject user, long now) {
        BaseProductionSupport.Intimacy credited = BaseProductionSupport.gainAllIntimacy(user, now);
        JSONObject context = new JSONObject(true);
        context.put("runs", credited.operators);
        MissionProgressSupport.onAction(user, "intimacyGained", context, now);
        JSONObject reply = CharacterBuildingSupport.result(pushed(user));
        reply.put("normal", credited.normal);
        reply.put("assist", credited.assist);
        reply.put("privateRelated", credited.privateRelated);
        return reply;
    }
    private static JSONObject privateIntimacy(JSONObject user, JSONObject body, long now) {
        int instId = CharacterBuildingSupport.integer(body, "charInstId");
        BaseProductionSupport.Intimacy credited = BaseProductionSupport.confirmPrivateDormIntimacy(
                user, instId, now);
        JSONObject context = new JSONObject(true);
        context.put("runs", credited.operators);
        MissionProgressSupport.onAction(user, "intimacyGained", context, now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject singleIntimacy(JSONObject user, JSONObject body, String operation, long now) {
        BaseProductionSupport.Intimacy credited = BaseProductionSupport.gainIntimacy(user,
                CharacterBuildingSupport.integer(body, "charInstId"),
                "gainAssistIntimacy".equals(operation) ? "assist" : "normal", now);
        JSONObject context = new JSONObject(true);
        context.put("runs", credited.operators);
        MissionProgressSupport.onAction(user, "intimacyGained", context, now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject setAssist(JSONObject user, JSONObject body, long now) {
        BaseProductionSupport.setAssist(user, CharacterBuildingSupport.integer(body, "type"),
                CharacterBuildingSupport.integer(body, "charInstId"), now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject setPrivateOwner(JSONObject user, JSONObject body, long now) {
        BaseProductionSupport.setPrivateOwner(user, body.getString("slotId"),
                CharacterBuildingSupport.integer(body, "charInsId"), now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject assign(JSONObject user, JSONObject body, long now) {
        String slotId = body.getString("roomSlotId");
        CharacterBuildingSupport.require(slotId != null, "Missing room slot");
        JSONArray instIds = body.getJSONArray("charInstIdList");
        CharacterBuildingSupport.require(instIds != null, "Missing operator list");
        BaseProductionSupport.assignChar(user, slotId, instIds, now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject buyLabor(JSONObject user, JSONObject body, long now) {
        BaseProductionSupport.buyLabor(user, CharacterBuildingSupport.integer(body, "costAp"), now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject upgradeSpecialization(JSONObject user, JSONObject body, long now) {
        TrainingSkillSupport.start(user, CharacterBuildingSupport.integer(body, "skillIndex"), now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject completeUpgradeSpecialization(JSONObject user, long now) {
        TrainingSkillSupport.complete(user, now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    private static JSONObject batchRest(JSONObject user, long now) {
        BaseProductionSupport.batchRestChar(user, now);
        return CharacterBuildingSupport.result(pushed(user));
    }
    /** Mirrors the sections the production routes mutate so the client sees the same values. */
    private static JSONObject pushed(JSONObject user) {
        JSONObject modified = new JSONObject(true);
        for (String key : new String[]{"building", "inventory", "status", "troop", "consumable"}) {
            if (user.containsKey(key)) modified.put(key, user.get(key));
        }
        return modified;
    }
    private JSONObject mutate(JSONObject body, HttpServletRequest request, HttpServletResponse response, String operation) {
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        synchronized (CharacterBuildingSupport.class) {
            if (!checkAccount(secret)) return createError(secret, response);
            List<Account> accounts = userDao.queryAccountBySecret(secret);
            if (accounts.size() != 1) return CharacterBuildingSupport.error("Account unavailable");
            Account account = accounts.get(0);
            try {
                JSONObject normalized=CharacterBuildingSupport.copy(body);
                if(normalized.containsKey("roomSlotId")) {
                    if(normalized.containsKey("slotId")&&!java.util.Objects.equals(normalized.getString("slotId"),normalized.getString("roomSlotId")))throw new IllegalArgumentException("Conflicting room slot identifiers");
                    normalized.put("slotId",normalized.get("roomSlotId"));
                }
                if(normalized.containsKey("targetLevel")) {
                    if(normalized.containsKey("level")&&!java.util.Objects.equals(normalized.get("level"),normalized.get("targetLevel")))throw new IllegalArgumentException("Conflicting room levels");
                    normalized.put("level",normalized.get("targetLevel"));
                }
                JSONObject buildingData = CharacterBuildingSupport.table("excel/building_data.json");
                JSONObject templates = CharacterBuildingSupport.table("defaultSyncData_1.json")
                        .getJSONObject("building").getJSONObject("rooms");
                templates = RecycleRoomBuildSupport.templatesForBuild(
                        buildingData, templates, normalized, operation);
                long now = TEST_NOW > 0 ? TEST_NOW : System.currentTimeMillis() / 1000L;
                JSONObject user = JSONObject.parseObject(account.getUser());
                TrainingSkillSupport.advance(user, now);
                CharacterBuildingSupport.Mutation change = CharacterBuildingSupport.construction(
                    user, normalized, operation, buildingData, templates, now);
                JSONObject missionBefore = CharacterBuildingSupport.copy(change.user.getJSONObject("mission"));
                MissionProgressSupport.normalize(change.user, now);
                MissionProgressSupport.writeDelta(change.user, change.response.getJSONObject("playerDataDelta"), missionBefore);
                if (userDao.setUserData(account.getUid(), change.user) != 1)
                    return CharacterBuildingSupport.error("Save failed");
                return change.response;
            } catch (IllegalArgumentException ex) {
                return CharacterBuildingSupport.error(ex.getMessage());
            }
        }
    }
}
