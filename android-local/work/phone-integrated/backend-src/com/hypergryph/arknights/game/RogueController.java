package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/** Entry and exit routes for integrated strategies (rlv2). */
@RestController
@RequestMapping("/rlv2")
public class RogueController {
    public RogueController() {
    }

    @PostMapping(value = "/createGame", produces = "application/json;charset=UTF-8")
    public JSONObject createGame(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (body == null) {
            response.setStatus(400);
            return ShopTransactionSupport.error("request body is required");
        }
        return ShopTransactionSupport.execute(response, request, user -> RogueSupport.createGame(
                user,
                ArknightsApplication.roguelikeTable,
                body,
                UUID.randomUUID().toString(),
                System.currentTimeMillis() / 1000L));
    }

    @PostMapping(value = "/giveUpGame", produces = "application/json;charset=UTF-8")
    public JSONObject giveUpGame(
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(
                response, request, RogueSupport::giveUpGame);
    }

    @PostMapping(value = "/chooseInitialRelic", produces = "application/json;charset=UTF-8")
    public JSONObject chooseInitialRelic(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.chooseInitialRelic(user, body.getString("select")));
    }

    @PostMapping(value = "/chooseInitialRecruitSet", produces = "application/json;charset=UTF-8")
    public JSONObject chooseInitialRecruitSet(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.chooseInitialRecruitSet(user, body.getString("select")));
    }

    @PostMapping(value = "/chooseInitialExploreTool", produces = "application/json;charset=UTF-8")
    public JSONObject chooseInitialExploreTool(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.chooseInitialExploreTool(user, body.getString("select")));
    }

    @PostMapping(value = "/recruitChar", produces = "application/json;charset=UTF-8")
    public JSONObject recruitChar(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.recruitChar(user, body));
    }

    @PostMapping(value = "/closeRecruitTicket", produces = "application/json;charset=UTF-8")
    public JSONObject closeRecruitTicket(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.closeRecruitTicket(user, body));
    }

    @PostMapping(value = "/finishEvent", produces = "application/json;charset=UTF-8")
    public JSONObject finishEvent(HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, RogueSupport::finishEvent);
    }

    @PostMapping(value = "/chooseInitialRecruit", produces = "application/json;charset=UTF-8")
    public JSONObject chooseInitialRecruit(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.chooseInitialRecruit(user, body.getString("select")));
    }

    @PostMapping(value = "/moveTo", produces = "application/json;charset=UTF-8")
    public JSONObject moveTo(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.moveTo(user, body));
    }

    @PostMapping(value = "/moveAndBattleStart", produces = "application/json;charset=UTF-8")
    public JSONObject moveAndBattleStart(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.moveAndBattleStart(user, body));
    }

    @PostMapping(value = "/battleStart", produces = "application/json;charset=UTF-8")
    public JSONObject battleStart(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.battleStart(user, body));
    }

    @PostMapping(value = "/battleFinish", produces = "application/json;charset=UTF-8")
    public JSONObject battleFinish(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.battleFinish(user, body));
    }

    @PostMapping(value = "/chooseBattleReward", produces = "application/json;charset=UTF-8")
    public JSONObject chooseBattleReward(
            @RequestBody JSONObject body,
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> RogueSupport.chooseBattleReward(user, body));
    }

    @PostMapping(value = "/finishBattleReward", produces = "application/json;charset=UTF-8")
    public JSONObject finishBattleReward(
            HttpServletRequest request,
            HttpServletResponse response) {
        return ShopTransactionSupport.execute(
                response, request, RogueSupport::finishBattleReward);
    }
}
