ALTER TABLE campaign_target_preview ADD COLUMN purpose text NOT NULL DEFAULT 'CAMPAIGN'
    CHECK (purpose IN ('CAMPAIGN', 'MASKED_EXPORT'));
