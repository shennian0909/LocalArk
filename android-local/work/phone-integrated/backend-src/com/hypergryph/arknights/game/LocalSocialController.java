package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping("/social")
public class LocalSocialController {
    public LocalSocialController() { }

    @PostMapping(value = "/setAssistCharList", produces = "application/json;charset=UTF-8")
    public JSONObject setAssistCharList(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        JSONArray requested = body == null ? null : body.getJSONArray("assistCharList");
        if (requested == null || requested.size() > 3) return ShopTransactionSupport.error("invalid assist character list");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject owned = user.getJSONObject("troop").getJSONObject("chars");
            JSONArray slots = new JSONArray();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < requested.size(); i++) {
                JSONObject slot = requested.getJSONObject(i);
                if (slot == null || slot.getIntValue("charInstId") == -1) { slots.add(null); continue; }
                int instance = CharacterBuildingSupport.integer(slot, "charInstId");
                JSONObject operator = owned.getJSONObject(Integer.toString(instance));
                CharacterBuildingSupport.require(operator != null && seen.add(instance), "invalid assist operator");
                int skill = slot.getIntValue("skillIndex");
                CharacterBuildingSupport.require(skill >= 0 && skill < operator.getJSONArray("skills").size(), "invalid assist skill");
                JSONObject clean = new JSONObject(true);
                clean.put("charInstId", instance); clean.put("skillIndex", skill);
                slots.add(clean);
            }
            while (slots.size() < 3) slots.add(null);
            ShopTransactionSupport.object(user, "social").put("assistCharList", slots);
            MissionProgressSupport.onAction(user, "assistConfigured", new JSONObject(), System.currentTimeMillis() / 1000L);
            JSONObject result = CharacterBuildingSupport.result(new JSONObject(true));
            result.getJSONObject("playerDataDelta").getJSONObject("modified").put("social", user.getJSONObject("social"));
            MissionProgressSupport.writeDelta(user, result.getJSONObject("playerDataDelta"));
            return result;
        });
    }

    @PostMapping(value = "/getFriendList", produces = "application/json;charset=UTF-8")
    public JSONObject getFriendList(@RequestBody(required = false) JSONObject body,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        return handle(request, response, (account, book) -> {
            JSONArray requested = body == null ? null : body.getJSONArray("idList");
            JSONArray friends = new JSONArray();
            JSONArray resultIdList = new JSONArray();
            JSONArray alias = new JSONArray();
            JSONArray list = book.getJSONArray("list");
            for (int i = 0; i < list.size(); i++) {
                JSONObject entry = list.getJSONObject(i);
                long uid = LocalFriendSupport.entryUid(entry);
                if (uid <= 0) continue;
                if (requested != null && !requested.isEmpty() && !contains(requested, uid)) continue;
                if (LocalFriendSupport.friendUser(uid) == null) continue;
                friends.add(friendCard(uid, entry));
                resultIdList.add(String.valueOf(uid));
                alias.add(entry.getString("friendAlias"));
            }
            JSONObject out = wrap(account);
            out.put("friends", friends);
            out.put("friendAlias", alias);
            out.put("resultIdList", resultIdList);
            return out;
        });
    }

    @PostMapping(value = "/getFriendAndRequestSendList", produces = "application/json;charset=UTF-8")
    public JSONObject getFriendAndRequestSendList(@RequestBody(required = false) JSONObject body,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        return handle(request, response, (account, book) -> {
            JSONArray friendsList = new JSONArray();
            JSONArray list = book.getJSONArray("list");
            for (int i = 0; i < list.size(); i++) {
                JSONObject entry = list.getJSONObject(i);
                long uid = LocalFriendSupport.entryUid(entry);
                if (uid <= 0 || LocalFriendSupport.friendUser(uid) == null) continue;
                friendsList.add(friendCard(uid, entry));
            }
            JSONObject out = new JSONObject(true);
            out.put("friendsList", friendsList);
            out.put("requestSendIdList", new JSONArray());
            return out;
        });
    }

    @PostMapping(value = "/getSortListInfo", produces = "application/json;charset=UTF-8")
    public JSONObject getSortListInfo(@RequestBody(required = false) JSONObject body,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        return handle(request, response, (account, book) -> {
            JSONArray result = new JSONArray();
            JSONArray list = book.getJSONArray("list");
            for (int i = 0; i < list.size(); i++) {
                JSONObject entry = list.getJSONObject(i);
                long uid = LocalFriendSupport.entryUid(entry);
                JSONObject user = LocalFriendSupport.friendUser(uid);
                if (uid <= 0 || user == null) continue;
                JSONObject row = new JSONObject(true);
                row.put("uid", String.valueOf(uid));
                row.put("level", user.getJSONObject("status").getIntValue("level"));
                row.put("infoShare", 0);
                row.put("infoShareVisited", 0);
                row.put("recentVisited", 0);
                row.put("lastOnlineTime", user.getJSONObject("status").getLongValue("lastOnlineTs"));
                row.put("activity", new JSONObject(true));
                result.add(row);
            }
            JSONObject out = wrap(account);
            out.put("result", result);
            out.put("starFriendList", new JSONArray());
            return out;
        });
    }

    private interface Action {
        JSONObject apply(Account account, JSONObject book) throws Exception;
    }

    private JSONObject handle(HttpServletRequest request, HttpServletResponse response, Action action) {
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return error("server is close");
        }
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (secret == null || secret.isEmpty()) return error("account not found");
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts == null || accounts.size() != 1) return error("account not found");
        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return error("account banned");
        }
        try {
            JSONObject book = LocalFriendSupport.readFriendBook(account.getUid());
            LocalFriendSupport.linkTestFriend(account.getUid(), book);
            return action.apply(account, LocalFriendSupport.readFriendBook(account.getUid()));
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return error(expected.getMessage());
        } catch (Exception failure) {
            ArknightsApplication.LOGGER.error("Local friend list failed: " + failure.getClass().getSimpleName());
            return error("friend list unavailable");
        }
    }

    private static JSONObject wrap(Account account) {
        JSONObject out = new JSONObject(true);
        out.put("result", 0);
        JSONObject delta = new JSONObject(true);
        delta.put("modified", new JSONObject(true));
        delta.put("deleted", new JSONObject(true));
        out.put("playerDataDelta", delta);
        return out;
    }

    static JSONObject friendCard(long uid, JSONObject entry) {
        JSONObject user = LocalFriendSupport.friendUser(uid);
        JSONObject status = user == null ? null : user.getJSONObject("status");
        JSONObject card = new JSONObject(true);
        card.put("uid", String.valueOf(uid));
        card.put("friendAlias", entry == null ? "" : entry.getString("friendAlias"));
        card.put("nickName", status == null ? "" : status.getString("nickName"));
        card.put("nickNumber", status == null ? "" : status.getString("nickNumber"));
        card.put("level", status == null ? 0 : status.getIntValue("level"));
        card.put("serverName", "");
        card.put("secretary", status.getString("secretary"));
        card.put("secretarySkinId", status.getString("secretarySkinId"));
        card.put("secretarySkinSp", status.getBooleanValue("secretarySkinSp"));
        card.put("avatar", status.getJSONObject("avatar"));
        card.put("lastOnlineTime", status == null ? 0 : status.getLongValue("lastOnlineTs"));
        card.put("recentVisited", 0);
        card.put("assistCharList", LocalAssistSupport.characters(user));
        card.put("board", new JSONArray());
        card.put("infoShare", 0);
        card.put("infoShareVisited", 0);
        card.put("skin", user.getJSONObject("nameCardStyle").getJSONObject("skin"));
        card.put("clueSendToMe", 0);
        card.put("clueReceiveFromMe", new JSONArray());
        card.put("friendPrivate", user.getJSONObject("nameCardStyle").getJSONObject("misc").getBooleanValue("showDetail") ? 0 : 1);
        card.put("activity", new JSONObject(true));
        return card;
    }

    private static boolean contains(JSONArray array, long uid) {
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (value == null) continue;
            if (String.valueOf(uid).equals(String.valueOf(value))) return true;
        }
        return false;
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject(true);
        out.put("result", 2);
        out.put("error", message);
        out.put("playerDataDelta", JSON.parseObject("{\"modified\":{},\"deleted\":{}}"));
        return out;
    }
}
