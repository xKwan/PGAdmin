import { describe, expect, it } from 'vitest';
import * as gen from './sqlGen.js';

const HOSTILE = 'a"; DROP TABLE t; --';

describe('SQL generation for management (admin Phase 5)', () => {
  it('quotes names and literals so they stay names and literals', () => {
    expect(gen.createSchema({ name: HOSTILE })).toBe('CREATE SCHEMA "a""; DROP TABLE t; --";\n');
    expect(gen.createSchema({ name: 'sales', owner: 'Ada' })).toBe('CREATE SCHEMA sales AUTHORIZATION "Ada";\n');
    expect(gen.createSchema({ name: 'select' })).toBe('CREATE SCHEMA "select";\n');
    expect(gen.quoteLiteral("it's")).toBe("'it''s'");
    expect(gen.quoteLiteral('C:\\temp\\x')).toBe("E'C:\\\\temp\\\\x'");
    expect(gen.quoteLiteral("a\\'b")).toBe("E'a\\\\''b'");
    expect(() => gen.quoteLiteral('a\0b')).toThrow(gen.InputError);
    expect(gen.setComment({ kind: 'table', schema: 's', name: 't', comment: "Ada's; DROP" })).toBe("COMMENT ON TABLE s.t IS 'Ada''s; DROP';\n");
    expect(gen.setComment({ kind: 'view', schema: 's', name: 'v', comment: '' })).toBe('COMMENT ON VIEW s.v IS NULL;\n');
  });

  it('refuses names that cannot exist and says why', () => {
    expect(() => gen.createSchema({ name: '' })).toThrow('Name is required.');
    expect(() => gen.createSchema({ name: '   ' })).toThrow('Name is required.');
    expect(() => gen.createSchema({ name: 'x'.repeat(64) })).toThrow('longer than 63 bytes');
    expect(() => gen.createSchema({ name: 'é'.repeat(32) })).toThrow('longer than 63 bytes'); // 64 bytes in UTF-8
    expect(() => gen.createSchema({ name: 'a\0' })).toThrow('NUL');
    expect(gen.checkName('x'.repeat(63), 'Name')).toHaveLength(63);
  });

  it('creates tables with columns, defaults, a primary key and a comment', () => {
    const sql = gen.createTable({
      schema: 'shop',
      name: 'Orders',
      unlogged: true,
      comment: 'All orders',
      columns: [
        { name: 'id', type: 'bigint', notNull: true, primaryKey: true, default: '' },
        { name: 'placed at', type: 'timestamptz', default: 'now();' },
        { name: '', type: '' },
        { name: 'total', type: 'numeric(10,2)', primaryKey: false },
      ],
    });
    expect(sql).toBe(
      'CREATE UNLOGGED TABLE shop."Orders" (\n    id bigint NOT NULL,\n    "placed at" timestamptz DEFAULT now(),\n    total numeric(10,2),\n    PRIMARY KEY (id)\n);\n' +
        'COMMENT ON TABLE shop."Orders" IS \'All orders\';\n',
    );
    expect(() => gen.createTable({ schema: 's', name: 't', columns: [] })).toThrow('Columns: at least one is required.');
    expect(() => gen.createTable({ schema: 's', name: 't', columns: [{ name: 'a', type: 'int; DROP' }] })).toThrow("doesn't look like a type");
    expect(() => gen.createTable({ schema: 's', name: 't', columns: [{ name: 'a', type: 'int -- x' }] })).toThrow("doesn't look like a type");
    expect(gen.checkType('public."My type"[]')).toBe('public."My type"[]');
    expect(gen.checkType(' varchar(80) ')).toBe('varchar(80)');
  });

  it('changes columns', () => {
    const t = { schema: 's', table: 't' };
    expect(gen.addColumn({ ...t, column: { name: 'Note', type: 'text', notNull: true, default: "''" } })).toBe('ALTER TABLE s.t ADD COLUMN "Note" text NOT NULL DEFAULT \'\';\n');
    expect(gen.renameColumn({ ...t, column: 'a', newName: 'b' })).toBe('ALTER TABLE s.t RENAME COLUMN a TO b;\n');
    expect(gen.alterColumnType({ ...t, column: 'a', type: 'integer', using: 'a::integer' })).toBe('ALTER TABLE s.t ALTER COLUMN a TYPE integer USING a::integer;\n');
    expect(gen.alterColumnType({ ...t, column: 'a', type: 'text' })).toBe('ALTER TABLE s.t ALTER COLUMN a TYPE text;\n');
    expect(gen.setNotNull({ ...t, column: 'a', notNull: true })).toBe('ALTER TABLE s.t ALTER COLUMN a SET NOT NULL;\n');
    expect(gen.setNotNull({ ...t, column: 'a', notNull: false })).toBe('ALTER TABLE s.t ALTER COLUMN a DROP NOT NULL;\n');
    expect(gen.setDefault({ ...t, column: 'a', default: '0' })).toBe('ALTER TABLE s.t ALTER COLUMN a SET DEFAULT 0;\n');
    expect(gen.setDefault({ ...t, column: 'a', default: '' })).toBe('ALTER TABLE s.t ALTER COLUMN a DROP DEFAULT;\n');
    expect(gen.dropColumn({ ...t, column: 'a', cascade: true })).toBe('ALTER TABLE s.t DROP COLUMN a CASCADE;\n');
    expect(() => gen.setDefault({ ...t, column: 'a', default: ';' })).toThrow('Default is required.');
  });

  it('renames and drops relations of each kind', () => {
    expect(gen.renameRelation({ kind: 'materialized_view', schema: 's', name: 'm', newName: 'n' })).toBe('ALTER MATERIALIZED VIEW s.m RENAME TO n;\n');
    expect(gen.renameRelation({ schema: 's', name: 't', newName: 'u' })).toBe('ALTER TABLE s.t RENAME TO u;\n');
    expect(gen.dropRelation({ kind: 'sequence', schema: 's', name: 'q' })).toBe('DROP SEQUENCE s.q;\n');
    expect(gen.dropRelation({ kind: 'view', schema: 's', name: 'v', cascade: true })).toBe('DROP VIEW s.v CASCADE;\n');
    expect(() => gen.dropRelation({ kind: 'function', schema: 's', name: 'f' })).toThrow('Unknown kind');
    expect(() => gen.renameRelation({ kind: 'x', schema: 's', name: 'f', newName: 'g' })).toThrow('Unknown kind');
    expect(() => gen.setComment({ kind: 'x', schema: 's', name: 'f' })).toThrow('Unknown kind');
  });

  it('adds and drops constraints', () => {
    const t = { schema: 's', table: 't' };
    expect(gen.addConstraint({ ...t, type: 'primary_key', columns: ['id'] })).toBe('ALTER TABLE s.t ADD PRIMARY KEY (id);\n');
    expect(gen.addConstraint({ ...t, type: 'unique', name: 't_code', columns: ['code', 'Region'] })).toBe('ALTER TABLE s.t ADD CONSTRAINT t_code UNIQUE (code, "Region");\n');
    expect(gen.addConstraint({ ...t, type: 'check', expression: 'total >= 0;' })).toBe('ALTER TABLE s.t ADD CHECK (total >= 0);\n');
    expect(gen.addConstraint({ ...t, type: 'foreign_key', columns: ['customer_id'], refSchema: 's', refTable: 'customers', refColumns: ['id'], onDelete: 'cascade' })).toBe(
      'ALTER TABLE s.t ADD FOREIGN KEY (customer_id) REFERENCES s.customers (id) ON DELETE CASCADE;\n',
    );
    expect(gen.addConstraint({ ...t, type: 'foreign_key', columns: ['c'], refSchema: 's', refTable: 'u', refColumns: ['id'] })).toBe('ALTER TABLE s.t ADD FOREIGN KEY (c) REFERENCES s.u (id);\n');
    expect(() => gen.addConstraint({ ...t, type: 'foreign_key', columns: ['a', 'b'], refSchema: 's', refTable: 'u', refColumns: ['id'] })).toThrow('same number');
    expect(() => gen.addConstraint({ ...t, type: 'foreign_key', columns: ['a'], refSchema: 's', refTable: 'u', refColumns: ['id'], onDelete: 'explode' })).toThrow('ON DELETE');
    expect(() => gen.addConstraint({ ...t, type: 'unique', columns: [] })).toThrow('Columns: at least one');
    expect(() => gen.addConstraint({ ...t })).toThrow('Choose a kind');
    expect(gen.dropConstraint({ ...t, name: 'c' })).toBe('ALTER TABLE s.t DROP CONSTRAINT c;\n');
  });

  it('creates and drops indexes', () => {
    expect(gen.createIndex({ schema: 's', table: 't', columns: ['a', 'b'] })).toBe('CREATE INDEX ON s.t (a, b);\n');
    expect(gen.createIndex({ schema: 's', table: 't', name: 'i', columns: ['doc'], method: 'gin', unique: false, concurrently: true })).toBe('CREATE INDEX CONCURRENTLY i ON s.t USING gin (doc);\n');
    expect(gen.createIndex({ schema: 's', table: 't', columns: ['a'], unique: true })).toBe('CREATE UNIQUE INDEX ON s.t (a);\n');
    expect(() => gen.createIndex({ schema: 's', table: 't', columns: ['a'], method: 'rtree' })).toThrow('Unknown index method');
    expect(gen.dropIndex({ schema: 's', name: 'i', cascade: true })).toBe('DROP INDEX s.i CASCADE;\n');
    expect(gen.dropIndex({ schema: 's', name: 'i', concurrently: true, cascade: true })).toBe('DROP INDEX CONCURRENTLY s.i;\n');
  });

  it('creates views and sequences', () => {
    expect(gen.createView({ schema: 's', name: 'v', query: 'SELECT 1;  ', orReplace: true })).toBe('CREATE OR REPLACE VIEW s.v AS\nSELECT 1;\n');
    expect(gen.createView({ schema: 's', name: 'v', query: 'SELECT 1', materialized: true, orReplace: true })).toBe('CREATE MATERIALIZED VIEW s.v AS\nSELECT 1;\n');
    expect(() => gen.createView({ schema: 's', name: 'v', query: '' })).toThrow('Query is required.');
    expect(gen.refreshMaterializedView({ schema: 's', name: 'm', concurrently: true })).toBe('REFRESH MATERIALIZED VIEW CONCURRENTLY s.m;\n');
    expect(gen.createSequence({ schema: 's', name: 'q' })).toBe('CREATE SEQUENCE s.q;\n');
    expect(gen.createSequence({ schema: 's', name: 'q', increment: '-1', min: '-100', max: '0', start: '0', cache: '5', cycle: true })).toBe(
      'CREATE SEQUENCE s.q\n    INCREMENT BY -1\n    MINVALUE -100\n    MAXVALUE 0\n    START WITH 0\n    CACHE 5\n    CYCLE;\n',
    );
    expect(() => gen.createSequence({ schema: 's', name: 'q', increment: '0' })).toThrow("can't be 0");
    expect(() => gen.createSequence({ schema: 's', name: 'q', start: '1.5' })).toThrow('whole number');
    expect(() => gen.createSequence({ schema: 's', name: 'q', cache: '0' })).toThrow('whole number');
    expect(gen.restartSequence({ schema: 's', name: 'q' })).toBe('ALTER SEQUENCE s.q RESTART;\n');
    expect(gen.restartSequence({ schema: 's', name: 'q', value: '100' })).toBe('ALTER SEQUENCE s.q RESTART WITH 100;\n');
  });

  it('never touches the connected database', () => {
    expect(gen.createDatabase({ name: 'Sales 2027', owner: 'ada', template: 'template0', encoding: 'UTF8' })).toBe('CREATE DATABASE "Sales 2027" OWNER ada TEMPLATE template0 ENCODING \'UTF8\';\n');
    expect(gen.createDatabase({ name: 'x' })).toBe('CREATE DATABASE x;\n');
    expect(() => gen.createDatabase({ name: 'x', encoding: "UTF8' x" })).toThrow('encoding name');
    expect(gen.renameDatabase({ name: 'old', newName: 'new', current: 'sales' })).toBe('ALTER DATABASE old RENAME TO new;\n');
    expect(gen.dropDatabase({ name: 'old', current: 'sales' })).toBe('DROP DATABASE old;\n');
    expect(() => gen.dropDatabase({ name: 'sales', current: 'sales' })).toThrow('connected to');
    expect(() => gen.renameDatabase({ name: 'sales', newName: 'x', current: 'sales' })).toThrow('connected to');
    expect(gen.alterDatabaseOwner({ name: 'sales', owner: 'ada' })).toBe('ALTER DATABASE sales OWNER TO ada;\n');
  });

  it('manages roles without passwords or superuser', () => {
    expect(gen.createRole({ name: 'reader' })).toBe('CREATE ROLE reader;\n');
    expect(gen.createRole({ name: 'App User', login: true, createdb: true, createrole: false, inherit: false, connectionLimit: '5', inRoles: ['readers', 'Writers'], comment: 'svc' })).toBe(
      'CREATE ROLE "App User" LOGIN NOINHERIT CREATEDB CONNECTION LIMIT 5 IN ROLE readers, "Writers";\nCOMMENT ON ROLE "App User" IS \'svc\';\n',
    );
    expect(gen.alterRole({ name: 'r', login: false, createrole: true, connectionLimit: '-1' })).toBe('ALTER ROLE r NOLOGIN CREATEROLE CONNECTION LIMIT -1;\n');
    expect(() => gen.alterRole({ name: 'r' })).toThrow('at least one change');
    expect(() => gen.alterRole({ name: 'r', connectionLimit: '-2' })).toThrow('whole number');
    expect(gen.renameRole({ name: 'a', newName: 'b' })).toBe('ALTER ROLE a RENAME TO b;\n');
    expect(gen.grantRole({ role: 'readers', member: 'ada', admin: true })).toBe('GRANT readers TO ada WITH ADMIN OPTION;\n');
    expect(gen.grantRole({ role: 'readers', member: 'ada' })).toBe('GRANT readers TO ada;\n');
    expect(gen.revokeRole({ role: 'readers', member: 'ada' })).toBe('REVOKE readers FROM ada;\n');
    expect(gen.dropRole({ name: 'old' })).toBe('DROP ROLE old;\n');
    const all = Object.values(gen).filter((f) => typeof f === 'function').map((f) => f.toString()).join('\n');
    expect(all).not.toMatch(/PASSWORD|SUPERUSER|REPLICATION|BYPASSRLS/);
  });

  it('grants and revokes privileges, only known ones', () => {
    expect(gen.grantTable({ schema: 's', name: 't', privileges: ['SELECT', 'UPDATE'], grantee: 'ada', grantOption: true })).toBe('GRANT SELECT, UPDATE ON TABLE s.t TO ada WITH GRANT OPTION;\n');
    expect(gen.grantTable({ schema: 's', name: 't', privileges: ['SELECT', 'ALL; DROP'], grantee: 'public' })).toBe('GRANT SELECT ON TABLE s.t TO PUBLIC;\n');
    expect(() => gen.grantTable({ schema: 's', name: 't', privileges: [], grantee: 'ada' })).toThrow('at least one privilege');
    expect(gen.revokeTable({ schema: 's', name: 't', privileges: ['DELETE'], grantee: '"PUBLIC"', cascade: true })).toBe('REVOKE DELETE ON TABLE s.t FROM """PUBLIC""" CASCADE;\n');
    expect(gen.grantSchemaUsage({ name: 's', grantee: 'readers' })).toBe('GRANT USAGE ON SCHEMA s TO readers;\n');
  });

  it('splits comma lists', () => {
    expect(gen.splitList(' a, b ,,c ')).toEqual(['a', 'b', 'c']);
    expect(gen.splitList(undefined)).toEqual([]);
  });
});
