package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tower")
public class tower {
    @PostMapping(value = "/createGame", produces = "application/json;charset=UTF-8")
    public JSONObject createGame(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.create(user, body));
    }

    @PostMapping(value = "/initGodCard", produces = "application/json;charset=UTF-8")
    public JSONObject initGodCard(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.initGodCard(user, body));
    }

    @PostMapping(value = "/chooseSubGodCard", produces = "application/json;charset=UTF-8")
    public JSONObject chooseSubGodCard(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.chooseSubGodCard(user, body));
    }

    @PostMapping(value = "/initGame", produces = "application/json;charset=UTF-8")
    public JSONObject initGame(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.initGame(user, body));
    }

    @PostMapping(value = "/initCard", produces = "application/json;charset=UTF-8")
    public JSONObject initCard(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.initCard(user, body));
    }

    @PostMapping(value = "/battleStart", produces = "application/json;charset=UTF-8")
    public JSONObject battleStart(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.start(user, body));
    }

    @PostMapping(value = "/battleFinish", produces = "application/json;charset=UTF-8")
    public JSONObject battleFinish(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> TowerTrainingSupport.finish(user, body));
    }

    @PostMapping(value = "/settleGame", produces = "application/json;charset=UTF-8")
    public JSONObject settleGame(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, TowerTrainingSupport::settle);
    }
}
