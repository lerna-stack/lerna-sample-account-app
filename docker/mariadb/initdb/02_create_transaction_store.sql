CREATE TABLE IF NOT EXISTS transaction_store(
    transaction_id VARCHAR(255) NOT NULL,
    transaction_type CHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    PRIMARY KEY (transaction_id)
);
