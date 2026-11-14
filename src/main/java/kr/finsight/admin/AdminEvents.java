package kr.finsight.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminEvents {
    enum Type {
        USER_SIGNED_UP, PROFILE_CONSENT_COMPLETED, APP_FOREGROUND, DIAGNOSIS_ENTRY_VIEWED,
        SURVEY_STARTED, QUESTION_VIEWED, QUESTION_COMPLETED, SURVEY_ABORTED, SURVEY_COMPLETED,
        RESULT_VIEWED, GUIDE_VIEWED, GUIDE_COMPLETION_SET, NOTIFICATION_OPENED, CONTENT_IMPRESSION, CONTENT_VIEWED,
        EXPERIMENT_EXPOSED, HELPFUL_SET, USER_DELETION_REQUESTED, USER_WITHDRAWAL_REQUESTED
    }

    record Filter(UUID eventId, UUID userId, UUID attemptId, UUID journeyId, Type type,
                  LocalDate from, LocalDate to, int page, int size) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Properties(Integer questionNo, Integer activeMs, LocalDate activityDate, LocalDate month, Boolean value) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Event(UUID eventId, Type eventName, UUID userId, UUID attemptId, UUID journeyId, UUID diagnosisId,
                 String contentId, UUID notificationId, OffsetDateTime occurredAt, OffsetDateTime receivedAt,
                 String contentKind, Integer contentRevision, String placement,
                 UUID experimentId, String screenId, String variantId,
                 boolean clockAdjusted, Properties properties) {}
    record Page(List<Event> items, int page, int size, long total) {}

    private final JdbcClient db;

    AdminEvents(JdbcClient db) {
        this.db = db;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Page list(AdminAuth.Identity actor, Filter filters) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var today = LocalDate.now(AdminUsers.KST);
        var to = filters.to() == null ? today : filters.to();
        var from = filters.from() == null ? to.minusDays(29) : filters.from();
        if (to.isAfter(today) || from.isAfter(to) || to.minusDays(365).isAfter(from) || filters.page() < 1 || filters.size() < 1
                || filters.size() > 100) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);

        var clauses = new ArrayList<>(List.of("e.occurred_at>=:start", "e.occurred_at<:end", "u.status='ACTIVE'"));
        var values = new LinkedHashMap<String, Object>();
        values.put("start", from.atStartOfDay(AdminUsers.KST).toOffsetDateTime());
        values.put("end", to.plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime());
        values.put("event_id", filters.eventId());
        values.put("user_id", filters.userId());
        values.put("attempt_id", filters.attemptId());
        values.put("journey_id", filters.journeyId());
        values.put("event_name", filters.type() == null ? null : filters.type().name());
        values.values().removeIf(java.util.Objects::isNull);
        for (String column : List.of("event_id", "user_id", "attempt_id", "journey_id", "event_name")) {
            if (values.containsKey(column)) clauses.add("e." + column + "=:" + column);
        }
        var source = " FROM app_event e JOIN app_user u ON u.id=e.user_id WHERE " + String.join(" AND ", clauses);
        long total = db.sql("SELECT count(*)" + source).params(values).query(Long.class).single();
        var events = db.sql("""
                SELECT e.event_id,e.event_name,e.user_id,e.attempt_id,e.journey_id,e.diagnosis_id,e.content_id,e.notification_id,
                       e.occurred_at,e.received_at,e.clock_adjusted,e.question_no,e.active_ms,e.activity_date,
                       e.completion_month,e.completion_value,e.content_kind,e.content_revision,e.placement,
                       e.experiment_id,e.experiment_screen_id,e.experiment_variant_id
                """ + source + " ORDER BY e.occurred_at DESC,e.event_id DESC LIMIT :size OFFSET :offset")
                .params(values).param("size", filters.size()).param("offset", (filters.page()-1L)*filters.size())
                .query(this::row).list();
        return new Page(events, filters.page(), filters.size(), total);
    }

    private Event row(ResultSet row, int index) throws SQLException {
        return new Event(row.getObject(1, UUID.class), Type.valueOf(row.getString(2)), row.getObject(3, UUID.class),
                row.getObject(4, UUID.class), row.getObject(5, UUID.class), row.getObject(6, UUID.class),
                row.getString(7), row.getObject(8, UUID.class), row.getObject(9, OffsetDateTime.class),
                row.getObject(10, OffsetDateTime.class), row.getString(17), row.getObject(18, Integer.class),
                row.getString(19), row.getObject(20, UUID.class), row.getString(21), row.getString(22), row.getBoolean(11),
                new Properties(row.getObject(12, Integer.class), row.getObject(13, Integer.class),
                        row.getObject(14, LocalDate.class), row.getObject(15, LocalDate.class), row.getObject(16, Boolean.class)));
    }
}
