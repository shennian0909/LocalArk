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

@RestController
public class LocalBusinessCardController {
    private static final int FRIEND_STATUS_NORMAL = 0;
    private static final int FRIEND_STATUS_ALREADY_ADD = 2;

    @PostMapping(value = "/businessCard/editNameCard", produces = "application/json;charset=UTF-8")
    public JSONObject editNameCard(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> LocalCosmeticSupport.editCard(user, body));
    }

    @PostMapping(value = "/businessCard/getOtherPlayerNameCard", produces = "application/json;charset=UTF-8")
    public JSONObject getOtherPlayerNameCard(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return nameCardResult(body, request, response, false);
    }

    @PostMapping(value = "/social/getNameCardWithStatus", produces = "application/json;charset=UTF-8")
    public JSONObject getNameCardWithStatus(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return nameCardResult(body, request, response, true);
    }

    private JSONObject nameCardResult(JSONObject body, HttpServletRequest request,
            HttpServletResponse response, boolean includeFriendStatus) {
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return ShopTransactionSupport.error("server is close");
        }
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (secret == null || secret.isEmpty()) return ShopTransactionSupport.error("account not found");
        List<Account> rows = userDao.queryAccountBySecret(secret);
        if (rows == null || rows.size() != 1) return ShopTransactionSupport.error("account not found");
        Account player = rows.get(0);
        if (player.getBan() == 1L) {
            response.setStatus(403);
            return ShopTransactionSupport.error("account banned");
        }
        try {
            long uid = requestedUid(body);
            boolean self = uid == player.getUid();
            JSONObject user = self ? JSONObject.parseObject(player.getUser()) : localFriendUser(player, uid);
            if (user == null) return ShopTransactionSupport.error("namecard belongs to no local friend");
            JSONObject result = CharacterBuildingSupport.result(new JSONObject(true));
            result.put("nameCard", nameCard(uid, user));
            if (includeFriendStatus) result.put("friendStatus", self ? FRIEND_STATUS_NORMAL : FRIEND_STATUS_ALREADY_ADD);
            return result;
        } catch (IllegalArgumentException ex) {
            return ShopTransactionSupport.error(ex.getMessage());
        }
    }

    private static JSONObject localFriendUser(Account player, long uid) {
        JSONObject book = JSONObject.parseObject(player.getFriend());
        JSONArray list = book == null ? null : book.getJSONArray("list");
        if (list == null || !LocalFriendSupport.containsUid(list, uid)) return null;
        List<Account> rows = userDao.queryAccountByUid(uid);
        if (rows == null || rows.size() != 1 || rows.get(0).getBan() != 0L) return null;
        return JSONObject.parseObject(rows.get(0).getUser());
    }

    private static long requestedUid(JSONObject body) {
        CharacterBuildingSupport.require(body != null && body.get("uid") != null, "Missing uid");
        try {
            long uid = Long.parseLong(body.getString("uid"));
            CharacterBuildingSupport.require(uid > 0, "Invalid uid");
            return uid;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid uid");
        }
    }

    private static JSONObject nameCard(long uid, JSONObject user) {
        JSONObject status = user.getJSONObject("status");
        JSONObject card = new JSONObject(true);
        card.put("uid", String.valueOf(uid));
        card.put("friendAlias", "");
        card.put("nickName", status.getString("nickName"));
        card.put("nickNumber", status.getString("nickNumber"));
        card.put("level", status.getIntValue("level"));
        card.put("serverName", "");
        card.put("secretary", status.getString("secretary"));
        card.put("secretarySkinId", status.getString("secretarySkinId"));
        card.put("secretarySkinSp", status.getBooleanValue("secretarySkinSp"));
        card.put("avatar", status.getJSONObject("avatar"));
        card.put("lastOnlineTime", status.getLongValue("lastOnlineTs"));
        card.put("recentVisited", 0);
        card.put("assistCharList", LocalAssistSupport.characters(user));
        card.put("board", new JSONArray());
        card.put("infoShare", 0);
        card.put("infoShareVisited", 0);
        card.put("skin", user.getJSONObject("nameCardStyle").getJSONObject("skin"));
        card.put("clueSendToMe", 0);
        card.put("clueReceiveFromMe", new JSONArray());
        card.put("friendPrivate",
                user.getJSONObject("nameCardStyle").getJSONObject("misc").getBooleanValue("showDetail") ? 0 : 1);
        card.put("activity", new JSONObject(true));
        for (String key : new String[]{"mainStageProgress", "resume"})
            card.put(key, status.get(key));
        card.put("registerTs", ProfileSupport.DISPLAY_REGISTER_TS);
        JSONObject birthday = status.getJSONObject("birthday");
        JSONObject misc = user.getJSONObject("nameCardStyle").getJSONObject("misc");
        if (birthday == null || misc == null || !misc.getBooleanValue("showBirthday"))
            birthday = JSONObject.parseObject("{\"month\":0,\"day\":0}");
        card.put("birthday", birthday);
        JSONObject roster = user.getJSONObject("troop").getJSONObject("chars");
        card.put("charCnt", roster.size());
        card.put("skinCnt", user.getJSONObject("skin").getJSONObject("characterSkins").size());
        int furnitureCount = 0;
        JSONObject furniture = user.getJSONObject("building").getJSONObject("furniture");
        for (String id : furniture.keySet()) furnitureCount += furniture.getJSONObject(id).getIntValue("count");
        card.put("furnCnt", furnitureCount);
        card.put("teamV2", teams(roster));
        card.put("nameCardStyle", user.getJSONObject("nameCardStyle"));
        card.put("medalBoard", medalBoard(user));
        card.put("equipStatus", equipment(roster));
        card.put("magazineBoard", GallerySupport.magazineBoard(user));
        return card;
    }

    private static JSONObject teams(JSONObject roster) {
        JSONObject teams = new JSONObject(true);
        JSONObject definitions = ArknightsApplication.characterJson;
        for (String id : definitions.keySet()) {
            JSONObject definition = definitions.getJSONObject(id);
            for (String key : new String[]{"nationId", "groupId", "teamId"}) {
                String team = definition.getString(key);
                if (team != null) teams.put(team, 0);
            }
        }
        for (String instId : roster.keySet()) {
            JSONObject definition = definitions.getJSONObject(roster.getJSONObject(instId).getString("charId"));
            if (definition == null) continue;
            for (String key : new String[]{"nationId", "groupId", "teamId"}) {
                String team = definition.getString(key);
                if (team != null) teams.put(team, teams.getIntValue(team) + 1);
            }
        }
        return teams;
    }

    private static JSONObject medalBoard(JSONObject user) {
        JSONObject custom = user.getJSONObject("medal").getJSONObject("custom");
        String index = custom.getString("currentIndex");
        JSONObject layout = index == null ? null : custom.getJSONObject("customs").getJSONObject(index);
        JSONObject board = new JSONObject(true);
        board.put("type", layout == null ? "EMPTY" : "CUSTOM");
        board.put("custom", layout);
        board.put("template", null);
        return board;
    }

    private static JSONObject equipment(JSONObject roster) {
        int unlocked = 0, level3 = 0, operators = 0;
        for (String instId : roster.keySet()) {
            JSONObject equips = roster.getJSONObject(instId).getJSONObject("equip");
            boolean any = false;
            for (String id : equips.keySet()) {
                JSONObject equip = equips.getJSONObject(id);
                if (equip.getIntValue("locked") != 0 || equip.getIntValue("hide") != 0) continue;
                unlocked++;
                if (equip.getIntValue("level") >= 3) level3++;
                any = true;
            }
            if (any) operators++;
        }
        JSONObject result = new JSONObject(true);
        result.put("unlockCnt", unlocked);
        result.put("level3EquipCnt", level3);
        result.put("equipCharCnt", operators);
        result.put("limitedEquipCnt", 0);
        result.put("limitedCharCnt", 0);
        return result;
    }
}
