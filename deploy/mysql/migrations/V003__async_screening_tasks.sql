USE alz_system;

CREATE TABLE IF NOT EXISTS screening_task (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    audio_record_id BIGINT NOT NULL,
    audio_name VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    progress TINYINT NOT NULL DEFAULT 0,
    model_version VARCHAR(128) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    trace_id CHAR(36) NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_screening_task_user_idempotency (user_id, idempotency_key),
    UNIQUE KEY uk_screening_task_audio (audio_record_id),
    KEY idx_screening_task_user_time (user_id, requested_at),
    KEY idx_screening_task_status_time (status, updated_at),
    CONSTRAINT fk_screening_task_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_screening_task_audio FOREIGN KEY (audio_record_id) REFERENCES audio_record (id) ON DELETE CASCADE,
    CONSTRAINT chk_screening_task_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS screening_task_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    storage_uri VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_screening_artifact_version (task_id, artifact_type, content_version),
    CONSTRAINT fk_screening_artifact_task FOREIGN KEY (task_id) REFERENCES screening_task (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_event (
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_publish (status, next_attempt_at, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS consumed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'diagnosis_report'
       AND column_name = 'screening_task_id') = 0,
    'ALTER TABLE diagnosis_report ADD COLUMN screening_task_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL AFTER screening_id',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'diagnosis_report'
       AND index_name = 'uk_diagnosis_report_task') = 0,
    'ALTER TABLE diagnosis_report ADD UNIQUE KEY uk_diagnosis_report_task (screening_task_id)',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.referential_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'diagnosis_report'
       AND constraint_name = 'fk_diagnosis_report_task') = 0,
    'ALTER TABLE diagnosis_report ADD CONSTRAINT fk_diagnosis_report_task FOREIGN KEY (screening_task_id) REFERENCES screening_task (id) ON DELETE SET NULL',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pdf_report'
       AND column_name = 'screening_task_id') = 0,
    'ALTER TABLE pdf_report ADD COLUMN screening_task_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL AFTER id',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pdf_report'
       AND column_name = 'file_sha256') = 0,
    'ALTER TABLE pdf_report ADD COLUMN file_sha256 CHAR(64) NULL AFTER pdf_name',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pdf_report'
       AND column_name = 'file_size') = 0,
    'ALTER TABLE pdf_report ADD COLUMN file_size BIGINT NULL AFTER file_sha256',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'pdf_report'
       AND index_name = 'uk_pdf_report_task') = 0,
    'ALTER TABLE pdf_report ADD UNIQUE KEY uk_pdf_report_task (screening_task_id)',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;

SET @v003_sql = IF(
    (SELECT COUNT(*) FROM information_schema.referential_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'pdf_report'
       AND constraint_name = 'fk_pdf_report_task') = 0,
    'ALTER TABLE pdf_report ADD CONSTRAINT fk_pdf_report_task FOREIGN KEY (screening_task_id) REFERENCES screening_task (id) ON DELETE SET NULL',
    'DO 0'
);
PREPARE v003_stmt FROM @v003_sql;
EXECUTE v003_stmt;
DEALLOCATE PREPARE v003_stmt;
