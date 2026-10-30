package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/** Starts, advances, and claims the training-room specialization task. */
final class TrainingSkillSupport {
    private static final int BUILT = 2;
    private static final int IDLE = 0;
    private static final int TRAINING = 1;
    private static final int FINISHED = 2;
    private static final int WAITING = 3;

    private TrainingSkillSupport() {}

    private static final class RoomContext {
        final String slotId;
        final JSONObject slot;
        final JSONObject room;

        RoomContext(String slotId, JSONObject slot, JSONObject room) {
            this.slotId = slotId;
            this.slot = slot;
            this.room = room;
        }
    }

    static void start(JSONObject user, int skillIndex, long now) {
        RoomContext context = trainingRoom(user);
        JSONObject room = context.room;
        JSONObject trainee = required(room, "trainee");
        JSONObject trainer = required(room, "trainer");
        int traineeId = trainee.getIntValue("charInstId");
        CharacterBuildingSupport.require(traineeId > 0, "Training room has no trainee");
        CharacterBuildingSupport.require(room.getIntValue("state") == IDLE
                        && (trainee.getIntValue("state") == WAITING
                        || trainee.getIntValue("state") == IDLE),
                "Training room is busy");
        requireStationed(context, traineeId, 1);

        JSONObject troop = CharacterBuildingSupport.object(user, "troop");
        JSONObject owned = CharacterBuildingSupport.object(troop, "chars")
                .getJSONObject(String.valueOf(traineeId));
        CharacterBuildingSupport.require(owned != null, "Trainee is not owned");
        CharacterBuildingSupport.require(owned.getIntValue("mainSkillLvl") == 7,
                "Specialization requires skill level 7");
        String charId = owned.getString("charId");
        JSONObject definition = CharacterBuildingSupport.table("excel/character_table.json")
                .getJSONObject(charId);
        CharacterBuildingSupport.require(definition != null, "Unknown trainee character");

        JSONArray runtimeSkills = owned.getJSONArray("skills");
        JSONArray skillDefinitions = definition.getJSONArray("skills");
        CharacterBuildingSupport.require(runtimeSkills != null && skillDefinitions != null
                        && skillIndex >= 0 && skillIndex < runtimeSkills.size()
                        && skillIndex < skillDefinitions.size(),
                "Invalid skillIndex");
        JSONObject runtimeSkill = runtimeSkills.getJSONObject(skillIndex);
        JSONObject skillDefinition = skillDefinitions.getJSONObject(skillIndex);
        CharacterBuildingSupport.require(runtimeSkill != null && skillDefinition != null
                        && runtimeSkill.getIntValue("unlock") == 1
                        && runtimeSkill.getString("skillId").equals(skillDefinition.getString("skillId")),
                "Skill is not unlocked");
        CharacterBuildingSupport.require(runtimeSkill.getIntValue("state") == IDLE,
                "Skill is already upgrading");

        int currentLevel = runtimeSkill.getIntValue("specializeLevel");
        int targetLevel = currentLevel + 1;
        int roomLevel = context.slot.getIntValue("level");
        JSONObject trainingData = CharacterBuildingSupport.table("excel/building_data.json")
                .getJSONObject("trainingData");
        JSONArray phases = trainingData == null ? null : trainingData.getJSONArray("phases");
        CharacterBuildingSupport.require(phases != null && roomLevel > 0 && roomLevel <= phases.size(),
                "Invalid training room level");
        int roomLimit = phases.getJSONObject(roomLevel - 1).getIntValue("specSkillLvlLimit");
        JSONArray conditions = skillDefinition.getJSONArray("levelUpCostCond");
        CharacterBuildingSupport.require(currentLevel >= 0 && targetLevel <= roomLimit
                        && conditions != null && targetLevel <= conditions.size(),
                "Specialization level capped");

        JSONObject level = conditions.getJSONObject(targetLevel - 1);
        CharacterBuildingSupport.require(level != null, "Missing specialization level data");
        requireUnlocked(owned, level.getJSONObject("unlockCond"));
        JSONArray cost = level.getJSONArray("levelUpCost");
        long duration = level.getLongValue("lvlUpTime");
        CharacterBuildingSupport.require(cost != null && duration > 0,
                "Invalid specialization cost or duration");
        CharacterBuildingSupport.spend(user, CharacterBuildingSupport.costs(cost));

        long completeTime = Math.addExact(now, duration);
        room.put("state", TRAINING);
        room.put("lastUpdateTime", now);
        room.put("completeWorkTime", completeTime);
        trainer.put("state", trainer.getIntValue("charInstId") > 0 ? TRAINING : IDLE);
        trainee.put("state", TRAINING);
        trainee.put("targetSkill", skillIndex);
        trainee.put("processPoint", (double) duration);
        trainee.put("speed", 1D);
        runtimeSkill.put("state", TRAINING);
        runtimeSkill.put("completeUpgradeTime", completeTime);
    }

    static void complete(JSONObject user, long now) {
        RoomContext context = trainingRoom(user);
        JSONObject room = context.room;
        JSONObject trainee = required(room, "trainee");
        JSONObject trainer = required(room, "trainer");
        CharacterBuildingSupport.require(room.getIntValue("state") == FINISHED
                        && trainee.getIntValue("state") == FINISHED,
                "Training is not finished");
        long completeTime = room.getLongValue("completeWorkTime");
        CharacterBuildingSupport.require(completeTime > 0 && now >= completeTime,
                "Training is not finished");

        int traineeId = trainee.getIntValue("charInstId");
        int skillIndex = trainee.getIntValue("targetSkill");
        CharacterBuildingSupport.require(traineeId > 0 && skillIndex >= 0,
                "Missing training target");
        requireStationed(context, traineeId, 1);
        JSONObject owned = CharacterBuildingSupport.object(
                CharacterBuildingSupport.object(user, "troop"), "chars")
                .getJSONObject(String.valueOf(traineeId));
        CharacterBuildingSupport.require(owned != null, "Trainee is not owned");
        JSONArray runtimeSkills = owned.getJSONArray("skills");
        JSONObject definition = CharacterBuildingSupport.table("excel/character_table.json")
                .getJSONObject(owned.getString("charId"));
        JSONArray definitions = definition == null ? null : definition.getJSONArray("skills");
        CharacterBuildingSupport.require(runtimeSkills != null && definitions != null
                        && skillIndex < runtimeSkills.size() && skillIndex < definitions.size(),
                "Invalid training target");
        JSONObject runtimeSkill = runtimeSkills.getJSONObject(skillIndex);
        JSONObject skillDefinition = definitions.getJSONObject(skillIndex);
        CharacterBuildingSupport.require(runtimeSkill.getIntValue("state") == TRAINING
                        && runtimeSkill.getString("skillId").equals(skillDefinition.getString("skillId")),
                "Training target changed");

        int currentLevel = runtimeSkill.getIntValue("specializeLevel");
        JSONArray conditions = skillDefinition.getJSONArray("levelUpCostCond");
        CharacterBuildingSupport.require(conditions != null && currentLevel < conditions.size(),
                "Specialization level capped");
        runtimeSkill.put("specializeLevel", currentLevel + 1);
        runtimeSkill.put("state", IDLE);
        runtimeSkill.put("completeUpgradeTime", -1L);

        room.put("state", IDLE);
        room.put("lastUpdateTime", now);
        room.put("completeWorkTime", -1L);
        trainer.put("state", trainer.getIntValue("charInstId") > 0 ? WAITING : IDLE);
        trainee.put("state", WAITING);
        trainee.put("targetSkill", -1);
        trainee.put("processPoint", -1D);
        trainee.put("speed", 1D);
    }

    static void advance(JSONObject user, long now) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        JSONObject trainingRooms = CharacterBuildingSupport.object(building, "rooms")
                .getJSONObject("TRAINING");
        if (trainingRooms == null) return;
        for (String slotId : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId);
            JSONObject room = trainingRooms.getJSONObject(slotId);
            if (room == null || slot.getIntValue("state") != BUILT
                    || !"TRAINING".equals(slot.getString("roomId"))) continue;
            JSONObject trainee = room.getJSONObject("trainee");
            JSONObject trainer = room.getJSONObject("trainer");
            if (trainee == null || trainer == null || room.getIntValue("state") != TRAINING
                    || trainee.getIntValue("state") != TRAINING) continue;

            long anchor = room.getLongValue("lastUpdateTime");
            long completeTime = room.getLongValue("completeWorkTime");
            double speed = trainee.getDoubleValue("speed");
            if (anchor <= 0 || completeTime <= 0 || !Double.isFinite(speed) || speed <= 0D) continue;
            if (anchor > now) {
                room.put("lastUpdateTime", now);
                continue;
            }

            long workedUntil = Math.min(now, completeTime);
            double storedPoint = Math.max(0D, trainee.getDoubleValue("processPoint"));
            // Older local saves stored elapsed seconds; reconcile active tasks to the time left
            // at the persisted anchor before applying any additional elapsed time.
            double pointAtAnchor = Math.max(0L, completeTime - anchor) * speed;
            if (Math.abs(storedPoint - pointAtAnchor) > 0.5D) storedPoint = pointAtAnchor;
            double point = Math.max(0D,
                    storedPoint - Math.max(0L, workedUntil - anchor) * speed);
            trainee.put("processPoint", point);
            room.put("lastUpdateTime", now);
            if (now >= completeTime) {
                room.put("state", FINISHED);
                trainee.put("state", FINISHED);
                trainer.put("state", trainer.getIntValue("charInstId") > 0 ? FINISHED : IDLE);
            }
        }
    }

    private static RoomContext trainingRoom(JSONObject user) {
        JSONObject building = CharacterBuildingSupport.object(user, "building");
        JSONObject slots = CharacterBuildingSupport.object(building, "roomSlots");
        JSONObject rooms = CharacterBuildingSupport.object(building, "rooms").getJSONObject("TRAINING");
        CharacterBuildingSupport.require(rooms != null, "No training rooms");
        for (String slotId : slots.keySet()) {
            JSONObject slot = slots.getJSONObject(slotId);
            JSONObject room = rooms.getJSONObject(slotId);
            if (room != null && slot.getIntValue("state") == BUILT
                    && "TRAINING".equals(slot.getString("roomId"))) {
                return new RoomContext(slotId, slot, room);
            }
        }
        throw new IllegalArgumentException("No built training room");
    }

    private static void requireStationed(RoomContext context, int instId, int index) {
        JSONArray stationed = context.slot.getJSONArray("charInstIds");
        CharacterBuildingSupport.require(stationed != null
                        && index < stationed.size()
                        && stationed.getIntValue(index) == instId,
                "Trainee is not stationed");
    }

    private static void requireUnlocked(JSONObject owned, JSONObject condition) {
        CharacterBuildingSupport.require(condition != null, "Missing specialization unlock");
        String phaseValue = condition.getString("phase");
        CharacterBuildingSupport.require(phaseValue != null && phaseValue.startsWith("PHASE_"),
                "Invalid specialization unlock phase");
        int requiredPhase = Integer.parseInt(phaseValue.substring("PHASE_".length()));
        int phase = owned.getIntValue("evolvePhase");
        int level = owned.getIntValue("level");
        int requiredLevel = condition.getIntValue("level");
        CharacterBuildingSupport.require(phase > requiredPhase
                        || (phase == requiredPhase && level >= requiredLevel),
                "Specialization requires a higher elite phase or level");
    }

    private static JSONObject required(JSONObject parent, String key) {
        JSONObject value = parent.getJSONObject(key);
        CharacterBuildingSupport.require(value != null, "Missing training state: " + key);
        return value;
    }
}
