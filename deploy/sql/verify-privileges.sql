-- Least-privilege verification for pgweb's own roles and the audit database (plan Phase 11, threat T21/T24/T27).
-- Run as a superuser or the DBA, connected to the audit database:
--   psql -X -v ON_ERROR_STOP=1 -h <host> -p <port> -U <dba> -d pgweb_audit -f deploy/sql/verify-privileges.sql
-- Prints one row per check and fails (exit code 3 from psql) if any check fails. Changes nothing.
\set ON_ERROR_STOP on

CREATE TEMP TABLE pgweb_privilege_check (seq serial, check_name text, ok boolean, detail text);

INSERT INTO pgweb_privilege_check (check_name, ok, detail)
-- 1. The three roles exist and have no powerful attributes.
SELECT 'role ' || r || ' exists', EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r), NULL
FROM unnest(ARRAY['pgweb_app', 'pgweb_migrator', 'pgweb_audit_reader']) r
UNION ALL
SELECT 'role ' || rolname || ' has no superuser, createdb, createrole, replication or bypassrls',
       NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls), NULL
FROM pg_roles WHERE rolname IN ('pgweb_app', 'pgweb_migrator', 'pgweb_audit_reader')
UNION ALL
SELECT 'pgweb_audit_reader cannot log in', NOT rolcanlogin, NULL FROM pg_roles WHERE rolname = 'pgweb_audit_reader'
UNION ALL
SELECT 'pgweb_app has a connection limit', rolconnlimit BETWEEN 1 AND 50, 'limit ' || rolconnlimit FROM pg_roles WHERE rolname = 'pgweb_app'
UNION ALL
-- 2. No membership in predefined roles that read, write or reach the server's files or programs.
SELECT r || ' is not a member of ' || p, NOT pg_has_role(r, p, 'MEMBER'), NULL
FROM unnest(ARRAY['pgweb_app', 'pgweb_migrator', 'pgweb_audit_reader']) r,
     unnest(ARRAY['pg_read_all_data', 'pg_write_all_data', 'pg_read_server_files', 'pg_write_server_files',
                  'pg_execute_server_program', 'pg_signal_backend', 'pg_checkpoint']) p
UNION ALL
-- 3. Ownership: the migrator owns the database and the audit schema; the runtime role owns nothing.
SELECT 'pgweb_migrator owns database pgweb_audit',
       (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()) = 'pgweb_migrator', current_database()
UNION ALL
SELECT 'pgweb_migrator owns schema audit', (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'audit') = 'pgweb_migrator', NULL
UNION ALL
SELECT 'pgweb_app owns no relations', NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner = 'pgweb_app'::regrole), NULL
UNION ALL
SELECT 'pgweb_app owns no functions', NOT EXISTS (SELECT 1 FROM pg_proc WHERE proowner = 'pgweb_app'::regrole), NULL
UNION ALL
-- 4. Database and schema access: nothing for PUBLIC; no CREATE for the runtime roles.
SELECT 'PUBLIC cannot connect to pgweb_audit', NOT has_database_privilege('public', current_database(), 'CONNECT'), NULL
UNION ALL
SELECT 'PUBLIC has no access to schema ' || s, NOT has_schema_privilege('public', s, 'USAGE') AND NOT has_schema_privilege('public', s, 'CREATE'), NULL
FROM unnest(ARRAY['audit', 'public']) s
UNION ALL
SELECT r || ' cannot create objects in schema ' || s, NOT has_schema_privilege(r, s, 'CREATE'), NULL
FROM unnest(ARRAY['pgweb_app', 'pgweb_audit_reader']) r, unnest(ARRAY['audit', 'public']) s
UNION ALL
SELECT r || ' cannot create schemas or temporary tables', NOT has_database_privilege(r, current_database(), 'CREATE')
       AND NOT has_database_privilege(r, current_database(), 'TEMP'), NULL
FROM unnest(ARRAY['pgweb_app', 'pgweb_audit_reader']) r
UNION ALL
-- 5. The audit trail: the runtime role may only append; the reader may only read; nobody may change history.
SELECT 'pgweb_app can INSERT event columns into audit.event', has_column_privilege('pgweb_app', 'audit.event', 'event_type', 'INSERT'), NULL
UNION ALL
-- The chain columns are set by the trigger only: the runtime role can't supply them (column-level INSERT grant).
SELECT 'pgweb_app cannot set audit.event.' || c, NOT has_column_privilege('pgweb_app', 'audit.event', c, 'INSERT'), NULL
FROM unnest(ARRAY['seq', 'occurred_at', 'prev_hash', 'row_hash']) c
UNION ALL
SELECT 'pgweb_app cannot ' || p || ' audit.event', NOT has_table_privilege('pgweb_app', 'audit.event', p), NULL
FROM unnest(ARRAY['SELECT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) p
UNION ALL
SELECT 'pgweb_app has no privilege on audit.chain_head',
       NOT has_table_privilege('pgweb_app', 'audit.chain_head', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE'), NULL
UNION ALL
SELECT 'pgweb_audit_reader can SELECT ' || t, has_table_privilege('pgweb_audit_reader', t, 'SELECT'), NULL
FROM unnest(ARRAY['audit.event', 'audit.chain_head']) t
UNION ALL
SELECT 'pgweb_audit_reader cannot ' || p || ' ' || t, NOT has_table_privilege('pgweb_audit_reader', t, p), NULL
FROM unnest(ARRAY['audit.event', 'audit.chain_head']) t, unnest(ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) p
UNION ALL
SELECT 'PUBLIC has no privilege on ' || c.oid::regclass, NOT has_table_privilege('public', c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE'), NULL
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'audit' AND c.relkind IN ('r', 'p')
UNION ALL
SELECT 'audit.event rejects UPDATE and DELETE (trigger)', EXISTS (
         SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
         WHERE t.tgrelid = 'audit.event'::regclass AND p.proname = 'reject_change' AND NOT t.tgisinternal), NULL
UNION ALL
SELECT 'audit.event rows are hash-chained (trigger)', EXISTS (
         SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
         WHERE t.tgrelid = 'audit.event'::regclass AND p.proname = 'chain_event' AND NOT t.tgisinternal), NULL
UNION ALL
SELECT 'audit functions are not executable by PUBLIC', NOT has_function_privilege('public', p.oid, 'EXECUTE'), p.proname
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'audit'
UNION ALL
SELECT 'SECURITY DEFINER audit functions have a fixed search_path', p.proconfig IS NOT NULL
       AND EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search\_path=%'), p.proname
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'audit' AND p.prosecdef
UNION ALL
-- 6. The runtime role's statements are bounded.
SELECT 'pgweb_app has a statement_timeout in pgweb_audit', EXISTS (
         SELECT 1 FROM pg_db_role_setting s JOIN pg_database d ON d.oid = s.setdatabase
         WHERE d.datname = current_database() AND s.setrole = 'pgweb_app'::regrole
           AND EXISTS (SELECT 1 FROM unnest(s.setconfig) c WHERE c LIKE 'statement\_timeout=%')), NULL;

SELECT seq, CASE WHEN ok THEN 'OK' ELSE 'FAIL' END AS result, check_name, detail FROM pgweb_privilege_check ORDER BY seq;

DO $$
DECLARE
    failed int;
BEGIN
    SELECT count(*) INTO failed FROM pgweb_privilege_check WHERE ok IS DISTINCT FROM true;
    IF failed > 0 THEN
        RAISE EXCEPTION 'pgweb privilege verification: % check(s) failed', failed;
    END IF;
    RAISE NOTICE 'pgweb privilege verification: all checks passed';
END $$;
