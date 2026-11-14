package kr.finsight.admin;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminCampaignTargetPreview {
    record Preview(UUID previewId, int total, int eligible, int excluded, OffsetDateTime expiresAt) {}
    record Draft(UUID previewId, String name, UUID noticeRevisionId, OffsetDateTime scheduledAt) {}
    record Receipt(UUID id) {}

    private final JdbcClient db;
    private final AdminUsers users;
    private final AdminCampaigns campaigns;
    private final AdminMutationJournal journal;

    AdminCampaignTargetPreview(JdbcClient db, AdminUsers users, AdminCampaigns campaigns, AdminMutationJournal journal) {
        this.db = db;
        this.users = users;
        this.campaigns = campaigns;
        this.journal = journal;
    }

    @Transactional
    Preview preview(UUID actor, AdminUsers.Filters filters) {
        var id = UUID.randomUUID();
        var expiry = db.sql("INSERT INTO campaign_target_preview(id,actor_id) VALUES (:id,:actor) RETURNING expires_at")
                .param("id", id).param("actor", actor).query(OffsetDateTime.class).single();
        int total = users.snapshotCampaignMatches(id, filters);
        int excluded = db.sql("""
                DELETE FROM campaign_target_preview_user t USING app_user u
                WHERE t.preview_id=:id AND t.user_id=u.id AND u.status<>'ACTIVE'
                """).param("id", id).update();
        db.sql("UPDATE campaign_target_preview SET total=:total,eligible=:eligible WHERE id=:id")
                .param("total", total).param("eligible", total - excluded).param("id", id).update();
        return new Preview(id, total, total - excluded, excluded, expiry);
    }

    Receipt create(AdminAuth.Identity actor, Draft draft, String key) {
        if (draft.previewId() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return journal.once(actor, "/campaigns/from-target-preview", key, draft, Receipt.class, () -> {
            var id = draft.previewId();
            int expected = db.sql("""
                    SELECT eligible FROM campaign_target_preview
                    WHERE id=:id AND actor_id=:actor AND purpose='CAMPAIGN' AND expires_at>clock_timestamp() FOR UPDATE
                    """).param("id", id).param("actor", actor.admin().id()).query(Integer.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var ids = db.sql("""
                    SELECT u.id FROM campaign_target_preview_user t JOIN app_user u ON u.id=t.user_id
                    WHERE t.preview_id=:id AND u.status='ACTIVE' AND t.data_epoch=u.content_event_epoch
                    ORDER BY u.id FOR SHARE OF u
                    """).param("id", id).query(UUID.class).list();
            if (ids.isEmpty() || ids.size() != expected) throw new ResponseStatusException(HttpStatus.CONFLICT);
            var campaign = campaigns.create(actor, new AdminCampaigns.Fields(draft.name(), null, null, ids,
                    AdminCampaigns.TEMPLATE, draft.noticeRevisionId(), draft.scheduledAt()));
            db.sql("DELETE FROM campaign_target_preview WHERE id=:id").param("id", id).update();
            return new Receipt(campaign.id());
        }, receipt -> {});
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void expire() {
        db.sql("DELETE FROM campaign_target_preview WHERE expires_at<=clock_timestamp()").update();
    }
}
