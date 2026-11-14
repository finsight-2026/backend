package kr.finsight.news;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
public class NewsCandidates {
    public record Candidate(String sourceUrl, String title, OffsetDateTime articlePublishedAt, OffsetDateTime collectedAt) {}
    public record Collection(String status, int attempts, OffsetDateTime nextRunAt, OffsetDateTime completedAt, int acceptedCount,
                             String errorCode) {}
    public record Inbox(List<Candidate> items, Collection collection) {}
    private record Run(String status, int attempts, OffsetDateTime nextRunAt) {}

    private final JdbcClient db;
    private final NewsCandidateSource source;
    private final TransactionTemplate transactions;

    NewsCandidates(JdbcClient db, NewsCandidateSource source, TransactionTemplate transactions) {
        this.db = db;
        this.source = source;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inbox inbox() {
        var now = OffsetDateTime.now(NewsEditorial.KST);
        var collection = db.sql("""
                SELECT status,attempts,next_run_at,completed_at,accepted_count,error_code
                FROM news_candidate_collection WHERE collection_date=:date
                """).param("date", now.toLocalDate()).query(Collection.class).optional().orElse(null);
        return new Inbox(available(now), collection);
    }

    List<Candidate> available(OffsetDateTime now) {
        return db.sql("""
                SELECT c.source_url,c.title,c.article_published_at,c.collected_at FROM news_candidate c
                WHERE c.article_published_at BETWEEN :oldest AND :now
                    AND NOT EXISTS (SELECT 1 FROM news_article a WHERE a.source_url=c.source_url)
                ORDER BY c.article_published_at DESC,c.source_url LIMIT 30
                """).param("now", now).param("oldest", now.minusHours(72)).query(Candidate.class).list();
    }

    @Scheduled(cron = "0 * 7 * * *", zone = "Asia/Seoul")
    void collectMorning() {
        collect(OffsetDateTime.now(NewsEditorial.KST));
    }

    void collect(OffsetDateTime timestamp) {
        var now = timestamp.atZoneSameInstant(NewsEditorial.KST).toOffsetDateTime();
        if (now.toLocalTime().isBefore(LocalTime.of(7, 0)) || !now.toLocalTime().isBefore(LocalTime.of(8, 0))) return;
        transactions.executeWithoutResult(status -> collectOnce(now));
    }

    private void collectOnce(OffsetDateTime now) {
        var date = now.toLocalDate();
        boolean locked = db.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "news-candidate-collection:" + date).query(Boolean.class).single();
        if (!locked) return;
        var previous = db.sql("SELECT status,attempts,next_run_at FROM news_candidate_collection WHERE collection_date=:date")
                .param("date", date).query(Run.class).optional();
        if (previous.isPresent() && (!previous.get().status().equals("QUEUED")
                || now.isBefore(previous.get().nextRunAt()))) return;
        int attempts = previous.map(run -> run.attempts() + 1).orElse(1);
        List<NewsCandidateSource.Item> rows;
        try {
            var snapshot = source.read();
            if (snapshot.isEmpty()) return;
            rows = snapshot.get();
        } catch (IOException failure) {
            recordFailure(now, attempts);
            return;
        }
        var candidates = rows.stream().map(row -> validated(row, now)).filter(Objects::nonNull)
                .sorted(Comparator.comparing(NewsCandidateSource.Item::articlePublishedAt).reversed()
                        .thenComparing(NewsCandidateSource.Item::title)).toList();
        var inserted = new java.util.HashSet<String>();
        int accepted = 0;
        for (var candidate : candidates) {
            if (!inserted.add(candidate.sourceUrl())) continue;
            accepted += db.sql("""
                    INSERT INTO news_candidate(source_url,title,article_published_at,collected_at)
                    SELECT :url,:title,:published,:now
                    WHERE NOT EXISTS(SELECT 1 FROM news_article WHERE source_url=:url)
                    ON CONFLICT(source_url) DO NOTHING
                    """).param("url", candidate.sourceUrl()).param("title", candidate.title())
                    .param("published", candidate.articlePublishedAt()).param("now", now).update();
        }
        db.sql("DELETE FROM news_candidate WHERE article_published_at<:oldest").param("oldest", now.minusHours(72)).update();
        saveRun(date, "COMPLETED", attempts, null, now, accepted, null);
    }

    private static NewsCandidateSource.Item validated(NewsCandidateSource.Item row, OffsetDateTime now) {
        var published = row.articlePublishedAt();
        if (published == null || published.isAfter(now) || published.isBefore(now.minusHours(72))
                || !NewsEditorial.text(row.title(), 80)) return null;
        try {
            return new NewsCandidateSource.Item(NewsEditorial.canonical(row.sourceUrl()), row.title().strip(), published);
        } catch (ResponseStatusException invalid) {
            return null;
        }
    }

    private void recordFailure(OffsetDateTime now, int attempts) {
        long delay = switch (attempts) { case 1 -> 1; case 2 -> 5; case 3 -> 15; default -> 0; };
        var next = now.plusMinutes(delay);
        if (delay == 0 || !next.toLocalTime().isBefore(LocalTime.of(8, 0))) {
            saveRun(now.toLocalDate(), "FAILED", attempts, null, now, 0, "NEWS_CANDIDATE_SOURCE_FAILED");
        } else {
            saveRun(now.toLocalDate(), "QUEUED", attempts, next, null, 0, "NEWS_CANDIDATE_SOURCE_FAILED");
        }
    }

    private void saveRun(LocalDate date, String status, int attempts, OffsetDateTime next, OffsetDateTime completed,
                         int accepted, String error) {
        db.sql("""
                INSERT INTO news_candidate_collection(collection_date,status,attempts,next_run_at,completed_at,accepted_count,error_code)
                VALUES (:date,:status,:attempts,:next,:completed,:accepted,:error)
                ON CONFLICT (collection_date) DO UPDATE SET status=excluded.status,attempts=excluded.attempts,
                    next_run_at=excluded.next_run_at,completed_at=excluded.completed_at,
                    accepted_count=excluded.accepted_count,error_code=excluded.error_code
                """).param("date", date).param("status", status).param("attempts", attempts)
                .param("next", next, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("completed", completed, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("accepted", accepted).param("error", error, java.sql.Types.VARCHAR).update();
    }
}
