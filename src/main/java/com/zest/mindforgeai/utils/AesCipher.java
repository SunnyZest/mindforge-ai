package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.common.properties.AiConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * API Key 的对称加解密（AES-GCM）。
 * <p>
 * 只用于「AI 模型配置里的第三方 API Key」这一处。
 *
 * <h3>为什么用 GCM 而不是 CBC</h3>
 * GCM 是<b>带认证</b>的加密（AEAD）：密文被改过一个字节，解密时就会抛异常，
 * 而不是吐出一段看起来正常、实际被篡改的内容。CBC 做不到这点，
 * 需要额外加 HMAC 才算完整 —— 既然 JDK 自带 GCM，就没必要自己拼。
 *
 * <h3>密文格式</h3>
 * {@code Base64(IV ‖ 密文+认证标签)}，IV 是每次加密随机生成的 12 字节。
 * <p>
 * ⚠️ <b>IV 必须每次随机、且不复用</b>：GCM 下同一个密钥配同一个 IV 加密两段明文，
 * 会直接泄漏明文异或值并破坏认证 —— 这是 GCM 最经典的误用。
 * 所以 IV 不存库、不配置，每次加密现生成，和解密需要的其它信息一起放进密文里。
 *
 * @author crx
 * @since 2026-10-08
 */
@Slf4j
@Component
public class AesCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐的 IV 长度（96 bit） */
    private static final int IV_BYTES = 12;

    /** 认证标签长度（bit） */
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey secretKey;

    public AesCipher(AiConfigProperties properties) {
        String raw = properties.getSecretKey();
        if (!StringUtils.hasText(raw)) {
            this.secretKey = null;
            // 只警告不抛异常：这个密钥只影响「保存 AI 配置」一个功能，
            // 不该因为它没配就让整个服务起不来
            log.warn("未配置 mindforge.ai-config.secret-key —— "
                    + "AI 模型配置的 API Key 无法加密保存，相关接口会直接报错");
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "mindforge.ai-config.secret-key 不是合法的 Base64", e);
        }
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                    "mindforge.ai-config.secret-key 解码后必须是 16 / 24 / 32 字节（AES-128/192/256），"
                            + "当前是 " + keyBytes.length + " 字节");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("AI 配置加密密钥已加载（AES-{}）", keyBytes.length * 8);
    }

    /** 密钥是否已配置 */
    public boolean isConfigured() {
        return secretKey != null;
    }

    /**
     * 加密。
     *
     * @throws BizException 密钥未配置时 —— 刻意报错而不是明文返回
     */
    public String encrypt(String plainText) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // ⚠️ 异常信息里绝不能带上 plainText
            log.error("API Key 加密失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "API Key 加密失败");
        }
    }

    /**
     * 解密。
     *
     * @throws BizException 密钥未配置、或密文被篡改 / 密钥换过
     */
    public String decrypt(String encoded) {
        requireConfigured();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(all, IV_BYTES, all.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见的原因是「换了 secret-key」—— 旧密文解不开了。
            // 报错信息里不要带密文片段
            log.error("API Key 解密失败（换了 secret-key？）", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "API Key 解密失败，可能是加密密钥已更换，请重新录入");
        }
    }

    /**
     * 生成 Key 的展示用提示，如 {@code ****abcd}。
     * <p>
     * 列表接口只回这个，<b>不回明文也不回密文</b>：
     * 管理员需要的是「确认存的是哪一把」，不是「把 Key 抄出来」。
     * 只露尾部 4 位，既能辨认，泄漏了也没用。
     */
    public static String hintOf(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String trimmed = apiKey.trim();
        return trimmed.length() <= 4
                ? "****"
                : "****" + trimmed.substring(trimmed.length() - 4);
    }

    private void requireConfigured() {
        if (secretKey == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "服务端未配置 AI 配置加密密钥（mindforge.ai-config.secret-key），"
                            + "出于安全考虑拒绝保存 API Key");
        }
    }
}
