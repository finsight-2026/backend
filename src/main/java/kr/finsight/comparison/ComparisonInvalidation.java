package kr.finsight.comparison;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComparisonInvalidation {
    private final JdbcClient db;

    ComparisonInvalidation(JdbcClient db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void diagnosisCompleted() {
        // A newer diagnosis can move a participant out of their previous cohort.
        db.sql("SELECT type FROM comparison_dataset ORDER BY type FOR UPDATE").query(String.class).list();
        db.sql("UPDATE comparison_dataset SET version = version + 1").update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForUser(UUID user) {
        // Lock the affected types in a consistent order before changing snapshots.
        db.sql("""
                SELECT type FROM comparison_dataset WHERE type IN (
                    SELECT s.type FROM comparison_snapshot_member m JOIN comparison_snapshot s ON s.id = m.snapshot_id
                    WHERE m.user_id = :user
                    UNION
                    SELECT d.representative_type FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                    WHERE a.user_id = :user
                ) ORDER BY type FOR UPDATE
                """).param("user", user).query(String.class).list().forEach(type -> db.sql("UPDATE comparison_dataset SET version = version + 1 WHERE type = :type")
                        .param("type", type).update());
        db.sql("""
                UPDATE comparison_snapshot SET status = 'INVALIDATED', version = version + 1
                WHERE status <> 'INVALIDATED' AND id IN (
                    SELECT snapshot_id FROM comparison_snapshot_member WHERE user_id = :user
                )
                """).param("user", user).update();
        db.sql("""
                UPDATE comparison_publication SET status = 'INVALIDATED', version = version + 1
                WHERE status IN ('ACTIVE','SCHEDULED') AND snapshot_id IN (
                    SELECT snapshot_id FROM comparison_snapshot_member WHERE user_id = :user
                )
                """).param("user", user).update();
    }
}
