package kr.finsight.notification;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscordOutbox {
    enum Event { SIGNUP, WITHDRAWAL_REQUESTED, WITHDRAWAL_COMPLETED, SURVEY, NEWS }

    private final JdbcClient db;
    private final Map<Event, URI> destinations = new EnumMap<>(Event.class);

    DiscordOutbox(JdbcClient db,
                  @Value("${finsight.discord.enabled:false}") boolean enabled,
                  @Value("${finsight.discord.signup:}") String signup,
                  @Value("${finsight.discord.withdrawal:}") String withdrawal,
                  @Value("${finsight.discord.survey:}") String survey,
                  @Value("${finsight.discord.news:}") String news) {
        this.db = db;
        if (enabled) {
            configure(Event.SIGNUP, signup);
            configure(Event.WITHDRAWAL_REQUESTED, withdrawal);
            configure(Event.WITHDRAWAL_COMPLETED, withdrawal);
            configure(Event.SURVEY, survey);
            configure(Event.NEWS, news);
        }
    }

    private void configure(Event event, String address) {
        if (address.isBlank()) return;
        if (!address.matches("https://discord\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Discord 웹후크 주소가 올바르지 않습니다. 이벤트 " + event);
        }
        destinations.put(event, URI.create(address + "?wait=true"));
    }

    URI destination(Event event) {
        return destinations.get(event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void signedUp(UUID userId, String email) {
        if (!destinations.containsKey(Event.SIGNUP)) return;
        long count = db.sql("SELECT count(*) FROM app_user").query(Long.class).single();
        enqueue(Event.SIGNUP, userId, "[회원가입 완료]가입명수: %d명,\n완료 유저: %s".formatted(count, email));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void surveyCompleted(UUID diagnosisId, UUID userId) {
        if (!destinations.containsKey(Event.SURVEY)) return;
        var email = db.sql("SELECT email FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
        long count = db.sql("""
                SELECT count(DISTINCT a.user_id) FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                """).query(Long.class).single();
        enqueue(Event.SURVEY, diagnosisId, "[설문조사 완료]완료명수: %d명,\n완료 유저: %s".formatted(count, email));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void withdrawalRequested(UUID requestId, String email) {
        if (!destinations.containsKey(Event.WITHDRAWAL_REQUESTED)) return;
        enqueue(Event.WITHDRAWAL_REQUESTED, requestId, "[회원탈퇴 신청]\n신청 유저: " + email);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void withdrawalCompleted(UUID requestId, String email, long completedCount) {
        if (!destinations.containsKey(Event.WITHDRAWAL_COMPLETED)) return;
        long users = db.sql("SELECT count(*) FROM app_user").query(Long.class).single();
        enqueue(Event.WITHDRAWAL_COMPLETED, requestId,
                "[회원탈퇴 완료] 14일이 지나 탈퇴 처리 완료\n탈퇴명수:%d명, 가입명수:%d명\n신청 유저: %s"
                        .formatted(completedCount, users, email));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void newsSelected(java.time.LocalDate date, Map<kr.finsight.diagnosis.YfsiScoring.Pressure, String> links) {
        if (!destinations.containsKey(Event.NEWS)) return;
        var content = new StringBuilder("[오늘의 뉴스 선정 완료]\n");
        for (var type : kr.finsight.diagnosis.YfsiScoring.Pressure.values()) {
            content.append("\n- ").append(type.badge).append(':').append(links.get(type));
        }
        var id = UUID.nameUUIDFromBytes(("news-edition:" + date).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        enqueue(Event.NEWS, id, content.toString());
    }

    private void enqueue(Event event, UUID id, String content) {
        db.sql("""
                INSERT INTO discord_outbox (event_type,event_id,content) VALUES (:type,:id,:content)
                ON CONFLICT (event_type,event_id) DO NOTHING
                """).param("type", event.name()).param("id", id).param("content", content).update();
    }
}
