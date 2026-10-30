package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping({"/social"})
public class social {
    @PostMapping(value = "/receiveSocialPoint", produces = "application/json;charset=UTF-8")
    public JSONObject receiveSocialPoint(@RequestBody JSONObject body, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.execute(response, request, user -> {
            NewCreditSupport.settle(user,
                    CharacterBuildingSupport.table("excel/building_data.json"),
                    System.currentTimeMillis() / 1000L);
            return ShopTransactionSupport.receiveCredit(user);
        });
    }
}
