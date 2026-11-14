package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("local")
class AdminExportLinks {
    record Token(String value, String signature, byte[] hash) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    AdminExportLinks(@Value("${finsight.auth.jwt-key}") String key) {
        this.key = new SecretKeySpec(HexFormat.of().parseHex(key), "HmacSHA256");
    }

    Token create(String domain, UUID id, UUID owner) {
        var random = new byte[32];
        RANDOM.nextBytes(random);
        var value = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new Token(value, HexFormat.of().formatHex(sign(domain, id, owner, value)), hash(random));
    }

    byte[] verify(String domain, UUID id, UUID owner, String token, String signature) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || signature == null || !signature.matches("[a-f0-9]{64}")
                || !MessageDigest.isEqual(HexFormat.of().parseHex(signature), sign(domain, id, owner, token))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return hash(Base64.getUrlDecoder().decode(token));
    }

    private byte[] sign(String domain, UUID id, UUID owner, String token) {
        try {
            var mac = Mac.getInstance(key.getAlgorithm());
            mac.init(key);
            return mac.doFinal((domain + ":" + id + ":" + owner + ":" + token).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] hash(byte[] token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
