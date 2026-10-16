package com.linyuan.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecretBox {

    private static final String TAG = "VC_SECRET";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "vc_phone_secret_v1";

    private static final String PREFIX = "enc1:";

    private static volatile SecretKey sKey;
    private static volatile boolean sBroken = false;

    private SecretBox() { }

    public static String seal(Context c, String plain) {
        if (plain == null || plain.isEmpty() || sBroken) return plain;
        try {
            Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
            cp.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cp.getIV();
            byte[] ct = cp.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            sBroken = true;
            Log.w(TAG, "加密不可用，退回明文存储: " + t);
            return plain;
        }
    }

    public static String open(Context c, String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            int ivLen = all[0] & 0xFF;
            byte[] iv = new byte[ivLen];
            System.arraycopy(all, 1, iv, 0, ivLen);
            Cipher cp = Cipher.getInstance("AES/GCM/NoPadding");
            cp.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cp.doFinal(all, 1 + ivLen, all.length - 1 - ivLen), "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "解密失败: " + t);
            return "";
        }
    }

    public static boolean active(Context c) {
        if (sBroken) return false;
        try { return key() != null; } catch (Throwable t) { return false; }
    }

    private static SecretKey key() throws Exception {
        SecretKey k = sKey;
        if (k != null) return k;
        synchronized (SecretBox.class) {
            if (sKey != null) return sKey;
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            java.security.Key existing = ks.getKey(ALIAS, null);
            if (existing instanceof SecretKey) {
                sKey = (SecretKey) existing;
                return sKey;
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)

                    .setUserAuthenticationRequired(false)
                    .build());
            sKey = kg.generateKey();
            return sKey;
        }
    }
}
