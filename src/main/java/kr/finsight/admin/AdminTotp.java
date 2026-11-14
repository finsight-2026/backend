package kr.finsight.admin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class AdminTotp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;

    AdminTotp(@Value("${finsight.admin.mfa-key:}") String key) {
        var bytes = HexFormat.of().parseHex(key);
        encryptionKey = bytes.length == 0 ? null : new SecretKeySpec(bytes, "AES");
        if (bytes.length != 0 && bytes.length != 32) {
            throw new IllegalArgumentException("관리자 MFA 암호화 키는 32바이트여야 합니다.");
        }
    }

    static byte[] decodeBase32(String value) {
        var input = value.strip().toUpperCase(Locale.ROOT);
        if (!input.matches("[A-Z2-7]{32}")) throw new IllegalArgumentException("관리자 TOTP 키는 Base32 32자여야 합니다.");
        var bytes = new byte[20];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (var digit : input.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(digit);
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes[index++] = (byte) (buffer >> bits);
            }
        }
        return bytes;
    }

    static String encodeBase32(byte[] value) {
        var encoded = new StringBuilder((value.length * 8 + 4) / 5);
        int bits = 0;
        int buffer = 0;
        for (byte item : value) {
            buffer = (buffer << 8) | (item & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(ALPHABET.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) encoded.append(ALPHABET.charAt((buffer << (5-bits)) & 31));
        return encoded.toString();
    }

    byte[] encrypt(byte[] secret) {
        var iv = new byte[12];
        random.nextBytes(iv);
        var encrypted = crypt(Cipher.ENCRYPT_MODE, iv, secret);
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    long matchingStep(byte[] encrypted, String code, long epochSeconds, long previousStep) {
        var secret = decrypt(encrypted);
        long current = epochSeconds / 30;
        for (long step = current + 1; step >= current - 1; step--) {
            if (step > previousStep && MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                    codeAt(secret, step).getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }

    byte[] decrypt(byte[] encrypted) {
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOf(encrypted, 12),
                Arrays.copyOfRange(encrypted, 12, encrypted.length));
    }

    static String codeAt(byte[] secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            var digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int index = digest[digest.length - 1] & 15;
            int truncated = ByteBuffer.wrap(digest, index, 4).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", truncated % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private byte[] crypt(int operation, byte[] iv, byte[] input) {
        if (encryptionKey == null) throw new IllegalStateException("관리자 MFA 암호화 키를 설정해주세요.");
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(operation, encryptionKey, new GCMParameterSpec(128, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("관리자 MFA 키 처리 실패", error);
        }
    }
}
