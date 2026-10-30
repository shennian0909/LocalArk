package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/crisisV2")
public class crisisV2 {
    @PostMapping(value = "/getGoodList", produces = "application/json;charset=UTF-8")
    public JSONObject getGoodList(HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, CrisisV2Support::goodList);
    }

    @PostMapping(value = "/getInfo", produces = "application/json;charset=UTF-8")
    public JSONObject getInfo(HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, CrisisV2Support::info);
    }

    @PostMapping(value = "/getSnapshot", produces = "application/json;charset=UTF-8")
    public JSONObject getSnapshot(HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, CrisisV2Support::snapshot);
    }

    @PostMapping(value = "/buyGood", produces = "application/json;charset=UTF-8")
    public JSONObject buyGood(@RequestBody JSONObject body, HttpServletRequest request,
                              HttpServletResponse response) {
        return ShopTransactionSupport.execute(
                response, request, user -> CrisisV2Support.buy(user, body));
    }
}
