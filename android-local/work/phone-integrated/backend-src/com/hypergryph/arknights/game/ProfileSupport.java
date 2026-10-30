package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;

final class ProfileSupport {
    // The profile UI formats this timestamp in UTC+8 as 2019-04-29.
    static final long DISPLAY_REGISTER_TS = 1556467200L;

    private ProfileSupport() {}

    static JSONObject bindBirthday(JSONObject user, JSONObject body) {
        int month = exactInt(body, "month");
        int day = exactInt(body, "day");
        int maxDay = switch (month) {
            case 2 -> 29;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
        if (month < 1 || month > 12 || day < 1 || day > maxDay)
            throw new IllegalArgumentException("invalid birthday");

        JSONObject birthday = new JSONObject(true);
        birthday.put("month", month);
        birthday.put("day", day);
        ShopTransactionSupport.object(user, "status").put("birthday", birthday);

        JSONObject status = new JSONObject(true);
        status.put("birthday", birthday);
        JSONObject modified = new JSONObject(true);
        modified.put("status", status);
        return CharacterBuildingSupport.result(modified);
    }

    private static int exactInt(JSONObject body, String key) {
        Object value = body == null ? null : body.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("invalid birthday");
        Number number = (Number) value;
        int result = number.intValue();
        if (number.doubleValue() != result) throw new IllegalArgumentException("invalid birthday");
        return result;
    }
}
