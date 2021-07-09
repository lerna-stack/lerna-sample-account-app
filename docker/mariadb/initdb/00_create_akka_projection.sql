-- Akka Projection Schema
-- https://doc.akka.io/docs/akka-projection/1.1.0/slick.html#schema

CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(4) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
);

CREATE INDEX projection_name_index ON akka_projection_offset_store (projection_name);
