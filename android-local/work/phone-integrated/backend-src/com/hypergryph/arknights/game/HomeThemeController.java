package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/homeTheme")
public class HomeThemeController {
    @PostMapping(value = "/change", produces = "application/json;charset=UTF-8")
    public JSONObject change(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        String id = body == null ? null : body.getString("themeId");
        return ShopTransactionSupport.execute(response, request,
                user -> HomeCustomizationSupport.select(user, "homeTheme", "themes", id));
    }
}
