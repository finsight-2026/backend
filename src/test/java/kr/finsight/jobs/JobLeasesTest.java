package kr.finsight.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobLeasesTest {
    @Autowired JdbcClient db;

    @Test
    @Transactional
    void heartbeatRenewsOnlyTheCurrentOwnerAndExpiredWorkCanBeClaimedAgain() {
        var id = UUID.randomUUID();
        var queue = "TEST_EXPORT";
        var leases = new JobLeases(db);
        try {
            var first = leases.acquire(queue, id).orElseThrow();
            assertTrue(leases.acquire(queue, id).isEmpty());
            setExpiry(id, 5);
            assertTrue(leases.renew(first));
            assertTrue(db.sql("SELECT expires_at>clock_timestamp()+interval '50 seconds' FROM job_execution_lease WHERE job_id=:id")
                    .param("id", id).query(Boolean.class).single());
            setExpiry(id, -5);
            assertFalse(leases.renew(first));
            var next = leases.acquire(queue, id).orElseThrow();
            assertNotEquals(first.token(), next.token());
            leases.release(first);
            assertFalse(leases.lockOwned(first));
            assertTrue(leases.lockOwned(next));
            leases.release(next);
            assertFalse(leases.lockOwned(next));
        } finally {
            leases.stop();
        }
    }

    private void setExpiry(UUID id, int seconds) {
        db.sql("UPDATE job_execution_lease SET expires_at=clock_timestamp()+make_interval(secs=>:seconds) WHERE job_id=:id")
                .param("seconds", seconds).param("id", id).update();
    }
}
