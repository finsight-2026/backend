ALTER TABLE app_user ADD COLUMN content_event_epoch bigint NOT NULL DEFAULT 0 CHECK (content_event_epoch>=0);

UPDATE app_user u SET content_event_epoch=1
WHERE u.status='WITHDRAWAL_PENDING'
   OR EXISTS(SELECT 1 FROM app_data_deletion d WHERE d.user_id=u.id)
   OR EXISTS(SELECT 1 FROM app_event e WHERE e.user_id=u.id AND e.event_name='USER_WITHDRAWAL_REQUESTED');
