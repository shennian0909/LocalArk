package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sandboxPerm")
public class sandboxPerm {
    @PostMapping(value = "/changeTopic", produces = "application/json;charset=UTF-8")
    public JSONObject changeTopic(@RequestBody JSONObject body,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxPermSupport.changeTopic(user, topicId,
                        System.currentTimeMillis() / 1000L));
    }

    @PostMapping(value = "/pinTopic", produces = "application/json;charset=UTF-8")
    public JSONObject pinTopic(@RequestBody JSONObject body,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        Object rawTopic = body == null ? null : body.get("topicId");
        if (!(rawTopic instanceof String)) return ShopTransactionSupport.error("topicId is required");
        String topicId = (String) rawTopic;
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxPermSupport.pinTopic(user, topicId));
    }

    @PostMapping(value = "/sandboxV3/homeEnter", produces = "application/json;charset=UTF-8")
    public JSONObject homeEnter(@RequestBody JSONObject body,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxPermSupport.enterHome(user, topicId,
                        System.currentTimeMillis() / 1000L));
    }

    @PostMapping(value = "/sandboxV3/createGame", produces = "application/json;charset=UTF-8")
    public JSONObject createGame(@RequestBody JSONObject body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        String topicId = requiredTopic(body);
        String nodeId = requiredText(body, "nodeId");
        Object rawDifficultyId = body == null ? null : body.get("difficultyId");
        if (topicId == null || nodeId == null || !isOptionalText(rawDifficultyId))
            return ShopTransactionSupport.error(
                    "topicId and nodeId are required; difficultyId must be null or a non-empty string");
        String difficultyId = (String) rawDifficultyId;
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.createGame(user, topicId, nodeId, difficultyId,
                        System.currentTimeMillis() / 1000L));
    }

    @PostMapping(value = "/sandboxV3/chooseBand", produces = "application/json;charset=UTF-8")
    public JSONObject chooseBand(@RequestBody JSONObject body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        String topicId = requiredTopic(body);
        String bandId = requiredText(body, "bandId");
        if (topicId == null || bandId == null)
            return ShopTransactionSupport.error("topicId and bandId are required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.chooseBand(user, topicId, bandId));
    }

    @PostMapping(value = "/sandboxV3/switchMode", produces = "application/json;charset=UTF-8")
    public JSONObject switchMode(@RequestBody JSONObject body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        String topicId = requiredTopic(body);
        String modeId = requiredText(body, "modeId");
        if (topicId == null || modeId == null)
            return ShopTransactionSupport.error("topicId and modeId are required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.switchMode(user, topicId, modeId));
    }

    @PostMapping(value = "/sandboxV3/initRecruit", produces = "application/json;charset=UTF-8")
    public JSONObject initRecruit(@RequestBody JSONObject body,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null || body.get("ownChars") == null)
            return ShopTransactionSupport.error("topicId and ownChars are required");
        if (!(body.get("ownChars") instanceof com.alibaba.fastjson.JSONArray))
            return ShopTransactionSupport.error("ownChars must be an array");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.initRecruit(user, topicId, body));
    }

    @PostMapping(value = "/sandboxV3/getDailyRecruitList", produces = "application/json;charset=UTF-8")
    public JSONObject getDailyRecruitList(@RequestBody JSONObject body,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        String topicId = requiredTopic(body);
        Object refresh = body == null ? null : body.get("refresh");
        if (topicId == null || (refresh != null && !(refresh instanceof Boolean)))
            return ShopTransactionSupport.error("topicId and boolean refresh are required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.getDailyRecruitList(user, topicId));
    }

    @PostMapping(value = "/sandboxV3/dailyRecruit", produces = "application/json;charset=UTF-8")
    public JSONObject dailyRecruit(@RequestBody JSONObject body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null || !(body.get("ownChar") instanceof JSONObject))
            return ShopTransactionSupport.error("topicId and ownChar are required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.dailyRecruit(user, topicId, body));
    }

    @PostMapping(value = "/sandboxV3/battleStart", produces = "application/json;charset=UTF-8")
    public JSONObject battleStart(@RequestBody JSONObject body,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.battleStart(user, topicId));
    }

    @PostMapping(value = "/sandboxV3/battleFinish", produces = "application/json;charset=UTF-8")
    public JSONObject battleFinish(@RequestBody JSONObject body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String topicId = requiredTopic(body);
        String data = requiredText(body, "data");
        if (topicId == null || data == null || !(body.get("sandboxV3Data") instanceof JSONObject))
            return ShopTransactionSupport.error("topicId, data and sandboxV3Data are required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.battleFinish(user, topicId, body));
    }

    @PostMapping(value = "/sandboxV3/nextDay", produces = "application/json;charset=UTF-8")
    public JSONObject nextDay(@RequestBody JSONObject body,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.nextDay(user, topicId));
    }

    @PostMapping(value = "/sandboxV3/giveUpGame", produces = "application/json;charset=UTF-8")
    public JSONObject giveUpGame(@RequestBody JSONObject body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.giveUpGame(user, topicId));
    }

    @PostMapping(value = "/sandboxV3/settleGame", produces = "application/json;charset=UTF-8")
    public JSONObject settleGame(@RequestBody JSONObject body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        String topicId = requiredTopic(body);
        if (topicId == null) return ShopTransactionSupport.error("topicId is required");
        return ShopTransactionSupport.execute(response, request,
                user -> SandboxProgressSupport.settleGame(user, topicId,
                        System.currentTimeMillis() / 1000L));
    }

    private static String requiredTopic(JSONObject body) {
        return requiredText(body, "topicId");
    }

    private static String requiredText(JSONObject body, String key) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof String)) return null;
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static boolean isOptionalText(Object value) {
        return value == null
                || value instanceof String && !((String) value).trim().isEmpty();
    }
}
