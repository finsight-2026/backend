CREATE TABLE app_event (
    event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_name text NOT NULL CHECK (event_name IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED','APP_FOREGROUND',
        'DIAGNOSIS_ENTRY_VIEWED','SURVEY_STARTED','QUESTION_VIEWED','QUESTION_COMPLETED','SURVEY_ABORTED','SURVEY_COMPLETED',
        'RESULT_VIEWED','GUIDE_VIEWED','GUIDE_COMPLETION_SET','NOTIFICATION_OPENED')),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    attempt_id uuid REFERENCES survey_attempt(id) ON DELETE CASCADE,
    journey_id uuid REFERENCES diagnosis_journey(id) ON DELETE CASCADE,
    diagnosis_id uuid REFERENCES diagnosis(id) ON DELETE CASCADE,
    content_id text,
    notification_id uuid REFERENCES app_notification(id) ON DELETE CASCADE,
    question_no smallint CHECK (question_no BETWEEN 1 AND 30),
    active_ms integer CHECK (active_ms BETWEEN 0 AND 86400000),
    activity_date date,
    completion_month date,
    completion_value boolean,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    clock_adjusted boolean NOT NULL DEFAULT false
);
CREATE INDEX app_event_occurred ON app_event(occurred_at DESC,event_id DESC);
CREATE INDEX app_event_name_occurred ON app_event(event_name,occurred_at DESC,event_id DESC);
CREATE INDEX app_event_user_occurred ON app_event(user_id,occurred_at DESC,event_id DESC);
CREATE INDEX app_event_attempt ON app_event(attempt_id) WHERE attempt_id IS NOT NULL;
CREATE INDEX app_event_journey ON app_event(journey_id) WHERE journey_id IS NOT NULL;
CREATE INDEX app_event_diagnosis ON app_event(diagnosis_id) WHERE diagnosis_id IS NOT NULL;
CREATE INDEX app_event_notification ON app_event(notification_id) WHERE notification_id IS NOT NULL;
CREATE UNIQUE INDEX app_event_account_milestones ON app_event(user_id,event_name)
    WHERE event_name IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED');

ALTER TABLE diagnosis_question_event ADD COLUMN client_occurred_at timestamptz;
ALTER TABLE diagnosis_view_event ADD COLUMN client_occurred_at timestamptz;

INSERT INTO app_event(event_name,user_id,occurred_at,received_at)
SELECT 'USER_SIGNED_UP',id,created_at,created_at FROM app_user;

WITH accepted AS (
    SELECT c.user_id,c.document_kind,min(c.recorded_at) AS accepted_at
    FROM app_user_consent c JOIN app_user_profile p ON p.user_id=c.user_id
    WHERE c.granted AND c.document_kind IN ('TERMS','PRIVACY') GROUP BY c.user_id,c.document_kind
)
INSERT INTO app_event(event_name,user_id,occurred_at,received_at)
SELECT 'PROFILE_CONSENT_COMPLETED',user_id,max(accepted_at),max(accepted_at)
FROM accepted GROUP BY user_id HAVING count(*)=2;

INSERT INTO app_event(event_name,user_id,journey_id,occurred_at,received_at)
SELECT 'DIAGNOSIS_ENTRY_VIEWED',user_id,id,entered_at,entered_at FROM diagnosis_journey;
INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,occurred_at,received_at)
SELECT 'SURVEY_STARTED',user_id,id,journey_id,started_at,started_at FROM survey_attempt;
INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,question_no,occurred_at,received_at)
SELECT 'SURVEY_ABORTED',user_id,id,journey_id,last_question,aborted_at,aborted_at
FROM survey_attempt WHERE aborted_at IS NOT NULL;
INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,diagnosis_id,occurred_at,received_at)
SELECT 'SURVEY_COMPLETED',a.user_id,a.id,a.journey_id,d.id,d.completed_at,d.completed_at
FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id;
INSERT INTO app_event(event_name,user_id,content_id,completion_month,completion_value,occurred_at,received_at)
SELECT 'GUIDE_COMPLETION_SET',user_id,content_id,month_start,true,completed_at,completed_at
FROM guide_activity WHERE completed_at IS NOT NULL;
INSERT INTO app_event(event_name,user_id,notification_id,occurred_at,received_at)
SELECT 'NOTIFICATION_OPENED',user_id,id,read_at,read_at FROM app_notification WHERE read_at IS NOT NULL;

WITH client_events AS (
    SELECT q.id,CASE q.kind WHEN 'VIEWED' THEN 'QUESTION_VIEWED' ELSE 'QUESTION_COMPLETED' END AS name,
           a.user_id,a.id AS attempt_id,a.journey_id,NULL::uuid AS diagnosis_id,NULL::text AS content_id,
           q.question_no,q.active_ms,q.recorded_at
    FROM diagnosis_question_event q JOIN survey_attempt a ON a.id=q.attempt_id
    UNION ALL
    SELECT v.id,CASE WHEN v.content_id IS NULL THEN 'RESULT_VIEWED' ELSE 'GUIDE_VIEWED' END,
           a.user_id,a.id,a.journey_id,d.id,v.content_id,NULL,NULL,v.recorded_at
    FROM diagnosis_view_event v JOIN survey_attempt a ON a.id=v.attempt_id JOIN diagnosis d ON d.attempt_id=a.id
)
INSERT INTO app_event(event_id,event_name,user_id,attempt_id,journey_id,diagnosis_id,content_id,question_no,active_ms,occurred_at,received_at)
SELECT DISTINCT ON(id) id,name,user_id,attempt_id,journey_id,diagnosis_id,content_id,question_no,active_ms,recorded_at,recorded_at
FROM client_events ORDER BY id,recorded_at,name
ON CONFLICT (event_id) DO NOTHING;

CREATE FUNCTION log_app_account_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='app_user' THEN
        INSERT INTO app_event(event_name,user_id,occurred_at,received_at)
        VALUES ('USER_SIGNED_UP',NEW.id,NEW.created_at,NEW.created_at);
    ELSIF TG_TABLE_NAME='app_foreground_day' THEN
        INSERT INTO app_event(event_name,user_id,activity_date,occurred_at,received_at)
        VALUES ('APP_FOREGROUND',NEW.user_id,NEW.activity_date,clock_timestamp(),clock_timestamp());
    ELSIF NEW.granted AND NEW.document_kind IN ('TERMS','PRIVACY')
        AND EXISTS(SELECT 1 FROM app_user_profile WHERE user_id=NEW.user_id)
        AND (SELECT count(DISTINCT document_kind) FROM app_user_consent WHERE user_id=NEW.user_id
             AND granted AND document_kind IN ('TERMS','PRIVACY'))=2 THEN
        INSERT INTO app_event(event_name,user_id,occurred_at,received_at)
        VALUES ('PROFILE_CONSENT_COMPLETED',NEW.user_id,NEW.recorded_at,NEW.recorded_at)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_event_signup AFTER INSERT ON app_user FOR EACH ROW EXECUTE FUNCTION log_app_account_event();
CREATE TRIGGER app_event_consent AFTER INSERT ON app_user_consent FOR EACH ROW EXECUTE FUNCTION log_app_account_event();
CREATE TRIGGER app_event_foreground AFTER INSERT ON app_foreground_day FOR EACH ROW EXECUTE FUNCTION log_app_account_event();

CREATE FUNCTION log_app_survey_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='diagnosis_journey' THEN
        INSERT INTO app_event(event_name,user_id,journey_id,occurred_at,received_at)
        VALUES ('DIAGNOSIS_ENTRY_VIEWED',NEW.user_id,NEW.id,NEW.entered_at,NEW.entered_at);
    ELSIF TG_TABLE_NAME='diagnosis' THEN
        INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,diagnosis_id,occurred_at,received_at)
        SELECT 'SURVEY_COMPLETED',a.user_id,a.id,a.journey_id,NEW.id,NEW.completed_at,NEW.completed_at
        FROM survey_attempt a WHERE a.id=NEW.attempt_id;
    ELSIF TG_OP='INSERT' THEN
        INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,occurred_at,received_at)
        VALUES ('SURVEY_STARTED',NEW.user_id,NEW.id,NEW.journey_id,NEW.started_at,NEW.started_at);
    ELSE
        INSERT INTO app_event(event_name,user_id,attempt_id,journey_id,question_no,occurred_at,received_at)
        VALUES ('SURVEY_ABORTED',NEW.user_id,NEW.id,NEW.journey_id,NEW.last_question,NEW.aborted_at,NEW.aborted_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_event_journey_insert AFTER INSERT ON diagnosis_journey FOR EACH ROW EXECUTE FUNCTION log_app_survey_event();
CREATE TRIGGER app_event_attempt_insert AFTER INSERT ON survey_attempt FOR EACH ROW EXECUTE FUNCTION log_app_survey_event();
CREATE TRIGGER app_event_diagnosis_insert AFTER INSERT ON diagnosis FOR EACH ROW EXECUTE FUNCTION log_app_survey_event();
CREATE TRIGGER app_event_attempt_abort AFTER UPDATE OF aborted_at ON survey_attempt
    FOR EACH ROW WHEN (OLD.aborted_at IS NULL AND NEW.aborted_at IS NOT NULL) EXECUTE FUNCTION log_app_survey_event();

CREATE FUNCTION log_app_diagnosis_client_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    adjusted boolean := coalesce(abs(extract(epoch FROM NEW.client_occurred_at-NEW.recorded_at))>300,false);
    occurred timestamptz := CASE WHEN adjusted THEN NEW.recorded_at ELSE coalesce(NEW.client_occurred_at,NEW.recorded_at) END;
BEGIN
    IF TG_TABLE_NAME='diagnosis_question_event' THEN
        INSERT INTO app_event(event_id,event_name,user_id,attempt_id,journey_id,question_no,active_ms,occurred_at,received_at,clock_adjusted)
        SELECT NEW.id,CASE NEW.kind WHEN 'VIEWED' THEN 'QUESTION_VIEWED' ELSE 'QUESTION_COMPLETED' END,
               a.user_id,a.id,a.journey_id,NEW.question_no,NEW.active_ms,occurred,NEW.recorded_at,adjusted
        FROM survey_attempt a WHERE a.id=NEW.attempt_id ON CONFLICT(event_id) DO NOTHING;
    ELSE
        INSERT INTO app_event(event_id,event_name,user_id,attempt_id,journey_id,diagnosis_id,content_id,occurred_at,received_at,clock_adjusted)
        SELECT NEW.id,CASE WHEN NEW.content_id IS NULL THEN 'RESULT_VIEWED' ELSE 'GUIDE_VIEWED' END,
               a.user_id,a.id,a.journey_id,d.id,NEW.content_id,occurred,NEW.recorded_at,adjusted
        FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id WHERE a.id=NEW.attempt_id
        ON CONFLICT(event_id) DO NOTHING;
    END IF;
    IF NOT FOUND THEN RETURN NULL; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_event_client_question BEFORE INSERT ON diagnosis_question_event
    FOR EACH ROW EXECUTE FUNCTION log_app_diagnosis_client_event();
CREATE TRIGGER app_event_client_view BEFORE INSERT ON diagnosis_view_event
    FOR EACH ROW EXECUTE FUNCTION log_app_diagnosis_client_event();

CREATE FUNCTION log_app_local_action_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='guide_activity' THEN
        INSERT INTO app_event(event_name,user_id,content_id,completion_month,completion_value,occurred_at,received_at)
        VALUES ('GUIDE_COMPLETION_SET',NEW.user_id,NEW.content_id,NEW.month_start,NEW.completed_at IS NOT NULL,
                coalesce(NEW.completed_at,clock_timestamp()),clock_timestamp());
    ELSE
        INSERT INTO app_event(event_name,user_id,notification_id,occurred_at,received_at)
        VALUES ('NOTIFICATION_OPENED',NEW.user_id,NEW.id,NEW.read_at,NEW.read_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_event_guide_completion AFTER UPDATE OF completed_at ON guide_activity
    FOR EACH ROW WHEN ((OLD.completed_at IS NULL) <> (NEW.completed_at IS NULL)) EXECUTE FUNCTION log_app_local_action_event();
CREATE TRIGGER app_event_notification_open AFTER UPDATE OF read_at ON app_notification
    FOR EACH ROW WHEN (OLD.read_at IS NULL AND NEW.read_at IS NOT NULL) EXECUTE FUNCTION log_app_local_action_event();

CREATE TABLE deleted_journey (
    digest bytea PRIMARY KEY,
    deleted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION mark_deleted_journey() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO deleted_journey(digest) VALUES (sha256(uuid_send(OLD.id))) ON CONFLICT DO NOTHING;
    RETURN OLD;
END;
$$;
CREATE TRIGGER app_event_deleted_journey BEFORE DELETE ON diagnosis_journey
    FOR EACH ROW EXECUTE FUNCTION mark_deleted_journey();

CREATE FUNCTION reject_audit_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit log is append-only' USING ERRCODE='check_violation';
END;
$$;
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_rewrite();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_rewrite();
