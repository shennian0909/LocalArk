
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
@SuppressWarnings("deprecation")
class AndroidApkBridge {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("sign")) {
            char[] password = Files.readString(Path.of(args[4])).trim().toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = new FileInputStream(args[3])) { store.load(input, password); }
            PrivateKey key = (PrivateKey)store.getKey("arknights-local-android", password);
            var cert = (X509Certificate)store.getCertificate("arknights-local-android");
            var signer = new ApkSigner.SignerConfig.Builder("LOCALANDROID", key, List.of(cert)).build();
            new ApkSigner.Builder(List.of(signer))
                .setInputApk(new File(args[1])).setOutputApk(new File(args[2]))
                .setMinSdkVersion(24).setV1SigningEnabled(true).setV2SigningEnabled(true)
                .setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign();
        }
        String apk = args[0].equals("sign") ? args[2] : args[1];
        var result = new ApkVerifier.Builder(new File(apk))
            .setMinCheckedPlatformVersion(31).setMaxCheckedPlatformVersion(31).build().verify();
        System.out.println("API31Verified=" + result.isVerified());
        System.out.println("V1=" + result.isVerifiedUsingV1Scheme());
        System.out.println("V2=" + result.isVerifiedUsingV2Scheme());
        System.out.println("V3=" + result.isVerifiedUsingV3Scheme());
        for (var cert : result.getSignerCertificates()) {
            System.out.println("Signer=" + cert.getSubjectX500Principal());
            System.out.println("CertificateSHA256=" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        }
        for (var error : result.getAllErrors()) System.out.println("Error=" + error);
        if (!result.isVerified()) System.exit(1);
    }
}
