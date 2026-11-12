package kr.finsight.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
public class MediaStore {
    public record Asset(UUID mediaId, String status, String mime, int width, int height, OffsetDateTime expiresAt) {
        @JsonProperty
        public String previewUrl() { return "/api/v1/admin/media/" + mediaId + "/file"; }
    }
    public record File(String mime, byte[] bytes) {}

    private static final String LINKED = "EXISTS (SELECT 1 FROM news_revision r WHERE r.media_id=m.id)";
    private static final String ADMIN_ACCESS = " FROM media_asset m WHERE m.id=:id AND m.status='READY' AND ("
            + LINKED + " OR (m.owner_id=:owner AND m.expires_at>statement_timestamp()))";
    private static final String FIELDS = "m.id AS media_id,m.status,m.mime,m.width,m.height,"
            + "CASE WHEN " + LINKED + " THEN NULL ELSE m.expires_at END AS expires_at";

    private final JdbcClient db;

    MediaStore(JdbcClient db) { this.db = db; }

    public Asset upload(UUID actor, MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        if (file.getSize() > NewsImageDecoder.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        NewsImageDecoder.Image image;
        try (var input = file.getInputStream()) {
            image = NewsImageDecoder.decode(input, file.getContentType());
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return db.sql("""
                INSERT INTO media_asset(id,owner_id,mime,width,height,status,bytes)
                VALUES (:id,:owner,:mime,:width,:height,'READY',:bytes)
                RETURNING id AS media_id,status,mime,width,height,expires_at
                """).param("id", UUID.randomUUID()).param("owner", actor).param("mime", image.mime())
                .param("width", image.width()).param("height", image.height()).param("bytes", image.bytes())
                .query(Asset.class).single();
    }

    public Asset details(UUID actor, UUID id) {
        return db.sql("SELECT " + FIELDS + ADMIN_ACCESS).param("id", id).param("owner", actor)
                .query(Asset.class).optional().orElseThrow(MediaStore::missing);
    }

    public File adminFile(UUID actor, UUID id) {
        return db.sql("SELECT m.mime,m.bytes" + ADMIN_ACCESS).param("id", id).param("owner", actor)
                .query(File.class).optional().orElseThrow(MediaStore::missing);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNewsAttachment(UUID actor, UUID article, UUID id) {
        if (id == null) return;
        var allowed = db.sql("""
                SELECT m.id FROM media_asset m WHERE m.id=:id AND m.status='READY'
                  AND ((m.owner_id=:owner AND (m.expires_at>statement_timestamp() OR EXISTS (
                        SELECT 1 FROM news_revision r WHERE r.media_id=m.id)))
                    OR EXISTS (SELECT 1 FROM news_revision r WHERE r.media_id=m.id AND r.article_id=:article))
                FOR SHARE OF m
                """).param("id", id).param("owner", actor).param("article", article).query(UUID.class).optional();
        if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "연결할 수 없는 이미지예요.");
    }

    public File publishedNewsFile(UUID article, int revision) {
        return db.sql("""
                SELECT m.mime,m.bytes FROM news_article a JOIN news_revision r
                    ON r.article_id=a.id AND r.revision=a.published_revision
                JOIN media_asset m ON m.id=r.media_id AND m.status='READY'
                WHERE a.id=:article AND r.revision=:revision AND r.status='PUBLISHED'
                    AND r.starts_at<=statement_timestamp() AND (r.ends_at IS NULL OR r.ends_at>statement_timestamp())
                """).param("article", article).param("revision", revision).query(File.class).optional()
                .orElseThrow(MediaStore::missing);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void purge() {
        db.sql("""
                DELETE FROM media_asset m WHERE m.id IN (
                    SELECT candidate.id FROM media_asset candidate
                    WHERE candidate.expires_at<=statement_timestamp()
                        AND NOT EXISTS (SELECT 1 FROM news_revision r WHERE r.media_id=candidate.id)
                    ORDER BY candidate.expires_at,candidate.id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                """).update();
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
