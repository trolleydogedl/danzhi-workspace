package cn.fudan.danzhi;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Crypto {
    static String rsaEncrypt(String plain, String publicKeyB64) throws Exception {
        byte[] der = Base64.decode(publicKeyB64, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    static String totp(String secret, long atMs) throws Exception {
        byte[] key = base32(secret);
        long counter = atMs / 30000L;
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(0, (int) (counter >>> 32));
        buf.putInt(4, (int) counter);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hmac = mac.doFinal(buf.array());
        int offset = hmac[hmac.length - 1] & 0xf;
        int code = ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) << 8)
                | (hmac[offset + 3] & 0xff);
        return String.format("%06d", code % 1_000_000);
    }

    private static byte[] base32(String secret) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = secret.replaceAll("[\\s=-]", "").toUpperCase();
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            int v = alphabet.indexOf(clean.charAt(i));
            if (v < 0) continue;
            bits.append(String.format("%5s", Integer.toBinaryString(v)).replace(' ', '0'));
        }
        int n = bits.length() / 8;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return out;
    }
}
