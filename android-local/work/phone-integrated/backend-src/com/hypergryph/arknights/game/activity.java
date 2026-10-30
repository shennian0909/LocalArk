package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.ArknightsApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/activity")
public class activity {
    public  activity() {
    }

    @PostMapping(
            value = {"/getChainLogInReward"},
            produces = {"application/json;charset=UTF-8"}
    )
    public static JSONObject getChainLogInReward(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        Object rawIndex = JsonBody == null ? null : JsonBody.get("index");
        if (!(rawIndex instanceof Number) || ((Number) rawIndex).doubleValue() != ((Number) rawIndex).intValue())
            return ShopTransactionSupport.error("invalid login reward index");
        int index = ((Number) rawIndex).intValue();
        JSONArray definitions = ArknightsApplication.openServer.getJSONObject("dataMap")
                .getJSONObject("openseverTaskGroup2").getJSONArray("chainLoginData");
        if (index < 0 || index >= definitions.size()) return ShopTransactionSupport.error("invalid login reward index");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject login = user.getJSONObject("openServer").getJSONObject("chainLogin");
            JSONArray history = login.getJSONArray("history");
            if (index >= history.size() || history.getIntValue(index) != 1)
                throw new IllegalArgumentException("login reward is unavailable or already claimed");
            JSONObject item = definitions.getJSONObject(index).getJSONObject("item");
            JSONObject reward = new JSONObject(true);
            reward.put("id", item.getString("itemId"));
            reward.put("type", item.getString("itemType"));
            reward.put("count", item.getIntValue("count"));
            JSONArray items = new JSONArray();
            ShopTransactionSupport.reward(user, reward, 1, items);
            // Each history position is a specific login day; removing it shifts other claim buttons.
            history.set(index, 0);
            boolean allClaimed = history.size() >= definitions.size();
            for (int i = 0; i < history.size(); i++) allClaimed &= history.getIntValue(i) == 0;
            if (allClaimed) { login.put("isAvailable", false); login.put("finalReward", 1); }
            JSONObject result = ShopTransactionSupport.success(user, items);
            result.put("reward", reward);
            result.getJSONObject("playerDataDelta").getJSONObject("modified").put("openServer", user.getJSONObject("openServer"));
            return result;
        });
    }


    @PostMapping(
            value = {"/getOpenServerCheckInReward"},
            produces = {"application/json;charset=UTF-8"}
    )
    public static JSONObject getOpenServerCheckInReward(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        Object rawIndex = JsonBody == null ? null : JsonBody.get("index");
        if (rawIndex != null && (!(rawIndex instanceof Number)
                || ((Number) rawIndex).doubleValue() != ((Number) rawIndex).intValue()))
            return ShopTransactionSupport.error("invalid check-in reward index");
        return ShopTransactionSupport.execute(response, request, user -> {
            JSONObject checkIn = user.getJSONObject("openServer").getJSONObject("checkIn");
            JSONArray history = checkIn.getJSONArray("history");
            int index = rawIndex == null ? history.size() - 1 : ((Number) rawIndex).intValue();
            JSONArray definitions = ArknightsApplication.openServer.getJSONObject("dataMap")
                    .getJSONObject("openseverTaskGroup2").getJSONArray("checkInData");
            if (index < 0 || index >= definitions.size() || index >= history.size() || history.getIntValue(index) != 1)
                throw new IllegalArgumentException("check-in reward is unavailable or already claimed");
            JSONObject item = definitions.getJSONObject(index).getJSONObject("item");
            JSONObject reward = new JSONObject(true);
            reward.put("id", item.getString("itemId"));
            reward.put("type", item.getString("itemType"));
            reward.put("count", item.getIntValue("count"));
            JSONArray items = new JSONArray();
            ShopTransactionSupport.reward(user, reward, 1, items);
            history.set(index, 0);
            if (history.size() == definitions.size() && !history.contains(1)) checkIn.put("isAvailable", false);
            JSONObject result = ShopTransactionSupport.success(user, items);
            result.put("reward", reward);
            result.getJSONObject("playerDataDelta").getJSONObject("modified").put("openServer", user.getJSONObject("openServer"));
            return result;
        });
    }

}
