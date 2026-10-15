-- pgweb audit database bootstrap (ADR-0005). Run ONCE by a DBA, as a superuser, on the dedicated audit cluster
-- (separate from 5432/5433). Passwords are psql variables, so they never appear in this file:
--
--   psql -h <audit-host> -p <port> -U postgres -d postgres ^
--        -v migrator_password="<generated>" -v app_password="<generated>" ^
--        -f deploy\sql\audit-bootstrap.sql
--
-- Afterwards: put the passwords into pgweb.properties as DPAPI secrets (deploy\windows\Protect-PgwebSecret.ps1),
-- run `java -jar pgweb.jar migrate --config ...`, and grant pgweb_audit_reader to named auditors only.

\set ON_ERROR_STOP on

-- Owner of the audit schema; used only by `pgweb migrate` and the chain verifier, never by the running service.
CREATE ROLE pgweb_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'migrator_password';
-- The running service: INSERT into audit.event only (granted by the migration).
CREATE ROLE pgweb_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 10 PASSWORD :'app_password';
-- Named auditors are made members of this role (GRANT pgweb_audit_reader TO "CORP\auditor1").
CREATE ROLE pgweb_audit_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

CREATE DATABASE pgweb_audit OWNER pgweb_migrator ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE pgweb_audit FROM PUBLIC;
GRANT CONNECT ON DATABASE pgweb_audit TO pgweb_app, pgweb_audit_reader, pgweb_migrator;

\connect pgweb_audit
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- Statement timeout as a backstop for the runtime role (the service also sets its own timeouts).
ALTER ROLE pgweb_app IN DATABASE pgweb_audit SET statement_timeout = '10s';
