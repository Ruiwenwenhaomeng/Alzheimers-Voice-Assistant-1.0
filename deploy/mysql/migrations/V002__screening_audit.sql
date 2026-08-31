USE alz_system;

ALTER TABLE audio_record
    ADD COLUMN consent_version VARCHAR(64) NULL AFTER image_name,
    ADD COLUMN consent_time DATETIME NULL AFTER consent_version,
    ADD COLUMN task_type VARCHAR(40) NULL AFTER consent_time;

ALTER TABLE diagnosis_report
    ADD COLUMN screening_id VARCHAR(64) NULL AFTER report,
    ADD COLUMN screening_status VARCHAR(24) NULL AFTER screening_id,
    ADD COLUMN risk_level VARCHAR(20) NULL AFTER screening_status,
    ADD COLUMN risk_score DECIMAL(8,7) NULL AFTER risk_level,
    ADD COLUMN quality_passed BOOLEAN NULL AFTER risk_score,
    ADD COLUMN quality_issues JSON NULL AFTER quality_passed,
    ADD COLUMN feature_highlights JSON NULL AFTER quality_issues,
    ADD COLUMN model_version VARCHAR(128) NULL AFTER feature_highlights,
    ADD COLUMN disclaimer_version VARCHAR(64) NULL AFTER model_version;

CREATE UNIQUE INDEX uk_diagnosis_report_screening
    ON diagnosis_report (screening_id);
