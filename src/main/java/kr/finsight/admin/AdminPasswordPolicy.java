package kr.finsight.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("local")
class AdminPasswordPolicy {
    private static final Set<String> LOCAL_BLOCKLIST = Set.of("password1234", "qwerty123456", "123456789012",
            "administrator", "passwordpassword", "letmeinletmein", "1234567890123456", "admin12345678",
            "1q2w3e4r5t6y", "qwertyuiop123", "iloveyou1234", "password12345");

    private final boolean breachCheck;
    private final HttpClient http;

    AdminPasswordPolicy(@Value("${finsight.admin.pwned-passwords:false}") boolean breachCheck) {
        this.breachCheck = breachCheck;
        this.http = breachCheck ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() : null;
    }

    void verifyNew(String password) {
        if (LOCAL_BLOCKLIST.contains(password.strip().toLowerCase(Locale.ROOT)) || (breachCheck && isExposed(password))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "사용할 수 없는 비밀번호입니다.");
        }
    }

    private boolean isExposed(String password) {
        String hash = sha1(password);
        var request = HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/" + hash.substring(0, 5)))
                .timeout(Duration.ofSeconds(3)).header("Add-Padding", "true")
                .header("User-Agent", "FINSIGHT-Admin").GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
            if (response.statusCode() != 200) throw unavailable();
            return exposedSuffix(hash.substring(5), response.body());
        } catch (IOException failure) {
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    static boolean exposedSuffix(String suffix, String body) {
        boolean hasEntries = false;
        boolean exposed = false;
        for (var line : body.lines().toList()) {
            if (line.isBlank()) continue;
            if (!line.matches("[A-Fa-f0-9]{35}:[0-9]+")) throw unavailable();
            hasEntries = true;
            if (line.regionMatches(true, 0, suffix, 0, 35) && !line.substring(36).matches("0+")) exposed = true;
        }
        if (!hasEntries) throw unavailable();
        return exposed;
    }

    private static String sha1(String value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "비밀번호 유출 여부를 확인할 수 없습니다.");
    }
}
