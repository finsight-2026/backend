package kr.finsight.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class PolicyDocuments {
    static final String PREVIEW_VERSION = "local-preview-v1";
    private static final String TODAY = "(statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date";

    enum Id {
        TERMS("terms", "서비스 이용약관", "terms"),
        PRIVACY_POLICY("privacy-policy", "개인정보 처리방침", "privacy_policy"),
        PRIVACY_CONSENT("privacy-consent", "개인정보 수집 및 이용 동의", "privacy_consent"),
        RESEARCH("research", "연구 활용 안내", "research");

        final String key;
        final String title;
        final String column;

        Id(String key, String title, String column) {
            this.key = key;
            this.title = title;
            this.column = column;
        }

        static Id find(String key) {
            for (var id : values()) if (id.key.equals(key)) return id;
            throw missing();
        }
    }

    record Document(String id, String title, String version, LocalDate effectiveDate, String status) {}
    record Detail(Document document, String body) {}
    record Catalog(String activeVersion, List<Document> documents) {}
    private record Release(String version, LocalDate effectiveDate, boolean scheduled) {}

    private final JdbcClient db;
    private final boolean preview;

    PolicyDocuments(JdbcClient db, @Value("${finsight.policy.preview:false}") boolean preview) {
        this.db = db;
        this.preview = preview;
    }

    Optional<String> activeVersion() {
        return db.sql("SELECT version FROM policy_release WHERE effective_date <= " + TODAY
                        + " ORDER BY effective_date DESC LIMIT 1")
                .query(String.class).optional().or(() -> preview ? Optional.of(PREVIEW_VERSION) : Optional.empty());
    }

    Catalog catalog() {
        var releases = db.sql("SELECT version,effective_date,effective_date > " + TODAY
                        + " AS scheduled FROM policy_release ORDER BY effective_date DESC")
                .query(Release.class).list();
        var active = releases.stream().filter(release -> !release.scheduled()).map(Release::version).findFirst()
                .orElse(preview ? PREVIEW_VERSION : null);
        var documents = new ArrayList<Document>();
        for (var release : releases) {
            var state = release.scheduled() ? "SCHEDULED" : release.version().equals(active) ? "CURRENT" : "HISTORICAL";
            for (var id : Id.values()) documents.add(document(id, release.version(), release.effectiveDate(), state));
        }
        if (preview) {
            for (var id : Id.values()) documents.add(document(id, PREVIEW_VERSION, null, "PREVIEW"));
        }
        return new Catalog(active, List.copyOf(documents));
    }

    Detail detail(String key, String version) {
        var id = Id.find(key);
        if (preview && PREVIEW_VERSION.equals(version)) {
            var resource = new ClassPathResource("policies/" + key + "." + version + ".txt");
            try {
                return new Detail(document(id, version, null, "PREVIEW"), resource.getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException unavailable) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "약관 본문을 불러올 수 없습니다.", unavailable);
            }
        }
        return db.sql("""
                SELECT effective_date, %s AS body,
                    CASE WHEN effective_date > %s THEN 'SCHEDULED'
                         WHEN EXISTS (SELECT 1 FROM policy_release p
                           WHERE p.effective_date > r.effective_date AND p.effective_date <= %s) THEN 'HISTORICAL'
                         ELSE 'CURRENT' END AS state
                FROM policy_release r WHERE version=:version
                """.formatted(id.column, TODAY, TODAY)).param("version", version)
                .query((row, index) -> new Detail(
                        document(id, version, row.getObject("effective_date", LocalDate.class), row.getString("state")),
                        row.getString("body"))).optional().orElseThrow(PolicyDocuments::missing);
    }

    private static Document document(Id id, String version, LocalDate effectiveDate, String state) {
        return new Document(id.key, id.title, version, effectiveDate, state);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "약관 버전을 찾을 수 없습니다.");
    }
}
