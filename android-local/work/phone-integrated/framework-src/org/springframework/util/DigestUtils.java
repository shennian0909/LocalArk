package org.springframework.util;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
public abstract class DigestUtils {
    public static byte[] md5Digest(byte[] bytes) {
        try { return MessageDigest.getInstance("MD5").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("MD5 unavailable", ex); }
    }
    public static String md5DigestAsHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        byte[] digest = md5Digest(bytes);
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            out[2*i] = hex[(digest[i] & 255) >>> 4];
            out[2*i+1] = hex[digest[i] & 15];
        }
        return new String(out);
    }
}
