package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/recalRune")
public final class RecalRuneController {
    @PostMapping("/battleStart")
    public JSONObject battleStart(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->RecalRuneSupport.start(user,body));
    }
    @PostMapping("/battleFinish")
    public JSONObject battleFinish(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->RecalRuneSupport.finish(user,body));
    }
    @PostMapping("/gainSeasonReward")
    public JSONObject gainSeasonReward(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->RecalRuneSupport.reward(user,body));
    }
}
