CREATE TABLE IF NOT EXISTS {prefix}accounts (
    player_uuid VARCHAR(36)     NOT NULL,
    player_name VARCHAR(64)     NOT NULL,
    currency_id VARCHAR(64)     NOT NULL,
    balance     DECIMAL(65, 18) NOT NULL DEFAULT 0,
    PRIMARY KEY (player_uuid, currency_id)
);

CREATE INDEX IF NOT EXISTS idx_{prefix}accounts_currency_balance
    ON {prefix}accounts (currency_id, balance DESC);