package kr.finsight.admin;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminCampaigns {
    static final String TEMPLATE = "NTF-06";
    static final String TITLE = "FINSIGHT 이용 안내가 있어요";
    static final String BODY = "서비스 이용에 관한 안내를 확인해주세요. 자세한 내용은 앱에서 볼 수 있어요.";
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime CLOSE = LocalTime.of(20, 0);

    record Fields(String name, UUID segmentId, Integer segmentVersion, List<UUID> userIds, String templateId,
                  UUID noticeRevisionId, OffsetDateTime scheduledAt) {}
    record Campaign(UUID id, String name, String status, long version, UUID segmentId, Integer segmentVersion,
                    List<UUID> userIds, UUID noticeRevisionId, String title, String body, String destination,
                    OffsetDateTime requestedAt, OffsetDateTime scheduledAt, OffsetDateTime updatedAt) {}
    record Preview(UUID previewId, AdminSegments.Preview counts, OffsetDateTime executionAt, OffsetDateTime expiresAt) {}
    record Job(UUID id, UUID campaignId, String status, OffsetDateTime scheduledAt, long version) {}
    private record StoredPreview(OffsetDateTime executionAt, OffsetDateTime expiresAt) {}
    private record Target(UUID segment, Integer revision, List<UUID> users) {}

    private static final String SELECT = """
            SELECT c.id,c.name,c.status,c.version,c.segment_id,c.segment_version,
                ARRAY(SELECT t.user_id FROM campaign_target t WHERE t.campaign_id=c.id ORDER BY t.user_id),
                c.notice_revision_id,c.title_snapshot,c.body_snapshot,c.destination,c.requested_at,c.scheduled_at,c.updated_at
            FROM campaign c
            """;
    private final JdbcClient db;
    private final AdminSegments segments;
    private final AdminMutationJournal journal;
    private final JsonMapper json;

    AdminCampaigns(JdbcClient db, AdminSegments segments, AdminMutationJournal journal, JsonMapper json) {
        this.db = db;
        this.segments = segments;
        this.journal = journal;
        this.json = json;
    }

    List<Campaign> list() {
        return db.sql(SELECT + " ORDER BY c.updated_at DESC,c.id DESC").query(this::row).list();
    }

    Campaign create(AdminAuth.Identity actor, Fields input) {
        var id = UUID.randomUUID();
        return journal.record(actor, "CRM_DRAFT_CREATED", "CAMPAIGN", id.toString(), () -> {
            var target = validate(input, null);
            db.sql("""
                    INSERT INTO campaign(id,actor_id,name,segment_id,segment_version,notice_revision_id,
                        template_id,template_version,title_snapshot,body_snapshot,destination,requested_at,status)
                    VALUES (:id,:actor,:name,:segment,:revision,:notice,:template,'1.0',:title,:body,'B_003',:requested,'DRAFT')
                    """).param("id", id).param("actor", actor.admin().id()).param("name", input.name().strip())
                    .param("segment", target.segment(), java.sql.Types.OTHER)
                    .param("revision", target.revision(), java.sql.Types.INTEGER)
                    .param("notice", input.noticeRevisionId()).param("template", TEMPLATE)
                    .param("title", TITLE).param("body", BODY)
                    .param("requested", input.scheduledAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
            saveTargets(id, target.users());
            return find(id);
        }, item -> Map.of("version", item.version()));
    }

    Campaign update(AdminAuth.Identity actor, UUID id, Fields input, long expected) {
        return journal.record(actor, "CRM_DRAFT_UPDATED", "CAMPAIGN", id.toString(), () -> {
            var current = lock(id);
            if (!current.status().equals("DRAFT") || current.version() != expected) throw conflict();
            var target = validate(input, current);
            db.sql("""
                    UPDATE campaign SET name=:name,segment_id=:segment,segment_version=:revision,notice_revision_id=:notice,
                        requested_at=:requested,version=version+1,updated_at=clock_timestamp() WHERE id=:id
                    """).param("id", id).param("name", input.name().strip())
                    .param("segment", target.segment(), java.sql.Types.OTHER)
                    .param("revision", target.revision(), java.sql.Types.INTEGER)
                    .param("notice", input.noticeRevisionId())
                    .param("requested", input.scheduledAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
            db.sql("DELETE FROM campaign_target WHERE campaign_id=:id").param("id", id).update();
            saveTargets(id, target.users());
            db.sql("DELETE FROM campaign_preview WHERE campaign_id=:id").param("id", id).update();
            return find(id);
        }, item -> Map.of("version", item.version()));
    }

    @Transactional
    Preview preview(AdminAuth.Identity actor, UUID id, Long expected) {
        var item = lock(id);
        if (!item.status().equals("DRAFT") || (expected != null && item.version() != expected)) throw conflict();
        var execution = executionTime(now(), item.requestedAt());
        activeNotice(item.noticeRevisionId(), execution);
        var day = execution.atZoneSameInstant(AdminUsers.KST).toLocalDate();
        var candidates = candidates(item, day);
        var counts = segments.count(candidates, item.segmentVersion() == null ? 0 : item.segmentVersion(),
                execution, item.noticeRevisionId());
        var preview = UUID.randomUUID();
        var expiry = db.sql("""
                INSERT INTO campaign_preview(id,campaign_id,actor_id,version,execution_at,counts)
                VALUES (:id,:campaign,:actor,:version,:execution,CAST(:counts AS jsonb)) RETURNING expires_at
                """).param("id", preview).param("campaign", id).param("actor", actor.admin().id())
                .param("version", item.version()).param("execution", execution)
                .param("counts", json.writeValueAsString(counts)).query(OffsetDateTime.class).single();
        return new Preview(preview, counts, execution, expiry);
    }

    AdminSegments.CandidateQuery candidates(Campaign item, java.time.LocalDate day) {
        return item.segmentId() != null
                ? segments.revisionCandidates(item.segmentId(), item.segmentVersion(), day)
                : new AdminSegments.CandidateQuery("""
                        SELECT u.id,u.status,coalesce(p.enabled,false) AS consent,
                            NOT notification_slot_available(u.id,:day,'REMINDER') AS capped
                        FROM campaign_target t JOIN app_user u ON u.id=t.user_id
                        LEFT JOIN app_notification_preference p ON p.user_id=u.id
                        WHERE t.campaign_id=:id
                        """, Map.of("day", day, "id", item.id()));
    }

    @Transactional
    Job confirm(AdminAuth.Identity actor, UUID id, UUID preview, long expected) {
        var item = lock(id);
        var replay = db.sql("""
                SELECT j.id FROM campaign_job j JOIN campaign_preview p ON p.id=j.preview_id
                WHERE j.campaign_id=:id AND j.preview_id=:preview AND p.actor_id=:actor AND p.version=:expected
                """).param("id", id).param("preview", preview).param("actor", actor.admin().id())
                .param("expected", expected).query(UUID.class).optional();
        if (replay.isPresent()) return job(replay.get(), item);
        if (!item.status().equals("DRAFT") || item.version() != expected) throw conflict();
        var stored = db.sql("""
                SELECT execution_at,expires_at FROM campaign_preview
                WHERE id=:preview AND campaign_id=:id AND actor_id=:actor AND version=:version AND used_at IS NULL
                    AND (counts->>'localEligible')::bigint>0
                """).param("preview", preview).param("id", id).param("actor", actor.admin().id())
                .param("version", expected).query((row, n) -> new StoredPreview(row.getObject(1, OffsetDateTime.class),
                        row.getObject(2, OffsetDateTime.class))).optional().orElseThrow(AdminCampaigns::conflict);
        var current = now();
        if (!stored.expiresAt().isAfter(current)) throw conflict();
        var execution = stored.executionAt();
        if (item.requestedAt() != null && !execution.isAfter(current)) throw conflict();
        if (!execution.isAfter(current) && !open(current)) throw conflict();
        activeNotice(item.noticeRevisionId(), execution.isAfter(current) ? execution : current);
        db.sql("""
                UPDATE campaign SET status=:status,scheduled_at=:execution,actor_id=:actor,version=version+1,updated_at=clock_timestamp()
                WHERE id=:id
                """).param("id", id).param("status", execution.isAfter(current) ? "SCHEDULED" : "QUEUED")
                .param("execution", execution).param("actor", actor.admin().id()).update();
        db.sql("UPDATE campaign_preview SET used_at=clock_timestamp() WHERE id=:id").param("id", preview).update();
        var job = UUID.randomUUID();
        db.sql("INSERT INTO campaign_job(id,campaign_id,preview_id) VALUES (:id,:campaign,:preview)")
                .param("id", job).param("campaign", id).param("preview", preview).update();
        journal.append(actor, "CRM_CAMPAIGN_CONFIRMED", "CAMPAIGN", id.toString(),
                Map.of("jobId", job, "version", expected + 1));
        return job(job, find(id));
    }

    Campaign cancel(AdminAuth.Identity actor, UUID id, long expected, String reason) {
        var normalized = reason == null ? "" : reason.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > 500) throw invalid();
        return journal.record(actor, "CRM_CAMPAIGN_CANCEL", "CAMPAIGN", id.toString(), () -> {
            var current = lock(id);
            if (current.version() != expected || !List.of("SCHEDULED", "QUEUED", "SENDING").contains(current.status())) {
                throw conflict();
            }
            db.sql("""
                    UPDATE campaign SET status=:status,version=version+1,updated_at=clock_timestamp() WHERE id=:id
                    """).param("id", id).param("status", current.status().equals("SCHEDULED") ? "CANCELED" : "CANCEL_REQUESTED")
                    .update();
            if (current.status().equals("SCHEDULED")) {
                db.sql("UPDATE campaign_job SET completed_at=clock_timestamp() WHERE campaign_id=:id")
                        .param("id", id).update();
            }
            return find(id);
        }, item -> Map.of("version", item.version(), "status", item.status(), "reasonLength", length));
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void expirePreviews() {
        db.sql("DELETE FROM campaign_preview WHERE expires_at<clock_timestamp()-interval '24 hours'").update();
    }

    private Target validate(Fields input, Campaign existing) {
        if (input == null || input.name() == null || input.name().isBlank()
                || input.name().strip().codePointCount(0, input.name().strip().length()) > 80
                || !TEMPLATE.equals(input.templateId()) || input.noticeRevisionId() == null
                || (input.segmentId() == null) == (input.userIds() == null)) throw invalid();
        var current = now();
        activeNotice(input.noticeRevisionId(), executionTime(current, input.scheduledAt()));
        if (input.segmentId() != null) {
            if (existing != null && input.segmentId().equals(existing.segmentId())
                    && java.util.Objects.equals(input.segmentVersion(), existing.segmentVersion())) {
                return new Target(existing.segmentId(), existing.segmentVersion(), List.of());
            }
            int revision = segments.activeRevision(input.segmentId());
            if (input.segmentVersion() == null || input.segmentVersion() != revision) throw conflict();
            return new Target(input.segmentId(), revision, List.of());
        }
        var users = input.userIds();
        if (input.segmentVersion() != null || users.isEmpty()
                || users.stream().anyMatch(java.util.Objects::isNull) || users.stream().distinct().count() != users.size()) {
            throw invalid();
        }
        return new Target(null, null, users);
    }

    private void saveTargets(UUID id, List<UUID> targets) {
        if (targets.isEmpty()) return;
        int added = db.sql("""
                INSERT INTO campaign_target(campaign_id,user_id) SELECT :id,u.id FROM app_user u
                WHERE u.id IN (SELECT value::uuid FROM jsonb_array_elements_text(CAST(:targets AS jsonb)))
                """).param("id", id).param("targets", json.writeValueAsString(targets)).update();
        if (added != targets.size()) throw invalid();
    }

    private void activeNotice(UUID revision, OffsetDateTime execution) {
        db.sql("""
                SELECT r.id FROM service_notice n JOIN service_notice_revision r ON r.id=n.published_revision_id
                WHERE n.kind='NOTICE' AND r.id=:revision AND r.status='PUBLISHED' AND r.starts_at<=clock_timestamp()
                AND (r.ends_at IS NULL OR r.ends_at>greatest(clock_timestamp(),:execution))
                FOR SHARE OF n,r
                """).param("revision", revision).param("execution", execution).query(UUID.class).optional()
                .orElseThrow(AdminCampaigns::conflict);
    }

    static OffsetDateTime executionTime(OffsetDateTime current, OffsetDateTime requested) {
        var kst = current.atZoneSameInstant(AdminUsers.KST).toOffsetDateTime();
        if (requested != null) {
            if (!requested.isAfter(current) || requested.isAfter(current.plusDays(30)) || !open(requested)) throw invalid();
            return requested;
        }
        if (open(current)) return current;
        var day = kst.toLocalTime().isBefore(OPEN) ? kst.toLocalDate() : kst.toLocalDate().plusDays(1);
        return day.atTime(OPEN).atZone(AdminUsers.KST).toOffsetDateTime();
    }

    private static boolean open(OffsetDateTime time) {
        var local = time.atZoneSameInstant(AdminUsers.KST).toLocalTime();
        return !local.isBefore(OPEN) && local.isBefore(CLOSE);
    }

    private Campaign lock(UUID id) {
        db.sql("SELECT id FROM campaign WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class)
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return find(id);
    }

    Campaign find(UUID id) {
        return db.sql(SELECT + " WHERE c.id=:id").param("id", id).query(this::row).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Campaign row(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Campaign(row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getLong(4),
                row.getObject(5, UUID.class), row.getObject(6, Integer.class),
                java.util.Arrays.stream((Object[]) row.getArray(7).getArray()).map(Object::toString).map(UUID::fromString).toList(),
                row.getObject(8, UUID.class), row.getString(9), row.getString(10), row.getString(11),
                row.getObject(12, OffsetDateTime.class), row.getObject(13, OffsetDateTime.class), row.getObject(14, OffsetDateTime.class));
    }

    private static Job job(UUID id, Campaign item) {
        return new Job(id, item.id(), item.status(), item.scheduledAt(), item.version());
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
