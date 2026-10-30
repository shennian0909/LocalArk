package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/campaignV2")
public class campaignV2 {
    @PostMapping("/battleStart")
    public JSONObject battleStart(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return new quest().BattleStart(body,response,request);
    }
    @PostMapping("/battleFinish")
    public JSONObject battleFinish(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return new quest().BattleFinish(body,response,request);
    }
    @PostMapping("/battleSweep")
    public JSONObject battleSweep(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->CampaignBattleSupport.sweep(user,body));
    }
    @PostMapping("/getBreakReward")
    public JSONObject getBreakReward(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->CampaignBattleSupport.claimBreak(user,body));
    }
    @PostMapping("/getExMissionReward")
    public JSONObject getExMissionReward(@RequestBody JSONObject body,HttpServletRequest request,HttpServletResponse response){
        return ShopTransactionSupport.execute(response,request,user->CampaignBattleSupport.claimMission(user,body));
    }
}
