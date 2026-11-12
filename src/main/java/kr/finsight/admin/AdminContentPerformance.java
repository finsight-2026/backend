package kr.finsight.admin;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.finsight.content.ContentEvents;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminContentPerformance {
    record Filter(LocalDate from, LocalDate to, ContentEvents.Kind kind, YfsiScoring.Pressure type,
                  String contentId, Integer revision, boolean combineVersions) {
        static Filter resolve(Filter input) {
            var value = input == null ? new Filter(null, null, null, null, null, null, false) : input;
            var range = AdminDashboard.Filter.resolve(new AdminDashboard.Filter(value.from(), value.to(), null, value.type()));
            if ((value.revision() != null && (value.revision() < 1 || value.combineVersions()))
                    || (value.contentId() != null && !value.contentId().matches("[A-Za-z0-9_-]{1,80}"))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return new Filter(range.from(), range.to(), value.kind(), value.type(), value.contentId(),
                    value.revision(), value.combineVersions());
        }
    }
    record Row(String kind, String contentId, Integer revision, String title, long impressions, long views,
               long ctrNumerator, BigDecimal ctr, Long helpful, Long completions) {}
    record Report(LocalDate from, LocalDate to, LocalDate completionMonth, OffsetDateTime asOf,
                  OffsetDateTime observedAt, boolean provisional, List<Row> items) {}
    private record Clock(OffsetDateTime asOf, OffsetDateTime observedAt) {}

    private final JdbcClient db;

    AdminContentPerformance(JdbcClient db) {
        this.db = db;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Report list(AdminAuth.Identity actor, Filter filter) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("ANALYST") && !actor.permissions().contains("OPERATOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return load(Filter.resolve(filter));
    }

    Report load(Filter filter) {
        var clock = db.sql("SELECT now() AS as_of,started_at AS observed_at FROM content_performance_observation WHERE singleton")
                .query(Clock.class).single();
        var start = filter.from().atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var end = filter.to().plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var month = filter.to().withDayOfMonth(1);
        var rows = db.sql("""
                WITH catalog AS (
                    SELECT 'NEWS' AS kind,a.id::text AS id,r.revision,r.title,r.types
                    FROM news_article a JOIN news_revision r ON r.article_id=a.id
                    WHERE r.starts_at IS NOT NULL AND r.starts_at<:end
                    UNION
                    SELECT n.kind,coalesce(n.external_key,n.id::text),r.revision_no,r.title,r.types
                    FROM service_notice n JOIN service_notice_revision r ON r.notice_id=n.id
                    WHERE n.kind IN ('GUIDE','INSIGHT') AND r.starts_at IS NOT NULL AND r.starts_at<:end
                        AND (r.status IN ('PUBLISHED','UNPUBLISHED','EXPIRED') OR EXISTS (
                            SELECT 1 FROM service_notice_publication_event e WHERE e.revision_id=r.id AND e.event_type='PUBLISHED'
                        ))
                    UNION
                    SELECT 'GUIDE',g.content_id,g.content_version,g.title,ARRAY[g.representative_type]
                    FROM guide_activity g JOIN app_user u ON u.id=g.user_id AND u.status='ACTIVE' AND NOT u.comparison_test_account
                ), requested AS (
                    SELECT DISTINCT ON (kind,id,version) kind,id,version,title FROM (
                        SELECT kind,id,CASE WHEN :combine THEN NULL ELSE revision END AS version,title,revision
                        FROM catalog
                        WHERE (:kind IS NULL OR kind=:kind) AND (:type IS NULL OR :type=ANY(types))
                            AND (:content IS NULL OR id=:content) AND (CAST(:revision AS integer) IS NULL OR revision=:revision)
                    ) candidates ORDER BY kind,id,version,revision DESC,title
                ), viewers AS (
                    SELECT e.content_kind AS kind,e.content_id AS id,
                        CASE WHEN :combine THEN NULL ELSE e.content_revision END AS version,e.user_id,
                        bool_or(e.event_name='CONTENT_IMPRESSION') AS impression,
                        bool_or(e.event_name IN ('CONTENT_VIEWED','GUIDE_VIEWED')) AS viewed
                    FROM app_event e JOIN app_user u ON u.id=e.user_id AND u.status='ACTIVE' AND NOT u.comparison_test_account
                    WHERE e.event_name IN ('CONTENT_IMPRESSION','CONTENT_VIEWED','GUIDE_VIEWED')
                        AND e.occurred_at>=:start AND e.occurred_at<:end AND (:type IS NULL OR e.content_type=:type)
                        AND (:kind IS NULL OR e.content_kind=:kind) AND (:content IS NULL OR e.content_id=:content)
                        AND (CAST(:revision AS integer) IS NULL OR e.content_revision=:revision)
                    GROUP BY 1,2,3,4
                ), counts AS (
                    SELECT kind,id,version,count(*) FILTER(WHERE impression) AS impressions,
                        count(*) FILTER(WHERE viewed) AS views,count(*) FILTER(WHERE impression AND viewed) AS ctr_numerator
                    FROM viewers GROUP BY kind,id,version
                ), completion AS (
                    SELECT g.content_id AS id,CASE WHEN :combine THEN NULL ELSE coalesce(g.last_completion_revision,g.content_version) END AS version,
                        count(DISTINCT g.user_id) AS total
                    FROM guide_activity g JOIN app_user u ON u.id=g.user_id AND u.status='ACTIVE' AND NOT u.comparison_test_account
                    WHERE g.month_start=:month AND g.completed_at IS NOT NULL
                        AND (:type IS NULL OR coalesce(g.last_completion_type,g.representative_type)=:type)
                    GROUP BY 1,2
                ), helpful_state AS (
                    SELECT DISTINCT ON (e.content_id,e.user_id) e.content_id,e.content_revision,e.content_type,e.completion_value
                    FROM app_event e JOIN app_user u ON u.id=e.user_id AND u.status='ACTIVE' AND NOT u.comparison_test_account
                    WHERE e.event_name='HELPFUL_SET' AND e.occurred_at<:end
                        AND (:content IS NULL OR e.content_id=:content)
                    ORDER BY e.content_id,e.user_id,e.occurred_at DESC,e.received_at DESC,e.event_id DESC
                ), helpful AS (
                    SELECT h.content_id AS id,CASE WHEN :combine THEN NULL ELSE h.content_revision END AS version,
                        count(*) AS total
                    FROM helpful_state h
                    WHERE h.completion_value AND (:type IS NULL OR h.content_type=:type)
                    GROUP BY 1,2
                )
                SELECT r.kind,r.id AS content_id,r.version AS revision,r.title,
                    coalesce(c.impressions,0) AS impressions,coalesce(c.views,0) AS views,
                    coalesce(c.ctr_numerator,0) AS ctr_numerator,
                    round(100.0*c.ctr_numerator/nullif(c.impressions,0),2) AS ctr,
                    CASE WHEN r.kind='INSIGHT' THEN coalesce(h.total,0) END AS helpful,CASE WHEN r.kind='GUIDE' THEN coalesce(g.total,0) END AS completions
                FROM requested r LEFT JOIN counts c ON c.kind=r.kind AND c.id=r.id AND c.version IS NOT DISTINCT FROM r.version
                LEFT JOIN completion g ON r.kind='GUIDE' AND g.id=r.id AND g.version IS NOT DISTINCT FROM r.version
                LEFT JOIN helpful h ON r.kind='INSIGHT' AND h.id=r.id AND h.version IS NOT DISTINCT FROM r.version
                ORDER BY impressions DESC,views DESC,r.kind,r.id,r.version DESC
                """).param("combine", filter.combineVersions())
                .param("kind", filter.kind() == null ? null : filter.kind().name(), Types.VARCHAR)
                .param("type", filter.type() == null ? null : filter.type().name(), Types.VARCHAR)
                .param("content", filter.contentId(), Types.VARCHAR).param("revision", filter.revision(), Types.INTEGER)
                .param("start", start).param("end", end).param("month", month).query(Row.class).list();
        return new Report(filter.from(), filter.to(), month, clock.asOf(), clock.observedAt(),
                start.isBefore(clock.observedAt()) || end.isAfter(clock.asOf()), rows);
    }
}
