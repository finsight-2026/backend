CREATE INDEX app_session_expiry_idx ON app_session(expires_at);
CREATE INDEX admin_session_expiry_idx ON admin_session(expires_at);
CREATE INDEX admin_session_idle_idx ON admin_session(last_interaction_at);
CREATE INDEX admin_auth_challenge_expiry_idx ON admin_auth_challenge(expires_at);
CREATE INDEX app_password_reset_expiry_idx ON app_password_reset(expires_at);
CREATE INDEX signup_email_challenge_expiry_idx ON signup_email_challenge(sent_at);
CREATE INDEX app_login_request_expiry_idx ON app_login_request(requested_at);
CREATE INDEX admin_login_request_expiry_idx ON admin_login_request(requested_at);
