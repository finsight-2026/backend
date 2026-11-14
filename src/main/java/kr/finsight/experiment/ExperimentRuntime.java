package kr.finsight.experiment;

import jakarta.validation.constraints.NotNull;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
public class ExperimentRuntime {
    public enum Screen { B_006 }
    public enum Variant { SUMMARY_FIRST, RESTART_FIRST }
    public record Capability(Screen screenId, List<Variant> variants, boolean enabled) {}
    record Request(@NotNull Screen screenId, @NotNull UUID journeyId) {}
    record Exposure(@NotNull Screen screenId, @NotNull UUID journeyId, @NotNull Variant variantId) {}
    record Assignment(UUID experimentId, Screen screenId, Variant variantId) {}
    private record Active(UUID id, List<String> variants) {}

    private final JdbcClient db;
    private final boolean diagnosisEntryEnabled;

    ExperimentRuntime(JdbcClient db,
            @Value("${finsight.experiments.diagnosis-entry:true}") boolean diagnosisEntryEnabled) {
        this.db = db;
        this.diagnosisEntryEnabled = diagnosisEntryEnabled;
    }

    public Capability capability() {
        return new Capability(Screen.B_006, List.of(Variant.SUMMARY_FIRST, Variant.RESTART_FIRST), diagnosisEntryEnabled);
    }

    @Transactional
    Optional<Assignment> assign(UUID user, Request request) {
        if (!diagnosisEntryEnabled) return Optional.empty();
        lockJourney(user, request.journeyId());
        var active = db.sql("""
                SELECT id,variants FROM screen_experiment WHERE screen_id=:screen AND status='ACTIVE'
                AND starts_at<=clock_timestamp() AND ends_at>clock_timestamp() FOR SHARE
                """).param("screen", request.screenId().name()).query((row, n) -> new Active(row.getObject(1, UUID.class),
                        List.of((String[]) row.getArray(2).getArray()))).optional();
        return active.map(work -> {
            var variant = work.variants().get(bucket(user, work.id(), work.variants().size()));
            var stored = db.sql("""
                    INSERT INTO screen_experiment_assignment(experiment_id,user_id,variant_id)
                    VALUES (:experiment,:user,:variant) ON CONFLICT (experiment_id,user_id)
                    DO UPDATE SET variant_id=screen_experiment_assignment.variant_id RETURNING variant_id
                    """).param("experiment", work.id()).param("user", user).param("variant", variant).query(String.class).single();
            return new Assignment(work.id(), request.screenId(), Variant.valueOf(stored));
        });
    }

    @Transactional
    void expose(UUID user, UUID id, Exposure exposure) {
        if (!diagnosisEntryEnabled) throw missing();
        lockJourney(user, exposure.journeyId());
        var time = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        if (db.sql("""
                SELECT e.id FROM screen_experiment e JOIN screen_experiment_assignment a ON a.experiment_id=e.id
                WHERE e.id=:id AND e.screen_id=:screen AND e.status='ACTIVE' AND e.starts_at<=:time AND e.ends_at>:time
                    AND a.user_id=:user AND a.variant_id=:variant AND a.assigned_at<=:time
                FOR SHARE OF e,a
                """).param("id", id).param("screen", exposure.screenId().name()).param("time", time)
                .param("user", user).param("variant", exposure.variantId().name()).query(UUID.class).optional().isEmpty()) {
            throw missing();
        }
        db.sql("""
                WITH first_exposure AS (
                    INSERT INTO screen_experiment_exposure(experiment_id,user_id,variant_id,journey_id,exposed_at)
                    VALUES (:id,:user,:variant,:journey,:time) ON CONFLICT (experiment_id,user_id) DO NOTHING
                    RETURNING experiment_id,user_id,variant_id,journey_id,exposed_at
                )
                INSERT INTO app_event(event_name,user_id,journey_id,experiment_id,experiment_screen_id,experiment_variant_id,
                    occurred_at,received_at)
                SELECT 'EXPERIMENT_EXPOSED',user_id,journey_id,experiment_id,:screen,variant_id,exposed_at,exposed_at
                FROM first_exposure
                """).param("id", id).param("user", user).param("variant", exposure.variantId().name())
                .param("journey", exposure.journeyId()).param("time", time).param("screen", exposure.screenId().name()).update();
    }

    private void lockJourney(UUID user, UUID journey) {
        if (db.sql("SELECT id FROM app_user WHERE id=:id AND status='ACTIVE' FOR UPDATE")
                .param("id", user).query(UUID.class).optional().isEmpty()) throw missing();
        if (db.sql("SELECT id FROM diagnosis_journey WHERE id=:id AND user_id=:user")
                .param("id", journey).param("user", user).query(UUID.class).optional().isEmpty()) throw missing();
    }

    private static int bucket(UUID user, UUID experiment, int size) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((user + ":" + experiment).getBytes(StandardCharsets.US_ASCII));
            return Integer.remainderUnsigned(ByteBuffer.wrap(digest).getInt(), size);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
