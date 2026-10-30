package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.mailDao;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import com.hypergryph.arknights.core.pojo.Mail;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Translates the client's mailbox DTOs to the per-account mails column. */
final class MailProtocolSupport {
    private static final int MAIL_NOT_RECEIVED = 0;
    private static final int MAIL_RECEIVED = 1;
    private static final int MAIL_FROM_SYSTEM = 1;

    private MailProtocolSupport() {}

    static JSONObject getMetaInfoList(
            JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        Account account = authenticatedAccount(response, request);
        if (account == null) return metaError();
        long now = now();
        JSONArray result = new JSONArray();
        JSONArray mails = accountMails(account);
        for (int i = 0; i < mails.size(); i++) {
            JSONObject mail = mails.getJSONObject(i);
            if (!isVisible(mail)) continue;
            JSONObject view = mailView(mail, account.getUid(), now);
            JSONObject meta = new JSONObject(true);
            meta.put("mailId", view.get("mailId"));
            meta.put("surveyMailId", view.get("surveyMailId"));
            meta.put("state", isReceived(view) ? MAIL_RECEIVED : MAIL_NOT_RECEIVED);
            meta.put("createTime", view.get("createAt"));
            JSONArray items = view.getJSONArray("items");
            meta.put("hasItem", items != null && !items.isEmpty());
            meta.put("type", view.get("type"));
            result.add(meta);
        }
        JSONObject responseBody = new JSONObject(true);
        responseBody.put("result", result);
        return responseBody;
    }

    static JSONObject listMailBox(
            JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        Account account = authenticatedAccount(response, request);
        if (account == null) return mailboxError();
        long now = now();
        JSONArray mailList = new JSONArray();
        JSONArray surveyMailList = new JSONArray();
        JSONArray mails = accountMails(account);
        for (int i = 0; i < mails.size(); i++) {
            JSONObject mail = mails.getJSONObject(i);
            if (!isVisible(mail)) continue;
            JSONObject view = mailView(mail, account.getUid(), now);
            if (isSurvey(view)) surveyMailList.add(view);
            else mailList.add(view);
        }
        JSONObject responseBody = new JSONObject(true);
        responseBody.put("mailList", mailList);
        responseBody.put("surveyMailList", surveyMailList);
        return responseBody;
    }

    static JSONObject receiveMail(
            JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        Account account = authenticatedAccount(response, request);
        if (account == null) return receiveError("account not found");
        JSONArray mails = accountMails(account);
        int index = findMail(mails, body == null ? null : body.get("mailId"));
        if (index < 0) return receiveError("mail not found");
        JSONObject mail = mails.getJSONObject(index);
        if (!isVisible(mail) || isExpired(mail, now())) return receiveError("mail unavailable");
        if (isReceived(mail)) return receiveSuccess(new JSONArray(), emptyPlayerDataDelta());

        JSONObject user = JSONObject.parseObject(account.getUser());
        JSONArray items = new JSONArray();
        if (!grantItems(user, mail, items) || !markReceived(mail)) {
            return receiveError("invalid mail reward");
        }
        updateHasGifts(user, mails, now());
        if (mailDao.setMailsAndUserData(account.getUid(), mails, user) != 1) {
            return receiveError("account update failed");
        }
        return receiveSuccess(items, playerDataDelta(user));
    }

    static JSONObject receiveAllMail(
            JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        Account account = authenticatedAccount(response, request);
        if (account == null) return receiveAllError("account not found");
        JSONArray mails = accountMails(account);
        JSONArray requestedIds = body == null ? null : body.getJSONArray("sysMailIdList");
        JSONObject user = JSONObject.parseObject(account.getUser());
        JSONArray items = new JSONArray();
        boolean changed = false;
        long now = now();
        for (int i = 0; i < mails.size(); i++) {
            JSONObject mail = mails.getJSONObject(i);
            if (!isVisible(mail) || isExpired(mail, now) || isReceived(mail)) continue;
            if (requestedIds != null && !requestedIds.isEmpty()
                    && !containsId(requestedIds, mailIdentifier(mail))) continue;
            if (!grantItems(user, mail, items) || !markReceived(mail)) {
                return receiveAllError("invalid mail reward");
            }
            changed = true;
        }
        if (changed) updateHasGifts(user, mails, now);
        if (changed && mailDao.setMailsAndUserData(account.getUid(), mails, user) != 1) {
            return receiveAllError("account update failed");
        }
        JSONObject responseBody = new JSONObject(true);
        responseBody.put("items", items);
        responseBody.put("playerDataDelta", changed ? playerDataDelta(user) : emptyPlayerDataDelta());
        return responseBody;
    }

    static JSONObject removeAllReceivedMail(
            JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        Account account = authenticatedAccount(response, request);
        if (account == null) return removeError("account not found");
        JSONArray mails = accountMails(account);
        JSONArray requestedIds = body == null ? null : body.getJSONArray("sysMailIdList");
        JSONArray remaining = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < mails.size(); i++) {
            JSONObject mail = mails.getJSONObject(i);
            boolean requested =
                    requestedIds == null
                            || requestedIds.isEmpty()
                            || containsId(requestedIds, mailIdentifier(mail));
            if (requested && isReceived(mail)) {
                changed = true;
                continue;
            }
            remaining.add(mail);
        }
        if (changed && mailDao.setMailsData(account.getUid(), remaining) != 1) {
            return removeError("account update failed");
        }
        JSONObject responseBody = new JSONObject(true);
        responseBody.put("playerDataDelta", emptyPlayerDataDelta());
        return responseBody;
    }

    static JSONObject getCollectionList(
            HttpServletResponse response, HttpServletRequest request) {
        authenticatedAccount(response, request);
        JSONObject result = new JSONObject(true);
        result.put("unlockIdList", new JSONArray());
        result.put("extraData", new JSONObject(true));
        return result;
    }

    private static Account authenticatedAccount(
            HttpServletResponse response, HttpServletRequest request) {
        if (!ArknightsApplication.enableServer) {
            response.setStatus(400);
            return null;
        }
        String secret =
                ArknightsApplication.getSecretByIP(ArknightsApplication.getIpAddr(request));
        if (secret == null || secret.isEmpty()) return null;
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts == null || accounts.size() != 1) return null;
        Account account = accounts.get(0);
        if (account.getBan() == 1L) {
            response.setStatus(403);
            return null;
        }
        return account;
    }

    private static JSONArray accountMails(Account account) {
        String stored = account.getMails();
        if (stored == null || stored.trim().isEmpty()) return new JSONArray();
        JSONArray mails = JSONArray.parseArray(stored);
        return mails == null ? new JSONArray() : mails;
    }

    private static JSONObject mailView(JSONObject stored, long uid, long now) {
        JSONObject view =
                JSON.parseObject(
                        JSON.toJSONString(stored, SerializerFeature.WriteMapNullValue));
        JSONObject template = template(stored);
        Object identifier = mailIdentifier(view);
        if (!view.containsKey("mailId") && identifier != null) view.put("mailId", identifier);
        if (!view.containsKey("surveyMailId")) view.put("surveyMailId", 0);
        if (!view.containsKey("type")) view.put("type", mailType(stored, template));
        if (!view.containsKey("uid")) view.put("uid", uid);
        JSONArray itemView = itemArray(stored.get("items"));
        if (itemView.isEmpty() && template != null) {
            itemView = itemArray(template.getString("items"));
        }
        view.put("items", itemView);
        if (!view.containsKey("from")) view.put("from", senderName(template));
        if (!view.containsKey("subject") && template != null) {
            view.put("subject", template.getString("subject"));
        }
        if (!view.containsKey("content") && template != null) {
            view.put("content", template.getString("content"));
        }
        if (!view.containsKey("createAt")) {
            Object createTime = stored.get("createTime");
            view.put("createAt", createTime == null ? now : createTime);
        }
        if (!view.containsKey("expireAt")) view.put("expireAt", 0L);
        if (!view.containsKey("receiveAt")) view.put("receiveAt", 0L);
        view.put("state", isReceived(stored) ? MAIL_RECEIVED : MAIL_NOT_RECEIVED);
        if (!view.containsKey("style")) {
            view.put(
                    "style",
                    JSONObject.parseObject("{\"route\":\"NORMAL\",\"banner\":\"\"}"));
        }
        if (!view.containsKey("collectionId")) view.put("collectionId", 0);
        return view;
    }

    private static JSONObject template(JSONObject stored) {
        Object id = stored.get("templateId");
        if (id == null) id = stored.get("mailTemplateId");
        if (id == null) return null;
        try {
            List<Mail> templates = mailDao.queryMailById(Integer.parseInt(String.valueOf(id)));
            return templates == null || templates.size() != 1
                    ? null
                    : JSONObject.parseObject(
                            JSON.toJSONString(
                                    templates.get(0), SerializerFeature.WriteMapNullValue));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JSONArray itemArray(Object items) {
        if (items == null) return new JSONArray();
        JSONArray parsed;
        try {
            parsed =
                    items instanceof JSONArray
                            ? (JSONArray) items
                            : JSONArray.parseArray(String.valueOf(items));
        } catch (RuntimeException ignored) {
            return new JSONArray();
        }
        if (parsed == null || parsed.isEmpty()) return new JSONArray();
        return JSON.parseArray(JSON.toJSONString(parsed, SerializerFeature.WriteMapNullValue));
    }

    private static Object mailType(JSONObject stored, JSONObject template) {
        Object type = stored.get("type");
        if (type != null) return normalizeMailType(type);
        Object from = template == null ? null : template.get("from");
        return normalizeMailType(from);
    }

    private static Object normalizeMailType(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        String text = value == null ? "" : String.valueOf(value);
        if ("FROM_PERSON".equalsIgnoreCase(text)) return 0;
        if ("SURVEY".equalsIgnoreCase(text)) return 2;
        if ("FROM_SYSTEM".equalsIgnoreCase(text)) return MAIL_FROM_SYSTEM;
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        return MAIL_FROM_SYSTEM;
    }

    private static String senderName(JSONObject template) {
        if (template == null) return "";
        Object from = template.get("from");
        if (from != null) {
            String text = String.valueOf(from);
            if (!text.startsWith("FROM_") && !"SURVEY".equalsIgnoreCase(text)) return text;
        }
        Object name = template.get("name");
        return name == null ? "" : String.valueOf(name);
    }

    private static boolean isSurvey(JSONObject mail) {
        Object type = mail.get("type");
        if (type != null
                && ("SURVEY".equalsIgnoreCase(String.valueOf(type))
                        || "2".equals(String.valueOf(type)))) return true;
        Object surveyMailId = mail.get("surveyMailId");
        if (surveyMailId instanceof Number) {
            return ((Number) surveyMailId).longValue() != 0L;
        }
        return surveyMailId != null
                && !String.valueOf(surveyMailId).isEmpty()
                && !"0".equals(String.valueOf(surveyMailId));
    }

    private static boolean isVisible(JSONObject mail) {
        Object remove = mail.get("remove");
        return remove == null
                || (remove instanceof Number && ((Number) remove).intValue() == 0)
                || "0".equals(String.valueOf(remove))
                || "false".equalsIgnoreCase(String.valueOf(remove));
    }

    private static boolean isExpired(JSONObject mail, long now) {
        long expireAt = longValue(mail.get("expireAt"));
        return expireAt > 0L && now > expireAt;
    }

    private static boolean isReceived(JSONObject mail) {
        Object state = mail.get("state");
        if (state instanceof Number) return ((Number) state).intValue() == MAIL_RECEIVED;
        String text = state == null ? "" : String.valueOf(state);
        return "1".equals(text)
                || "RECEIVED".equalsIgnoreCase(text)
                || "RECIEVED".equalsIgnoreCase(text);
    }

    private static boolean markReceived(JSONObject mail) {
        mail.put("state", MAIL_RECEIVED);
        mail.put("receiveAt", now());
        return true;
    }

    private static void updateHasGifts(JSONObject user, JSONArray mails, long now) {
        JSONObject pushFlags = user.getJSONObject("pushFlags");
        if (pushFlags == null) return;
        boolean hasGifts = false;
        for (int i = 0; i < mails.size(); i++) {
            JSONObject mail = mails.getJSONObject(i);
            if (isVisible(mail) && !isExpired(mail, now) && !isReceived(mail)) {
                hasGifts = true;
                break;
            }
        }
        pushFlags.put("hasGifts", hasGifts ? 1 : 0);
    }

    private static boolean grantItems(JSONObject user, JSONObject mail, JSONArray output) {
        JSONArray items = mail.getJSONArray("items");
        if (items == null || items.isEmpty()) return true;
        try {
            for (int i = 0; i < items.size(); i++) {
                ShopTransactionSupport.reward(user, items.getJSONObject(i), 1, output);
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int findMail(JSONArray mails, Object requestedId) {
        if (requestedId == null) return -1;
        for (int i = 0; i < mails.size(); i++) {
            if (sameId(requestedId, mailIdentifier(mails.getJSONObject(i)))) return i;
        }
        return -1;
    }

    private static boolean containsId(JSONArray ids, Object id) {
        if (id == null) return false;
        for (int i = 0; i < ids.size(); i++) if (sameId(ids.get(i), id)) return true;
        return false;
    }

    private static boolean sameId(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).longValue() == ((Number) right).longValue();
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static Object mailIdentifier(JSONObject mail) {
        Object id = mail.get("mailId");
        if (id == null) id = mail.get("sysMailId");
        if (id == null) id = mail.get("surveyMailId");
        if (id == null) id = mail.get("id");
        return id;
    }

    private static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static JSONObject metaError() {
        JSONObject result = new JSONObject(true);
        result.put("result", new JSONArray());
        return result;
    }

    private static JSONObject mailboxError() {
        JSONObject result = new JSONObject(true);
        result.put("mailList", new JSONArray());
        result.put("surveyMailList", new JSONArray());
        return result;
    }

    private static JSONObject receiveSuccess(JSONArray items, JSONObject playerDataDelta) {
        JSONObject result = new JSONObject(true);
        result.put("result", 0);
        result.put("items", items);
        result.put("playerDataDelta", playerDataDelta);
        return result;
    }

    private static JSONObject receiveError(String message) {
        JSONObject result = new JSONObject(true);
        result.put("items", new JSONArray());
        result.put("result", 2);
        result.put("error", message);
        return result;
    }

    private static JSONObject receiveAllError(String message) {
        JSONObject result = new JSONObject(true);
        result.put("items", new JSONArray());
        result.put("result", 2);
        result.put("error", message);
        return result;
    }

    private static JSONObject removeError(String message) {
        JSONObject result = new JSONObject(true);
        result.put("result", 2);
        result.put("error", message);
        return result;
    }

    private static JSONObject emptyPlayerDataDelta() {
        return playerDataDelta(null);
    }

    private static JSONObject playerDataDelta(JSONObject user) {
        JSONObject deleted = new JSONObject(true);
        JSONObject modified = new JSONObject(true);
        if (user != null) {
            for (String key : new String[]{"status", "inventory", "troop", "skin", "pushFlags"}) {
                if (user.containsKey(key)) modified.put(key, user.get(key));
            }
        }
        JSONObject delta = new JSONObject(true);
        delta.put("deleted", deleted);
        delta.put("modified", modified);
        return delta;
    }
}
