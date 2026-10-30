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
@RequestMapping("/building")
public class LocalVisitController {
    public LocalVisitController() { }

    @PostMapping(value = "/visitBuilding", produces = "application/json;charset=UTF-8")
    public JSONObject visitBuilding(@RequestBody(required = false) JSONObject body,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return error("server is close");
        }
        String secret = ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (secret == null || secret.isEmpty()) return error("account not found");
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts == null || accounts.size() != 1) return error("account not found");
        Account player = accounts.get(0);
        if (player.getBan() == 1L) {
            response.setStatus(403);
            return error("account banned");
        }
        long friendUid = parseUid(body == null ? null : body.get("friendId"));
        if (friendUid <= 0) return error("friendId is required");
        if (friendUid == player.getUid()) return error("cannot visit your own base");
        if (!LocalFriendSupport.visitable(player.getUid(), friendUid)) {
            // No official account is reachable from this offline local server.
            return error("friend is not a local account; official accounts are unreachable");
        }
        JSONObject friendUser = LocalFriendSupport.friendUser(friendUid);
        if (friendUser == null) return error("friend save is unavailable");
        JSONObject building = friendUser.getJSONObject("building");
        if (building == null) return error("friend has no base data");

        JSONObject status = friendUser.getJSONObject("status");
        JSONObject out = new JSONObject(true);
        out.put("result", 0);
        JSONObject delta = new JSONObject(true);
        delta.put("modified", new JSONObject(true));
        delta.put("deleted", new JSONObject(true));
        out.put("playerDataDelta", delta);
        // The client nests RoomOwner inside VisitBuildingResponse.Snapshot.
        JSONObject snapshot = new JSONObject(true);
        JSONObject owner = new JSONObject(true);
        for (String key : new String[]{"uid", "nickName", "nickNumber", "level", "avatar",
                "secretarySkinId", "secretary", "secretarySkinSp"}) {
            if (status.containsKey(key)) owner.put(key, status.get(key));
        }
        owner.put("uid", String.valueOf(friendUid));
        JSONObject skin = friendUser.getJSONObject("nameCardStyle").getJSONObject("skin");
        String selected = skin.getString("selected");
        owner.put("nameCardSkinId", selected);
        owner.put("nameCardSkinTmpl", skin.getJSONObject("tmpl").getIntValue(selected));
        snapshot.put("owner", owner);
        JSONObject chars = new JSONObject(true);
        JSONObject roster = friendUser.getJSONObject("troop").getJSONObject("chars");
        for (String instId : roster.keySet()) {
            JSONObject owned = roster.getJSONObject(instId);
            JSONObject character = new JSONObject(true);
            character.put("charId", owned.getString("charId"));
            character.put("skin", owned.getString("skin"));
            chars.put(instId, character);
        }
        snapshot.put("chars", chars);
        for (String key : new String[]{"roomSlots", "music"}) {
            snapshot.put(key, building.get(key));
        }
        JSONObject rooms = new JSONObject(true);
        for (String key : new String[]{"DORMITORY", "MEETING", "PRIVATE"}) {
            JSONObject branch = building.getJSONObject("rooms").getJSONObject(key);
            if (branch != null) rooms.put(key, branch);
        }
        snapshot.put("rooms", rooms);
        out.put("snapshot", snapshot);
        out.put("visitorList", new JSONArray());
        out.put("rewards", new JSONArray());
        out.put("notice", 0);
        // Evidence the parent can key the VisitBuilding mission event on.
        out.put("visitedUid", String.valueOf(friendUid));
        out.put("visitedNickName", status == null ? "" : status.getString("nickName"));
        out.put("localFriend", friendUser.getBooleanValue("_localTestFriend"));
        out.put("visitedTs", System.currentTimeMillis() / 1000L);
        JSONObject user = JSON.parseObject(player.getUser());
        JSONObject missionBefore = CharacterBuildingSupport.copy(user.getJSONObject("mission"));
        boolean changed = MissionProgressSupport.onAction(user, "visitFriend", new JSONObject(),
                System.currentTimeMillis() / 1000L);
        if (changed) {
            if (userDao.setUserData(player.getUid(), user) != 1) return error("Visit save failed");
            MissionProgressSupport.writeDelta(user, delta, missionBefore);
        }
        return out;
    }

    private static long parseUid(Object raw) {
        if (raw == null) return -1L;
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException notNumeric) {
            return -1L;
        }
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject(true);
        out.put("result", 2);
        out.put("error", message);
        out.put("playerDataDelta", JSON.parseObject("{\"modified\":{},\"deleted\":{}}"));
        return out;
    }
}
