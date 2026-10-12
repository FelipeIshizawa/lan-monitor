CREATE TABLE devices (
 device_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 segment VARCHAR(100) NOT NULL,
 mac BINARY(6) NOT NULL,
 current_ip VARBINARY(16) NOT NULL,
 hostname VARCHAR(253),
 status VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
 failures INT NOT NULL DEFAULT 0,
 first_seen_at DATETIME(6) NOT NULL,
 last_seen_at DATETIME(6) NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 state_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_mac(segment, mac),
 KEY ix_status(segment,status),
 CHECK(status IN ('UNKNOWN','UP','DOWN'))
) ENGINE=InnoDB;
CREATE TABLE agents (
 agent_id VARCHAR(100) PRIMARY KEY,
 segment VARCHAR(100) NOT NULL UNIQUE,
 last_heartbeat_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE ingestion_batches (
 agent_id VARCHAR(100) NOT NULL,
 batch_id CHAR(36) NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 received_at DATETIME(6) NOT NULL,
 PRIMARY KEY(agent_id,batch_id),
 KEY ix_retention(received_at)
) ENGINE=InnoDB;
CREATE TABLE latency_samples (
 device_id BIGINT UNSIGNED NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 batch_id CHAR(36) NOT NULL,
 sample_no INT NOT NULL,
 target_ip VARBINARY(16) NOT NULL,
 probe_kind VARCHAR(4) NOT NULL,
 result_code VARCHAR(16) NOT NULL,
 latency_us INT UNSIGNED,
 PRIMARY KEY(device_id,observed_at,batch_id,sample_no),
 CHECK(probe_kind IN ('ICMP','DNS')),
 CHECK(result_code IN ('OK','TIMEOUT','ERROR')),
 CHECK((result_code='OK' AND latency_us IS NOT NULL) OR (result_code<>'OK' AND latency_us IS NULL))
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(observed_at) (
 PARTITION p_before_202610 VALUES LESS THAN ('2026-10-01'),
 PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
 PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
 PARTITION p_future VALUES LESS THAN (MAXVALUE)
);
CREATE TABLE device_events (
 device_id BIGINT UNSIGNED NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 state_version BIGINT NOT NULL,
 previous_status VARCHAR(8) NOT NULL,
 new_status VARCHAR(8) NOT NULL,
 reason VARCHAR(64) NOT NULL,
 PRIMARY KEY(device_id,occurred_at,state_version),
 KEY ix_timeline(occurred_at)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(occurred_at) (
 PARTITION p_before_202610 VALUES LESS THAN ('2026-10-01'),
 PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
 PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
 PARTITION p_future VALUES LESS THAN (MAXVALUE)
);
