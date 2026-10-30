package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/building")
public class LocalClueController {
    private final LocalSocialController social = new LocalSocialController();

    @PostMapping(value = "/getClueFriendList", produces = "application/json;charset=UTF-8")
    public JSONObject getClueFriendList(@RequestBody(required = false) JSONObject body,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        return social.getFriendList(body, request, response);
    }
}
