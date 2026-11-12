CREATE TABLE operating_settings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    version bigint NOT NULL DEFAULT 0,
    comparison_metrics text[] NOT NULL DEFAULT ARRAY['YFSI_MEAN','EXTERNAL_AREA_MEAN','RESOURCE_DOMAIN_MEAN','PERCEIVED_STRESS_MEAN']
        CHECK (comparison_metrics <@ ARRAY['YFSI_MEAN','EXTERNAL_AREA_MEAN','RESOURCE_DOMAIN_MEAN','PERCEIVED_STRESS_MEAN']),
    crm_paused boolean NOT NULL DEFAULT false,
    automatic_push_paused boolean NOT NULL DEFAULT false,
    crm_resumed_at timestamptz,
    automatic_push_resumed_at timestamptz,
    reminder_cap integer NOT NULL DEFAULT 1 CHECK (reminder_cap BETWEEN 0 AND 1),
    total_cap integer NOT NULL DEFAULT 2 CHECK (total_cap BETWEEN 0 AND 2 AND total_cap>=reminder_cap),
    funnel_check numeric NOT NULL DEFAULT 1.8 CHECK (funnel_check BETWEEN 0 AND 100),
    funnel_priority numeric NOT NULL DEFAULT 2.5 CHECK (funnel_priority BETWEEN funnel_check AND 100),
    operator_id uuid REFERENCES admin_account(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid REFERENCES admin_account(id) ON DELETE SET NULL
);
INSERT INTO operating_settings(singleton) VALUES (true);

CREATE FUNCTION notification_slot_available(p_user uuid,p_day date,p_category text)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT count(s.notification_id)<o.total_cap
        AND CASE p_category
            WHEN 'REMINDER' THEN count(*) FILTER(WHERE s.category='REMINDER')<o.reminder_cap
            WHEN 'ECONOMY' THEN count(*) FILTER(WHERE s.category='ECONOMY')=0
            ELSE true
        END
    FROM operating_settings o
    LEFT JOIN app_notification_local_slot s ON s.user_id=p_user AND s.delivery_day=p_day
    WHERE o.singleton
    GROUP BY o.total_cap,o.reminder_cap
$$;
