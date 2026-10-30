package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.core.decrypt.Utils;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Permanent matrix challenges use BattleLog ZIP data, not the ordinary encrypted battle result. */
final class RecalRuneSupport {
    private static final String RECEIPT="_localRecalRuneBattle";
    private RecalRuneSupport(){}

    static JSONObject start(JSONObject user,JSONObject body){
        JSONObject season=season(text(body,"seasonId"));
        String stageId=text(body,"stageId");
        JSONObject stage=season.getJSONObject("stages").getJSONObject(stageId);
        require(stage!=null,"unknown matrix stage");
        require(body.get("runes") instanceof JSONArray,"matrix runes are required");
        JSONArray selected=selectRunes(stage,body.getJSONArray("runes"));
        validateSlots(user,body.getJSONArray("slots"));
        JSONObject receipt=new JSONObject(true);
        receipt.put("battleId",UUID.randomUUID().toString());
        receipt.put("seasonId",season.getString("seasonId"));receipt.put("stageId",stageId);
        receipt.put("levelId",stage.getString("levelId"));receipt.put("runes",selected);
        receipt.put("score",score(stage,selected));receipt.put("settled",false);
        user.put(RECEIPT,receipt);
        normalize(user);
        JSONObject result=result(user,new JSONArray());
        result.put("battleId",receipt.getString("battleId"));result.put("isApProtect",false);
        result.put("apFailReturn",0);result.put("inApProtectPeriod",false);
        return result;
    }

    static JSONObject finish(JSONObject user,JSONObject body){
        String encoded=text(body,"battleLog");
        JSONObject log;
        try{log=Utils.BattleReplay_decrypt(encoded);}catch(RuntimeException error){throw new IllegalArgumentException("invalid matrix battle log");}
        require(log!=null&&log.getJSONObject("journal")!=null,"invalid matrix battle log");
        JSONObject metadata=log.getJSONObject("journal").getJSONObject("metadata");
        JSONObject receipt=user.getJSONObject(RECEIPT);
        require(receipt!=null&&!receipt.getBooleanValue("settled"),"matrix battle is missing or already settled");
        require(metadata!=null&&receipt.getString("stageId").equals(metadata.getString("stageId"))
                &&receipt.getString("levelId").equals(metadata.getString("levelId")),"matrix battle log does not match the pending stage");
        int outcome=metadata.getIntValue("gameResult");
        require(outcome==1||outcome==2,"matrix battle has not finished");
        require(metadata.getIntValue("remainingLifePoint")>=0,"invalid remaining life points");
        normalize(user);
        JSONObject saved=user.getJSONObject("recalRune").getJSONObject("seasons").getJSONObject(receipt.getString("seasonId"))
                .getJSONObject("stage").getJSONObject(receipt.getString("stageId"));
        boolean passed=outcome==1;
        int gained=passed?receipt.getIntValue("score"):0;
        boolean record=passed&&(saved.getIntValue("state")==0||gained>saved.getIntValue("record"));
        if(passed){
            saved.put("state",1);saved.put("record",Math.max(saved.getIntValue("record"),gained));
            JSONArray runes=saved.getJSONArray("runes");
            for(Object id:receipt.getJSONArray("runes"))if(!runes.contains(id))runes.add(id);
        }
        receipt.put("settled",true);
        JSONObject result=result(user,new JSONArray());
        result.put("seasonId",receipt.getString("seasonId"));result.put("stageId",receipt.getString("stageId"));
        result.put("state",passed?1:0);result.put("score",gained);result.put("newRecord",record);
        result.put("runes",receipt.getJSONArray("runes"));result.put("hp",metadata.getIntValue("remainingLifePoint"));
        result.put("ts",System.currentTimeMillis()/1000L);
        return result;
    }

    static JSONObject reward(JSONObject user,JSONObject body){
        JSONObject definition=season(text(body,"seasonId"));
        Object raw=body.get("rewardType");String type;
        if(raw instanceof Integer||raw instanceof Long){
            int number=((Number)raw).intValue();require(number==0||number==1,"invalid matrix reward type");type=number==0?"junior":"senior";
        }else{
            require("JUNIOR".equals(raw)||"SENIOR".equals(raw),"invalid matrix reward type");type="JUNIOR".equals(raw)?"junior":"senior";
        }
        normalize(user);
        JSONObject saved=user.getJSONObject("recalRune").getJSONObject("seasons").getJSONObject(definition.getString("seasonId"));
        require(saved.getJSONObject("reward").getIntValue(type)==0,"matrix season reward already claimed");
        int medals=0;
        for(String id:definition.getJSONObject("stages").keySet()){
            JSONObject stage=definition.getJSONObject("stages").getJSONObject(id),record=saved.getJSONObject("stage").getJSONObject(id);
            if(record.getIntValue("state")==1&&record.getIntValue("record")>=stage.getIntValue(type+"MedalScore"))medals++;
        }
        require(medals>=table().getJSONObject("constData").getIntValue(type+"RewardMedalCount"),"matrix medal requirements not met");
        JSONObject item=definition.getJSONObject(type+"Reward");
        JSONArray items=new JSONArray();ShopTransactionSupport.reward(user,item,1,items);
        saved.getJSONObject("reward").put(type,1);
        return result(user,items);
    }

    static void normalize(JSONObject user){
        JSONObject seasons=ShopTransactionSupport.object(ShopTransactionSupport.object(user,"recalRune"),"seasons");
        long now=System.currentTimeMillis()/1000L;
        JSONObject definitions=table().getJSONObject("seasons");
        for(String id:definitions.keySet()){
            JSONObject definition=definitions.getJSONObject(id);
            if(definition.getLongValue("startTs")>now)continue;
            JSONObject season=ShopTransactionSupport.object(seasons,id),stages=ShopTransactionSupport.object(season,"stage");
            JSONObject rewards=ShopTransactionSupport.object(season,"reward");
            if(!rewards.containsKey("junior"))rewards.put("junior",0);
            if(!rewards.containsKey("senior"))rewards.put("senior",0);
            for(String stage:definition.getJSONObject("stages").keySet())if(!stages.containsKey(stage))
                stages.put(stage,JSONObject.parseObject("{state:0,record:0,runes:[]}"));
        }
    }

    private static JSONArray selectRunes(JSONObject stage,JSONArray input){
        JSONObject definitions=stage.getJSONObject("runes");
        Set<String> seen=new HashSet<>(),groups=new HashSet<>();JSONArray selected=new JSONArray();
        for(Object value:input){
            require(value instanceof String&&definitions.containsKey(value),"unknown matrix rune");
            String id=(String)value;require(seen.add(id),"duplicate matrix rune");
            String group=definitions.getJSONObject(id).getString("exclusiveGroupId");
            require(group==null||groups.add(group),"mutually exclusive matrix runes");selected.add(id);
        }
        for(String id:definitions.keySet()){
            JSONObject definition=definitions.getJSONObject(id);
            if(!definition.getBooleanValue("essential"))continue;
            String group=definition.getString("exclusiveGroupId");
            if(group!=null)require(groups.contains(group),"select a required matrix option");
            else if(!seen.contains(id))selected.add(id);
        }
        return selected;
    }

    private static int score(JSONObject stage,JSONArray selected){
        int score=0;for(Object id:selected)score+=stage.getJSONObject("runes").getJSONObject((String)id).getIntValue("score");return score;
    }

    private static void validateSlots(JSONObject user,JSONArray slots){
        require(slots!=null&&slots.size()<=12,"invalid matrix squad");Set<Integer> seen=new HashSet<>();
        for(Object value:slots){
            if(value==null)continue;require(value instanceof JSONObject,"invalid matrix squad slot");
            int instance=CharacterBuildingSupport.integer((JSONObject)value,"charInstId");if(instance==-1)continue;
            require(seen.add(instance)&&user.getJSONObject("troop").getJSONObject("chars").containsKey(Integer.toString(instance)),"matrix operator is not owned or duplicated");
        }
    }

    private static JSONObject season(String id){
        JSONObject result=table().getJSONObject("seasons").getJSONObject(id);
        require(result!=null&&result.getLongValue("startTs")<=System.currentTimeMillis()/1000L,"unknown or future matrix season");return result;
    }
    private static JSONObject table(){return CharacterBuildingSupport.table("excel/crisis_v2_table.json").getJSONObject("recalRuneData");}
    private static String text(JSONObject object,String key){require(object!=null&&object.get(key) instanceof String&&!object.getString(key).isEmpty(),"missing "+key);return object.getString(key);}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    private static JSONObject result(JSONObject user,JSONArray items){JSONObject result=ShopTransactionSupport.success(user,items);result.getJSONObject("playerDataDelta").getJSONObject("modified").put("recalRune",user.getJSONObject("recalRune"));return result;}
}
