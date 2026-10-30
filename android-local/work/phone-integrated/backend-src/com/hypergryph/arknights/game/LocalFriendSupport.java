package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.auth.LocalAccountRegistration;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A labelled test account in this device's ordinary local account table. */
public final class LocalFriendSupport {
    public static final String NICK_NAME = "本地测试好友";
    private static final String MARKER = "_localTestFriend";

    private LocalFriendSupport() { }

    static JSONObject readFriendBook(long uid) {
        JSONObject book = JSON.parseObject(single(userDao.queryAccountByUid(uid)).getFriend());
        return book == null ? JSON.parseObject("{list:[],request:[]}") : book;
    }

    static long entryUid(JSONObject entry) {
        return entry.getLongValue("uid");
    }

    static boolean containsUid(JSONArray list, long uid) {
        for (int i = 0; i < list.size(); i++) {
            if (entryUid(list.getJSONObject(i)) == uid) return true;
        }
        return false;
    }

    static Account testFriend() {
        for (Object row : userDao.queryNickName(NICK_NAME)) {
            long uid = ((Number) ((Map<?, ?>) row).get("uid")).longValue();
            Account candidate = single(userDao.queryAccountByUid(uid));
            if (JSON.parseObject(candidate.getUser()).getBooleanValue(MARKER)) return prepareVisitRoom(candidate);
        }
        // A generated login avoids relabelling an existing player's account on a name collision.
        String login = "localfriend" + UUID.randomUUID().toString().replace("-", "");
        LocalAccountRegistration.Result created = LocalAccountRegistration.register(login,
                UUID.randomUUID().toString().replace("-", ""));
        if (!created.isCreated()) throw new IllegalStateException("Local test friend registration failed");
        Account account = single(userDao.queryAccountByUid(created.uid()));
        JSONObject user = JSON.parseObject(account.getUser());
        user.put(MARKER, true);
        user.getJSONObject("status").put("nickName", NICK_NAME);
        user.getJSONObject("status").put("nickNumber", "0001");
        JSONObject chars = user.getJSONObject("troop").getJSONObject("chars");
        for (String instId : chars.keySet()) {
            JSONObject owned = chars.getJSONObject(instId);
            if (!"char_002_amiya".equals(owned.getString("charId"))) continue;
            JSONObject assist = new JSONObject(true);
            assist.put("charInstId", owned.getIntValue("instId"));
            assist.put("skillIndex", owned.getIntValue("defaultSkillIndex"));
            user.getJSONObject("social").getJSONArray("assistCharList").set(0, assist);
            break;
        }
        if (userDao.setUserData(created.uid(), user) != 1) throw new IllegalStateException("Friend save failed");
        return prepareVisitRoom(single(userDao.queryAccountByUid(created.uid())));
    }

    private static Account prepareVisitRoom(Account account) {
        JSONObject user = JSON.parseObject(account.getUser());
        JSONObject building = user.getJSONObject("building");
        JSONObject rooms = building.getJSONObject("rooms");
        JSONObject meeting = rooms.getJSONObject("MEETING");
        if (meeting != null && !meeting.isEmpty()) return account;
        // A new account has no meeting room, but the friend's visit page opens one directly.
        JSONObject template = CharacterBuildingSupport.table("defaultSyncData_1.json").getJSONObject("building");
        meeting = JSON.parseObject(template.getJSONObject("rooms").getJSONObject("MEETING").toJSONString());
        String slotId = meeting.keySet().iterator().next();
        JSONObject slot = JSON.parseObject(template.getJSONObject("roomSlots").getJSONObject(slotId).toJSONString());
        slot.put("charInstIds", JSON.parseArray("[-1,-1]"));
        long now = System.currentTimeMillis() / 1000L;
        JSONObject room = meeting.getJSONObject(slotId);
        room.put("lastUpdateTime", now);
        room.put("completeWorkTime", -1);
        room.getJSONObject("infoShare").put("ts", 0);
        rooms.put("MEETING", meeting);
        building.getJSONObject("roomSlots").put(slotId, slot);
        if (userDao.setUserData(account.getUid(), user) != 1) throw new IllegalStateException("Friend visit room save failed");
        return single(userDao.queryAccountByUid(account.getUid()));
    }

    static void linkTestFriend(long playerUid, JSONObject book) {
        Account friend = testFriend();
        if (friend.getUid() == playerUid) return;
        JSONArray list = book.getJSONArray("list");
        if (containsUid(list, friend.getUid())) return;
        JSONObject entry = new JSONObject(true);
        entry.put("uid", String.valueOf(friend.getUid()));
        entry.put("friendAlias", NICK_NAME);
        entry.put("alias", NICK_NAME);
        list.add(entry);
        if (userDao.setFriendData(playerUid, book) != 1) throw new IllegalStateException("Friend list save failed");
    }

    static boolean visitable(long playerUid, long friendUid) {
        if (friendUid == playerUid || !containsUid(readFriendBook(playerUid).getJSONArray("list"), friendUid))
            return false;
        List<Account> rows = userDao.queryAccountByUid(friendUid);
        return rows.size() == 1 && rows.get(0).getBan() == 0L;
    }

    static JSONObject friendUser(long friendUid) {
        List<Account> rows = userDao.queryAccountByUid(friendUid);
        return rows.size() == 1 ? JSON.parseObject(rows.get(0).getUser()) : null;
    }

    static Account single(List<Account> accounts) {
        if (accounts.size() != 1) throw new IllegalArgumentException("Local account not found");
        return accounts.get(0);
    }
}
