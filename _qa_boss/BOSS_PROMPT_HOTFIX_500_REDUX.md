# 🚨 P0 HOTFIX REDUX — site went 500 again with the SAME NUL-byte bug, V59 had a gap

Same PSQLException 22021 the operator screenshotted at 19:10 yesterday, except this time **V59 didn't catch it**. The NUL was in a column V59 doesn't constrain.

```
SQL [SELECT S.PRIMARY_ID, S.SESSION_ID, ..., SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES ...];
ERROR: invalid byte sequence for encoding "UTF8": 0x00
```

V59 covered `spring_session.primary_id` + `session_id`. But the SELECT also returns `SA.attribute_name` (varchar 200), and `spring_session.principal_name` (varchar 100) is updateable too. Either was the leak.

## Already done by boss (verify, then build on)

1. **Site is back up** — `docker exec sbox-pg psql -U skinbox -d skinbox -c "TRUNCATE spring_session_attributes, spring_session CASCADE;"` cleared the corruption. Public 200 confirmed.
2. **Live constraints applied** to Postgres directly via psql:
   - `spring_session_no_nul_bytes` extended to cover `principal_name`
   - `spring_session_attrs_no_nul_bytes` (NEW) covers `attribute_name` + `session_primary_id`
3. **V60 migration written** at `src/main/resources/db/migration/V60__spring_session_full_nul_check.sql` — formalizes both constraints. Idempotent (DROP IF EXISTS + re-add).

## Your job

### 1. Commit + deploy V60 (so constraints survive a fresh DB)

```bash
git add src/main/resources/db/migration/V60__spring_session_full_nul_check.sql \
        _qa_boss/BOSS_PROMPT_HOTFIX_500_REDUX.md
git commit -m "Hotfix: V60 extends NUL CHECK to attribute_name + principal_name"
docker build -t sbox-app:latest . && bash deploy/run-local.sh
# verify Flyway logs show "Migrating to V60"
docker logs sbox-app --since 1m | grep -i Migrating
```

### 2. FIND THE ACTUAL WRITE PATH (this is the real fix)

V60 is a backstop. The real bug is whatever writes a NUL byte into a TEXT column. Find it. Suspects from `grep -rn 'setAttribute' src/main/groovy`:

- `SteamAuthController:64` — `setAttribute(SESSION_NEXT, safeNext)` where `safeNext` comes from a query param. If a malformed cookie OR a `?next=...%00...` URL reaches this, `safeNext` carries NUL → goes into spring_session_attributes WHEN the JdbcSession serializes the attribute. The attribute value goes into `attribute_bytes` (BYTEA — fine), but the attribute NAME is `SESSION_NEXT` (constant string, fine).

  Hmm. So this can't be the source unless `SESSION_NEXT` itself is being computed from something dynamic. Verify it's a const.

- `ApiKeyAuthFilter:105` — `setAttribute(SESSION_USER_ID, ctx.userId as Long)` — Long, safe.

- `SteamAuthController:147-148` — Long values, safe.

The mystery: **all setAttribute callsites store Long or constant String names**. The NUL shouldn't appear in `attribute_name` from any of these. Yet it did.

Possibilities:
  - **A different write path** we haven't found. Grep for `JdbcIndexedSessionRepository` (Spring's bean), `INSERT INTO spring_session`, any reflective access.
  - **The principal_name column** is being set by Spring Session's auto-detection of the principal. If our `User` entity's `username` field accepts NUL bytes from Steam Open ID returns (or anywhere), and Spring resolves it as the principal, → `principal_name` has NUL.
  - **A non-Spring write** — a controller or service doing raw JDBC inserts into spring_session for a test/seed/admin endpoint.

### 3. Add a DAMN ErrorPage handler that doesn't depend on Spring Session

The reason this 500 rendered Tomcat's STUB page (instead of our branded `GlobalErrorController`) is that BOTH the original request AND the `/error` mapping run through `SessionRepositoryFilter` which trips on the same SELECT. So when the DB is poisoned, the user sees raw Tomcat HTML.

Fix: register a high-precedence Filter that catches `DataAccessException` / `DataIntegrityViolationException` and serves a static branded HTML 500 panel directly from a classpath resource — never touches the session. Suggested location: `src/main/groovy/com/sboxmarket/config/CatastrophicErrorFilter.groovy` ordered `Ordered.HIGHEST_PRECEDENCE`. Sister to `SessionAttributeNulStripFilter` but for catching unrecoverable DB errors.

### 4. Add a regression test

`src/test/groovy/com/sboxmarket/config/SessionNulByteRegressionSpec.groovy` — verifies that:
- Inserting `NUL` into `spring_session.principal_name` throws ConstraintViolation
- Inserting `NUL` into `spring_session_attributes.attribute_name` throws ConstraintViolation
- A normal session lifecycle (login → setAttribute → save → reload) succeeds without NUL

This catches future regressions in CI before they take down prod.

## Acceptance

- [ ] V60 committed + deployed (Flyway log shows "Migrating to V60")
- [ ] Both CHECK constraints visible in `\d spring_session` + `\d spring_session_attributes` after deploy
- [ ] Regression test passes locally + in CI
- [ ] CatastrophicErrorFilter installed; verified by induced 500 (insert NUL into spring_session.principal_name via raw SQL → request → branded 500 panel, NOT Tomcat stub)
- [ ] Source of NUL writes IDENTIFIED in the commit message — name the call site OR document "no source found, scrubbing layer is the only defense"
- [ ] Site stays 200 for ≥10 min after deploy
- [ ] Update `production_checklist.md`: close P0, add a P2 if the source isn't found

GO. The site has now 500'd TWICE in 24 hours from the same root cause. This is the operator's "fully functional / sellable" P0 blocker.
