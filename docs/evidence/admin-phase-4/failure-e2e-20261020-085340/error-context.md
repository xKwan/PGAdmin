# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: editor.spec.js >> admin Phase 4: cancel another session's query and end that session, after confirming
- Location: tests\editor.spec.js:273:1

# Error details

```
Error: expect(locator).toContainText(expected) failed

Locator: getByRole('region', { name: 'Server activity' }).getByRole('status')
Expected substring: "Cancel request sent"
Error: strict mode violation: getByRole('region', { name: 'Server activity' }).getByRole('status') resolved to 2 elements:
    1) <span role="status" class="spinner" aria-live="polite">…</span> aka getByRole('status').filter({ hasText: 'Loading activity' })
    2) <p role="status" class="admin-note">Cancel request sent to session 38064.</p> aka getByText('Cancel request sent to session')

Call log:
  - Expect "toContainText" getByRole('region', { name: 'Server activity' }).getByRole('status') with timeout 15000ms
  - waiting for getByRole('region', { name: 'Server activity' }).getByRole('status')

```

# Page snapshot

Omitted (it showed the workstation's Windows account). The full trace is kept outside the repository.
