package kr.finsight.news;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NewsCandidatesTest {
    @TempDir Path files;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate transactions;

    @Test
    @Transactional
    void morningCollectionValidatesTimeAndSourceAndKeepsCandidatesOutOfEditorialUntilSelected() throws Exception {
        var time = OffsetDateTime.parse("2026-11-09T07:00:00+09:00");
        var file = files.resolve("candidates.json");
        Files.writeString(file, """
                [
                  {"sourceUrl":"https://hankyung.com/article/930001/?utm_source=test","title":"최근 기사","articlePublishedAt":"2026-11-09T06:00:00+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930001#link","title":"같은 기사","articlePublishedAt":"2026-11-09T05:00:00+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930002","title":"72시간 경계","articlePublishedAt":"2026-11-06T07:00:00+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930003","title":"오래된 기사","articlePublishedAt":"2026-11-06T06:59:59+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930004","title":"미래 기사","articlePublishedAt":"2026-11-09T07:00:01+09:00"},
                  {"sourceUrl":"https://127.0.0.1/article/930005","title":"내부 주소","articlePublishedAt":"2026-11-09T06:00:00+09:00"},
                  {"sourceUrl":"https://hankyung.com@host.example/article/930006","title":"다른 주소","articlePublishedAt":"2026-11-09T06:00:00+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930007","title":"<script>","articlePublishedAt":"2026-11-09T06:00:00+09:00"},
                  {"sourceUrl":"https://www.hankyung.com/article/930008","title":"발행 시각 없음","articlePublishedAt":"invalid"}
                ]
                """);
        var reads = new AtomicInteger();
        var source = new LocalNewsCandidateSource(file.toString(), json);
        var collector = new NewsCandidates(db, () -> { reads.incrementAndGet(); return source.read(); }, transactions);
        collector.collect(time.minusSeconds(1));
        collector.collect(time);
        collector.collect(time.plusMinutes(2));
        collector.collect(time.plusHours(1));
        assertEquals(1, reads.get());
        assertEquals(List.of("최근 기사", "72시간 경계"),
                collector.available(time).stream().map(NewsCandidates.Candidate::title).toList());
        assertEquals(List.of("https://www.hankyung.com/article/930001", "https://www.hankyung.com/article/930002"),
                collector.available(time).stream().map(NewsCandidates.Candidate::sourceUrl).toList());
        assertEquals(2, db.sql("SELECT accepted_count FROM news_candidate_collection WHERE collection_date=:date")
                .param("date", time.toLocalDate()).query(Integer.class).single());
        assertEquals(0, db.sql("""
                SELECT count(*) FROM news_article WHERE source_url LIKE 'https://www.hankyung.com/article/93000%'
                """).query(Integer.class).single());
        db.sql("""
                INSERT INTO news_article(id,source_url) VALUES (gen_random_uuid(),'https://www.hankyung.com/article/930001')
                """).update();
        assertEquals(List.of(), collector.available(time.plusSeconds(1)));
    }

    @Test
    @Transactional
    void retriesUnavailableSourceWithinMorningAndDoesNotRetryCompletedCollection() throws Exception {
        var time = OffsetDateTime.parse("2026-11-10T07:00:00+09:00");
        var file = files.resolve("later.json");
        var collector = new NewsCandidates(db, new LocalNewsCandidateSource(file.toString(), json), transactions);
        collector.collect(time);
        collector.collect(time.plusMinutes(1));
        collector.collect(time.plusMinutes(6));
        assertEquals(time.plusMinutes(21).toInstant(), db.sql("""
                SELECT next_run_at FROM news_candidate_collection WHERE collection_date=:date AND attempts=3
                """).param("date", time.toLocalDate()).query(OffsetDateTime.class).single().toInstant());
        Files.writeString(file, "[]");
        collector.collect(time.plusMinutes(20));
        assertEquals("QUEUED", status(time));
        collector.collect(time.plusMinutes(21));
        assertEquals("COMPLETED", status(time));
        Files.delete(file);
        collector.collect(time.plusMinutes(22));
        assertEquals("COMPLETED", status(time));
        collector.collect(time.plusDays(1).plusMinutes(59));
        assertEquals("FAILED", status(time.plusDays(1)));
    }

    private String status(OffsetDateTime time) {
        return db.sql("SELECT status FROM news_candidate_collection WHERE collection_date=:date")
                .param("date", time.toLocalDate()).query(String.class).single();
    }
}
