package cn.fudan.danzhi.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

/** PKCS#1 v1.5 RSA for known public moduli. No Android APIs. */
public final class RsaPkcs1 {
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private RsaPkcs1() {}

    public static String encryptToBase64(String plain, String modulusHex, String exponentHex) throws Exception {
        return Base64.getEncoder().encodeToString(encryptRaw(plain, modulusHex, exponentHex));
    }

    /**
     * Tencent Exmail login.js: RSA.encrypt(pwd+"\\n"+ts+"\\n") then hex2b64(Res).
     * hex2b64 is Tom Wu's 3-hex-digit packing, not standard Base64 of the raw bytes.
     */
    public static String encryptToExmail(String plain, String modulusHex, String exponentHex) throws Exception {
        return hex2b64(toHex(encryptRaw(plain, modulusHex, exponentHex)));
    }

    public static String hex2b64(String h) {
        if (h == null) return "";
        StringBuilder ret = new StringBuilder();
        int i = 0;
        for (; i + 3 <= h.length(); i += 3) {
            int c = Integer.parseInt(h.substring(i, i + 3), 16);
            ret.append(B64.charAt(c >> 6)).append(B64.charAt(c & 63));
        }
        if (i + 1 == h.length()) {
            int c = Integer.parseInt(h.substring(i, i + 1), 16);
            ret.append(B64.charAt(c << 2));
        } else if (i + 2 == h.length()) {
            int c = Integer.parseInt(h.substring(i, i + 2), 16);
            ret.append(B64.charAt(c >> 2)).append(B64.charAt((c & 3) << 4));
        }
        while ((ret.length() & 3) > 0) ret.append('=');
        return ret.toString();
    }

    static byte[] encryptRaw(String plain, String modulusHex, String exponentHex) throws Exception {
        if (plain == null) throw new IllegalArgumentException("empty plaintext");
        if (modulusHex == null || modulusHex.isEmpty() || exponentHex == null || exponentHex.isEmpty())
            throw new IllegalArgumentException("missing RSA modulus");
        BigInteger n = new BigInteger(modulusHex, 16);
        BigInteger e = new BigInteger(exponentHex, 16);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    static String toHex(byte[] raw) {
        StringBuilder h = new StringBuilder(raw.length * 2);
        for (byte b : raw) h.append(String.format("%02x", b & 0xff));
        return h.toString();
    }
}
