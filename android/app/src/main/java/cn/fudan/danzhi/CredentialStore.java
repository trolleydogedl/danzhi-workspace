package cn.fudan.danzhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** Credentials are stored only with an AndroidKeyStore key; encryption failure is never a plaintext fallback. */
final class CredentialStore {
    private static final String ALIAS="danzhi.session.v2";
    private final SharedPreferences sp;
    CredentialStore(Context context){sp=context.getSharedPreferences("danzhi-secrets-v2",Context.MODE_PRIVATE);}
    private static synchronized SecretKey key()throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());return g.generateKey();
    }
    synchronized void put(String name,String value)throws Exception {
        if(value==null||value.isEmpty()){sp.edit().remove(name).apply();return;}
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        String encoded=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!sp.edit().putString(name,encoded).commit())throw new java.io.IOException("安全会话存储写入失败");
    }
    synchronized String get(String name)throws Exception {
        String encoded=sp.getString(name,"");if(encoded.isEmpty())return "";
        String[] p=encoded.split(":",2);if(p.length!=2)throw new java.io.IOException("安全会话存储损坏，请重新登录");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    void clear(){sp.edit().clear().apply();}
}
