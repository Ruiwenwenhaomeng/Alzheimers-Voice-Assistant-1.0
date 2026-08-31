USE alz_system;

CREATE TABLE IF NOT EXISTS assistant_conversation (
    id CHAR(36) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_key VARCHAR(128) NOT NULL,
    title VARCHAR(120) NOT NULL DEFAULT '新对话',
    user_turn_count INT NOT NULL DEFAULT 0,
    summary_up_to_turn INT NOT NULL DEFAULT 0,
    rolling_summary TEXT NOT NULL,
    generation_status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    generation_started_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_assistant_conversation_owner_time (owner_type, owner_key, updated_at),
    CONSTRAINT chk_assistant_conversation_owner CHECK (owner_type IN ('USER','ANONYMOUS')),
    CONSTRAINT chk_assistant_conversation_turns CHECK (user_turn_count BETWEEN 0 AND 100),
    CONSTRAINT chk_assistant_conversation_summary_turn CHECK (summary_up_to_turn BETWEEN 0 AND user_turn_count),
    CONSTRAINT chk_assistant_conversation_status CHECK (generation_status IN ('IDLE','GENERATING'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS assistant_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id CHAR(36) NOT NULL,
    turn_no INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    title VARCHAR(160) NULL,
    intent VARCHAR(80) NULL,
    urgent BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_assistant_message_turn_role (conversation_id, turn_no, role),
    KEY idx_assistant_message_conversation (conversation_id, turn_no, id),
    CONSTRAINT fk_assistant_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant_conversation (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_message_turn CHECK (turn_no BETWEEN 1 AND 100),
    CONSTRAINT chk_assistant_message_role CHECK (role IN ('USER','ASSISTANT'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS assistant_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id CHAR(36) NOT NULL,
    from_turn INT NOT NULL,
    to_turn INT NOT NULL,
    summary TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_assistant_memory_block (conversation_id, from_turn, to_turn),
    CONSTRAINT fk_assistant_memory_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant_conversation (id) ON DELETE CASCADE,
    CONSTRAINT chk_assistant_memory_range CHECK (from_turn >= 1 AND to_turn >= from_turn AND to_turn <= 100)
) ENGINE=InnoDB;
