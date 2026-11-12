package kr.finsight.news;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import kr.finsight.media.MediaStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}", exposedHeaders = ContentEvents.EPOCH_HEADER)
@RequestMapping("/api/v1/news")
class AppNewsController {
    record Article(UUID id, String title, String source, String sourceUrl, String summary, String body,
                   String reason, String action, OffsetDateTime articlePublishedAt, LocalDate editionDate,
                   int revision, boolean hasImage, OffsetDateTime endsAt, @JsonIgnore String viewerType) {}
    record Feed(LocalDate today, boolean preparing, List<Article> articles) {}

    private final JdbcClient db;
    private final ContentEvents events;
    private final MediaStore media;

    AppNewsController(JdbcClient db, ContentEvents events, MediaStore media) {
        this.db = db;
        this.events = events;
        this.media = media;
    }

    @GetMapping
    ResponseEntity<Feed> feed(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate edition) {
        var today = LocalDate.now(NewsEditorial.KST);
        if (edition != null && edition.isAfter(today)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var articles = visible(jwt, today, edition);
        if (edition != null && articles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Feed(today, edition == null && (articles.isEmpty() || articles.getFirst().editionDate().isBefore(today)), articles));
    }

    @PostMapping("/{id}/events")
    @Transactional
    ResponseEntity<Void> event(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam LocalDate edition,
                               @Valid @RequestBody ContentEvents.Report input) {
        var user = UUID.fromString(jwt.getSubject());
        db.sql("SELECT id FROM app_user WHERE id=:id AND status='ACTIVE' FOR UPDATE")
                .param("id", user).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var article = visible(jwt, LocalDate.now(NewsEditorial.KST), edition).stream()
                .filter(row -> row.id().equals(id) && row.revision() == input.revision()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var epoch = events.record(user, ContentEvents.Kind.NEWS, id.toString(), article.revision(), article.viewerType(),
                input.action() == ContentEvents.Action.IMPRESSION ? ContentEvents.Placement.NEWS_LIST : null, input);
        return ResponseEntity.noContent().header(ContentEvents.EPOCH_HEADER, Long.toString(epoch)).build();
    }

    @GetMapping("/{id}")
    ResponseEntity<Article> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) LocalDate edition, @RequestParam(required = false) Integer revision) {
        var article = visible(jwt, LocalDate.now(NewsEditorial.KST), edition).stream()
                .filter(row -> row.id().equals(id) && (revision == null || revision == row.revision()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(article);
    }

    @GetMapping("/{id}/image")
    ResponseEntity<byte[]> image(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam LocalDate edition, @RequestParam int revision) {
        if (visible(jwt, LocalDate.now(NewsEditorial.KST), edition).stream()
                .noneMatch(row -> row.id().equals(id) && row.revision() == revision && row.hasImage())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var file = media.publishedNewsFile(id, revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mime()))
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    private List<Article> visible(Jwt jwt, LocalDate today, LocalDate edition) {
        return db.sql("""
                WITH viewer AS (
                    SELECT u.id, (SELECT d.representative_type FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id
                        WHERE a.user_id=u.id ORDER BY d.completed_at DESC,d.id DESC LIMIT 1) AS type
                    FROM app_user u WHERE u.id=:viewer AND u.status='ACTIVE'
                ), eligible AS (
                    SELECT a.id,r.title,a.source_url,r.summary,r.body,
                        CASE WHEN viewer.type IS NOT NULL THEN r.reason END AS reason,
                        r.action,r.article_published_at,e.edition_date,e.slot,r.revision,r.ends_at,
                        r.media_id IS NOT NULL AS has_image,viewer.type AS viewer_type
                    FROM viewer CROSS JOIN news_edition_item e JOIN news_article a ON a.id=e.article_id
                    JOIN news_revision r ON r.article_id=a.id AND r.revision=e.article_revision AND r.revision=a.published_revision
                    WHERE e.edition_date <= :today AND (:edition IS NULL OR e.edition_date=:edition) AND (viewer.type IS NULL OR e.type=viewer.type)
                        AND e.type=ANY(r.types) AND r.status='PUBLISHED' AND r.starts_at<=statement_timestamp()
                        AND (r.ends_at IS NULL OR r.ends_at>statement_timestamp())
                ), deduplicated AS (
                    SELECT DISTINCT ON (id) * FROM eligible
                    WHERE edition_date = (SELECT max(edition_date) FROM eligible)
                    ORDER BY id,slot
                )
                SELECT * FROM deduplicated ORDER BY slot,article_published_at DESC,id LIMIT 3
                """).param("viewer", UUID.fromString(jwt.getSubject())).param("today", today).param("edition", edition, java.sql.Types.DATE)
                .query((row, index) -> new Article(row.getObject("id", UUID.class), row.getString("title"), "한국경제",
                        row.getString("source_url"), row.getString("summary"), row.getString("body"),
                        row.getString("reason"), row.getString("action"), row.getObject("article_published_at", OffsetDateTime.class),
                        row.getObject("edition_date", LocalDate.class), row.getInt("revision"), row.getBoolean("has_image"),
                        row.getObject("ends_at", OffsetDateTime.class), row.getString("viewer_type"))).list();
    }
}
