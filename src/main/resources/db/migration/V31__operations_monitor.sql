CREATE TABLE deletion_operation (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('DATA_DELETION','ACCOUNT_WITHDRAWAL')),
    status text NOT NULL CHECK (status IN ('SCHEDULED','COMPLETED','CANCELED')),
    requested_at timestamptz,
    due_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz
);

INSERT INTO deletion_operation(id,kind,status,requested_at,due_at)
SELECT withdrawal_request_id,'ACCOUNT_WITHDRAWAL','SCHEDULED',withdrawal_due_at-interval '336 hours',
       withdrawal_due_at+interval '24 hours'
FROM app_user WHERE status='WITHDRAWAL_PENDING' AND withdrawal_request_id IS NOT NULL
AND withdrawal_due_at IS NOT NULL;

INSERT INTO deletion_operation(id,kind,status,completed_at)
SELECT gen_random_uuid(),'DATA_DELETION','COMPLETED',completed_at FROM app_data_deletion;

ALTER TABLE bulk_tag_job ADD COLUMN started_at timestamptz;
ALTER TABLE bulk_tag_job ADD COLUMN error_code text;

ALTER TABLE app_data_export ADD COLUMN started_at timestamptz;
ALTER TABLE app_data_export ADD COLUMN completed_at timestamptz;
ALTER TABLE app_data_export ADD COLUMN error_code text;

CREATE VIEW admin_operation_monitor AS
SELECT j.id,'CAMPAIGN'::text AS kind,c.status,c.actor_id,
       CASE WHEN c.segment_id IS NULL THEN 'SELECTED_USERS' ELSE 'SEGMENT' END AS scope,
       j.created_at AS requested_at,j.started_at,j.completed_at,NULL::timestamptz AS expires_at,
       NULL::timestamptz AS due_at,d.done AS processed,d.total,j.error_code
FROM campaign_job j JOIN campaign c ON c.id=j.campaign_id
CROSS JOIN LATERAL (
    SELECT count(*) FILTER(WHERE status<>'WAITING') AS done,count(*) AS total
    FROM campaign_local_delivery WHERE campaign_id=c.id
) d
UNION ALL
SELECT id,'BULK_TAG',status,actor_id,'SELECTED_USERS',created_at,started_at,completed_at,NULL,NULL,
       CASE WHEN status='COMPLETED' THEN total ELSE 0 END,total,error_code
FROM bulk_tag_job
UNION ALL
SELECT id,'APP_EXPORT',status,NULL,'ACCOUNT_FILE',requested_at,started_at,completed_at,expires_at,NULL,
       CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,error_code
FROM app_data_export
UNION ALL
SELECT id,kind,status,NULL,CASE WHEN kind='DATA_DELETION' THEN 'SERVICE_DATA' ELSE 'ACCOUNT' END,
       requested_at,started_at,completed_at,NULL,due_at,CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,NULL
FROM deletion_operation;
