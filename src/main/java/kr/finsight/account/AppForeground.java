package kr.finsight.account;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/account/activity")
class AppForeground {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient db;

    AppForeground(JdbcClient db) {
        this.db = db;
    }

    record Day(LocalDate date) {}

    @PostMapping
    @Transactional
    Day record(@AuthenticationPrincipal Jwt jwt) {
        var user = UUID.fromString(jwt.getSubject());
        var session = UUID.fromString(jwt.getClaimAsString("sid"));
        db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE")
                .param("user", user).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!db.sql("""
                SELECT EXISTS(SELECT 1 FROM app_session WHERE id=:session AND user_id=:user
                AND expires_at>clock_timestamp())
                """).param("session", session).param("user", user).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var date = LocalDate.now(KST);
        db.sql("INSERT INTO app_foreground_observation VALUES (true,:first) ON CONFLICT DO NOTHING")
                .param("first", date.plusDays(1)).update();
        db.sql("INSERT INTO app_foreground_day VALUES (:user,:date) ON CONFLICT DO NOTHING")
                .param("user", user).param("date", date).update();
        return new Day(date);
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Seoul")
    void expire() {
        db.sql("DELETE FROM app_foreground_day WHERE activity_date < :first")
                .param("first", LocalDate.now(KST).minusDays(399)).update();
    }
}
