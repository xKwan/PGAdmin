-- pgweb audit schema (ADR-0005, docs/audit-policy.md). Applied by the migrator role (pgweb_migrator), never by the
-- runtime role. Placeholders: ${app_role} (INSERT only) and ${reader_role} (named auditors, SELECT only).
--
-- Integrity design:
--   * occurred_at comes from the server clock (column default); the app role has no INSERT privilege on it, so it
--     can't backdate events. seq, prev_hash and row_hash are set by a trigger under a lock on audit.chain_head,
--     so every row is chained to the previous one in commit order.
--   * row_hash = sha256(prev_hash || canonical), where canonical is the row as jsonb text without the two hash
--     columns, rendered with TimeZone=UTC. The verifier (pgweb.audit.ChainVerifier) recomputes it independently.
--   * UPDATE, DELETE and TRUNCATE are rejected by triggers; the app role holds INSERT only.
--   * Superusers can still disable triggers: on a cluster they administer, the trail is tamper-evident (the chain
--     breaks), not tamper-proof (threat T21).

CREATE SCHEMA IF NOT EXISTS audit;
REVOKE ALL ON SCHEMA audit FROM PUBLIC;

CREATE TABLE audit.chain_head (
    id        integer PRIMARY KEY CHECK (id = 1),
    seq       bigint  NOT NULL,
    row_hash  bytea   NOT NULL
);
INSERT INTO audit.chain_head (id, seq, row_hash) VALUES (1, 0, decode(repeat('00', 32), 'hex'));

CREATE TABLE audit.event (
    seq               bigint      NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    event_id          uuid        NOT NULL,
    event_type        text        NOT NULL CHECK (event_type ~ '^[a-z][a-z_]{1,62}$'),
    windows_user      text,
    user_sid          text        CHECK (user_sid IS NULL OR user_sid ~ '^S-1-[0-9-]+$'),
    data_admin        boolean,
    client_ip         text,
    request_id        text,
    app_session_hash  text,
    db_session_id     text,
    execution_id      uuid,
    intent_id         uuid,
    profile           text        CHECK (profile IS NULL OR profile IN ('internal', 'external')),
    database_name     text,
    pg_session_user   text,
    backend_pid       integer,
    statement_class   text,
    sql_normalised    text,
    sql_hmac          text        CHECK (sql_hmac IS NULL OR sql_hmac ~ '^[0-9a-f]{64}$'),
    sql_length        integer,
    parse_status      text,
    outcome           text,
    cancel_outcome    text,
    sqlstate          text        CHECK (sqlstate IS NULL OR sqlstate ~ '^[0-9A-Z]{5}$'),
    row_count         bigint,
    truncated         boolean,
    duration_ms       bigint,
    tx_before         text,
    tx_after          text,
    started_at        timestamptz,
    ended_at          timestamptz,
    details           jsonb,
    prev_hash         bytea       NOT NULL,
    row_hash          bytea       NOT NULL,
    PRIMARY KEY (seq, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit.event_default PARTITION OF audit.event DEFAULT;

CREATE INDEX event_occurred_at_idx ON audit.event (occurred_at);
CREATE INDEX event_user_sid_idx ON audit.event (user_sid, occurred_at);
CREATE INDEX event_execution_idx ON audit.event (execution_id) WHERE execution_id IS NOT NULL;
CREATE INDEX event_intent_idx ON audit.event (intent_id) WHERE intent_id IS NOT NULL;

-- Chain every inserted row. SECURITY DEFINER: the app role can't touch chain_head itself.
CREATE FUNCTION audit.chain_event() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, audit
    SET TimeZone = 'UTC'
AS $$
DECLARE
    head audit.chain_head%ROWTYPE;
BEGIN
    SELECT * INTO head FROM audit.chain_head WHERE id = 1 FOR UPDATE;
    NEW.seq := head.seq + 1;
    NEW.prev_hash := head.row_hash;
    NEW.row_hash := decode(repeat('00', 32), 'hex');
    NEW.row_hash := sha256(NEW.prev_hash || convert_to(((to_jsonb(NEW) - 'prev_hash') - 'row_hash')::text, 'UTF8'));
    UPDATE audit.chain_head SET seq = NEW.seq, row_hash = NEW.row_hash WHERE id = 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER event_chain BEFORE INSERT ON audit.event FOR EACH ROW EXECUTE FUNCTION audit.chain_event();

CREATE FUNCTION audit.reject_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'audit records are append-only (% rejected)', TG_OP USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER event_no_update BEFORE UPDATE OR DELETE ON audit.event FOR EACH ROW EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER event_no_truncate BEFORE TRUNCATE ON audit.event FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER chain_head_no_delete BEFORE DELETE ON audit.chain_head FOR EACH ROW EXECUTE FUNCTION audit.reject_change();
CREATE TRIGGER chain_head_no_truncate BEFORE TRUNCATE ON audit.chain_head FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_change();

-- Monthly partitions, created ahead of time. Callable by the app role (only creates partitions; never drops).
CREATE FUNCTION audit.ensure_partitions(months_ahead integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, audit
    SET TimeZone = 'UTC'
AS $$
DECLARE
    m       date;
    created integer := 0;
    name    text;
BEGIN
    IF months_ahead < 0 OR months_ahead > 36 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 36';
    END IF;
    FOR i IN 0 .. months_ahead LOOP
        m := (date_trunc('month', clock_timestamp()) + make_interval(months => i))::date;
        name := format('event_y%sm%s', to_char(m, 'YYYY'), to_char(m, 'MM'));
        IF to_regclass('audit.' || name) IS NULL THEN
            EXECUTE format('CREATE TABLE audit.%I PARTITION OF audit.event FOR VALUES FROM (%L) TO (%L)',
                           name, m::timestamptz, (m + interval '1 month')::timestamptz);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

SELECT audit.ensure_partitions(12);

-- Privileges. The app role may insert only the descriptive columns; seq, occurred_at and the hashes are the
-- server's. No SELECT for the app role (inserts never use RETURNING).
REVOKE ALL ON ALL TABLES IN SCHEMA audit FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.chain_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.reject_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.ensure_partitions(integer) FROM PUBLIC;

GRANT USAGE ON SCHEMA audit TO ${app_role}, ${reader_role};
GRANT INSERT (event_id, event_type, windows_user, user_sid, data_admin, client_ip, request_id, app_session_hash,
              db_session_id, execution_id, intent_id, profile, database_name, pg_session_user, backend_pid,
              statement_class, sql_normalised, sql_hmac, sql_length, parse_status, outcome, cancel_outcome, sqlstate,
              row_count, truncated, duration_ms, tx_before, tx_after, started_at, ended_at, details)
    ON audit.event TO ${app_role};
GRANT EXECUTE ON FUNCTION audit.ensure_partitions(integer) TO ${app_role};
GRANT SELECT ON audit.event, audit.chain_head TO ${reader_role};
