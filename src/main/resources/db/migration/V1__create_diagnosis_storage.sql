CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE,
    password_hash text,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWAL_PENDING')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE survey_attempt (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    survey_version text NOT NULL,
    formula_version text NOT NULL,
    started_at timestamptz NOT NULL,
    aborted_at timestamptz,
    last_question smallint CHECK (last_question BETWEEN 1 AND 30),
    CHECK ((aborted_at IS NULL) = (last_question IS NULL))
);

CREATE TABLE diagnosis (
    attempt_id uuid PRIMARY KEY REFERENCES survey_attempt(id) ON DELETE CASCADE,
    id uuid NOT NULL UNIQUE,
    completed_at timestamptz NOT NULL,
    representative_type text NOT NULL CHECK (representative_type IN
        ('DEBT', 'INCOME', 'EMERGENCY_FUND', 'FIXED_COST', 'FUTURE', 'FAMILY')),
    score_snapshot jsonb NOT NULL CHECK (jsonb_typeof(score_snapshot) = 'object')
);

CREATE TABLE diagnosis_answer (
    diagnosis_id uuid NOT NULL REFERENCES diagnosis(id) ON DELETE CASCADE,
    question_no smallint NOT NULL CHECK (question_no BETWEEN 1 AND 30),
    response smallint NOT NULL CHECK (response BETWEEN 1 AND 5),
    PRIMARY KEY (diagnosis_id, question_no)
);

CREATE INDEX survey_attempt_user_idx ON survey_attempt(user_id);
CREATE INDEX diagnosis_completed_at_idx ON diagnosis(completed_at DESC, id DESC);
