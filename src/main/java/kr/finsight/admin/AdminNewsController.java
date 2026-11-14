package kr.finsight.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.content.EditorialPage;
import kr.finsight.diagnosis.YfsiScoring;
import kr.finsight.news.NewsEditorial;
import kr.finsight.news.NewsCandidates;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin")
class AdminNewsController {
    record DraftRequest(UUID articleId, NewsEditorial.Draft fields) {}
    record EditRequest(Long expectedVersion, NewsEditorial.Draft fields) {}
    record EditionRequest(List<NewsEditorial.EditionSlot> slots, Long expectedVersion) {}

    private final AdminAuth auth;
    private final NewsEditorial news;
    private final NewsCandidates candidates;
    private final AdminMutationJournal journal;

    AdminNewsController(AdminAuth auth, NewsEditorial news, NewsCandidates candidates, AdminMutationJournal journal) {
        this.auth = auth;
        this.news = news;
        this.candidates = candidates;
        this.journal = journal;
    }

    @GetMapping("/news/candidates")
    ResponseEntity<NewsCandidates.Inbox> candidates(@CookieValue(name = AdminAuthController.SESSION_COOKIE,
            required = false) String token) {
        require(token, "OPERATOR", "SUPER_ADMIN");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(candidates.inbox());
    }

    @GetMapping("/news")
    List<NewsEditorial.Article> list(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token) {
        require(token, "OPERATOR", "SUPER_ADMIN");
        return news.list();
    }

    @GetMapping("/news/page")
    ResponseEntity<EditorialPage<NewsEditorial.Article>> page(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @RequestParam(required = false) NewsEditorial.Status status, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID authorId, @RequestParam(required = false) YfsiScoring.Pressure type,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        require(token, "OPERATOR", "SUPER_ADMIN");
        var filter = new EditorialPage.Filter(status == null ? null : status.name(), q, authorId, from, to, type);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(news.page(filter, page, size));
    }

    @GetMapping("/news/{id}")
    ResponseEntity<NewsEditorial.Article> detail(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @PathVariable UUID id, @RequestParam(required = false) Integer revision) {
        require(token, "OPERATOR", "SUPER_ADMIN");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(news.lookup(id, revision));
    }

    @GetMapping("/news/{id}/revisions")
    ResponseEntity<List<NewsEditorial.Article>> revisions(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token, @PathVariable UUID id) {
        require(token, "OPERATOR", "SUPER_ADMIN");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(news.revisions(id));
    }

    @GetMapping({"/news-editions", "/news/editions/options"})
    ResponseEntity<NewsEditorial.EditionChoices> editionOptions(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @RequestParam LocalDate date, @RequestParam YfsiScoring.Pressure type) {
        require(token, "SUPER_ADMIN");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(news.editionOptions(date, type));
    }

    @PostMapping("/news")
    @ResponseStatus(HttpStatus.CREATED)
    NewsEditorial.Article draft(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
                                @RequestBody DraftRequest request) {
        var actor = require(token, "OPERATOR");
        if (request.fields() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return news.draft(actor.admin().id(), request.articleId(), request.fields());
    }

    @PatchMapping("/news/{id}/{revision}")
    NewsEditorial.Article edit(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
                               @PathVariable UUID id, @PathVariable int revision, @RequestBody EditRequest request) {
        var actor = require(token, "OPERATOR");
        if (request.fields() == null || request.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return news.edit(actor.admin().id(), id, revision, request.expectedVersion(), request.fields());
    }

    @PostMapping("/news/{id}/{revision}/transitions")
    NewsEditorial.Article transition(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @PathVariable UUID id, @PathVariable int revision,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody NewsEditorial.Change change) {
        if (change.action() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        if (change.action() == NewsEditorial.Action.SUBMIT || change.action() == NewsEditorial.Action.WITHDRAW) {
            var actor = require(token, "OPERATOR");
            return news.transition(actor.admin().id(), id, revision, change);
        }
        var actor = change.action() == NewsEditorial.Action.ARCHIVE
                ? require(token, "OPERATOR", "SUPER_ADMIN") : require(token, "SUPER_ADMIN");
        if ((change.action() == NewsEditorial.Action.PUBLISH || change.action() == NewsEditorial.Action.SCHEDULE)
                && !Boolean.TRUE.equals(change.previewConfirmed())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return journal.once(actor, "/news/" + id + "/" + revision + "/transitions", key, change,
                NewsEditorial.Article.class, () -> news.transition(actor.admin().id(), id, revision, change,
                        actor.permissions().contains("OPERATOR"), actor.permissions().contains("SUPER_ADMIN")),
                article -> journal.append(actor, "NEWS_" + change.action(), "NEWS_ARTICLE", id.toString(),
                        Map.of("revision", revision, "version", article.version(), "status", article.status())));
    }

    @PutMapping({"/news-editions/{date}/{type}", "/news/editions/{date}/{type}"})
    NewsEditorial.Edition edition(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @PathVariable LocalDate date, @PathVariable YfsiScoring.Pressure type, @RequestBody EditionRequest request) {
        var actor = require(token, "SUPER_ADMIN");
        return journal.record(actor, "NEWS_EDITION_SET", "NEWS_EDITION", date + ":" + type,
                () -> news.edition(date, type, request.slots(), request.expectedVersion()),
                result -> Map.of("slots", result.slots(), "version", result.version()));
    }

    private AdminAuth.Identity require(String token, String... roles) {
        var identity = auth.identity(token);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        for (var role : roles) if (identity.permissions().contains(role)) return identity;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
