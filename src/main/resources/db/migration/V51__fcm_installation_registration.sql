ALTER TABLE app_push_registration DROP CONSTRAINT app_push_registration_provider_check;
ALTER TABLE app_push_registration ADD CONSTRAINT app_push_registration_provider_check
    CHECK (provider IN ('LOCAL','FCM','FCM_FID'));

ALTER TABLE app_push_delivery DROP CONSTRAINT app_push_delivery_provider_check;
ALTER TABLE app_push_delivery ADD CONSTRAINT app_push_delivery_provider_check
    CHECK (provider IN ('LOCAL','FCM','FCM_FID'));
