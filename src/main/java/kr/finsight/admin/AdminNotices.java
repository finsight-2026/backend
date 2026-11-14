package kr.finsight.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring.Pressure;
import kr.finsight.content.EditorialPage;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminNotices {
    enum Kind { NOTICE, GUIDE, INSIGHT }
    enum Metric { YFSI_MEAN, EXTERNAL_AREA_MEAN, RESOURCE_DOMAIN_MEAN, PERCEIVED_STRESS_MEAN }
    enum Category { START, LEARN }
    enum Status { DRAFT, IN_REVIEW, APPROVED, SCHEDULED, PUBLISHED, UNPUBLISHED, EXPIRED, ARCHIVED }
    enum Action { SUBMIT, WITHDRAW, APPROVE, REJECT, PUBLISH, SCHEDULE, CANCEL_SCHEDULE, UNPUBLISH, ARCHIVE }
    record Fields(String title, String summary, String body, Integer sortOrder, List<Pressure> types,
                  String todayAction, String nextAction, Category guideCategory,
                  UUID snapshotId, Metric metricKey, String insightExplanation) {
        Fields(String title, String summary, String body, Integer sortOrder) {
            this(title, summary, body, sortOrder, null, null, null, null, null, null, null);
        }
    }
    record Change(UUID revisionId, Action action, Long expectedVersion, String reason, OffsetDateTime startsAt,
                  OffsetDateTime endsAt, Boolean serviceOnlyConfirmed) {}
    record Revision(UUID id, UUID revisionId, int revisionNo, UUID authorId, Status status, UUID publishedRevisionId,
                    String title, String summary, String body, int sortOrder, OffsetDateTime startsAt,
                    OffsetDateTime endsAt, long version, OffsetDateTime updatedAt, String reviewReason, Kind kind,
                    List<Pressure> types, String todayAction, String nextAction, Category guideCategory,
                    UUID snapshotId, Metric metricKey, String insightExplanation,
                    Integer publishedRevisionNo, UUID updatedBy, String updatedByName) {}
    record Preview(UUID id, UUID revisionId, String title, String summary, String body,
                   String todayAction, String nextAction, Metric metricKey, String insightExplanation, JsonNode metricValue) {}
    record InsightSource(UUID snapshotId, String type, Metric metricKey, java.time.LocalDate from, java.time.LocalDate to) {}
    private record Due(UUID notice, UUID revision) {}

    private static final String COLUMNS = """
             n.id,r.id,r.revision_no,r.author_id,r.status,n.published_revision_id,
                r.title,r.summary,r.body,r.sort_order,r.starts_at,r.ends_at,r.version,r.updated_at,r.review_reason,
                n.kind,r.types,r.today_action,r.next_action,r.guide_category,r.snapshot_id,r.metric_key,r.insight_explanation,
                published.revision_no,r.updated_by,editor.name
            """;
    private static final String FROM = """
            FROM service_notice n JOIN service_notice_revision r ON r.notice_id=n.id
            LEFT JOIN service_notice_revision published ON published.id=n.published_revision_id
            LEFT JOIN admin_account editor ON editor.id=r.updated_by
            """;
    private static final String SELECT = "SELECT " + COLUMNS + FROM;
    private static final String ACTIVE = """
            n.published_revision_id=r.id AND r.status='PUBLISHED'
            AND r.starts_at<=clock_timestamp() AND (r.ends_at IS NULL OR r.ends_at>clock_timestamp())
            """;
    private final JdbcClient db;
    private final AdminMutationJournal journal;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    AdminNotices(JdbcClient db, AdminMutationJournal journal, TransactionTemplate transactions, JsonMapper json) {
        this.db = db;
        this.journal = journal;
        this.transactions = transactions;
        this.json = json;
    }

    List<Revision> list(Status status, String query) {
        return list(Kind.NOTICE, status, query, null, null, null, null);
    }

    List<Revision> list(Kind kind, Status status, String query, UUID author, LocalDate from, LocalDate to, Pressure type) {
        var filter = new EditorialPage.Filter(status == null ? null : status.name(), query, author, from, to, type);
        return bindListing(db.sql(SELECT + listingWhere(status) + " ORDER BY r.updated_at DESC,r.id DESC"), kind, filter)
                .query(this::row).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    EditorialPage<Revision> page(Kind kind, Status status, EditorialPage.Filter filter, int page, int size) {
        long offset = EditorialPage.offset(page, size);
        var conditions = listingWhere(status);
        long total = bindListing(db.sql("SELECT count(DISTINCT n.id) " + FROM + conditions), kind, filter)
                .query(Long.class).single();
        var items = bindListing(db.sql("""
                WITH selected AS (
                    SELECT DISTINCT ON (n.id) r.id,r.updated_at
                """ + FROM + conditions + """
                    ORDER BY n.id,r.revision_no DESC
                )
                """ + SELECT + """
                JOIN selected s ON s.id=r.id ORDER BY s.updated_at DESC,r.id DESC LIMIT :size OFFSET :offset
                """), kind, filter).param("size", size).param("offset", offset).query(this::row).list();
        return new EditorialPage<>(items, total, page, size);
    }

    Revision lookup(Kind kind, String key, Integer revision) {
        if (!key.matches("[A-Za-z0-9_-]{1,80}") || revision != null && revision < 1) throw invalid();
        return db.sql(SELECT + """
                WHERE n.kind=:kind AND (n.id::text=:key OR n.external_key=:key)
                    AND (CAST(:revision AS integer) IS NULL OR r.revision_no=:revision)
                ORDER BY r.revision_no DESC LIMIT 1
                """).param("kind", kind.name()).param("key", key).param("revision", revision, java.sql.Types.INTEGER)
                .query(this::row).optional().orElseThrow(AdminNotices::missing);
    }

    private static String listingWhere(Status status) {
        return " WHERE n.kind=:kind " + EditorialPage.Filter.WHERE + (status == null ? " AND r.status<>'ARCHIVED'"
                : status == Status.PUBLISHED ? " AND " + ACTIVE : "");
    }

    private static JdbcClient.StatementSpec bindListing(JdbcClient.StatementSpec query, Kind kind, EditorialPage.Filter filter) {
        return filter.bind(query).param("kind", kind.name());
    }

    List<Revision> revisions(UUID id) {
        var revisions = db.sql(SELECT + " WHERE n.id=:id ORDER BY r.revision_no DESC")
                .param("id", id).query(this::row).list();
        if (revisions.isEmpty()) throw missing();
        return revisions;
    }

    Revision latest(UUID id) {
        return db.sql(SELECT + " WHERE n.id=:id ORDER BY r.revision_no DESC LIMIT 1")
                .param("id", id).query(this::row).optional().orElseThrow(AdminNotices::missing);
    }

    Preview preview(UUID id, UUID revision) {
        var selected = get(id, revision);
        JsonNode metric = null;
        if (selected.kind() == Kind.INSIGHT) {
            metric = db.sql("""
                    SELECT metric_value::text FROM approved_comparison_metric
                    WHERE snapshot_id=:snapshot AND metric_key=:metric AND type=:type
                    """).param("snapshot", selected.snapshotId()).param("metric", selected.metricKey().name())
                    .param("type", selected.types().getFirst().name()).query(String.class).optional()
                    .map(json::readTree).orElse(null);
        }
        return new Preview(id, revision, selected.title(), selected.summary(), selected.body(),
                selected.todayAction(), selected.nextAction(), selected.metricKey(), selected.insightExplanation(), metric);
    }

    List<InsightSource> insightSources() {
        return db.sql("""
                SELECT snapshot_id,type,metric_key,period_start,period_end FROM approved_comparison_metric
                ORDER BY period_end DESC,snapshot_id,metric_key LIMIT 300
                """).query((row, n) -> new InsightSource(row.getObject(1, UUID.class), row.getString(2),
                        Metric.valueOf(row.getString(3)), row.getObject(4, LocalDate.class),
                        row.getObject(5, LocalDate.class))).list();
    }

    Revision draft(AdminAuth.Identity actor, UUID id, UUID restoreFrom, Fields fields) {
        return draft(actor, Kind.NOTICE, id, restoreFrom, fields);
    }

    Revision draft(AdminAuth.Identity actor, Kind kind, UUID id, UUID restoreFrom, Fields fields) {
        var notice = id == null ? UUID.randomUUID() : id;
        var revision = UUID.randomUUID();
        return journal.record(actor, kind + "_DRAFT_CREATED", kind.name(), notice.toString(), () -> {
            int number = 1;
            if (id == null) {
                if (restoreFrom != null) throw invalid();
                db.sql("INSERT INTO service_notice(id,kind) VALUES (:id,:kind)").param("id", notice).param("kind", kind.name()).update();
            } else {
                lock(notice);
                var last = latest(notice);
                if (last.kind() != kind) throw missing();
                if (List.of(Status.DRAFT, Status.IN_REVIEW, Status.SCHEDULED).contains(last.status())) throw conflict();
                number = last.revisionNo() + 1;
            }
            Fields input;
            if (restoreFrom == null) {
                input = validate(kind, fields);
            } else {
                var source = get(notice, restoreFrom);
                input = validate(kind, fields(source));
            }
            bind(db.sql("""
                    INSERT INTO service_notice_revision(id,notice_id,revision_no,author_id,status,title,summary,body,sort_order,
                        types,today_action,next_action,guide_category,snapshot_id,metric_key,insight_explanation,updated_by)
                    VALUES (:revision,:notice,:number,:author,'DRAFT',:title,:summary,:body,:sortOrder,
                        string_to_array(:types,','),:today,:next,:category,:snapshot,:metric,:explanation,:author)
                    """), input).param("revision", revision).param("notice", notice).param("number", number)
                    .param("author", actor.admin().id()).update();
            return get(notice, revision);
        }, AdminNotices::metadata);
    }

    Revision edit(AdminAuth.Identity actor, UUID id, UUID revision, long expected, Fields fields) {
        var kind = get(id, revision).kind();
        var input = validate(kind, fields);
        return journal.record(actor, kind + "_DRAFT_UPDATED", kind.name(), id.toString(), () -> {
            lock(id);
            var current = get(id, revision);
            author(actor, current);
            requireStatus(current, Status.DRAFT);
            if (current.version() != expected) throw conflict();
            bind(db.sql("""
                    UPDATE service_notice_revision SET title=:title,summary=:summary,body=:body,sort_order=:sortOrder,
                        types=string_to_array(:types,','),today_action=:today,next_action=:next,guide_category=:category,
                        snapshot_id=:snapshot,metric_key=:metric,insight_explanation=:explanation,
                        version=version+1,updated_at=clock_timestamp(),updated_by=:editor WHERE id=:id
                    """), input).param("id", revision).param("editor", actor.admin().id()).update();
            return get(id, revision);
        }, AdminNotices::metadata);
    }

    Revision transition(AdminAuth.Identity actor, UUID id, Change change, String key) {
        if (change.revisionId() == null || change.action() == null || change.expectedVersion() == null) throw invalid();
        return journal.once(actor, "/contents/" + id + "/transitions", key, change, Revision.class,
                () -> apply(actor, id, change),
                row -> journal.append(actor, row.kind() + "_" + change.action(), row.kind().name(), id.toString(),
                        auditMetadata(row, change.reason())));
    }

    private Revision apply(AdminAuth.Identity actor, UUID id, Change change) {
        lock(id);
        var current = get(id, change.revisionId());
        if (current.version() != change.expectedVersion()) throw conflict();
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        switch (change.action()) {
            case SUBMIT -> {
                author(actor, current);
                requireStatus(current, Status.DRAFT);
                setStatus(current.revisionId(), Status.IN_REVIEW);
            }
            case WITHDRAW -> {
                author(actor, current);
                requireStatus(current, Status.IN_REVIEW);
                setStatus(current.revisionId(), Status.DRAFT);
            }
            case APPROVE -> {
                requireStatus(current, Status.IN_REVIEW);
                if (actor.admin().id().equals(current.authorId())) reason(change.reason());
                setStatus(current.revisionId(), Status.APPROVED);
            }
            case REJECT -> {
                requireStatus(current, Status.IN_REVIEW);
                reason(change.reason());
                db.sql("UPDATE service_notice_revision SET review_reason=:reason WHERE id=:id")
                        .param("reason", change.reason().strip()).param("id", current.revisionId()).update();
                setStatus(current.revisionId(), Status.DRAFT);
            }
            case PUBLISH, SCHEDULE -> {
                requireStatus(current, Status.APPROVED);
                if (!Boolean.TRUE.equals(change.serviceOnlyConfirmed())) throw invalid();
                validate(current.kind(), fields(current));
                if (actor.admin().id().equals(current.authorId())) reason(change.reason());
                boolean scheduled = change.action() == Action.SCHEDULE;
                if (scheduled && db.sql("""
                        SELECT EXISTS(SELECT 1 FROM service_notice_revision WHERE notice_id=:id AND status='SCHEDULED')
                        """).param("id", id).query(Boolean.class).single()) throw conflict();
                var start = change.startsAt() == null ? now : change.startsAt();
                if (scheduled != start.isAfter(now) || (change.endsAt() != null
                        && (!change.endsAt().isAfter(start) || !change.endsAt().isAfter(now)))) throw invalid();
                if (current.kind() == Kind.INSIGHT && !comparisonAvailable(current, start, scheduled)) throw conflict();
                db.sql("""
                        UPDATE service_notice_revision SET starts_at=:start,ends_at=:end,scheduled_by=:actor
                        WHERE id=:revision
                        """).param("revision", current.revisionId()).param("start", start)
                        .param("end", change.endsAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("actor", actor.admin().id()).update();
                if (scheduled) setStatus(current.revisionId(), Status.SCHEDULED);
                else publish(current);
            }
            case CANCEL_SCHEDULE -> {
                requireStatus(current, Status.SCHEDULED);
                setStatus(current.revisionId(), Status.APPROVED);
            }
            case UNPUBLISH -> {
                requireStatus(current, Status.PUBLISHED);
                reason(change.reason());
                removePublication(current, Status.UNPUBLISHED);
            }
            case ARCHIVE -> {
                if (current.status() == Status.DRAFT) {
                    if (!actor.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                    author(actor, current);
                } else {
                    if (!List.of(Status.UNPUBLISHED, Status.EXPIRED).contains(current.status())
                            || current.revisionId().equals(current.publishedRevisionId())) throw conflict();
                    if (!actor.permissions().contains("SUPER_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                setStatus(current.revisionId(), Status.ARCHIVED);
            }
        }
        updatedBy(current.revisionId(), actor.admin().id());
        return get(id, current.revisionId());
    }

    private void publish(Revision item) {
        if (item.publishedRevisionId() != null) {
            setStatus(item.publishedRevisionId(), Status.UNPUBLISHED);
            event(item.id(), item.publishedRevisionId(), Status.UNPUBLISHED);
        }
        setStatus(item.revisionId(), Status.PUBLISHED);
        db.sql("UPDATE service_notice SET published_revision_id=:revision WHERE id=:id")
                .param("revision", item.revisionId()).param("id", item.id()).update();
        event(item.id(), item.revisionId(), Status.PUBLISHED);
    }

    private void removePublication(Revision item, Status status) {
        if (!item.revisionId().equals(item.publishedRevisionId())) throw conflict();
        db.sql("UPDATE service_notice SET published_revision_id=NULL WHERE id=:id").param("id", item.id()).update();
        setStatus(item.revisionId(), status);
        event(item.id(), item.revisionId(), status);
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    void processDue() {
        db.sql("""
                SELECT notice_id,id FROM service_notice_revision
                WHERE (status='SCHEDULED' AND starts_at<=clock_timestamp())
                   OR (status='PUBLISHED' AND ends_at<=clock_timestamp())
                ORDER BY starts_at,id LIMIT 100
                """).query((row, n) -> new Due(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
                .list().forEach(item -> transactions.executeWithoutResult(status -> process(item.notice(), item.revision())));
    }

    void process(UUID id, UUID revision) {
        lock(id);
        var current = get(id, revision);
        var now = OffsetDateTime.now();
        if (current.status() == Status.PUBLISHED && current.endsAt() != null && !current.endsAt().isAfter(now)) {
            removePublication(current, Status.EXPIRED);
        }
        if (current.status() != Status.SCHEDULED || current.startsAt().isAfter(now)) return;
        if (current.endsAt() != null && !current.endsAt().isAfter(now)) {
            setStatus(revision, Status.EXPIRED);
            return;
        }
        var admin = db.sql("""
                SELECT a.id,a.name FROM service_notice_revision n JOIN admin_account a ON a.id=n.scheduled_by
                JOIN admin_role r ON r.admin_id=a.id AND r.role='SUPER_ADMIN'
                WHERE n.id=:revision AND a.status='ACTIVE' FOR SHARE OF a,r
                """).param("revision", revision).query((row, n) ->
                new AdminAuth.Admin(row.getObject(1, UUID.class), row.getString(2))).optional();
        if (admin.isEmpty()) {
            setStatus(revision, Status.APPROVED);
            return;
        }
        if (current.kind() == Kind.INSIGHT && !comparisonAvailable(current, now, false)) {
            if (!comparisonAvailable(current, now, true)) setStatus(revision, Status.APPROVED);
            return;
        }
        validate(current.kind(), fields(current));
        publish(current);
        updatedBy(revision, admin.get().id());
        journal.append(new AdminAuth.Identity(admin.get(), List.of("SUPER_ADMIN")), current.kind() + "_SCHEDULE_ACTIVATED",
                current.kind().name(), id.toString(), metadata(get(id, revision)));
    }

    private boolean comparisonAvailable(Revision item, OffsetDateTime time, boolean scheduled) {
        return db.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM approved_comparison_metric m JOIN comparison_publication p ON p.snapshot_id=m.snapshot_id
                    CROSS JOIN operating_settings o
                    WHERE m.snapshot_id=:snapshot AND m.metric_key=:metric AND m.type=:type
                        AND m.metric_key=ANY(p.allowed_metrics) AND p.allowed_metrics<@o.comparison_metrics
                        AND (p.status='ACTIVE' OR (:scheduled AND p.status='SCHEDULED'))
                        AND p.starts_at<=:time AND p.ends_at>:time AND p.ends_at>statement_timestamp()
                )
                """).param("snapshot", item.snapshotId()).param("metric", item.metricKey().name())
                .param("type", item.types().getFirst().name()).param("time", time).param("scheduled", scheduled)
                .query(Boolean.class).single();
    }

    private void event(UUID notice, UUID revision, Status status) {
        db.sql("""
                INSERT INTO service_notice_publication_event(id,notice_id,revision_id,event_type)
                VALUES (:id,:notice,:revision,:status) ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("notice", notice).param("revision", revision)
                .param("status", status.name()).update();
    }

    private void lock(UUID id) {
        db.sql("SELECT id FROM service_notice WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class)
                .optional().orElseThrow(AdminNotices::missing);
    }

    private void setStatus(UUID revision, Status status) {
        db.sql("""
                UPDATE service_notice_revision SET status=:status,version=version+1,updated_at=clock_timestamp()
                WHERE id=:id
                """).param("id", revision).param("status", status.name()).update();
    }

    private void updatedBy(UUID revision, UUID actor) {
        db.sql("UPDATE service_notice_revision SET updated_by=:actor WHERE id=:revision")
                .param("actor", actor).param("revision", revision).update();
    }

    private Revision get(UUID id, UUID revision) {
        return db.sql(SELECT + " WHERE n.id=:id AND r.id=:revision").param("id", id).param("revision", revision)
                .query(this::row).optional().orElseThrow(AdminNotices::missing);
    }

    private Revision row(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Revision(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getInt(3),
                row.getObject(4, UUID.class), Status.valueOf(row.getString(5)), row.getObject(6, UUID.class),
                row.getString(7), row.getString(8), row.getString(9), row.getInt(10),
                row.getObject(11, OffsetDateTime.class), row.getObject(12, OffsetDateTime.class), row.getLong(13),
                row.getObject(14, OffsetDateTime.class), row.getString(15), Kind.valueOf(row.getString(16)),
                types(row.getArray(17)), row.getString(18), row.getString(19),
                row.getString(20) == null ? null : Category.valueOf(row.getString(20)), row.getObject(21, UUID.class),
                row.getString(22) == null ? null : Metric.valueOf(row.getString(22)), row.getString(23),
                row.getObject(24, Integer.class), row.getObject(25, UUID.class), row.getString(26));
    }

    private static Map<String, ?> metadata(Revision item) {
        return Map.of("revisionId", item.revisionId(), "status", item.status(), "version", item.version());
    }

    private static void author(AdminAuth.Identity actor, Revision item) {
        if (!actor.admin().id().equals(item.authorId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static void requireStatus(Revision item, Status status) {
        if (item.status() != status) throw conflict();
    }

    private static Map<String, ?> auditMetadata(Revision item, String reason) {
        var result = new java.util.HashMap<String, Object>(metadata(item));
        if (reason != null && !reason.isBlank()) result.put("reason", plain(reason, 1, 500));
        return result;
    }

    private static List<Pressure> types(java.sql.Array array) throws java.sql.SQLException {
        return array == null ? null : java.util.Arrays.stream((String[]) array.getArray()).map(Pressure::valueOf).toList();
    }

    private static Fields fields(Revision source) {
        return new Fields(source.title(), source.summary(), source.body(), source.sortOrder(), source.types(),
                source.todayAction(), source.nextAction(), source.guideCategory(), source.snapshotId(), source.metricKey(),
                source.insightExplanation());
    }

    private Fields validate(Kind kind, Fields value) {
        if (value == null || value.sortOrder() == null || value.sortOrder() < 0 || value.sortOrder() > 999) throw invalid();
        var title = plain(value.title(), 1, 80);
        var summary = plain(value.summary(), 1, 300);
        var body = plain(value.body(), 1, 10000);
        if (kind == Kind.NOTICE) return new Fields(title, summary, body, value.sortOrder());
        var types = value.types();
        if (types == null || types.isEmpty() || types.size() > Pressure.values().length
                || types.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(types).size() != types.size()) throw invalid();
        if (kind == Kind.INSIGHT) {
            if (types.size() != 1 || value.snapshotId() == null || value.metricKey() == null
                    || db.sql("SELECT type FROM comparison_snapshot WHERE id=:id").param("id", value.snapshotId())
                    .query(String.class).optional().filter(types.getFirst().name()::equals).isEmpty()) throw invalid();
            return new Fields(title, summary, body, value.sortOrder(), types, null, null, null, value.snapshotId(),
                    value.metricKey(), plain(value.insightExplanation(), 1, 1000));
        }
        if (value.guideCategory() == null) throw invalid();
        return new Fields(title, summary, body, value.sortOrder(), types, plain(value.todayAction(), 1, 200),
                plain(value.nextAction(), 1, 1000), value.guideCategory(), null, null, null);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, Fields value) {
        var types = value.types() == null ? null : String.join(",", value.types().stream().map(Pressure::name).toList());
        return sql.param("title", value.title()).param("summary", value.summary()).param("body", value.body())
                .param("sortOrder", value.sortOrder()).param("types", types, java.sql.Types.VARCHAR)
                .param("today", value.todayAction(), java.sql.Types.VARCHAR)
                .param("next", value.nextAction(), java.sql.Types.VARCHAR)
                .param("category", value.guideCategory() == null ? null : value.guideCategory().name(), java.sql.Types.VARCHAR)
                .param("snapshot", value.snapshotId(), java.sql.Types.OTHER)
                .param("metric", value.metricKey() == null ? null : value.metricKey().name(), java.sql.Types.VARCHAR)
                .param("explanation", value.insightExplanation(), java.sql.Types.VARCHAR);
    }

    private static void reason(String value) { plain(value, 10, 500); }

    private static String plain(String value, int min, int max) {
        var text = value == null ? "" : value.strip();
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max || text.indexOf('<') >= 0 || text.indexOf('>') >= 0) throw invalid();
        return text;
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
