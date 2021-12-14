CREATE TABLE IF NOT EXISTS comment_store(
    comment_id VARCHAR(255) NOT NULL,
    comment VARCHAR(255) NOT NULL,
    PRIMARY KEY (comment_id),
    FOREIGN KEY(comment_id) references transaction_store(transaction_id)
)
