package com.hypergryph.arknights;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hypergryph.arknights.command.CommandManager;
import com.hypergryph.arknights.command.ICommandSender;
import com.hypergryph.arknights.core.file.IOTools;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ArknightsApplication {
    public static final Logger LOGGER = LogManager.getLogger();
    public static JdbcTemplate jdbcTemplate = null;
    public static JSONObject serverConfig = new JSONObject();
    public static boolean enableServer;
    public static JSONObject checkIn;
    public static JSONObject openServer;
    public static JSONObject monthlyPass;
    public static JSONObject DefaultSyncData;
    public static JSONObject characterJson;
    public static JSONObject roguelikeTable;
    public static JSONObject stageTable;
    public static JSONObject itemTable;
    public static JSONObject mainStage;
    public static JSONObject normalGachaData;
    public static JSONObject uniequipTable;
    public static JSONObject skinGoodList;
    public static JSONObject skinTable;
    public static JSONObject charwordTable;
    public static JSONObject CrisisData;
    public static JSONObject CrisisV2Data;
    public static JSONArray CashGoodList;
    public static JSONObject GPGoodList;
    public static JSONObject LowGoodList;
    public static JSONObject HighGoodList;
    public static JSONObject ExtraGoodList;
    public static JSONObject LMTGSGoodList;
    public static JSONObject EPGSGoodList;
    public static JSONObject RepGoodList;
    public static JSONObject FurniGoodList;
    public static JSONObject SocialGoodList;
    public static JSONObject AllProductList;
    public static JSONObject unlockActivity;
    public static JSONObject buildingData;
    public static CommandManager ConsoleCommandManager;
    public static ICommandSender Sender;

    public ArknightsApplication() {
    }

    /** Called after the host installs its SQLite-backed jdbcTemplate. Never creates tables. */
    public static synchronized void initialize(java.io.File root) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Assign jdbcTemplate before initialize(root)");
        }
        com.codex.arklocal.BackendPaths.setRoot(root);
        java.io.File config = new java.io.File(com.codex.arklocal.BackendPaths.rootFile(), "config.json");
        if (!config.isFile()) throw new IllegalStateException("Missing backend root/config.json");
        reloadServerConfig();
    }

    public static String getIpAddr(HttpServletRequest request) {
        String ipAddress = null;

        try {
            ipAddress = request.getHeader("x-forwarded-for");
            if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getHeader("Proxy-Client-IP");
            }

            if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getHeader("WL-Proxy-Client-IP");
            }

            if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
                ipAddress = request.getRemoteAddr();
                // Keep loopback stable on Android; InetAddress.getLocalHost() is unavailable.
            }

            if (ipAddress != null) {
                return ipAddress.contains(",") ? ipAddress.split(",")[0] : ipAddress;
            } else {
                return "";
            }
        } catch (Exception var4) {
            Exception e = var4;

            return "";
        }
    }

    public static int checkCheckInItemType(String item_type) {
        if (item_type == null) {
            return -1;
        }
        return switch (item_type.trim().toUpperCase(Locale.ROOT)) {
            case "GOLD" -> 1;
            case "CARD_EXP", "MATERIAL" -> 2;
            case "TKT_RECRUIT", "TKT_INST_FIN", "TKT_GACHA", "CLASSIC_TKT_GACHA_10", "TKT_GACHA_10",
                 "EXCLUSIVE_TKT_GACHA", "EXCLUSIVE_TKT_GACHA_10" -> 3;
            case "LGG_SHD", "HGG_SHD" -> 4;
            case "VOUCHER_PICK", "OPTIONAL_VOUCHER_PICK", "MATERIAL_ISSUE_VOUCHER", "VOUCHER_LEVELMAX_5",
                 "VOUCHER_ELITE_II_5", "VOUCHER_ELITE_II_4", "VOUCHER_ELITE_II_6", "RENAMING_CARD", "AP_SUPPLY" -> 5;
            case "DIAMOND" -> 6;
            case "CHAR" -> 7;
            case "MCARD_VOUCHER" -> 8;
            case "HOME_THEME", "HOME_BACKGROUND", "PLAYER_AVATAR"-> 9;
            case "DIAMOND_SHD" -> 10;
            default -> -1;
        };
    }

    public static long getTimestamp() {
        // 读取 JSON 配置中的时间字符串
        String ts = serverConfig.getJSONObject("timer").getString("set_server_time");

        // 处理 null 或空字符串情况
        if (ts == null || ts.isEmpty()) {
            return System.currentTimeMillis() / 1000L; // 返回当前时间戳（秒）
        }

        try {
            long timestamp = Long.parseLong(ts);
            // 如果是秒级时间戳（10位）或毫秒级（13位）
            if (timestamp > 0) {
                if (timestamp > 9999999999L) {
                    // 毫秒级时间戳（13位）
                    LOGGER.info("使用毫秒时间戳: {}", timestamp);
                    return timestamp / 1000L;
                } else {
                    // 秒级时间戳（10位）
                    LOGGER.info("使用秒时间戳: {}", timestamp);
                    return timestamp;
                }
            }
        } catch (NumberFormatException ignored) {
            // 不是纯数字，继续尝试解析为时间字符串
        }

        List<String> timeFormats = com.google.common.collect.ImmutableList.of(
                "yyyy/MM/dd HH:mm:ss",
                "ddMMyyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyyMMdd HH:mm:ss"
        );

        for (String format : timeFormats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                LocalDateTime dt = LocalDateTime.parse(ts, formatter);
                long unixTime = dt.atZone(ZoneId.systemDefault()).toEpochSecond(); // 转换为 Unix 时间戳（秒）
                return unixTime == -1 ? System.currentTimeMillis() / 1000L : unixTime; // -1 时返回真实时间
            } catch (Exception ignored) {
            }
        }
        return System.currentTimeMillis() / 1000L;
    }
        public static final ConcurrentHashMap<String, String> IP_SECRET_MAP = new ConcurrentHashMap<>();

        // 绑定 IP -> secret
        public static void addSecretForIP (String ip, String secret){
            IP_SECRET_MAP.put(ip, secret);
        }

    public static int getCharRarity(String charId) {
        String rarity = characterJson.getJSONObject(charId).getString("rarity");
        return Integer.parseInt(rarity.substring(5)) - 1;
    }

    public static int resolveGoodType(String product_id){
        if (product_id == null) return -1;
        if (product_id.startsWith("CS")) return 0;
        if (product_id.startsWith("GP")) return 1;
        return -1;
    }

    public static int getCharPhase(String phase) {
        if (phase.startsWith("PHASE_")) {
            return Integer.parseInt(phase.substring(6));
        }
        return Integer.parseInt(phase);
    }

    // 获取 secret
    public static String getSecretByIP(String ip) {
        return IP_SECRET_MAP.get(ip);
    }

    public static void reloadServerConfig() {
        long startTime = System.currentTimeMillis();
        LOGGER.info("载入服务器配置...");
        serverConfig = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/config.json");
        enableServer = serverConfig.getJSONObject("server").getBooleanValue("enableServer");
        LOGGER.info("载入游戏数据...");
        checkIn = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/checkin_table.json").getJSONObject("groups");
        monthlyPass = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/checkin_table.json").getJSONObject("monthlySubItem");
        openServer = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/open_server_table.json");
        DefaultSyncData = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/defaultSyncData.json");
        characterJson = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/character_table.json");
        roguelikeTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/roguelike_topic_table.json");
        stageTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/stage_table.json").getJSONObject("stages");
        itemTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/item_table.json").getJSONObject("items");
        mainStage = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/battle/stage.json").getJSONObject("MainStage");
        uniequipTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/uniequip_table.json").getJSONObject("equipDict");
        skinGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/SkinGoodList.json");
        skinTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/skin_table.json").getJSONObject("charSkins");
        charwordTable = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/charword_table.json");
        CashGoodList = IOTools.ReadJsonArray(com.codex.arklocal.BackendPaths.root() + "/data/shop/CashGoodList.json");
        GPGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/GPGoodList.json");
        normalGachaData = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/normalGacha.json");
        LowGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/LowGoodList.json");
        HighGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/HighGoodList.json");
        ExtraGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/ExtraGoodList.json");
        LMTGSGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/LMTGSGoodList.json");
        EPGSGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/EPGSGoodList.json");
        RepGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/RepGoodList.json");
        FurniGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/FurniGoodList.json");
        SocialGoodList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/SocialGoodList.json");
        AllProductList = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/shop/AllProductList.json");
        CrisisData = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/crisis/cc12.json");
        CrisisV2Data = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/crisisv2/cc3.json");
        buildingData = IOTools.ReadJsonFile(com.codex.arklocal.BackendPaths.root() + "/data/excel/building_data.json").getJSONObject("workshopFormulas");
        long endTime = System.currentTimeMillis();
        LOGGER.info("载入完成，耗时：" + (endTime - startTime) + "ms");
    }

    static {
        enableServer = false;
        DefaultSyncData = new JSONObject();
        checkIn = new JSONObject();
        monthlyPass = new JSONObject();
        openServer = new JSONObject();
        characterJson = new JSONObject();
        roguelikeTable = new JSONObject();
        stageTable = new JSONObject();
        itemTable = new JSONObject();
        mainStage = new JSONObject();
        normalGachaData = new JSONObject();
        uniequipTable = new JSONObject();
        skinGoodList = new JSONObject();
        skinTable = new JSONObject();
        charwordTable = new JSONObject();
        CrisisData = new JSONObject();
        CrisisV2Data = new JSONObject();
        CashGoodList = new JSONArray();
        GPGoodList = new JSONObject();
        LowGoodList = new JSONObject();
        HighGoodList = new JSONObject();
        ExtraGoodList = new JSONObject();
        LMTGSGoodList = new JSONObject();
        EPGSGoodList = new JSONObject();
        RepGoodList = new JSONObject();
        FurniGoodList = new JSONObject();
        SocialGoodList = new JSONObject();
        AllProductList = new JSONObject();
        unlockActivity = new JSONObject();
        buildingData = new JSONObject();
        ConsoleCommandManager = new CommandManager();
        Sender = () -> {
            return "Console";
        };
    }
}