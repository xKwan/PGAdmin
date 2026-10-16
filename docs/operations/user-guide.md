# pgweb User Guide

pgweb lets you run SQL against the organisation's PostgreSQL servers from Edge, signed in with your Windows account. There is no separate password: the database sees you as yourself, with your own PostgreSQL role and privileges.

> **SQL execution is switched off** until the organisation signs it off. Until then you can sign in, connect and browse database objects, and every statement is refused with "Running SQL is switched off by the administrator."

## 1. Signing in

Open the address your administrator gave you (for example `https://pgweb.corp.example:8443/`) in Edge on a domain-joined computer. You are signed in automatically; there is no sign-in form. If the browser asks for a user name and password, close the dialog and tell the administrator: the site isn't set up for automatic sign-in on your computer.

Your sign-in lasts while you use it: it ends after 30 minutes without activity, and at the latest after 8 hours. The page then signs you in again by itself and reloads.

## 2. Choosing a server

The landing page shows the servers you can use:

| Tile | Who | What you see |
|---|---|---|
| **External PostgreSQL** | Everyone | The databases you own or that someone shared with you (you have `CONNECT` on them) |
| **Internal PostgreSQL** | Members of the **DataAdmin** group only | The internal server's databases, as your own internal role. The workspace may show a coloured banner (for example `INTERNAL`) so you always know where you are. |

If you were just added to DataAdmin and the internal tile is missing, sign out of Windows and back in (your sign-in has to pick up the new group), then reload.

## 3. Sessions

Choosing a database opens a **session**: a connection to PostgreSQL as your own role. You can have up to 3 sessions open at once. The session bar shows the server, database and your PostgreSQL user, and the transaction state.

- **Disconnect** when you are done. A session also closes after 15 minutes without running anything, after 10 minutes idle inside an open transaction (you are warned at 8), and an hour after a transaction began. Viewing the page doesn't count as activity.
- If you reach the limit, pgweb lists your open sessions so you can disconnect one.
- A session marked **Limited** works, but object browsing and the cancel escalation aren't available in it.
- If pgweb is restarted, every session closes and open transactions are rolled back. Connect again.

## 4. Running SQL

Type in the editor and press **Ctrl+Enter**. With text selected, the selection runs; otherwise the statement at the cursor.

- **One statement at a time.** Text with two statements (`SELECT 1; SELECT 2`) is refused, with the number of statements found. A trailing `;` is fine. Scripts aren't supported in this release: run the statements one by one, inside `BEGIN` … `COMMIT` if they must all succeed together.
- Your SQL is sent **exactly as you wrote it**. pgweb doesn't add `LIMIT` or change anything.
- Not supported: `COPY … FROM STDIN` / `TO STDOUT`, `LISTEN`, `UNLISTEN`, and psql commands such as `\dt` or `\copy` (they aren't SQL).
- **Time limit:** a statement is stopped after 60 seconds on the external server and 5 minutes on the internal one. `SET statement_timeout` doesn't change pgweb's limit.
- **Cancel** stops a running statement. If PostgreSQL doesn't stop it within 5 seconds, pgweb ends that database connection and closes the session.
- **"Waiting for a lock held by another session"** means another session holds a row or table you need; your statement continues when they commit or roll back, or stops at the time limit.

### Confirmations

Before `DROP`, `TRUNCATE`, `GRANT`/`REVOKE`, other `CREATE`/`ALTER`, `DO` blocks, and an `UPDATE` or `DELETE` without `WHERE`, pgweb asks you to confirm. This guards against mistakes; it doesn't limit what you may do. Your PostgreSQL privileges decide that.

## 5. Results

- Up to **1,000 rows** are shown, and at most about 10 MB. When more were available, the result says it was cut and why. Add `ORDER BY` and `LIMIT`/`OFFSET`, or a `WHERE`, to see other rows.
- A value longer than 64 KB is cut and marked "This value was cut at the cell size limit".
- Values look as they do in `psql`: all `numeric` digits, `bytea` as hex (`\x…`), timestamps in your session's time zone. `NULL` is shown distinctly from an empty text and from the text `NULL`.
- Sorting by a column header sorts only the rows on screen ("Sorted locally"); it isn't `ORDER BY`.
- Statements that change rows report "Completed. N rows affected."
- **Outcome unknown** ("indeterminate"): the connection broke while the statement ran. **It may have taken effect.** Check the data before running it again.

## 6. Transactions

Each statement is committed on its own unless you start a transaction:

- Type `BEGIN` (then `COMMIT` or `ROLLBACK`), or switch the transaction bar to **manual commit**: the next statement begins a transaction with the isolation level you chose (read committed, repeatable read, serializable) and optionally **Read only**. Read only is a convenience, not a security control.
- After an error inside a transaction it is **failed**: PostgreSQL accepts only `ROLLBACK`, and Run is disabled until you roll back. `COMMIT` on a failed transaction rolls back; pgweb tells you "Nothing was saved".
- Deadlocks and serialization conflicts are reported as such; nothing is retried for you. Roll back and run again.
- Disconnecting with an open transaction asks first, because it rolls the transaction back.

## 7. Browsing objects

The object panel lists schemas, tables, views, functions and other objects in the database you're connected to, with their columns, constraints and indexes. Objects you can see in the catalog but have no privilege on are marked **no access**.

## 8. What is recorded

Every sign-in, connection and statement is recorded in an audit trail the administrators and named auditors can review. For each statement: when, who, which server and database, the kind of statement, its outcome and row count, and the statement **with every literal value replaced** (`WHERE id = $1`). The values you type aren't stored, and neither is the full text; a keyed fingerprint lets an investigator confirm whether a given text was run. When the audit trail can't be written, statements are refused until it is back; this protects you and the organisation from unrecorded changes.

## 9. Messages you may see

| Message | Meaning |
|---|---|
| Running SQL is switched off by the administrator. | Execution isn't switched on (yet), or was turned off |
| The statement was not run: the audit database is unavailable. | The audit database is down; try again in a few minutes |
| … found N statements … Select one statement, or put the cursor in it, and run again. | Run one statement at a time |
| A syntax error, with its position | PostgreSQL's grammar rejected the text at that character |
| You already have 3 open database sessions; close one first. | Disconnect one from the list shown |
| Could not connect to the database. Check that you have access to it, or ask the administrator. | No role, no `CONNECT`, or the server refused your sign-in |
| Windows sign-in for the database connection did not complete. … | Open pgweb by its full host name, from a domain-joined computer |
| Superuser roles cannot be used through this application. | Your role is a PostgreSQL superuser; ask the DBA for a normal role |
| The database session has ended. Connect again. | The session timed out, was closed after a cancel, or pgweb restarted |
| permission denied for … | Your PostgreSQL role lacks the privilege; ask the database owner |
| You have no database on this server that you can connect to. | Nobody shared a database with you on that server |
