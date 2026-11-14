ALTER TABLE app_push_delivery ADD COLUMN manual_retry_at timestamptz;
ALTER TABLE app_push_delivery DROP CONSTRAINT app_push_delivery_state_check;
ALTER TABLE app_push_delivery ADD CONSTRAINT app_push_delivery_state_check
    CHECK (state IN ('WAITING','SENDING','SIMULATED','ACCEPTED','CANCELED','EXPIRED','UNKNOWN','INVALID','REJECTED','FAILED'));
UPDATE app_push_delivery p SET state=CASE WHEN p.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE 'FAILED' END
FROM app_notification n WHERE n.id=p.notification_id AND n.template_id='NTF-06'
    AND p.state='WAITING' AND p.attempts>=4;
