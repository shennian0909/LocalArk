package com.hypergryph.arknights;

import com.alibaba.fastjson.JSONObject;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class network {
    public network() {
    }

    @RequestMapping({"/"})
    public JSONObject network_config(HttpServletRequest request) {
        String clientIp = ArknightsApplication.getIpAddr(request);
        ArknightsApplication.LOGGER.info("[/" + clientIp + "] /config/prod/official/network_config");
        JSONObject server_network = ArknightsApplication.serverConfig.getJSONObject("network");
        JSONObject network = new JSONObject(true);
        network.put("sign", server_network.getString("sign"));
        JSONObject content = new JSONObject(true);
        JSONObject configs = JSONObject.parseObject(
                server_network.getJSONObject("configs").toJSONString());
        content.put("configVer", server_network.getString("configVer"));
        content.put("funcVer", server_network.getString("funcVer"));
        String serverUrl = ArknightsApplication.serverConfig.getJSONObject("server").getString("url");

        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            JSONObject funcNetwork = configs.getJSONObject(entry.getKey().toString()).getJSONObject("network");
            for (Map.Entry<String, Object> funcNetworkEntry : funcNetwork.entrySet()) {
                if (funcNetworkEntry.getValue() instanceof String) {
                    String value = (String) funcNetworkEntry.getValue();
                    funcNetwork.put(funcNetworkEntry.getKey(), value.replace("{server}", serverUrl));
                }
            }
        }

        content.put("configs", configs);
        network.put("content", content.toJSONString());
        return network;
    }
}
