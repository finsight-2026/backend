ALTER TABLE app_push_delivery DROP CONSTRAINT app_push_delivery_state_check;
ALTER TABLE app_push_delivery ADD CONSTRAINT app_push_delivery_state_check
    CHECK (state IN ('WAITING','SENDING','SIMULATED','ACCEPTED','CANCELED','EXPIRED','UNKNOWN','INVALID','REJECTED'));
