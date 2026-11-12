CREATE TABLE app_foreground_day (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    activity_date date NOT NULL,
    PRIMARY KEY (user_id, activity_date)
);
CREATE INDEX app_foreground_day_date ON app_foreground_day(activity_date, user_id);

CREATE TABLE app_foreground_observation (
    singleton boolean PRIMARY KEY CHECK (singleton),
    first_full_date date NOT NULL
);
