package kr.finsight.content;

import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring.Pressure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

public record EditorialPage<T>(List<T> items, long total, int page, int size) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Filter(String status, String title, UUID authorId, LocalDate from, LocalDate to, Pressure type) {
        public Filter {
            title = title == null ? "" : title.strip();
            if (title.codePointCount(0, title.length()) > 80 || from != null && to != null && from.isAfter(to)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        public static final String WHERE = """
                AND (CAST(:status AS text) IS NULL OR r.status=:status)
                AND (CAST(:type AS text) IS NULL OR :type=ANY(r.types))
                AND (:title='' OR position(lower(:title) in lower(r.title))>0)
                AND (CAST(:author AS uuid) IS NULL OR r.author_id=:author)
                AND (CAST(:start AS timestamptz) IS NULL OR r.updated_at>=:start)
                AND (CAST(:end AS timestamptz) IS NULL OR r.updated_at<:end)
                """;

        public JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query) {
            return query.param("status", status, Types.VARCHAR)
                    .param("type", type == null ? null : type.name(), Types.VARCHAR)
                    .param("title", title).param("author", authorId, Types.OTHER)
                    .param("start", from == null ? null : from.atStartOfDay(KST).toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("end", to == null ? null : to.plusDays(1).atStartOfDay(KST).toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    public static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return ((long) page - 1) * size;
    }
}
