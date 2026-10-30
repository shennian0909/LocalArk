package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/background")
public class background {
    @PostMapping(value = "/setBackground", produces = "application/json;charset=UTF-8")
    public JSONObject setBackground(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        String id = body == null ? null : body.getString("bgID");
        return ShopTransactionSupport.execute(response, request,
                user -> HomeCustomizationSupport.select(user, "background", "bgs", id));
    }
}
