package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.dao.orderIdDao;
import com.hypergryph.arknights.core.dao.userDao;
import com.hypergryph.arknights.core.pojo.Account;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping({"/pay"})
public class pay {

    @PostMapping(value = "/createOrder",
            produces = "application/json;charset=UTF-8")
    public JSONObject createOrder(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        // Currency packs are handled by /shop/buyGPGood; cash orders have no local payment provider.
        return ShopTransactionSupport.error("cash payments are unsupported");
    };

    @GetMapping("/order/v1/state")
    public JSONObject getOrderState(@RequestParam("orderId") String orderId, HttpServletResponse response, HttpServletRequest request) {
        return ShopTransactionSupport.error("cash payments are unsupported");
    }

    @PostMapping(
            value = "/confirmOrder",
            produces = "application/json;charset=UTF-8"
    )
    public JSONObject confirmOrder(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        // Currency packs are handled by /shop/buyGPGood; cash orders have no local payment provider.
        return ShopTransactionSupport.error("cash payments are unsupported");
    }

    @PostMapping(
            value = "/getUnconfirmedOrderIdList",
            produces = "application/json;charset=UTF-8"
    )
    public Map<String, Object> getUnconfirmOrderId(@RequestBody JSONObject JsonBody, HttpServletResponse response, HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        String secret = ArknightsApplication.getSecretByIP(clientIp);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /pay/getUnconfirmedOrderIdList");
        ArknightsApplication.LOGGER.info("Received JSON: " + JsonBody.toJSONString());

        JSONObject result = new JSONObject(true); // true 保持字段顺序

        try {
            JSONArray orderIdArray = new JSONArray();
            if (orderIdDao.queryOrderIdBtSecret(secret) != null) {
                String orderId = orderIdDao.queryOrderIdBtSecret(secret);
                orderIdArray.add(orderId);
                orderIdDao.DelTable(orderId);
            }
            result.put("orderIdList", orderIdArray);

            JSONArray pushMessageArray = LoginNoticeSupport.consumePushMessages(secret);
            result.put("pushMessage", pushMessageArray);

            // 5. 构建 playerDataDelta
            JSONObject playerDataDelta = buildPlayerDataDelta(settleDailyCredit(secret));
            result.put("playerDataDelta", playerDataDelta);

            ArknightsApplication.LOGGER.info("Response: " + result.toJSONString());

        } catch (Exception e) {
            ArknightsApplication.LOGGER.error("Error processing request", e);
            // 返回空数据
            result.put("orderIdList", new JSONArray());
            result.put("pushMessage", new JSONArray());
            result.put("playerDataDelta", buildEmptyPlayerDataDelta());
        }
        return result;
    }
    private JSONObject settleDailyCredit(String secret) {
        List<Account> accounts = userDao.queryAccountBySecret(secret);
        if (accounts == null || accounts.size() != 1) return null;
        Account account = accounts.get(0);
        JSONObject user = JSONObject.parseObject(account.getUser());
        if (!NewCreditSupport.settle(user,
                CharacterBuildingSupport.table("excel/building_data.json"),
                System.currentTimeMillis() / 1000L)) return null;
        if (userDao.setUserData(account.getUid(), user) != 1)
            throw new IllegalStateException("daily credit save failed");
        return user.getJSONObject("social");
    }

    private JSONObject buildPlayerDataDelta(JSONObject social) {
        JSONObject playerDataDelta = new JSONObject();
        JSONObject modified = new JSONObject(true);
        if (social != null) modified.put("social", social);
        playerDataDelta.put("modified", modified);
        playerDataDelta.put("deleted", new JSONObject());
        return playerDataDelta;
    }

    private JSONObject buildEmptyPlayerDataDelta() {
        JSONObject delta = new JSONObject();
        delta.put("modified", new JSONObject());
        delta.put("deleted", new JSONObject());
        return delta;
    }
}
