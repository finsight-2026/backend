ALTER TABLE app_event DROP CONSTRAINT app_event_event_name_check;
ALTER TABLE app_event ADD CHECK (event_name IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED','APP_FOREGROUND',
    'DIAGNOSIS_ENTRY_VIEWED','SURVEY_STARTED','QUESTION_VIEWED','QUESTION_COMPLETED','SURVEY_ABORTED','SURVEY_COMPLETED',
    'RESULT_VIEWED','GUIDE_VIEWED','GUIDE_COMPLETION_SET','NOTIFICATION_OPENED','CONTENT_IMPRESSION','CONTENT_VIEWED'));

ALTER TABLE app_event ADD COLUMN content_kind text CHECK(content_kind IN ('NEWS','GUIDE'));
ALTER TABLE app_event ADD COLUMN content_revision integer CHECK(content_revision>0);
ALTER TABLE app_event ADD COLUMN content_type text;
ALTER TABLE app_event ADD COLUMN placement text CHECK(placement IN ('NEWS_LIST','GUIDE_LIST'));
ALTER TABLE app_event ADD COLUMN view_session_id uuid;
CREATE UNIQUE INDEX app_event_content_impression_once ON app_event(user_id,view_session_id,content_kind,content_id)
    WHERE event_name='CONTENT_IMPRESSION';
CREATE INDEX app_event_content_performance ON app_event(content_kind,occurred_at,content_id,content_revision,user_id)
    WHERE content_kind IS NOT NULL;
CREATE TABLE content_performance_observation (
    singleton boolean PRIMARY KEY CHECK(singleton),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO content_performance_observation(singleton) VALUES (true);

CREATE FUNCTION add_guide_event_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT 'GUIDE',g.content_version,g.representative_type
    INTO NEW.content_kind,NEW.content_revision,NEW.content_type
    FROM guide_activity g WHERE g.user_id=NEW.user_id AND g.content_id=NEW.content_id
        AND g.month_start<=date_trunc('month', NEW.received_at AT TIME ZONE 'Asia/Seoul')::date
    ORDER BY g.month_start DESC LIMIT 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER app_event_guide_revision BEFORE INSERT ON app_event
    FOR EACH ROW WHEN (NEW.event_name IN ('GUIDE_VIEWED','GUIDE_COMPLETION_SET'))
    EXECUTE FUNCTION add_guide_event_revision();
