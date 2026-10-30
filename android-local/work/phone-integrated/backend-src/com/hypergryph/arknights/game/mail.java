package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class mail {
    @PostMapping(value = "/mail/getMetaInfoList", produces = "application/json;charset=UTF-8")
    public JSONObject getMetaInfoList(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.getMetaInfoList(body, response, request);
    }

    @PostMapping(value = "/mail/listMailBox", produces = "application/json;charset=UTF-8")
    public JSONObject listMailBox(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.listMailBox(body, response, request);
    }

    @PostMapping(value = "/mail/receiveMail", produces = "application/json;charset=UTF-8")
    public JSONObject receiveMail(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.receiveMail(body, response, request);
    }

    @PostMapping(value = "/mail/receiveAllMail", produces = "application/json;charset=UTF-8")
    public JSONObject receiveAllMail(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.receiveAllMail(body, response, request);
    }

    @PostMapping(value = "/mail/removeAllReceivedMail", produces = "application/json;charset=UTF-8")
    public JSONObject removeAllReceivedMail(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.removeAllReceivedMail(body, response, request);
    }

    @PostMapping(value = "/mailCollection/getList", produces = "application/json;charset=UTF-8")
    public JSONObject getCollectionList(
            @RequestBody(required = false) JSONObject body,
            HttpServletResponse response,
            HttpServletRequest request) {
        return MailProtocolSupport.getCollectionList(response, request);
    }
}
