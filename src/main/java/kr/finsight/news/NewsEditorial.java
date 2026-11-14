package kr.finsight.news;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
import kr.finsight.content.EditorialPage;
import kr.finsight.notification.DiscordOutbox;
import kr.finsight.media.MediaStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
public class NewsEditorial {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public enum Status { DRAFT, IN_REVIEW, APPROVED, SCHEDULED, PUBLISHED, UNPUBLISHED, EXPIRED, ARCHIVED }
    public enum Action { SUBMIT, WITHDRAW, APPROVE, REJECT, PUBLISH, SCHEDULE, CANCEL_SCHEDULE, UNPUBLISH, ARCHIVE }
    public record Draft(String sourceUrl, String title, String summary, String body, String reason, String action,
                        OffsetDateTime articlePublishedAt, List<YfsiScoring.Pressure> types, UUID mediaId) {
        public Draft(String sourceUrl, String title, String summary, String body, String reason, String action,
                     OffsetDateTime articlePublishedAt, List<YfsiScoring.Pressure> types) {
            this(sourceUrl, title, summary, body, reason, action, articlePublishedAt, types, null);
        }
    }
    public record Change(Action action, Long expectedVersion, String reason, OffsetDateTime startsAt, OffsetDateTime endsAt,
                         Boolean previewConfirmed) {
        public Change(Action action, Long expectedVersion, String reason, OffsetDateTime startsAt, OffsetDateTime endsAt) {
            this(action, expectedVersion, reason, startsAt, endsAt, null);
        }
    }
    public record Article(UUID id, int revision, long version, UUID authorId, String status,
                          Integer publishedRevision, String sourceUrl, String title, String summary,
                          String body, String reason, String action, OffsetDateTime articlePublishedAt,
                          List<String> types, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID mediaId,
                          OffsetDateTime updatedAt, UUID updatedBy, String updatedByName) {}
    public record EditionOption(UUID contentId, int revisionId, String title, String status) {}
    public record EditionSlot(int slot, UUID contentId, Integer revisionId) {}
    public record EditionChoices(List<EditionOption> options, List<EditionSlot> slots, long version) {}
    public record Edition(LocalDate date, String type, List<EditionSlot> slots, long version) {}
    private record SavedSlot(int slot, UUID contentId, Integer revisionId, OffsetDateTime assignedAt) {
        EditionSlot value() { return new EditionSlot(slot, contentId, revisionId); }
    }
    public record Due(UUID articleId, int revision) {}
    public record Activation(UUID actorId, Article article) {}

    private static final String COLUMNS = """
             a.id,r.revision,r.version,r.author_id,r.status,a.published_revision,a.source_url,
                   r.title,r.summary,r.body,r.reason,r.action,r.article_published_at,r.types,r.starts_at,r.ends_at,r.media_id,r.updated_at,r.updated_by,editor.name
            """;
    private static final String FROM = """
            FROM news_article a JOIN news_revision r ON r.article_id=a.id
            LEFT JOIN admin_account editor ON editor.id=r.updated_by
            """;
    private static final String SELECT = "SELECT " + COLUMNS + FROM;
    private static final String ACTIVE = """
            AND a.published_revision=r.revision AND r.status='PUBLISHED' AND r.starts_at<=statement_timestamp()
            AND (r.ends_at IS NULL OR r.ends_at>statement_timestamp())
            """;
    private static final String EDITION_ELIGIBLE = """
            r.article_id=a.id AND (r.status='SCHEDULED' OR (r.status='PUBLISHED' AND r.revision=a.published_revision))
                AND :type=ANY(r.types) AND r.article_published_at>=:since
                AND r.starts_at<:end AND (r.ends_at IS NULL OR r.ends_at>greatest(:day,statement_timestamp()))
            """;

    private final JdbcClient db;
    private final DiscordOutbox discord;
    private final JsonMapper json;
    private final MediaStore media;

    NewsEditorial(JdbcClient db, JsonMapper json, DiscordOutbox discord, MediaStore media) {
        this.db = db;
        this.discord = discord;
        this.json = json;
        this.media = media;
    }

    public List<Article> list() {
        return db.sql(SELECT + " WHERE r.status<>'ARCHIVED' ORDER BY r.updated_at DESC,a.id,r.revision DESC LIMIT 100")
                .query(this::article).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EditorialPage<Article> page(EditorialPage.Filter filter, int page, int size) {
        long offset = EditorialPage.offset(page, size);
        var conditions = " WHERE true " + EditorialPage.Filter.WHERE + (filter.status() == null ? " AND r.status<>'ARCHIVED'"
                : "PUBLISHED".equals(filter.status()) ? ACTIVE : "");
        long total = filter.bind(db.sql("SELECT count(DISTINCT a.id) " + FROM + conditions)).query(Long.class).single();
        var items = filter.bind(db.sql("""
                WITH selected AS (
                    SELECT DISTINCT ON (a.id) a.id,r.revision,r.updated_at
                """ + FROM + conditions + """
                    ORDER BY a.id,r.revision DESC
                )
                """ + SELECT + """
                JOIN selected s ON s.id=a.id AND s.revision=r.revision
                ORDER BY s.updated_at DESC,a.id DESC LIMIT :size OFFSET :offset
                """)).param("size", size).param("offset", offset).query(this::article).list();
        return new EditorialPage<>(items, total, page, size);
    }

    public List<Article> revisions(UUID id) {
        var rows = db.sql(SELECT + " WHERE a.id=:id ORDER BY r.revision DESC")
                .param("id", id).query(this::article).list();
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows;
    }

    public Article lookup(UUID id, Integer revision) {
        if (revision != null) {
            if (revision < 1) throw invalid();
            return get(id, revision);
        }
        return db.sql(SELECT + " WHERE a.id=:id ORDER BY r.revision DESC LIMIT 1")
                .param("id", id).query(this::article).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EditionChoices editionOptions(LocalDate date, YfsiScoring.Pressure type) {
        var day = date.atStartOfDay(KST).toOffsetDateTime();
        var options = editionWindow(db.sql("""
                SELECT a.id AS content_id,r.revision AS revision_id,r.title,r.status
                FROM news_article a JOIN news_revision r ON
                """ + EDITION_ELIGIBLE + """
                ORDER BY r.article_published_at DESC,a.id DESC,r.revision DESC
                """), type, day).query(EditionOption.class).list();
        return new EditionChoices(options, editionSlots(date, type).stream().map(SavedSlot::value).toList(), editionVersion(date, type));
    }

    @Transactional
    public Article draft(UUID actor, UUID id, Draft input) {
        var source = canonical(input.sourceUrl());
        validate(input);
        var article = id == null ? UUID.randomUUID() : id;
        if (id != null) lock(article);
        media.requireNewsAttachment(actor, article, input.mediaId());
        int revision;
        if (id == null) {
            var added = db.sql("""
                    INSERT INTO news_article(id,source_url) VALUES (:id,:source)
                    ON CONFLICT (source_url) DO NOTHING
                    """).param("id", article).param("source", source).update();
            if (added == 0) throw conflict();
            revision = 1;
        } else {
            var last = db.sql(SELECT + " WHERE a.id = :id ORDER BY r.revision DESC LIMIT 1")
                    .param("id", article).query(this::article).single();
            if (!last.sourceUrl().equals(source) || List.of("DRAFT","IN_REVIEW","SCHEDULED").contains(last.status())) throw conflict();
            revision = last.revision() + 1;
        }
        writeDraft(actor, article, revision, input, null);
        return get(article, revision);
    }

    @Transactional
    public Article edit(UUID actor, UUID id, int revision, long version, Draft input) {
        validate(input);
        lock(id);
        var current = get(id, revision);
        if (!current.authorId().equals(actor) || !current.status().equals("DRAFT")
                || current.version() != version || !current.sourceUrl().equals(canonical(input.sourceUrl()))) throw conflict();
        media.requireNewsAttachment(actor, id, input.mediaId());
        writeDraft(actor, id, revision, input, version);
        return get(id, revision);
    }

    private void writeDraft(UUID actor, UUID article, int revision, Draft input, Long version) {
        var sql = version == null ? """
                INSERT INTO news_revision(article_id,revision,author_id,status,title,summary,body,reason,action,
                    article_published_at,types,media_id,updated_by)
                VALUES (:id,:revision,:author,'DRAFT',:title,:summary,:body,:reason,:action,:published,
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:types AS jsonb))),:media,:author)
                """ : """
                UPDATE news_revision SET title=:title, summary=:summary,body=:body,reason=:reason,action=:action,
                    article_published_at=:published, types=ARRAY(SELECT jsonb_array_elements_text(CAST(:types AS jsonb))),media_id=:media,
                    version=version+1,updated_at=clock_timestamp(),updated_by=:author
                WHERE article_id=:id AND revision=:revision AND author_id=:author
                """;
        db.sql(sql).param("id", article).param("revision", revision).param("author", actor)
                .param("title", input.title().strip()).param("summary", input.summary().strip())
                .param("body", input.body().strip()).param("reason", input.reason().strip())
                .param("action", input.action().strip()).param("published", input.articlePublishedAt())
                .param("types", json.writeValueAsString(input.types())).param("media", input.mediaId(), java.sql.Types.OTHER).update();
    }

    @Transactional
    public Article transition(UUID actor, UUID id, int revision, Change change) {
        return transition(actor, id, revision, change, true, false);
    }

    @Transactional
    public Article transition(UUID actor, UUID id, int revision, Change change, boolean operator, boolean supervisor) {
        lock(id);
        var row = get(id, revision);
        if (change.expectedVersion() == null || row.version() != change.expectedVersion()) throw conflict();
        if ((change.action() == Action.SUBMIT || change.action() == Action.WITHDRAW) && !row.authorId().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        var startsAt = change.startsAt() == null ? now : change.startsAt();
        var target = switch (change.action()) {
            case SUBMIT -> from(row, "DRAFT", "IN_REVIEW");
            case WITHDRAW -> from(row, "IN_REVIEW", "DRAFT");
            case APPROVE -> {
                if (actor.equals(row.authorId())) requireReason(change.reason());
                yield from(row, "IN_REVIEW", "APPROVED");
            }
            case REJECT -> {
                requireReason(change.reason());
                yield from(row, "IN_REVIEW", "DRAFT");
            }
            case PUBLISH, SCHEDULE -> {
                if (!row.status().equals("APPROVED")) throw conflict();
                boolean scheduled = change.action() == Action.SCHEDULE;
                if (scheduled != startsAt.isAfter(now) || (change.endsAt() != null
                        && (!change.endsAt().isAfter(startsAt) || !change.endsAt().isAfter(now)))) throw invalid();
                if (actor.equals(row.authorId())) requireReason(change.reason());
                if (scheduled && db.sql("""
                        SELECT EXISTS(SELECT 1 FROM news_revision WHERE article_id=:id AND status='SCHEDULED')
                        """).param("id", id).query(Boolean.class).single()) throw conflict();
                db.sql("""
                        UPDATE news_revision SET starts_at=:start,ends_at=:end,scheduled_by=:scheduler
                        WHERE article_id=:id AND revision=:revision
                        """).param("id", id).param("revision", revision).param("start", startsAt)
                        .param("end", change.endsAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("scheduler", scheduled ? actor : null, java.sql.Types.OTHER).update();
                if (!scheduled) publish(row, actor);
                yield scheduled ? "SCHEDULED" : "PUBLISHED";
            }
            case CANCEL_SCHEDULE -> {
                if (!row.status().equals("SCHEDULED")) throw conflict();
                clearSchedule(id, revision);
                yield "APPROVED";
            }
            case UNPUBLISH -> {
                requireReason(change.reason());
                if (!Integer.valueOf(revision).equals(row.publishedRevision())) throw conflict();
                yield from(row, "PUBLISHED", "UNPUBLISHED");
            }
            case ARCHIVE -> {
                if (row.status().equals("DRAFT")) {
                    if (!operator || !row.authorId().equals(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                } else {
                    if (!List.of("UNPUBLISHED", "EXPIRED").contains(row.status())
                            || Integer.valueOf(revision).equals(row.publishedRevision())) throw conflict();
                    if (!supervisor) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                yield "ARCHIVED";
            }
        };
        if (change.action() == Action.UNPUBLISH) {
            db.sql("UPDATE news_article SET published_revision=NULL WHERE id=:id").param("id", id).update();
        }
        setStatus(id, revision, target, actor);
        return get(id, revision);
    }

    private void setStatus(UUID id, int revision, String target, UUID actor) {
        db.sql("""
                UPDATE news_revision SET status=:status,version=version+1,updated_at=clock_timestamp(),updated_by=:actor
                WHERE article_id=:id AND revision=:revision
                """).param("id", id).param("revision", revision).param("status", target).param("actor", actor).update();
    }

    private void clearSchedule(UUID id, int revision) {
        db.sql("""
                UPDATE news_revision SET starts_at=NULL,ends_at=NULL,scheduled_by=NULL
                WHERE article_id=:id AND revision=:revision
                """).param("id", id).param("revision", revision).update();
    }

    private void publish(Article row, UUID actor) {
        if (row.publishedRevision() != null) setStatus(row.id(), row.publishedRevision(), "UNPUBLISHED", actor);
        db.sql("UPDATE news_article SET published_revision=:revision WHERE id=:id")
                .param("id", row.id()).param("revision", row.revision()).update();
    }

    public List<Due> duePublications() {
        return db.sql("""
                SELECT article_id,revision FROM news_revision
                WHERE (status='SCHEDULED' AND starts_at<=clock_timestamp())
                   OR (status='PUBLISHED' AND ends_at<=clock_timestamp())
                ORDER BY CASE WHEN status='SCHEDULED' THEN starts_at ELSE ends_at END,article_id,revision LIMIT 100
                """).query(Due.class).list();
    }

    @Transactional
    public Activation processPublication(Due due) {
        var id = due.articleId();
        if (db.sql("SELECT id FROM news_article WHERE id=:id FOR UPDATE SKIP LOCKED")
                .param("id", id).query(UUID.class).optional().isEmpty()) return null;
        var row = get(id, due.revision());
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        boolean ended = row.endsAt() != null && !row.endsAt().isAfter(now);
        if (ended && row.status().equals("PUBLISHED") && Integer.valueOf(row.revision()).equals(row.publishedRevision())) {
            db.sql("UPDATE news_article SET published_revision=NULL WHERE id=:id").param("id", id).update();
            setStatus(id, row.revision(), "EXPIRED", lastEditor(row));
            return null;
        }
        if (!row.status().equals("SCHEDULED") || row.startsAt().isAfter(now)) return null;
        if (ended) {
            setStatus(id, row.revision(), "EXPIRED", lastEditor(row));
            return null;
        }
        var actor = db.sql("""
                SELECT a.id FROM news_revision n JOIN admin_account a ON a.id=n.scheduled_by
                JOIN admin_role role ON role.admin_id=a.id AND role.role='SUPER_ADMIN'
                WHERE n.article_id=:id AND n.revision=:revision AND a.status='ACTIVE' FOR SHARE OF a,role
                """).param("id", id).param("revision", row.revision()).query(UUID.class).optional();
        if (actor.isEmpty()) {
            clearSchedule(id, row.revision());
            setStatus(id, row.revision(), "APPROVED", lastEditor(row));
            return null;
        }
        media.requireNewsAttachment(actor.get(), id, row.mediaId());
        publish(row, actor.get());
        setStatus(id, row.revision(), "PUBLISHED", actor.get());
        db.sql("""
                SELECT DISTINCT edition_date FROM news_edition_item
                WHERE article_id=:id AND article_revision=:revision AND edition_date>=:today
                """).param("id", id).param("revision", row.revision()).param("today", now.atZoneSameInstant(KST).toLocalDate())
                .query(LocalDate.class).list().forEach(this::announceCompleteEdition);
        return new Activation(actor.get(), get(id, row.revision()));
    }

    private static UUID lastEditor(Article row) {
        return row.updatedBy() == null ? row.authorId() : row.updatedBy();
    }

    @Transactional
    public Edition edition(LocalDate date, YfsiScoring.Pressure type, List<EditionSlot> input, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0 || input == null || input.isEmpty() || input.size() > 3
                || date.isBefore(LocalDate.now(KST)) || input.stream().anyMatch(slot -> slot == null
                || slot.contentId() == null || slot.revisionId() == null || slot.revisionId() < 1)) throw invalid();
        var slots = input.stream().sorted(java.util.Comparator.comparingInt(EditionSlot::slot)).toList();
        if (slots.stream().map(EditionSlot::contentId).distinct().count() != slots.size()) throw invalid();
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).slot() != i + 1) throw invalid();
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "news-edition:" + date).query((row, index) -> true).single();
        long version = editionVersion(date, type);
        if (version != expectedVersion) throw conflict();
        slots.stream().map(EditionSlot::contentId).sorted().forEach(this::lock);
        var day = date.atStartOfDay(KST).toOffsetDateTime();
        for (var slot : slots) {
            boolean eligible = editionWindow(db.sql("""
                    SELECT EXISTS(SELECT 1 FROM news_article a JOIN news_revision r ON
                    """ + EDITION_ELIGIBLE + " WHERE a.id=:id AND r.revision=:revision)"), type, day)
                    .param("id", slot.contentId()).param("revision", slot.revisionId()).query(Boolean.class).single();
            if (!eligible) throw invalid();
        }
        var previous = editionSlots(date, type);
        if (slots.equals(previous.stream().map(SavedSlot::value).toList())) return new Edition(date, type.name(), slots, version);
        db.sql("DELETE FROM news_edition_item WHERE edition_date=:date AND type=:type")
                .param("date", date).param("type", type.name()).update();
        for (var slot : slots) {
            var prior = previous.stream().filter(saved -> saved.value().equals(slot)).findFirst();
            db.sql("""
                    INSERT INTO news_edition_item(edition_date,type,slot,article_id,article_revision,assigned_at)
                    VALUES (:date,:type,:slot,:id,:revision,CASE WHEN :preserved THEN :assigned ELSE clock_timestamp() END)
                    """).param("date", date).param("type", type.name()).param("slot", slot.slot())
                    .param("id", slot.contentId()).param("revision", slot.revisionId())
                    .param("preserved", prior.isPresent())
                    .param("assigned", prior.map(SavedSlot::assignedAt).orElse(null), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        }
        db.sql("""
                INSERT INTO news_edition_version(edition_date,type,version) VALUES (:date,:type,:version)
                ON CONFLICT(edition_date,type) DO UPDATE SET version=excluded.version
                """).param("date", date).param("type", type.name()).param("version", version + 1).update();
        announceCompleteEdition(date);
        return new Edition(date, type.name(), slots, version + 1);
    }

    private long editionVersion(LocalDate date, YfsiScoring.Pressure type) {
        return db.sql("SELECT version FROM news_edition_version WHERE edition_date=:date AND type=:type")
                .param("date", date).param("type", type.name()).query(Long.class).optional().orElse(0L);
    }

    private List<SavedSlot> editionSlots(LocalDate date, YfsiScoring.Pressure type) {
        return db.sql("""
                SELECT slot,article_id AS content_id,article_revision AS revision_id,assigned_at
                FROM news_edition_item WHERE edition_date=:date AND type=:type ORDER BY slot
                """).param("date", date).param("type", type.name()).query(SavedSlot.class).list();
    }

    private static JdbcClient.StatementSpec editionWindow(JdbcClient.StatementSpec sql, YfsiScoring.Pressure type, OffsetDateTime day) {
        return sql.param("type", type.name()).param("since", day.minusHours(72)).param("end", day.plusDays(1)).param("day", day);
    }

    private void announceCompleteEdition(LocalDate date) {
        var selected = new java.util.EnumMap<YfsiScoring.Pressure, String>(YfsiScoring.Pressure.class);
        var day = date.atStartOfDay(KST).toOffsetDateTime();
        db.sql("""
                SELECT e.type,a.source_url FROM news_edition_item e JOIN news_article a ON a.id=e.article_id
                JOIN news_revision r ON r.article_id=a.id AND r.revision=e.article_revision AND r.revision=a.published_revision
                WHERE e.edition_date=:date AND e.slot=1 AND r.status='PUBLISHED' AND e.type=ANY(r.types)
                    AND r.article_published_at>=:since AND r.starts_at<:end
                    AND (r.ends_at IS NULL OR (r.ends_at>:day AND r.ends_at>statement_timestamp()))
                """).param("date", date).param("since", day.minusHours(72)).param("day", day).param("end", day.plusDays(1))
                .query((row, index) -> {
            selected.put(YfsiScoring.Pressure.valueOf(row.getString(1)), row.getString(2));
            return true;
        }).list();
        if (selected.size() == YfsiScoring.Pressure.values().length) discord.newsSelected(date, selected);
    }

    private void lock(UUID id) {
        db.sql("SELECT id FROM news_article WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Article get(UUID id, int revision) {
        return db.sql(SELECT + " WHERE a.id=:id AND r.revision=:revision").param("id", id).param("revision", revision)
                .query(this::article).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Article article(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Article(row.getObject(1, UUID.class), row.getInt(2), row.getLong(3), row.getObject(4, UUID.class),
                row.getString(5), row.getObject(6, Integer.class), row.getString(7), row.getString(8), row.getString(9),
                row.getString(10), row.getString(11), row.getString(12), row.getObject(13, OffsetDateTime.class),
                Arrays.asList((String[]) row.getArray(14).getArray()), row.getObject(15, OffsetDateTime.class),
                row.getObject(16, OffsetDateTime.class), row.getObject(17, UUID.class), row.getObject(18, OffsetDateTime.class),
                row.getObject(19, UUID.class), row.getString(20));
    }

    static String canonical(String value) {
        if (value == null || value.length() > 300) throw invalid();
        try {
            var uri = URI.create(value.strip());
            var host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !("hankyung.com".equalsIgnoreCase(host) || "www.hankyung.com".equalsIgnoreCase(host))
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !uri.getPath().matches("/article/[0-9]+/?")) throw invalid();
            return "https://www.hankyung.com" + uri.getPath().replaceFirst("/$", "");
        } catch (IllegalArgumentException invalidUri) {
            throw invalid();
        }
    }

    private static void validate(Draft input) {
        if (!text(input.title(),80) || !text(input.summary(),300) || !text(input.body(),10000)
                || !text(input.reason(),300) || !text(input.action(),200)
                || input.articlePublishedAt() == null || input.articlePublishedAt().isAfter(OffsetDateTime.now())
                || input.types() == null || input.types().isEmpty() || input.types().stream().anyMatch(java.util.Objects::isNull)
                || input.types().stream().distinct().count() != input.types().size()) throw invalid();
    }

    static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.indexOf('<') < 0 && value.indexOf('>') < 0;
    }

    private static String from(Article row, String expected, String target) {
        if (!row.status().equals(expected)) throw conflict();
        return target;
    }

    private static void requireReason(String reason) {
        if (!text(reason,500) || reason.strip().length() < 10) throw invalid();
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
