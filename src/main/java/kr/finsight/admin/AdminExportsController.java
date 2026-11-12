package kr.finsight.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin")
class AdminExportsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final AdminDashboardExports aggregates;
    private final AdminPiiExports pii;

    enum Kind { DASHBOARD_AGGREGATE, CONTENT_PERFORMANCE, PII_RAW }
    record Filter(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type,
                  UUID userId, UUID diagnosisId, AdminPiiExports.Scope scope,
                  ContentEvents.Kind kind, String contentId, Integer revision, Boolean combineVersions) {
        AdminDashboard.Filter dashboard() {
            if (userId != null || diagnosisId != null || scope != null || contentFields()) throw invalid();
            return new AdminDashboard.Filter(from, to, age, type);
        }
        AdminContentPerformance.Filter performance() {
            if (userId != null || diagnosisId != null || scope != null || age != null) throw invalid();
            return new AdminContentPerformance.Filter(from, to, kind, type, contentId, revision, Boolean.TRUE.equals(combineVersions));
        }
        AdminPiiExports.Filter personal() {
            if (from != null || to != null || age != null || type != null || contentFields()) throw invalid();
            return new AdminPiiExports.Filter(userId, diagnosisId, scope);
        }
        private boolean contentFields() {
            return kind != null || contentId != null || revision != null || combineVersions != null;
        }
    }
    record Request(Kind kind, Filter filter, List<String> columns, AdminDashboardExports.Format format,
                   String reason, UUID grantId) {}
    record Download(UUID grantId) {}
    record Cancel(String reason, Kind kind) {}
    record Retry(String reason) {}

    AdminExportsController(AdminAuth auth, AdminDashboardExports aggregates, AdminPiiExports pii) {
        this.auth = auth;
        this.aggregates = aggregates;
        this.pii = pii;
    }

    @PostMapping("/exports")
    ResponseEntity<AdminDashboardExports.Job> request(@CookieValue(name = SESSION, required = false) String session,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Request input) {
        var actor = auth.identity(session);
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (input.kind() == null) throw invalid();
        var job = switch (input.kind()) {
            case DASHBOARD_AGGREGATE -> {
                if (input.reason() != null || input.grantId() != null) throw invalid();
                yield aggregates.request(actor, new AdminDashboardExports.Request(AdminDashboardExports.Kind.DASHBOARD_AGGREGATE,
                        input.filter() == null ? null : input.filter().dashboard(), input.columns(), input.format()));
            }
            case CONTENT_PERFORMANCE -> {
                if (input.reason() != null || input.grantId() != null) throw invalid();
                yield aggregates.requestPerformance(actor, input.filter() == null ? null : input.filter().performance(),
                        input.columns(), input.format(), key);
            }
            case PII_RAW -> pii.request(actor, session, input.filter() == null ? null : input.filter().personal(),
                    input.grantId(), input.reason(), input.columns(), input.format());
        };
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(job);
    }

    @PostMapping("/exports/{id}/download")
    ResponseEntity<AdminDashboardExports.Link> issue(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody(required = false) Download input) {
        var actor = auth.identity(session);
        var link = input == null || input.grantId() == null
                ? aggregates.issue(actor, id) : pii.issue(actor, session, id, input.grantId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(link);
    }

    @GetMapping("/exports/{id}/file")
    ResponseEntity<byte[]> file(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestParam(required = false) String token, @RequestParam(required = false) String signature,
            @RequestParam(name = "pii", defaultValue = "false") boolean personal) {
        var actor = auth.identity(session);
        var file = personal ? new AdminDashboardExports.File(AdminDashboardExports.Format.CSV, pii.download(actor, session, id, token, signature), null)
                : aggregates.download(actor, id, token, signature);
        boolean pdf = file.format() == AdminDashboardExports.Format.PDF;
        String name = personal ? "finsight-private-" : file.kind() == AdminDashboardExports.Kind.CONTENT_PERFORMANCE
                ? "finsight-content-performance-" : "finsight-dashboard-";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + id + (pdf ? ".pdf" : ".csv")).build().toString())
                .header("Referrer-Policy", "no-referrer")
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(file.bytes());
    }

    @PostMapping("/jobs/{id}/retry")
    ResponseEntity<AdminDashboardExports.Job> retry(@CookieValue(name = SESSION, required = false) String session,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @PathVariable UUID id, @RequestBody Retry input) {
        var job = aggregates.retry(auth.identity(session), id, input.reason(), key);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(job);
    }

    @PostMapping("/jobs/{id}/cancel")
    ResponseEntity<AdminDashboardExports.Job> cancel(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Cancel input) {
        var actor = auth.identity(session);
        var job = input.kind() == Kind.PII_RAW ? pii.cancel(actor, session, id, input.reason())
                : aggregates.cancel(actor, id, input.reason(), input.kind() == Kind.CONTENT_PERFORMANCE
                        ? AdminDashboardExports.Kind.CONTENT_PERFORMANCE : AdminDashboardExports.Kind.DASHBOARD_AGGREGATE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(job);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
