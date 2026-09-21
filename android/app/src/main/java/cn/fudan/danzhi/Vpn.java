package cn.fudan.danzhi;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Vpn {
    private static final byte[] KEY = "wrdvpnisthebest!".getBytes(StandardCharsets.US_ASCII);
    static final String LOGIN = "https://webvpn.fudan.edu.cn/login?cas_login=true";

    static boolean isCampus(String url) {
        try {
            String h = URI.create(url).getHost();
            return "fdjwgl.fudan.edu.cn".equals(h)
                    || "jwfw.fudan.edu.cn".equals(h)
                    || "yjsxk.fudan.edu.cn".equals(h)
                    || "10.64.130.6".equals(h);
        } catch (Exception e) {
            return false;
        }
    }

    static String wrap(String url) {
        try {
            URI u = URI.create(url);
            if ("webvpn.fudan.edu.cn".equals(u.getHost())) return url;
            String proto = u.getScheme();
            Cipher c = Cipher.getInstance("AES/CFB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(KEY));
            byte[] enc = c.doFinal(u.getHost().getBytes(StandardCharsets.UTF_8));
            String hostSeg = toHex(KEY) + toHex(enc);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            if (path.startsWith("/")) path = path.substring(1);
            String q = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            return "https://webvpn.fudan.edu.cn/" + proto + "/" + hostSeg + "/" + path + q;
        } catch (Exception e) {
            return url;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }
}
