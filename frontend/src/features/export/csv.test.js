import { describe, expect, it } from 'vitest';
import { csvCell, CsvError, IMPORT_MAX_ROWS, insertScript, parseCsv, toCsv, toJson } from './csv.js';

const cols = [{ name: 'id' }, { name: 'Name' }, { name: 'note' }];

describe('export (admin Phase 7)', () => {
  it('writes CSV that keeps NULL and the empty string apart', () => {
    expect(toCsv(cols, [['1', 'Ada', null], ['2', '', 'say "hi", then\nleave']])).toBe('id,Name,note\r\n1,Ada,\r\n2,"","say ""hi"", then\nleave"\r\n');
  });

  it("neutralises text a spreadsheet would run as a formula, but not numbers", () => {
    expect(csvCell('=HYPERLINK("x")')).toBe('"\'=HYPERLINK(""x"")"');
    expect(csvCell('+44 20 7946')).toBe("'+44 20 7946");
    expect(csvCell('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvCell('-x')).toBe("'-x");
    expect(csvCell('\tx')).toBe("'\tx");
    expect(csvCell('-5')).toBe('-5');
    expect(csvCell('+1.5e3')).toBe('+1.5e3');
    expect(csvCell('-.5')).toBe('-.5');
    expect(csvCell('a=b')).toBe('a=b');
  });

  it('writes JSON objects keyed by column', () => {
    expect(JSON.parse(toJson(cols, [['1', 'Ada', null]]))).toEqual([{ id: '1', Name: 'Ada', note: null }]);
  });
});

describe('import (admin Phase 7)', () => {
  it('parses quoted fields, doubled quotes, CRLF, a BOM, NULL and empty strings', () => {
    const { header, rows } = parseCsv('﻿id,Name,note\r\n1,"O""Brien, Pat",\r\n2,"",x\n3,"multi\r\nline","a"\r\n');
    expect(header).toEqual(['id', 'Name', 'note']);
    expect(rows).toEqual([
      ['1', 'O"Brien, Pat', null],
      ['2', '', 'x'],
      ['3', 'multi\r\nline', 'a'],
    ]);
    expect(parseCsv('a\n1').rows).toEqual([['1']]);
    expect(parseCsv('a,b\n,').rows).toEqual([[null, null]]);
  });

  it('says what is wrong with a file', () => {
    expect(() => parseCsv('')).toThrow('empty');
    expect(() => parseCsv('a,b\n1')).toThrow('Line 2 has 1 fields; the header has 2.');
    expect(() => parseCsv('a,\n1,2')).toThrow('Column 2 of the header has no name.');
    expect(() => parseCsv('a,a\n1,2')).toThrow('names a column twice');
    expect(() => parseCsv('a\n"open')).toThrow('ends inside a quoted field');
    expect(() => parseCsv('a\n"x"y')).toThrow('text after a closing quote');
    expect(() => parseCsv('a\nx\0')).toThrow(CsvError);
  });

  it('turns rows into reviewable INSERT statements in one transaction', () => {
    const csv = parseCsv("Name,id\nO'Brien,1\n,2\n");
    const script = insertScript('shop', 'Customers', cols, csv, 'people.csv');
    expect(script).toBe(
      '-- Import of people.csv into shop."Customers": 2 rows.\n' +
        '-- Nothing has run yet. Review, then use Run script: it runs in one transaction and stops at the first error;\n' +
        '-- if it stops, press Roll back.\n' +
        'BEGIN;\n' +
        'INSERT INTO shop."Customers" ("Name", id) VALUES\n' +
        "('O''Brien', '1'),\n" +
        "(NULL, '2');\n" +
        'COMMIT;\n',
    );
    expect(insertScript('s', 't', cols, parseCsv('id\n1'), 'evil\n-- name.csv')).toContain('-- Import of evil -- name.csv into');
  });

  it('splits large files into statements of 100 rows and enforces the limits', () => {
    const many = { header: ['id'], rows: Array.from({ length: 250 }, (_, i) => [String(i)]) };
    expect(insertScript('s', 't', cols, many, 'f.csv').match(/INSERT INTO/g)).toHaveLength(3);
    expect(() => insertScript('s', 't', cols, { header: ['id', 'nope'], rows: [['1', '2']] }, 'f')).toThrow('Not columns of s.t: nope.');
    expect(() => insertScript('s', 't', cols, { header: ['id'], rows: [] }, 'f')).toThrow('no rows');
    expect(() => insertScript('s', 't', cols, { header: ['id'], rows: Array.from({ length: IMPORT_MAX_ROWS + 1 }, () => ['1']) }, 'f')).toThrow('at most 5000');
    const wide = { header: ['note'], rows: Array.from({ length: 4000 }, () => ['x'.repeat(300)]) };
    expect(() => insertScript('s', 't', cols, wide, 'f')).toThrow('900 KB');
  });
});
