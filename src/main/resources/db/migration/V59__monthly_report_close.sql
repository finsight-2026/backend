CREATE TABLE monthly_report_summary (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    month_start date NOT NULL,
    diagnosis_count bigint NOT NULL CHECK (diagnosis_count>0),
    first_attempt_id uuid NOT NULL REFERENCES diagnosis(attempt_id) ON DELETE CASCADE,
    last_attempt_id uuid NOT NULL REFERENCES diagnosis(attempt_id) ON DELETE CASCADE,
    guide_views bigint NOT NULL CHECK (guide_views>=0),
    guide_completions bigint NOT NULL CHECK (guide_completions BETWEEN 0 AND guide_views),
    closed_at timestamptz NOT NULL,
    PRIMARY KEY(user_id,month_start)
);
CREATE INDEX monthly_report_summary_first_attempt ON monthly_report_summary(first_attempt_id);
CREATE INDEX monthly_report_summary_last_attempt ON monthly_report_summary(last_attempt_id);

CREATE FUNCTION monthly_report_source(account uuid, report_month date)
RETURNS TABLE(diagnosis_count bigint, first_attempt_id uuid, last_attempt_id uuid, guide_views bigint, guide_completions bigint)
LANGUAGE sql STABLE AS $$
    WITH active_account AS (
        SELECT id FROM app_user WHERE id=account AND status='ACTIVE'
    ), completed AS (
        SELECT d.attempt_id,d.completed_at,d.id
        FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id JOIN active_account u ON u.id=a.user_id
        WHERE d.completed_at>=report_month::timestamp AT TIME ZONE 'Asia/Seoul'
            AND d.completed_at<(report_month+interval '1 month') AT TIME ZONE 'Asia/Seoul'
    )
    SELECT (SELECT count(*) FROM completed),
        (SELECT attempt_id FROM completed ORDER BY completed_at,id LIMIT 1),
        (SELECT attempt_id FROM completed ORDER BY completed_at DESC,id DESC LIMIT 1),
        count(*),count(g.completed_at)
    FROM guide_activity g JOIN active_account u ON u.id=g.user_id WHERE g.month_start=report_month
$$;

CREATE FUNCTION invalidate_monthly_report_diagnosis() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        DELETE FROM monthly_report_summary s USING survey_attempt a
        WHERE a.id=OLD.attempt_id AND s.user_id=a.user_id
            AND s.month_start=date_trunc('month',OLD.completed_at AT TIME ZONE 'Asia/Seoul')::date;
    END IF;
    IF TG_OP<>'DELETE' THEN
        DELETE FROM monthly_report_summary s USING survey_attempt a
        WHERE a.id=NEW.attempt_id AND s.user_id=a.user_id
            AND s.month_start=date_trunc('month',NEW.completed_at AT TIME ZONE 'Asia/Seoul')::date;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_diagnosis_changed AFTER INSERT OR UPDATE OR DELETE ON diagnosis
    FOR EACH ROW EXECUTE FUNCTION invalidate_monthly_report_diagnosis();

CREATE FUNCTION invalidate_monthly_report_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM monthly_report_summary s USING diagnosis d
    WHERE d.attempt_id=OLD.id AND s.user_id=OLD.user_id
        AND s.month_start=date_trunc('month',d.completed_at AT TIME ZONE 'Asia/Seoul')::date;
    RETURN OLD;
END;
$$;
CREATE TRIGGER monthly_report_attempt_removed BEFORE DELETE ON survey_attempt
    FOR EACH ROW EXECUTE FUNCTION invalidate_monthly_report_attempt();

CREATE FUNCTION invalidate_monthly_report_guide() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        DELETE FROM monthly_report_summary WHERE user_id=OLD.user_id AND month_start=OLD.month_start;
    END IF;
    IF TG_OP<>'DELETE' THEN
        DELETE FROM monthly_report_summary WHERE user_id=NEW.user_id AND month_start=NEW.month_start;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER monthly_report_guide_changed AFTER INSERT OR UPDATE OF completed_at,month_start,user_id OR DELETE ON guide_activity
    FOR EACH ROW EXECUTE FUNCTION invalidate_monthly_report_guide();
