package kr.finsight.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/campaigns")
class AdminCampaignsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    record Update(AdminCampaigns.Fields fields, Long expectedVersion) {}
    record PreviewInput(Long expectedVersion) {}
    record Confirm(UUID previewId, Long expectedVersion) {}
    record Cancel(Long expectedVersion, String reason) {}
    record Retry(String reason) {}

    private final AdminAuth auth;
    private final AdminCampaigns campaigns;
    private final AdminCampaignDispatch dispatch;

    AdminCampaignsController(AdminAuth auth, AdminCampaigns campaigns, AdminCampaignDispatch dispatch) {
        this.auth = auth;
        this.campaigns = campaigns;
        this.dispatch = dispatch;
    }

    @GetMapping("/{id}/deliveries")
    ResponseEntity<AdminCampaignDispatch.Deliveries> deliveries(
            @CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id,
            @RequestParam(required = false) AdminCampaignDispatch.DeliveryStatus status) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dispatch.deliveries(id, status));
    }

    @PostMapping("/{id}/deliveries/retry")
    ResponseEntity<AdminCampaignDispatch.RetryReceipt> retryDeliveries(
            @CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Retry input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
                dispatch.retryDevices(operator(session), id, key, input.reason(), java.time.OffsetDateTime.now()));
    }

    @GetMapping
    ResponseEntity<List<AdminCampaigns.Campaign>> list(@CookieValue(name = SESSION, required = false) String session) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(campaigns.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminCampaigns.Campaign create(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody AdminCampaigns.Fields input) {
        return campaigns.create(operator(session), input);
    }

    @PatchMapping("/{id}")
    AdminCampaigns.Campaign update(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Update input) {
        return campaigns.update(operator(session), id, input.fields(), version(input.expectedVersion()));
    }

    @PostMapping("/{id}/preview")
    ResponseEntity<AdminCampaigns.Preview> preview(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody(required = false) PreviewInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(campaigns.preview(
                operator(session), id, input == null ? null : input.expectedVersion()));
    }

    @PostMapping("/{id}/send")
    ResponseEntity<AdminCampaigns.Job> send(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Confirm input) {
        var actor = operator(session);
        if (input.previewId() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.accepted().body(campaigns.confirm(actor, id, input.previewId(), version(input.expectedVersion())));
    }

    @PostMapping("/{id}/cancel")
    AdminCampaigns.Campaign cancel(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Cancel input) {
        return campaigns.cancel(operator(session), id, version(input.expectedVersion()), input.reason());
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return value;
    }

    private AdminAuth.Identity operator(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return identity;
    }
}
