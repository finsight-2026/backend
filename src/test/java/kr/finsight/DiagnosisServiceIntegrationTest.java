package kr.finsight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.finsight.diagnosis.DiagnosisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DiagnosisServiceIntegrationTest {
    @Autowired
    DiagnosisService service;
    @Autowired
    JdbcClient db;

    @Test
    void concurrentSubmissionsHaveOneSnapshotAndThirtyAnswers() throws Exception {
        var user = createUser();
        var attempt = service.start(user);
        var ready = new CountDownLatch(2);
        var submit = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                ready.countDown();
                submit.await();
                return service.complete(user, attempt.id(), Collections.nCopies(30, 3));
            });
            var second = workers.submit(() -> {
                ready.countDown();
                submit.await();
                return service.complete(user, attempt.id(), Collections.nCopies(30, 5));
            });
            boolean workersReady = ready.await(5, TimeUnit.SECONDS);
            submit.countDown();
            assertTrue(workersReady);

            var result = first.get(10, TimeUnit.SECONDS);
            assertEquals(result, second.get(10, TimeUnit.SECONDS));
            assertEquals(result, service.findByAttempt(user, attempt.id()).orElseThrow());
            assertTrue(service.findByAttempt(createUser(), attempt.id()).isEmpty());
            assertEquals(30, db.sql("SELECT count(*) FROM diagnosis_answer WHERE diagnosis_id = :id")
                    .param("id", result.id()).query(Integer.class).single());
        }
    }

    @Test
    void abortedAndInvalidAttemptsDoNotPersistAnswers() {
        var user = createUser();
        var aborted = service.start(user);
        service.abort(user, aborted.id(), 12);
        assertThrows(IllegalStateException.class,
                () -> service.complete(user, aborted.id(), Collections.nCopies(30, 3)));
        assertTrue(service.findByAttempt(user, aborted.id()).isEmpty());

        var unfinished = service.start(user);
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(user, unfinished.id(), Collections.nCopies(29, 3)));
        assertTrue(service.findByAttempt(user, unfinished.id()).isEmpty());
        assertEquals(50, service.complete(user, unfinished.id(), Collections.nCopies(30, 3)).result().total().display());
    }

    private UUID createUser() {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id, email, status) VALUES (:id, :email, 'ACTIVE')")
                .param("id", id).param("email", id + "@example.test").update();
        return id;
    }
}
