package com.hypergryph.arknights.auth;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.codex.arklocal.LocalSaveGrants;
import com.hypergryph.arknights.ArknightsApplication;
import com.hypergryph.arknights.core.pojo.Account;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

/**
 * Shared local-account login and registration policy.
 *
 * <p>The host supplies a SQLite-backed transaction runner. Account lookup and insertion
 * run under that transaction, so a failed save-grant initialization cannot leave a row
 * behind. Secret and password values are never included in exceptions or log messages.</p>
 */
public final class LocalAccountRegistration {
    private static final String PASSWORD_KEY = "IxMMveJRWsxStJgX";
    private static final int MIN_UID = 10000000;
    private static final int MAX_UID = Integer.MAX_VALUE;
    private static final int SECRET_ATTEMPTS = 8;
    private static final Object ACCOUNT_LOCK = new Object();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static volatile TransactionRunner transactionRunner;
    private static volatile SecretSource secretSource = LocalAccountRegistration::secureSecret;

    private LocalAccountRegistration() {
    }

    public enum Outcome {
        EXISTING,
        CREATED,
        DUPLICATE,
        INVALID_INPUT,
        INVALID_PASSWORD
    }

    /** Result deliberately excludes the account and password. */
    public static final class Result {
        private final Outcome outcome;
        private final long uid;
        private final String secret;

        private Result(Outcome outcome, long uid, String secret) {
            this.outcome = outcome;
            this.uid = uid;
            this.secret = secret;
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean isSuccess() {
            return outcome == Outcome.EXISTING || outcome == Outcome.CREATED;
        }

        public boolean isCreated() {
            return outcome == Outcome.CREATED;
        }

        public long uid() {
            return uid;
        }

        public String secret() {
            return secret;
        }
    }

    @FunctionalInterface
    public interface RegistrationOperation {
        Result run(JdbcTemplate jdbc) throws Exception;
    }

    /** The host adapter must run the operation inside one SQLite transaction. */
    @FunctionalInterface
    public interface TransactionRunner {
        Result run(RegistrationOperation operation) throws Exception;
    }

    @FunctionalInterface
    interface SecretSource {
        String nextSecret();
    }

    /**
     * Installed by the Android host, normally as:
     * {@code operation -> store.transaction(operation::run)}.
     */
    public static void setTransactionRunner(TransactionRunner runner) {
        transactionRunner = runner;
    }

    static void setSecretSource(SecretSource source) {
        secretSource = source == null ? LocalAccountRegistration::secureSecret : source;
    }

    public static Result loginOrCreate(String account, String password) {
        if (!isValidCredential(account)) {
            return new Result(Outcome.INVALID_INPUT, 0L, null);
        }
        return runTransaction(jdbc -> {
            List<Account> accounts = findAccounts(jdbc, account);
            if (accounts.size() > 1) {
                throw new IllegalStateException("Duplicate local account rows");
            }
            if (accounts.size() == 1) {
                Account existing = accounts.get(0);
                if (!passwordMatches(password, existing.getPassword())) {
                    return new Result(Outcome.INVALID_PASSWORD, existing.getUid(), null);
                }
                return new Result(Outcome.EXISTING, existing.getUid(), existing.getSecret());
            }
            if (!isValidCredential(password)) {
                return new Result(Outcome.INVALID_INPUT, 0L, null);
            }
            return createAccount(jdbc, account, password);
        });
    }

    public static Result register(String account, String password) {
        if (!isValidCredential(account) || !isValidCredential(password)) {
            return new Result(Outcome.INVALID_INPUT, 0L, null);
        }
        return runTransaction(jdbc -> {
            List<Account> accounts = findAccounts(jdbc, account);
            if (!accounts.isEmpty()) {
                return new Result(Outcome.DUPLICATE, accounts.get(0).getUid(), null);
            }
            return createAccount(jdbc, account, password);
        });
    }

    public static boolean isValidCredential(String value) {
        if (value == null || value.isEmpty() || value.length() > 255) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    public static String passwordDigest(String password) {
        if (password == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest((password + PASSWORD_KEY).getBytes(StandardCharsets.UTF_8));
            char[] output = new char[bytes.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                output[i * 2] = hex[value >>> 4];
                output[i * 2 + 1] = hex[value & 0x0f];
            }
            return new String(output);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required password digest is unavailable");
        }
    }

    private static Result runTransaction(RegistrationOperation operation) {
        TransactionRunner runner = transactionRunner;
        if (runner == null) {
            throw new IllegalStateException("Local account transaction runner is not configured");
        }
        synchronized (ACCOUNT_LOCK) {
            try {
                return runner.run(operation);
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException("Local account transaction failed");
            }
        }
    }

    private static List<Account> findAccounts(JdbcTemplate jdbc, String account) {
        String sql = "SELECT uid, phone, password, secret, user, mails, assistCharList, friend, ban "
                + "FROM account WHERE phone = ?";
        BeanPropertyRowMapper<Account> mapper = new BeanPropertyRowMapper<Account>(Account.class);
        return jdbc.query(sql, new Object[]{account}, mapper);
    }

    private static Result createAccount(JdbcTemplate jdbc, String account, String password) {
        long uid = nextUid(jdbc);
        String secret = nextUniqueSecret(jdbc);
        JSONObject user = createInitialUser(uid);
        String serialized = JSON.toJSONString(user, SerializerFeature.WriteMapNullValue);

        String sql = "INSERT INTO account "
                + "(uid, phone, password, secret, user, mails, assistCharList, friend, ban) "
                + "VALUES (?, ?, ?, ?, ?, '[]', '{}', ?, 0)";
        Object[] parameters = new Object[]{
                uid,
                account,
                passwordDigest(password),
                secret,
                serialized,
                "{\"list\":[],\"request\":[]}"
        };
        if (jdbc.update(sql, parameters) != 1) {
            throw new IllegalStateException("Local account insert did not create exactly one row");
        }
        return new Result(Outcome.CREATED, uid, secret);
    }

    private static long nextUid(JdbcTemplate jdbc) {
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(uid), 0) FROM account", Long.class);
        if (current == null || current >= MAX_UID) {
            throw new IllegalStateException("No safe local account UID is available");
        }
        return Math.max(MIN_UID, current + 1L);
    }

    private static String nextUniqueSecret(JdbcTemplate jdbc) {
        for (int attempt = 0; attempt < SECRET_ATTEMPTS; attempt++) {
            String candidate = secretSource.nextSecret();
            if (candidate == null || candidate.isEmpty()) {
                throw new IllegalStateException("Local account secret source returned an invalid value");
            }
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE secret = ?", Long.class, candidate);
            if (count == null || count == 0L) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique local account secret");
    }

    private static JSONObject createInitialUser(long uid) {
        JSONObject source = ArknightsApplication.DefaultSyncData;
        JSONObject items = ArknightsApplication.itemTable;
        JSONObject skins = ArknightsApplication.skinTable;
        if (source == null || items == null || skins == null) {
            throw new IllegalStateException("Initial local save data is unavailable");
        }

        JSONObject user = JSON.parseObject(
                JSON.toJSONString(source, SerializerFeature.WriteMapNullValue));
        if (user == null) {
            throw new IllegalStateException("Initial local save data is invalid");
        }
        Object statusValue = user.get("status");
        if (!(statusValue instanceof JSONObject)) {
            throw new IllegalStateException("Initial local save status is missing");
        }

        long now = System.currentTimeMillis() / 1000L;
        JSONObject status = (JSONObject) statusValue;
        status.put("uid", uid);
        status.put("registerTs", now);
        status.put("lastApAddTime", now);

        JSONObject events = user.getJSONObject("event");
        if (events == null) {
            events = new JSONObject(true);
            user.put("event", events);
        }
        // The default template contains a fixed historical deadline. Leaving it overdue
        // causes the client to request building.sync continuously.
        events.put("building", now + 60L);

        LocalSaveGrants.apply(user, items, skins, true, true, now);

        // The default save may have zero gacha counters. New accounts receive the same
        // five configured resource floors used by the local host's refill action.
        status = user.getJSONObject("status");
        status.put("androidDiamond", 1000000);
        status.put("iosDiamond", 1000000);
        status.put("diamondShard", 99992520);
        status.put("gachaTicket", 99999);
        status.put("tenGachaTicket", 9987);
        return user;
    }

    private static boolean passwordMatches(String supplied, String storedDigest) {
        String suppliedDigest = passwordDigest(supplied);
        if (suppliedDigest == null || storedDigest == null) {
            return false;
        }
        return MessageDigest.isEqual(
                suppliedDigest.getBytes(StandardCharsets.US_ASCII),
                storedDigest.getBytes(StandardCharsets.US_ASCII));
    }

    private static String secureSecret() {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        char[] output = new char[random.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(output);
    }
}
