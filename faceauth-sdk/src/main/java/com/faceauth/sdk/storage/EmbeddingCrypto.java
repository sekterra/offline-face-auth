package com.faceauth.sdk.storage;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.faceauth.sdk.logging.SafeLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore + AES-256-GCM 기반 임베딩 암호화.
 *
 * BLOB 포맷: [IV(12 bytes)] + [ciphertext + GCM tag(16 bytes)]
 *
 * SSoT §6.2: embedding BLOB는 Android Keystore 기반 키로 암호화
 */
public final class EmbeddingCrypto {

    private static final String TAG              = "EmbeddingCrypto";
    private static final String KEYSTORE_ALIAS   = "FaceAuthEmbeddingKey";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORM        = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH        = 12;    // GCM 표준
    private static final int    TAG_LENGTH_BITS  = 128;   // 16 bytes

    private final SecretKey secretKey;

    public EmbeddingCrypto() throws CryptoException {
        try {
            secretKey = getOrCreateKey();
        } catch (Exception e) {
            throw new CryptoException("Keystore 초기화 실패", e);
        }
    }

    /**
     * float[] → 암호화된 byte[] (IV 포함).
     */
    public byte[] encrypt(float[] embedding) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv         = cipher.getIV();
            byte[] plainBytes = floatArrayToBytes(embedding);
            byte[] cipherText = cipher.doFinal(plainBytes);

            // [IV(12)] + [cipherText]
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherText.length);
            buf.put(iv);
            buf.put(cipherText);
            return buf.array();
        } catch (Exception e) {
            throw new CryptoException("임베딩 암호화 실패", e);
        }
    }

    /**
     * 암호화된 byte[] → float[].
     */
    public float[] decrypt(byte[] blob) throws CryptoException {
        if (blob == null || blob.length <= IV_LENGTH) {
            throw new CryptoException("유효하지 않은 BLOB 크기: " + (blob == null ? "null" : blob.length));
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(blob);
            byte[] iv         = new byte[IV_LENGTH];
            buf.get(iv);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            byte[] plain = cipher.doFinal(cipherText);
            return bytesToFloatArray(plain);
        } catch (Exception e) {
            throw new CryptoException("임베딩 복호화 실패", e);
        }
    }

    // ── 키 관리 ───────────────────────────────────────────────────────────

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(KEYSTORE_ALIAS, null);
            if (entry != null) {
                SafeLogger.d(TAG, "기존 Keystore 키 사용");
                return entry.getSecretKey();
            }
        }

        SafeLogger.i(TAG, "새 Keystore 키 생성");
        KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGen.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return keyGen.generateKey();
    }

    // ── byte ↔ float 변환 ─────────────────────────────────────────────────

    private static byte[] floatArrayToBytes(float[] fa) {
        ByteBuffer buf = ByteBuffer.allocate(fa.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : fa) buf.putFloat(f);
        return buf.array();
    }

    private static float[] bytesToFloatArray(byte[] ba) {
        ByteBuffer buf = ByteBuffer.wrap(ba).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[ba.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = buf.getFloat();
        return result;
    }
}
