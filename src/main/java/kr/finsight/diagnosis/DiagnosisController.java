package kr.finsight.diagnosis;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/diagnosis/attempts")
class DiagnosisController {
    record Submission(@NotNull @Size(min = 30, max = 30)
                      List<@NotNull @Min(1) @Max(5) Integer> answers) {}
    record Abort(@NotNull @Min(1) @Max(30) Integer lastQuestion) {}

    record Journey(@NotNull UUID journeyId) {}
    record Start(UUID journeyId) {}
    record Milestone(@NotNull DiagnosisService.Milestone kind) {}
    record QuestionEvent(@NotNull UUID eventId, @NotNull @Min(1) @Max(30) Integer questionNo,
                         @NotNull DiagnosisService.QuestionEventKind kind, @Min(0) @Max(86400000) Integer activeMs,
                         OffsetDateTime occurredAt) {
        @JsonAnySetter
        void rejectField(String key, Object value) {
            throw new IllegalArgumentException("Invalid question event field");
        }
    }
    record View(@NotNull UUID eventId, @Size(min = 1, max = 80) String contentId, @Min(1) Integer contentRevision, OffsetDateTime occurredAt) {
        @JsonAnySetter
        void rejectField(String key, Object value) {
            throw new IllegalArgumentException("Invalid view event field");
        }
    }

    private final DiagnosisService diagnoses;

    DiagnosisController(DiagnosisService diagnoses) {
        this.diagnoses = diagnoses;
    }

    @GetMapping
    DiagnosisService.HistoryPage history(@AuthenticationPrincipal Jwt user,
                                        @RequestParam(required = false) OffsetDateTime before,
                                        @RequestParam(required = false) UUID beforeId) {
        if ((before == null) != (beforeId == null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return diagnoses.history(userId(user), before, beforeId);
    }

    @GetMapping("/trend")
    List<DiagnosisService.HistoryItem> trend(@AuthenticationPrincipal Jwt user) {
        return diagnoses.trend(userId(user));
    }

    @GetMapping("/widget")
    ResponseEntity<DiagnosisService.WidgetSummary> widget(@AuthenticationPrincipal Jwt user,
            @RequestParam(defaultValue = "false") boolean showScore,
            @RequestParam(defaultValue = "false") boolean showType) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(diagnoses.widgetSummary(userId(user), showScore, showType));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DiagnosisService.Attempt start(@AuthenticationPrincipal Jwt user, @RequestBody(required = false) Start request) {
        return diagnoses.start(userId(user), request == null ? null : request.journeyId());
    }

    @PostMapping("/journeys")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void enter(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Journey journey) {
        diagnoses.enter(userId(user), journey.journeyId());
    }

    @PostMapping("/{id}/milestones")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void milestone(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody Milestone milestone) {
        diagnoses.recordMilestone(userId(user), id, milestone.kind());
    }

    @PostMapping("/{id}/question-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void questionEvent(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody QuestionEvent event) {
        if ((event.kind() == DiagnosisService.QuestionEventKind.COMPLETED) != (event.activeMs() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        diagnoses.recordQuestionEvent(userId(user), id, event.eventId(), event.questionNo(), event.kind(),
                event.activeMs(), event.occurredAt());
    }

    @PostMapping("/{id}/submit")
    DiagnosisService.Diagnosis submit(@AuthenticationPrincipal Jwt user, @PathVariable UUID id,
                                      @Valid @RequestBody Submission submission) {
        return diagnoses.complete(userId(user), id, submission.answers());
    }

    @PostMapping("/{id}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void abort(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody Abort abort) {
        diagnoses.abort(userId(user), id, abort.lastQuestion());
    }

    @PostMapping("/{id}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void view(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody View view) {
        diagnoses.recordView(userId(user), id, view.eventId(), view.contentId(), view.contentRevision(), view.occurredAt());
    }

    @GetMapping("/{id}/result")
    DiagnosisService.Diagnosis result(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
        return diagnoses.findByAttempt(userId(user), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(DiagnosisService.AttemptNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void attemptNotFound() {}

    @ExceptionHandler(DiagnosisService.AttemptConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void invalidAttemptState() {}

    private static UUID userId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
