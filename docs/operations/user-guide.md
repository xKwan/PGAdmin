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

### The status line

The top of the workspace shows four things separately:

| Item | What it means |
|---|---|
| **Signed in** | Your Windows account, and how you signed in (Kerberos; NTLM only on a test machine) |
| **Server** | Whether the PostgreSQL server answered **for you**. When the workspace opens, pgweb asks the server which databases you may use; it does that by connecting as you. **Reachable** means that worked. Otherwise it says why: *Could not connect*, *Windows sign-in incomplete*, *Access refused*, *Too many failed attempts*, *Audit service unavailable*, *Application server unreachable*. |
| **Database** | The database of your open session, or *none open* |
| **Session** | *No session*, *Opening …*, *Open*, *Switching to …*, or *Ended* |

"Server: Reachable" with "Session: No session" is normal: the server answered, and you haven't opened a database yet. Nothing is reported as open until the server has confirmed it.

### Opening, switching and reconnecting

Your databases are listed as soon as the workspace opens. Choosing one opens a **session**: a connection to PostgreSQL as your own role. pgweb doesn't open one by itself, because every session is recorded and counts against your limit; the database you used last on that server is offered first (**Connect to …**). You can have up to 3 sessions open at once. The session bar shows the database, your PostgreSQL user, the server version, and the transaction state. **Refresh list** asks the server again.

- **Switch database** (in the session bar) lists your databases with the current one marked. Choosing another opens the new session first; only when that worked is the old one closed. If it fails (no access, the limit reached, the server down), you stay connected to the database you had, and the message says so. The editor keeps your text; results and the object browser start afresh for the new database.
- **An open transaction is never discarded silently.** If you switch while a transaction is open, pgweb asks first: switching closes the session, and PostgreSQL rolls back everything not committed. Nothing is committed for you. To keep the changes, cancel and **Commit** first. While a statement runs, switching isn't offered.
- At the session limit a switch can't open the new session; disconnect this or another session first.
- When a session ends (timeout, server restart, a lost connection), the session bar offers **Reconnect to …** (the same database) and **Choose another database**.
- **Disconnect** when you are done. A session also closes after 15 minutes without running anything, after 10 minutes idle inside an open transaction (you are warned at 8), and an hour after a transaction began. Viewing the page doesn't count as activity.
- If you reach the limit, pgweb lists your open sessions so you can disconnect one.
- A session marked **Limited** works, but object browsing and the cancel escalation aren't available in it.
- If pgweb is restarted, every session closes and open transactions are rolled back. Connect again.

## 4. Running SQL

### The editor: tabs and files

- The editor is there as soon as the workspace opens, even before you connect: you can write, open and save SQL, but nothing runs until a database session is open.
- **+ New** opens another tab. Each tab keeps its own text, cursor and undo history, also when you switch database, disconnect or reconnect. A tab with changes that aren't saved to a file shows **• unsaved**; closing it, or leaving the workspace with **← Servers**, asks first. Closing the browser tab asks too.
- **Open file…** reads a `.sql` (or `.txt`) file from your computer into a new tab. **Opening never runs anything.** Files must be UTF-8 text and at most 5 MB.
- **Save** writes the tab back to the file it came from (in Edge, after your permission). For a new tab, or with **Save as…**, you choose where. Where the browser can't write files directly, Save downloads the file instead.
- Files go between your computer and the editor only; pgweb's server never receives or keeps them.

### Run and Run script

**Run** (or **Ctrl+Enter**) runs the selection, or the statement at the cursor. **Run script** runs every statement in the tab (or in the selection), one at a time.

- **Run** takes **one statement**. Text with two statements (`SELECT 1; SELECT 2`) is refused, with the number of statements found. A trailing `;` is fine.
- **Run script** (there is no shortcut, so it can't happen by accident):
  - Before anything runs, PostgreSQL's own parser checks the whole script. If any statement has a syntax error or isn't supported, **nothing runs** and you're shown where.
  - If any statement needs a confirmation (below), you're asked **once** for the script.
  - The statements then run in order and the script **stops at the first statement that fails** (or is cancelled, or reaches the time limit). The results list every statement: *Completed*, *Failed* with PostgreSQL's error, or *Not run*. **Show in editor** selects a statement's text.
  - Each statement commits on its own as usual, unless you're in a transaction (yours, or one the script begins with `BEGIN`). After a failure inside a transaction, it is up to you to roll back (or fix and continue); nothing is committed or rolled back for you.
  - **Cancel** stops the running statement, and the rest don't start. At most 1,000 statements per script.
- Your SQL is sent **exactly as you wrote it**. pgweb doesn't add `LIMIT` or change anything.
- Not supported: `COPY … FROM STDIN` / `TO STDOUT`, `LISTEN`, `UNLISTEN`, and psql commands such as `\dt` or `\copy` (they aren't SQL).
- **Time limit:** a statement is stopped after 60 seconds on the external server and 5 minutes on the internal one. `SET statement_timeout` doesn't change pgweb's limit.
- **Cancel** stops a running statement. If PostgreSQL doesn't stop it within 5 seconds, pgweb ends that database connection and closes the session.
- **"Waiting for a lock held by another session"** means another session holds a row or table you need; your statement continues when they commit or roll back, or stops at the time limit.

### Confirmations

Before `DROP`, `TRUNCATE`, `GRANT`/`REVOKE`, other `CREATE`/`ALTER`, `DO` blocks, an `UPDATE` or `DELETE` without `WHERE`, and `EXPLAIN ANALYZE`, pgweb asks you to confirm. This guards against mistakes; it doesn't limit what you may do. Your PostgreSQL privileges decide that.

### Explain

**Explain** shows how PostgreSQL would run the statement at the cursor (or the selection), without running it: the plan as a tree, with each step's estimated cost and rows and its conditions (filters, join and index conditions). **Explain analyze** runs the statement and shows the actual times and rows next to the estimates, and marks steps whose estimate was off by ten times or more. Because ANALYZE really runs the statement (an `UPDATE` really updates), pgweb always asks first, also when you type `EXPLAIN ANALYZE` yourself; run it inside a transaction and roll back if you only want the measurement. **Show as table** shows PostgreSQL's raw output. If you type your own `EXPLAIN`, it is sent as written.

## 5. Results

- Up to **1,000 rows** come back at first, and at most about 10 MB.
- **Inside a transaction**, a larger result keeps the rest on the server: **Load more rows** fetches the next 1,000, up to 10,000 rows in all. The rows stay available until you run another statement, commit, roll back or disconnect.
- **Outside a transaction** the result is cut at 1,000 rows. **Run again in a read-only transaction to load more** begins a read-only transaction (nothing can be changed in it) and runs the statement again, so you can load more. Finish it with **Commit** or **Rollback** in the transaction bar; an open transaction keeps locks and is closed after 10 idle minutes.
- To see other rows without loading them all, add `ORDER BY` and `LIMIT`/`OFFSET`, or a `WHERE`.
- A value longer than 64 KB is cut and marked "This value was cut at the cell size limit".
- Values look as they do in `psql`: all `numeric` digits, `bytea` as hex (`\x…`), timestamps in your session's time zone. `NULL` is shown distinctly from an empty text and from the text `NULL`.
- Sorting by a column header sorts only the rows on screen ("Sorted locally"); it isn't `ORDER BY`.
- Statements that change rows report "Completed. N rows affected."
- **Outcome unknown** ("indeterminate"): the connection broke while the statement ran. **It may have taken effect.** Check the data before running it again.
- With several tabs open, the results say which tab they came from.

### History

The **History** tab next to Results lists what you ran in this browser tab, newest first (up to 50): when, in which database, how it ended, and the text. **Insert** puts the text at the editor's cursor; **Open in a new tab** opens it in its own tab. Nothing runs until you run it. The history holds the full text you typed, including values, **only in this browser tab**: closing the tab, or **Clear history**, removes it. It is separate from the audit trail (below).

## 6. Transactions

Each statement is committed on its own unless you start a transaction:

- Type `BEGIN` (then `COMMIT` or `ROLLBACK`), or switch the transaction bar to **manual commit**: the next statement begins a transaction with the isolation level you chose (read committed, repeatable read, serializable) and optionally **Read only**. Read only is a convenience, not a security control.
- After an error inside a transaction it is **failed**: PostgreSQL accepts only `ROLLBACK`, and Run is disabled until you roll back. `COMMIT` on a failed transaction rolls back; pgweb tells you "Nothing was saved".
- Deadlocks and serialization conflicts are reported as such; nothing is retried for you. Roll back and run again.
- Disconnecting with an open transaction asks first, because it rolls the transaction back.

## 7. Browsing objects

The object panel shows the database you're connected to, its schemas, and their tables, partitioned tables (each partition is marked **partition**), views, sequences, functions and other objects, with columns, indexes, constraints and triggers (a disabled trigger is marked). **Extensions** lists the extensions installed in the database, and **Other databases** the databases you can switch to (**Switch** works like Switch database, including the question about an open transaction). Objects you can see in the catalog but have no privilege on are marked **no access**. **Filter objects by name** narrows the objects of the schemas you have opened. An opened table or view also shows its **owner**, **your privileges** on it and who was granted what. **Definition** opens an object's SQL (`CREATE …`) in a new editor tab, for reading or as a starting point; nothing is run. A table's definition is rebuilt from the catalog (columns, constraints, indexes, triggers, comment), not a full dump: ownership, privileges and storage options are left out, as its first lines say. The panel shows committed objects: it reloads by itself after a change to objects is committed, and **Refresh** reloads it any time.

## 7a. Administration views

Next to **Results** and **History**, three tabs show information about the server, read as your own role. Each is read when you open it and when you press **Refresh**; nothing is refreshed automatically. Nothing on these tabs changes anything, except **Cancel query** and **End session** on the Activity tab, which ask first.

- **Activity**: the sessions on the server (your session is marked **this session**, and **browser** marks the connection pgweb uses to read the catalog for you), what they wait for, how long their statement has run, and which session blocks a waiting one (**blocked by**). Below, the locks other sessions hold or wait for. For other users' sessions PostgreSQL shows the details only to roles allowed to see them; otherwise you see "not visible to your role". The SQL of other users' sessions is never shown ("another role's SQL is not shown"); you see the SQL of your own role's sessions.
  - **Cancel query** (on a session running a statement) and **End session** (on any other session you can see): pgweb asks first and says whose work it affects. Ending a session disconnects it and rolls back its open transaction. PostgreSQL decides whether your role may do it: you need the privileges of that session's role (your own sessions in another browser, for example), or membership of `pg_signal_backend`. Every attempt is recorded in the audit log. Not available while running SQL is switched off. Your own pgweb session has no such buttons: use **Cancel** in the editor, or **Disconnect**.
- **Database**: the database's owner, your role and what it may do here, size, encoding, collation and other properties, who was granted which privileges on it, the sizes of the databases you may connect to, and the 100 largest tables you can use.
- **Roles**: the roles on the server with their attributes, what each is a member of and who its members are, and which roles you belong to. **Show predefined roles** adds PostgreSQL's built-in `pg_*` roles. Roles and privileges are changed with SQL (`GRANT`, `REVOKE`, `ALTER ROLE`), under your own role.

## 7b. Managing objects (Manage tab)

**Manage** writes the SQL for common changes, so you don't have to remember the syntax. Choose what to do (create a schema or table, add a column or index, add a constraint, create a view or sequence, create or drop a database, create a role, grant privileges, …), fill in the form, and the SQL appears next to it as you type. **Open in editor** puts it in a new editor tab. **Nothing runs from the Manage tab**: you run the SQL with **Run** or **Run script**, like anything you type, as your own PostgreSQL role. Changes to the structure, privileges and drops ask for confirmation when you run them, and every statement is recorded in the audit log.

- Names are quoted for you where needed (`"Sales 2027"`). Types, defaults, check conditions and view queries are SQL you write; check them in the preview.
- The database you are connected to can't be renamed or dropped from here: switch to another database first. Dropping a database fails while anyone is connected to it; pgweb doesn't force them off.
- Roles never get passwords (sign-in is through Windows) and can't be made superusers here.
- What your role may do is PostgreSQL's decision: without the privilege, running the SQL fails with "permission denied".

## 7c. Table data (Data tab)

**Data** next to a table or view in the explorer shows its rows in the **Data** tab, 100 at a time, ordered by the primary key; **Next 100** and **Previous 100** page through them, and **Open query in editor** gives you the SELECT to adapt. Reading runs like any statement you run, as your own role.

In a table with a primary key, and only as far as your role may change it:

- **Edit** opens the row in a form; **Delete** marks it (**Keep row** undoes that); **Add row** opens an empty form, where each column can take its **Default** or **NULL**. Generated columns can't be changed. Nothing is saved yet: the changes are listed as "unsaved changes".
- **Review and save** shows every statement that will run. **Save** runs them in one transaction. It is committed only if each change hit exactly its row; if someone else changed or deleted a row since you loaded it, or PostgreSQL refuses a value, **nothing is saved** and pgweb says which row. **Discard changes** drops them.
- Saving needs no open transaction of your own: commit or roll back first.
- Tables without a primary key can be read here, not changed: use SQL with a WHERE clause instead.

## 7d. Export and import

- **Export CSV** and **Export JSON** (above a result's rows) save the rows you see, including pages loaded with **Load more rows**, as a file. It contains exactly what the grid shows: PostgreSQL's text for each value; in CSV an empty field is NULL and `""` is an empty text. Text that a spreadsheet would treat as a formula (starting with `=`, `+`, `-`, `@`) gets a leading `'`. Each export is recorded in the audit log (format and how many rows and columns, never the data); if that record can't be written, nothing is saved. To export more rows than pgweb shows, ask the database owner for an extract.
- **Import CSV…** (in the Data tab of a table you may insert into) reads a UTF-8 CSV file of up to 1 MB and 5,000 rows, with a header row naming the columns. It writes INSERT statements, in one transaction, into a new editor tab. **Nothing is imported until you press Run script**; if the script stops at an error, press **Roll back**. An empty field becomes NULL, `""` an empty text, and PostgreSQL converts each value to the column's type.
- Backup and restore are not available in pgweb.

## 8. What is recorded

Every sign-in, connection and statement (each statement of a script separately, and each extra page of rows you load) is recorded in an audit trail the administrators and named auditors can review. For each statement: when, who, which server and database, the kind of statement, its outcome and row count, and the statement **with every literal value replaced** (`WHERE id = $1`). The values you type aren't stored, and neither is the full text; a keyed fingerprint lets an investigator confirm whether a given text was run. Opening an object, a definition or an administration tab is recorded by name only (for example "activity"), never what was shown. When the audit trail can't be written, statements are refused until it is back; this protects you and the organisation from unrecorded changes.

## 9. Messages you may see

| Message | Meaning |
|---|---|
| Running SQL is switched off by the administrator. | Execution isn't switched on (yet), or was turned off |
| The statement was not run: the audit database is unavailable. | The audit database is down; try again in a few minutes |
| … found N statements … Select one statement, or put the cursor in it, and run again. | **Run** takes one statement; use **Run script** to run them all |
| Stopped at statement N of M: … | The script stopped there; the statements after it were not run |
| The script has N statements; at most 1000 … | Split the script into smaller ones |
| The rest of these rows is no longer available … | Another statement ran or the transaction ended; run the statement again |
| … is not UTF-8 text / … files up to 5 MB can be opened | Save the file as UTF-8, or split it |
| A syntax error, with its position | PostgreSQL's grammar rejected the text at that character |
| You already have 3 open database sessions; close one first. | Disconnect one from the list shown |
| Could not connect to the database. Check that you have access to it, or ask the administrator. | No role, no `CONNECT`, or the server refused your sign-in |
| Windows sign-in for the database connection did not complete. … | Open pgweb by its full host name, from a domain-joined computer |
| Superuser roles cannot be used through this application. | Your role is a PostgreSQL superuser; ask the DBA for a normal role |
| The database session has ended. Connect again. | The session timed out, was closed after a cancel, or pgweb restarted; use **Reconnect to …** |
| Could not switch to … You are still connected to … | The other database couldn't be opened; your current session is unchanged |
| The current session has an open transaction. … | Commit or roll back first, or confirm that switching rolls it back |
| A statement is still running in this session. … | Wait for it or cancel it, then switch |
| Server: Could not connect (with no session) | The server didn't answer for you: it may be down, or you have no access to its maintenance database. **Refresh list** tries again |
| Server: Windows sign-in incomplete | Open pgweb by its full host name, from a domain-joined computer |
| permission denied for … | Your PostgreSQL role lacks the privilege; ask the database owner |
| You have no database on this server that you can connect to. | Nobody shared a database with you on that server |
| This statement is EXPLAIN ANALYZE, which runs the statement to measure it … | Confirm to run it; inside a transaction you can roll back afterwards |
| not visible to your role (Activity) | Another user's session: PostgreSQL shows its details only to roles allowed to see them |
| another role's SQL is not shown (Activity) | pgweb never shows the statements of other roles' sessions |
| Your role may not … that session | PostgreSQL's rule: you need that role's privileges or `pg_signal_backend` |
| That session has already ended | It ended before the request arrived; press Refresh |
| Only the first … are shown. | An administration list reached its limit |
| … is required. / … is longer than 63 bytes. / … doesn't look like a type name. (Manage) | Fill in or correct that field; no SQL is produced until the form is complete |
| That is the database you are connected to: switch to another database first. | Rename and drop work only on other databases |
| Nothing was saved: the row with … was changed or deleted by someone else … (Data) | Reload, check the row, and make your change again |
| Nothing was saved: PostgreSQL refused it: … (Data) | A value doesn't fit the column, or a constraint or privilege refused it; nothing was changed |
| This table has no primary key, so its rows can be read here but not changed. | Use SQL to change rows of such a table |
| Not exported: the audit database is unavailable … | The export couldn't be recorded, so nothing was saved; try again later |
| Not imported: … (Import CSV) | The file isn't UTF-8 CSV, is too large, names a column the table doesn't have, or has a line with the wrong number of fields |
