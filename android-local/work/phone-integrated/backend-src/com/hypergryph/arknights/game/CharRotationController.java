package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/charRotation")
public class CharRotationController {
    @PostMapping(value = "/createPreset", produces = "application/json;charset=UTF-8")
    public JSONObject createPreset(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, CharRotationSupport::createPreset);
    }

    @PostMapping(value = "/updatePreset", produces = "application/json;charset=UTF-8")
    public JSONObject updatePreset(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> CharRotationSupport.updatePreset(user, body));
    }

    @PostMapping(value = "/deletePreset", produces = "application/json;charset=UTF-8")
    public JSONObject deletePreset(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> CharRotationSupport.deletePreset(user, body));
    }

    @PostMapping(value = "/setCurrent", produces = "application/json;charset=UTF-8")
    public JSONObject setCurrent(@RequestBody JSONObject body, HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> CharRotationSupport.setCurrent(user, body));
    }
}
