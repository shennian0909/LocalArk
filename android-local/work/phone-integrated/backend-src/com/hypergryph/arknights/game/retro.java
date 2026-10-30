package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.file.IOTools;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/retro")
public class retro {
    @PostMapping(value = "/unlockRetroBlock", produces = "application/json;charset=UTF-8")
    public JSONObject unlockRetroBlock(@RequestBody JSONObject body,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String retroId = body == null ? null : body.getString("retroId");
        if (retroId == null || retroId.trim().isEmpty()) {
            return ShopTransactionSupport.error("retroId is required");
        }
        JSONObject table = IOTools.ReadJsonFile("data/excel/retro_table.json");
        if (table == null) return ShopTransactionSupport.error("retro table unavailable");
        return ShopTransactionSupport.execute(
                response, request, user -> RetroSupport.unlock(user, table, retroId));
    }
}
