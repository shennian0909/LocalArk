package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Supplies the missing RECYCLE room template without changing the shared table cache. */
final class RecycleRoomBuildSupport {
    private static final String ROOM_ID = "RECYCLE";

    private RecycleRoomBuildSupport() {}

    static JSONObject templatesForBuild(
            JSONObject buildingData, JSONObject templates, JSONObject request, String operation) {
        if (!"buildRoom".equals(operation) || request == null
                || !ROOM_ID.equals(request.getString("roomId"))) {
            return templates;
        }

        JSONObject localTemplates = CharacterBuildingSupport.copy(templates);
        JSONObject examples = new JSONObject(true);
        examples.put("template", initialRoom(buildingData));
        localTemplates.put(ROOM_ID, examples);
        return localTemplates;
    }

    private static JSONObject initialRoom(JSONObject buildingData) {
        JSONObject recycleData = CharacterBuildingSupport.object(buildingData, "recycleData");
        JSONArray phases = recycleData.getJSONArray("phases");
        CharacterBuildingSupport.require(phases != null && phases.size() > 1,
                "Missing recycle room level-one phase data");
        JSONObject phase = phases.getJSONObject(1);

        JSONObject pools = CharacterBuildingSupport.object(
                buildingData, "recycleRoomOutputPoolDatas");
        CharacterBuildingSupport.require(pools.size() == 1,
                "Recycle room output pool is ambiguous");
        String poolId = pools.keySet().iterator().next();

        JSONObject buff = new JSONObject(true);
        buff.put("speed", 0);
        buff.put("boostSpeed", 0);
        JSONObject display = new JSONObject(true);
        display.put("baseBuff", 0);
        display.put("buff", 0);

        JSONObject room = new JSONObject(true);
        room.put("buff", buff);
        room.put("state", 0);
        room.put("lastUpdateTime", 0);
        room.put("completeWorkTime", 0);
        room.put("productList", new JSONArray());
        room.put("capacity", phase.getIntValue("recycleCapacity"));
        room.put("curPoint", 0);
        room.put("totalPoint", 0);
        room.put("boostRemainTime", 0);
        room.put("speed", phase.getDoubleValue("recycleSpeed"));
        room.put("poolId", poolId);
        room.put("display", display);
        room.put("presetQueue", new JSONArray());
        return room;
    }
}
