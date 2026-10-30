package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Set;

/** Kill ladders and weekly orundum use campaignsV2, which differs from the route name. */
final class CampaignBattleSupport {
    private CampaignBattleSupport(){}

    static JSONObject finish(JSONObject user,JSONObject receipt,JSONObject decoded,JSONObject stage){
        String stageId=receipt.getString("stageId");
        JSONObject definition=definition(stageId),stats=decoded.getJSONObject("battleData").getJSONObject("stats");
        int kills=CharacterBuildingSupport.integer(stats,"killedEnemiesCnt");
        JSONArray ladders=definition.getJSONArray("breakLadders");
        int maximum=ladders.getJSONObject(ladders.size()-1).getIntValue("killCnt");
        require(kills>=0&&kills<=maximum,"invalid extermination kill count");
        JSONObject campaign=state(user);refreshWeek(campaign);
        String kind=gainKind(campaign,stageId);
        JSONObject gainData=definition.getJSONObject("dropGains").getJSONObject(kind);
        JSONObject gain=ladder(gainData.getJSONArray("gainLadders"),kills);
        if(gain==null&&"TRAINING".equals(kind)){gain=new JSONObject(true);gain.put("apFailReturn",stage.getIntValue("apCost"));}
        require(gain!=null,"extermination gain table unavailable");
        int feeBefore=campaign.getIntValue("campaignCurrentFee");
        int awarded=Math.min(Math.max(0,campaign.getIntValue("campaignTotalFee")-feeBefore),gain.getIntValue("displayDiamondShdNum"));
        JSONArray rewards=new JSONArray();
        grant(user,"4003","DIAMOND_SHD",awarded,rewards);
        grant(user,"4001","GOLD",gain.getIntValue("goldGain"),rewards);
        campaign.put("campaignCurrentFee",feeBefore+awarded);
        quest common=new quest();
        common.handleApConsumption(user,Math.max(0,stage.getIntValue("apCost")-gain.getIntValue("apFailReturn")),0,stage);
        common.handleExpReward(user,gain.getIntValue("expGain"));
        JSONObject favorStage=CharacterBuildingSupport.copy(stage);
        favorStage.put("passFavor",gain.getIntValue("favor"));favorStage.put("completeFavor",gain.getIntValue("favor"));
        common.updateOperatorFavor(user,decoded,favorStage,3);
        JSONObject saved=instance(user,stageId);int previous=saved.getIntValue("maxKills");
        saved.put("maxKills",Math.max(previous,kills));
        JSONObject sweepMaxKills=ShopTransactionSupport.object(campaign,"sweepMaxKills");
        sweepMaxKills.put(stageId,Math.max(sweepMaxKills.getIntValue(stageId),kills));
        JSONObject missions=ShopTransactionSupport.object(campaign,"missions");
        for(String id:table().getJSONObject("campaignMissions").keySet()){
            JSONObject mission=table().getJSONObject("campaignMissions").getJSONObject(id);
            int threshold=mission.getJSONArray("param").getIntValue(definition.getIntValue("isSmallScale")==0?0:1);
            if(missions.getIntValue(id)==0&&kills>=threshold)missions.put(id,1);
        }
        JSONObject row=user.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
        row.put("state",Math.max(row.getIntValue("state"),kills==maximum?3:kills>0?2:1));
        if(kills>0)row.put("completeTimes",row.getIntValue("completeTimes")+1);
        JSONArray unlockStages=new JSONArray();
        unlockStages.addAll(CampaignProgressionSupport.normalize(
                user,com.hypergryph.arknights.ArknightsApplication.stageTable).keySet());
        JSONObject drop=ladder(gainData.getJSONArray("dropLadders"),kills);
        if(drop!=null&&drop.getJSONObject("dropInfo")!=null){
            JSONObject info=drop.getJSONObject("dropInfo");grantItems(user,info.getJSONArray("passRewards"),rewards);
            if(previous<drop.getIntValue("killCnt"))grantItems(user,info.getJSONArray("firstPassRewards"),rewards);
        }
        NewCreditSupport.recordBattle(user,receipt,decoded.getIntValue("completeState"),System.currentTimeMillis()/1000L);
        MissionProgressSupport.onAction(user,"battleFinish",BattleMissionSupport.context(receipt,stats,stage,decoded.getIntValue("completeState")),System.currentTimeMillis()/1000L);
        receipt.put("settled",true);
        JSONObject result=result(user,rewards);
        result.put("rewards",rewards);result.put("firstRewards",new JSONArray());
        for(String key:new String[]{"additionalRewards","unusualRewards","furnitureRewards","alert"})result.put(key,new JSONArray());
        result.put("unlockStages",unlockStages);
        result.put("expScale",1);result.put("goldScale",1);result.put("apFailReturn",gain.getIntValue("apFailReturn"));
        result.put("currentFeeBefore",feeBefore);result.put("currentFeeAfter",feeBefore+awarded);result.put("suggestFriend",false);
        return result;
    }

    static JSONObject sweep(JSONObject user,JSONObject body){
        require(body!=null&&body.get("stageId") instanceof String&&body.get("itemId") instanceof String,
                "stageId and itemId are required");
        String stageId=body.getString("stageId"),itemId=body.getString("itemId");
        int instId=CharacterBuildingSupport.integer(body,"instId");
        JSONObject item=CharacterBuildingSupport.table("excel/item_table.json").getJSONObject("items").getJSONObject(itemId);
        require(instId>=0&&item!=null&&"EXTERMINATION_AGENT".equals(item.getString("itemType")),
                "invalid extermination sweep ticket");
        JSONObject consumable=user.getJSONObject("consumable");
        JSONObject tickets=consumable==null?null:consumable.getJSONObject(itemId);
        JSONObject bucket=tickets==null?null:tickets.getJSONObject(Integer.toString(instId));
        require(bucket!=null&&bucket.getIntValue("count")>0,"insufficient extermination sweep ticket");
        long expiry=bucket.getLongValue("ts");
        require(expiry<0||expiry>System.currentTimeMillis()/1000L,"extermination sweep ticket expired");
        JSONObject definition=definition(stageId),campaign=state(user);refreshWeek(campaign);
        String kind=gainKind(campaign,stageId);

        JSONObject saved=instance(user,stageId);
        int kills=campaign.getJSONObject("sweepMaxKills").getIntValue(stageId);
        require(kills>0&&saved.getIntValue("maxKills")>=kills,"extermination stage has no completed sweep record");
        JSONObject gainData=definition.getJSONObject("dropGains").getJSONObject(kind);
        JSONObject gain=ladder(gainData.getJSONArray("gainLadders"),kills);
        require(gain!=null,"extermination sweep gain table unavailable");

        bucket.put("count",bucket.getIntValue("count")-1);
        int feeBefore=campaign.getIntValue("campaignCurrentFee");
        int awarded=Math.min(Math.max(0,campaign.getIntValue("campaignTotalFee")-feeBefore),gain.getIntValue("displayDiamondShdNum"));
        JSONArray rewards=new JSONArray();
        grant(user,"4003","DIAMOND_SHD",awarded,rewards);
        grant(user,"4001","GOLD",gain.getIntValue("goldGain"),rewards);
        campaign.put("campaignCurrentFee",feeBefore+awarded);
        new quest().handleExpReward(user,gain.getIntValue("expGain"));

        JSONObject missions=ShopTransactionSupport.object(campaign,"missions");
        for(String id:table().getJSONObject("campaignMissions").keySet()){
            JSONObject mission=table().getJSONObject("campaignMissions").getJSONObject(id);
            int threshold=mission.getJSONArray("param").getIntValue(definition.getIntValue("isSmallScale")==0?0:1);
            if(missions.getIntValue(id)==0&&kills>=threshold)missions.put(id,1);
        }
        JSONObject row=user.getJSONObject("dungeon").getJSONObject("stages").getJSONObject(stageId);
        row.put("completeTimes",row.getIntValue("completeTimes")+1);
        JSONObject drop=ladder(gainData.getJSONArray("dropLadders"),kills);
        if(drop!=null&&drop.getJSONObject("dropInfo")!=null)
            grantItems(user,drop.getJSONObject("dropInfo").getJSONArray("passRewards"),rewards);

        JSONObject result=result(user,rewards);
        result.put("rewards",rewards);result.put("firstRewards",new JSONArray());
        for(String keyName:new String[]{"additionalRewards","unusualRewards","furnitureRewards","unlockStages","alert"})
            result.put(keyName,new JSONArray());
        result.put("expScale",1);result.put("goldScale",1);result.put("apFailReturn",0);
        result.put("currentFeeBefore",feeBefore);result.put("currentFeeAfter",feeBefore+awarded);result.put("suggestFriend",false);
        return result;
    }

    static JSONObject claimBreak(JSONObject user,JSONObject body){
        require(body!=null&&body.get("stageId") instanceof String&&body.get("indexList") instanceof JSONArray,"stageId and indexList are required");
        String stageId=body.getString("stageId");JSONObject definition=definition(stageId);
        JSONArray requested=body.getJSONArray("indexList"),ladders=definition.getJSONArray("breakLadders");
        require(!requested.isEmpty(),"empty break reward list");JSONObject saved=instance(user,stageId);JSONArray flags=saved.getJSONArray("rewardStatus");
        Set<Integer> seen=new HashSet<>();
        for(Object value:requested){
            require(value instanceof Integer,"invalid break reward index");int index=(Integer)value;
            require(index>=0&&index<ladders.size()&&seen.add(index),"invalid or duplicate break reward index");
            require(saved.getIntValue("maxKills")>=ladders.getJSONObject(index).getIntValue("killCnt"),"break reward not earned");
        }
        int added=0;JSONArray items=new JSONArray();
        for(int index:seen)if(flags.getIntValue(index)==0){
            JSONObject rung=ladders.getJSONObject(index);grantItems(user,rung.getJSONArray("rewards"),items);
            flags.set(index,1);added+=rung.getIntValue("breakFeeAdd");
        }
        JSONObject campaign=state(user);campaign.put("campaignTotalFee",campaign.getIntValue("campaignTotalFee")+added);
        JSONObject result=result(user,items);result.put("feeAdd",added);return result;
    }

    static JSONObject claimMission(JSONObject user,JSONObject body){
        require(body!=null&&body.get("id") instanceof String,"campaign mission id required");String id=body.getString("id");
        JSONObject definition=table().getJSONObject("campaignMissions").getJSONObject(id),campaign=state(user);
        require(definition!=null&&campaign.getJSONObject("missions").getIntValue(id)==1,"campaign mission not earned or already claimed");
        campaign.getJSONObject("missions").put(id,2);int added=definition.getIntValue("breakFeeAdd");
        campaign.put("campaignTotalFee",campaign.getIntValue("campaignTotalFee")+added);
        return result(user,new JSONArray());
    }

    static void normalizeRewardFlags(JSONObject user){
        JSONObject campaign=user.getJSONObject("campaignsV2");
        if(campaign==null||campaign.getJSONObject("instances")==null)return;
        for(Object value:campaign.getJSONObject("instances").values()){
            JSONArray flags=((JSONObject)value).getJSONArray("rewardStatus");
            if(flags==null)continue;
            // The old local claim path paid rewards before writing 2; both positive values mean claimed to the client.
            // A saved 1 can also be a real imported claim, so battle receipts alone must never reset it.
            for(int i=0;i<flags.size();i++)if(flags.getIntValue(i)==2)flags.set(i,1);
        }
    }

    private static JSONObject instance(JSONObject user,String stageId){
        JSONObject instances=ShopTransactionSupport.object(state(user),"instances");JSONObject saved=instances.getJSONObject(stageId);
        if(saved==null){saved=new JSONObject(true);saved.put("maxKills",0);JSONArray flags=new JSONArray();for(Object ignored:definition(stageId).getJSONArray("breakLadders"))flags.add(0);saved.put("rewardStatus",flags);instances.put(stageId,saved);}return saved;
    }
    private static JSONObject state(JSONObject user){JSONObject result=user.getJSONObject("campaignsV2");require(result!=null,"extermination state is missing");return result;}
    private static JSONObject definition(String id){JSONObject result=table().getJSONObject("campaigns").getJSONObject(id);require(result!=null,"unknown extermination stage");return result;}
    private static JSONObject table(){return CharacterBuildingSupport.table("excel/campaign_table.json");}
    private static JSONObject ladder(JSONArray rows,int kills){JSONObject chosen=null;for(Object value:rows){JSONObject row=(JSONObject)value;if(row.getIntValue("killCnt")<=kills&&(chosen==null||row.getIntValue("killCnt")>chosen.getIntValue("killCnt")))chosen=row;}return chosen;}
    private static String gainKind(JSONObject campaign,String id){JSONObject open=campaign.getJSONObject("open");if(id.equals(open.getString("rotate")))return "ROTATE";if(open.getJSONArray("permanent").contains(id))return "PERMANENT";require(open.getJSONArray("training").contains(id),"extermination stage is not currently open");return "TRAINING";}
    private static void refreshWeek(JSONObject campaign){ZonedDateTime now=Instant.now().atZone(ZoneId.of("Asia/Shanghai"));LocalDate day=now.minusHours(4).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));long start=day.atStartOfDay(now.getZone()).plusHours(4).toEpochSecond();if(campaign.getLongValue("lastRefreshTs")<start){campaign.put("campaignCurrentFee",0);campaign.put("lastRefreshTs",start);}}
    private static void grant(JSONObject user,String id,String type,int count,JSONArray items){if(count<=0)return;JSONObject item=new JSONObject(true);item.put("id",id);item.put("type",type);item.put("count",count);ShopTransactionSupport.reward(user,item,1,items);}
    private static void grantItems(JSONObject user,JSONArray rewards,JSONArray items){if(rewards!=null)for(Object value:rewards)ShopTransactionSupport.reward(user,(JSONObject)value,1,items);}
    private static JSONObject result(JSONObject user,JSONArray items){normalizeRewardFlags(user);JSONObject result=ShopTransactionSupport.success(user,items),modified=result.getJSONObject("playerDataDelta").getJSONObject("modified");modified.put("campaignsV2",state(user));modified.put("dungeon",user.getJSONObject("dungeon"));MissionProgressSupport.writeDelta(user,result.getJSONObject("playerDataDelta"));return result;}
    private static void require(boolean test,String message){if(!test)throw new IllegalArgumentException(message);}
}
