CREATE TABLE IF NOT EXISTS deposit_store(
    deposit_id BIGINT NOT NULL AUTO_INCREMENT,
    account_no VARCHAR(255) NOT NULL,
    amount BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(deposit_id)
);
