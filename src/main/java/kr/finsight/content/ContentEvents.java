package kr.finsight.content;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@Service
public class ContentEvents {
    public static final String EPOCH_HEADER = "X-Finsight-Content-Epoch";
    public enum Kind { NEWS, GUIDE, INSIGHT }
    public enum Action { IMPRESSION, VIEWED }
    public enum Placement { NEWS_LIST, GUIDE_LIST, INSIGHT_LIST }

    public record Report(@NotNull UUID eventId, @NotNull Action action, @NotNull @Positive Integer revision,
                         UUID viewSessionId, @NotNull OffsetDateTime occurredAt, @PositiveOrZero Long contentEventEpoch) {
        @JsonAnySetter
        void rejectField(String key, Object value) {
            throw new IllegalArgumentException("Invalid content event field");
        }
    }

    private final JdbcClient db;

    ContentEvents(JdbcClient db) { this.db = db; }

    public long record(UUID user, Kind kind, String content, int revision, String type,
                       Placement placement, Report input) {
        boolean impression = input.action() == Action.IMPRESSION;
        if (impression != (input.viewSessionId() != null) || (impression && placement == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return db.sql("""
                WITH current_epoch AS (SELECT content_event_epoch AS epoch FROM app_user WHERE id=:user),
                received AS (SELECT clock_timestamp() AS stamp), event AS (
                    SELECT stamp,coalesce(abs(extract(epoch FROM CAST(:time AS timestamptz)-stamp))>300,false) AS adjusted
                    FROM received
                ), saved AS (
                    INSERT INTO app_event(event_id,event_name,user_id,content_kind,content_id,content_revision,
                        content_type,placement,view_session_id,occurred_at,received_at,clock_adjusted)
                    SELECT :id,:name,:user,:kind,:content,:revision,:type,:placement,:session,
                        CASE WHEN adjusted THEN stamp ELSE :time END,stamp,adjusted FROM event CROSS JOIN current_epoch
                    WHERE epoch=coalesce(:epoch,0)
                    ON CONFLICT DO NOTHING
                )
                SELECT epoch FROM current_epoch
                """).param("id", input.eventId()).param("name", impression ? "CONTENT_IMPRESSION" : "CONTENT_VIEWED")
                .param("user", user).param("kind", kind.name()).param("content", content).param("revision", revision)
                .param("type", type, java.sql.Types.VARCHAR)
                .param("placement", placement == null ? null : placement.name(), java.sql.Types.VARCHAR)
                .param("session", input.viewSessionId(), java.sql.Types.OTHER)
                .param("time", input.occurredAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("epoch", input.contentEventEpoch(), java.sql.Types.BIGINT).query(Long.class).single();
    }
}
