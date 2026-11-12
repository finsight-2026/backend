package kr.finsight.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.experiment.ExperimentRuntime;
import kr.finsight.experiment.ExperimentRuntime.Screen;
import kr.finsight.experiment.ExperimentRuntime.Variant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminExperiments {
    enum Status { DRAFT, ACTIVE, PAUSED, ENDED }
    enum Action { ACTIVATE, PAUSE, END }

    record Fields(@NotBlank @Size(max = 80) String name, @NotNull Screen screenId,
                  @NotNull @Size(min = 2, max = 4) List<@NotNull Variant> variants,
                  @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt) {}
    record Edit(@NotNull @Valid Fields fields, @NotNull @PositiveOrZero Long expectedVersion) {}
    record Transition(@NotNull Action action, @NotBlank @Size(max = 300) String reason,
                      @NotNull @PositiveOrZero Long expectedVersion) {}
    record Item(UUID id, String name, Screen screenId, List<String> variants, OffsetDateTime startsAt,
                OffsetDateTime endsAt, Status status, long version, OffsetDateTime endedAt) {}
    record Listing(List<Item> items, ExperimentRuntime.Capability capability) {}
    record VariantStats(String variantId, long exposed, long completed) {}
    record Report(Item experiment, List<VariantStats> variants) {}

    private static final String COLUMNS = "id,name,screen_id,variants,starts_at,ends_at,status,version,ended_at";
    private final JdbcClient db;
    private final ExperimentRuntime runtime;
    private final AdminMutationJournal journal;

    AdminExperiments(JdbcClient db, ExperimentRuntime runtime, AdminMutationJournal journal) {
        this.db = db;
        this.runtime = runtime;
        this.journal = journal;
    }

    Listing list(AdminAuth.Identity actor) {
        require(actor, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return new Listing(db.sql("SELECT " + COLUMNS + " FROM screen_experiment ORDER BY updated_at DESC,id")
                .query(this::row).list(), runtime.capability());
    }

    @Transactional
    Item create(AdminAuth.Identity actor, Fields fields) {
        require(actor, "OPERATOR");
        validate(fields);
        var item = bindFields(db.sql("""
                INSERT INTO screen_experiment(id,name,screen_id,variants,starts_at,ends_at,author_id)
                VALUES (:id,:name,:screen,string_to_array(:variants,','),:start,:end,:author)
                RETURNING
                """ + COLUMNS), fields).param("id", UUID.randomUUID()).param("author", actor.admin().id())
                .query(this::row).single();
        journal.append(actor, "EXPERIMENT_CREATED", "SCREEN_EXPERIMENT", item.id().toString(),
                Map.of("screenId", item.screenId(), "variants", item.variants(), "version", item.version()));
        return item;
    }

    @Transactional
    Item edit(AdminAuth.Identity actor, UUID id, Edit edit) {
        require(actor, "OPERATOR");
        validate(edit.fields());
        var item = bindFields(db.sql("""
                UPDATE screen_experiment SET name=:name,screen_id=:screen,variants=string_to_array(:variants,','),
                    starts_at=:start,ends_at=:end,version=version+1,updated_at=clock_timestamp()
                WHERE id=:id AND status='DRAFT' AND version=:version RETURNING
                """ + COLUMNS), edit.fields()).param("id", id).param("version", edit.expectedVersion())
                .query(this::row).optional().orElseThrow(() -> {
                    find(id, false);
                    return conflict();
                });
        journal.append(actor, "EXPERIMENT_EDITED", "SCREEN_EXPERIMENT", id.toString(),
                Map.of("screenId", item.screenId(), "variants", item.variants(), "version", item.version()));
        return item;
    }

    @Transactional
    Item transition(AdminAuth.Identity actor, UUID id, Transition change) {
        require(actor, "SUPER_ADMIN");
        lockTransitions();
        var current = find(id, true);
        if (current.version() != change.expectedVersion()) throw conflict();
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        var target = switch (change.action()) {
            case ACTIVATE -> {
                if ((current.status() != Status.DRAFT && current.status() != Status.PAUSED)
                        || !current.endsAt().isAfter(now) || !runtime.capability().enabled()) throw conflict();
                expireDue();
                if (db.sql("SELECT EXISTS(SELECT 1 FROM screen_experiment WHERE screen_id=:screen AND status='ACTIVE')")
                        .param("screen", current.screenId().name()).query(Boolean.class).single()) throw conflict();
                yield Status.ACTIVE;
            }
            case PAUSE -> {
                if (current.status() != Status.ACTIVE || !current.endsAt().isAfter(now)) throw conflict();
                yield Status.PAUSED;
            }
            case END -> {
                if (current.status() != Status.ACTIVE && current.status() != Status.PAUSED) throw conflict();
                yield Status.ENDED;
            }
        };
        var item = db.sql("""
                UPDATE screen_experiment SET status=:status,version=version+1,updated_at=:now,
                    ended_at=CASE WHEN :status='ENDED' THEN least(:now,ends_at) END
                WHERE id=:id RETURNING
                """ + COLUMNS).param("status", target.name()).param("now", now).param("id", id).query(this::row).single();
        journal.append(actor, "EXPERIMENT_" + change.action(), "SCREEN_EXPERIMENT", id.toString(),
                Map.of("previousStatus", current.status(), "status", item.status(), "reason", change.reason().strip(),
                        "version", item.version()));
        return item;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Report results(AdminAuth.Identity actor, UUID id) {
        require(actor, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        var experiment = find(id, false);
        var variants = db.sql("""
                SELECT v.variant_id,count(u.id),count(u.id) FILTER (
                    WHERE d.completed_at>=x.exposed_at AND d.completed_at<x.exposed_at+interval '24 hours'
                    AND d.completed_at<least(e.ends_at,e.ended_at))
                FROM screen_experiment e CROSS JOIN LATERAL unnest(e.variants) WITH ORDINALITY v(variant_id,ordinal)
                LEFT JOIN screen_experiment_exposure x ON x.experiment_id=e.id AND x.variant_id=v.variant_id
                LEFT JOIN app_user u ON u.id=x.user_id AND u.status='ACTIVE' AND NOT u.comparison_test_account
                LEFT JOIN survey_attempt a ON a.journey_id=x.journey_id
                LEFT JOIN diagnosis d ON d.attempt_id=a.id
                WHERE e.id=:id GROUP BY v.variant_id,v.ordinal ORDER BY v.ordinal
                """).param("id", id).query((row, index) -> new VariantStats(row.getString(1),
                        row.getLong(2), row.getLong(3))).list();
        return new Report(experiment, variants);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    void expire() {
        lockTransitions();
        expireDue();
    }

    private void lockTransitions() {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('screen-experiments',0))")
                .query((row, index) -> true).single();
    }

    private void expireDue() {
        db.sql("""
                UPDATE screen_experiment SET status='ENDED',version=version+1,updated_at=clock_timestamp(),ended_at=ends_at
                WHERE status IN ('ACTIVE','PAUSED') AND ends_at<=clock_timestamp()
                """).update();
    }

    private Item find(UUID id, boolean lock) {
        return db.sql("SELECT " + COLUMNS + " FROM screen_experiment WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(this::row).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void validate(Fields fields) {
        var capability = runtime.capability();
        if (fields.screenId() != capability.screenId() || !capability.variants().containsAll(fields.variants())
                || new HashSet<>(fields.variants()).size() != fields.variants().size()
                || !fields.endsAt().isAfter(fields.startsAt())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private JdbcClient.StatementSpec bindFields(JdbcClient.StatementSpec query, Fields fields) {
        var variants = String.join(",", fields.variants().stream().map(Variant::name).toList());
        return query.param("name", fields.name().strip()).param("screen", fields.screenId().name())
                .param("variants", variants).param("start", fields.startsAt()).param("end", fields.endsAt());
    }

    private Item row(ResultSet row, int index) throws SQLException {
        return new Item(row.getObject(1, UUID.class), row.getString(2), Screen.valueOf(row.getString(3)),
                List.of((String[]) row.getArray(4).getArray()), row.getObject(5, OffsetDateTime.class),
                row.getObject(6, OffsetDateTime.class), Status.valueOf(row.getString(7)), row.getLong(8),
                row.getObject(9, OffsetDateTime.class));
    }

    private static void require(AdminAuth.Identity actor, String... roles) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (List.of(roles).stream().noneMatch(actor.permissions()::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
