-- pgTAP tests for restricting the two audit-log INSERT policies (20260911010000).
-- Run with: supabase test db
--
-- Before this migration both `secret_access_log` and `plugin_api_key_logs` had an
-- INSERT policy of `WITH CHECK (true)` with no `TO` clause, so it applied to
-- PUBLIC, and both tables were writable by anon: one by explicit grant, the other
-- by the schema-wide ALTER DEFAULT PRIVILEGES in 20251023000014_grants.sql. The
-- interesting assertions are therefore about what must now be REFUSED.
--
-- WHAT THESE ASSERTIONS ACTUALLY COVER, measured by reverting each half of the
-- migration and re-running rather than by reading:
--
--   COVERED. Restoring `WITH CHECK (true)` on secret_access_log fails 3 (the
--   misattribution case, the anon case and the predicate check). Dropping the
--   REVOKE on anon fails 4 of the per-verb privilege assertions. Restoring the
--   PUBLIC policy on plugin_api_key_logs fails 2.
--
--   Privileges are asserted PER VERB rather than with a single ALL check,
--   because `has_table_privilege(..., 'ALL')` is true only when every verb is
--   held. One re-granted verb would pass an ALL-shaped assertion while leaving
--   the hole open.
--
--   NOT COVERED, and worth stating so nobody assumes otherwise: service_role
--   bypasses RLS entirely, so the plugin_api_key_logs policy scoped `TO
--   service_role` is belt and braces and asserting it proves nothing about the
--   real write path. log_api_key_action() is SECURITY DEFINER and would keep
--   working with no policy at all. It is asserted below only so that a future
--   change which drops the definer attribute does not silently lose the ability
--   to write.

begin;
select plan(19);

-- ---------------------------------------------------------------------------
-- Row level security is still on. Everything below is meaningless without it.
-- ---------------------------------------------------------------------------

select is(
    (select relrowsecurity from pg_class where oid = 'public.secret_access_log'::regclass),
    true,
    'secret_access_log still has row level security enabled'
);

select is(
    (select relrowsecurity from pg_class where oid = 'public.plugin_api_key_logs'::regclass),
    true,
    'plugin_api_key_logs still has row level security enabled'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: the INSERT policy is scoped and no longer unconditional.
-- ---------------------------------------------------------------------------

select is(
    (select roles::text from pg_policies
      where schemaname = 'public' and tablename = 'secret_access_log'
        and policyname = 'secret_access_log_insert'),
    '{authenticated}',
    'the secret_access_log INSERT policy names authenticated, not PUBLIC'
);

select ok(
    (select with_check like '%uid()%' from pg_policies
      where schemaname = 'public' and tablename = 'secret_access_log'
        and policyname = 'secret_access_log_insert'),
    'the secret_access_log INSERT predicate ties the row to the caller'
);

select isnt(
    (select with_check from pg_policies
      where schemaname = 'public' and tablename = 'secret_access_log'
        and policyname = 'secret_access_log_insert'),
    'true',
    'the secret_access_log INSERT predicate is no longer unconditional'
);

-- The read policy is untouched. A fix that quietly removed it would hide the
-- forged rows rather than stop them being written.
select isnt_empty(
    $$ select 1 from pg_policies
        where schemaname = 'public' and tablename = 'secret_access_log'
          and policyname = 'secret_access_log_select' $$,
    'the secret_access_log SELECT policy still exists'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: anon holds nothing, checked one verb at a time.
-- ---------------------------------------------------------------------------

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'SELECT'),
    'anon cannot read the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'INSERT'),
    'anon cannot append to the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'UPDATE'),
    'anon cannot amend the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'DELETE'),
    'anon cannot erase from the secret audit log'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: authenticated keeps exactly what the logging functions
-- need, and nothing that would let it rewrite history.
-- ---------------------------------------------------------------------------

select ok(
    has_table_privilege('authenticated', 'public.secret_access_log', 'SELECT'),
    'authenticated can still read its own audit rows'
);

select ok(
    has_table_privilege('authenticated', 'public.secret_access_log', 'INSERT'),
    'authenticated can still log an operation, which the secret functions rely on'
);

select ok(
    not has_table_privilege('authenticated', 'public.secret_access_log', 'UPDATE'),
    'authenticated cannot amend an audit row'
);

select ok(
    not has_table_privilege('authenticated', 'public.secret_access_log', 'DELETE'),
    'authenticated cannot erase an audit row'
);

-- ---------------------------------------------------------------------------
-- plugin_api_key_logs
-- ---------------------------------------------------------------------------

select is(
    (select roles::text from pg_policies
      where schemaname = 'public' and tablename = 'plugin_api_key_logs'
        and policyname = 'Service role can insert API key logs'),
    '{service_role}',
    'the API key log INSERT policy finally names the role its own name claims'
);

select ok(
    not has_table_privilege('anon', 'public.plugin_api_key_logs', 'INSERT'),
    'anon cannot forge API key usage history'
);

select ok(
    not has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'INSERT'),
    'authenticated cannot forge API key usage history either'
);

select ok(
    has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'SELECT'),
    'authenticated keeps SELECT, so "Users can view own API key logs" still resolves'
);

select isnt_empty(
    $$ select 1 from pg_policies
        where schemaname = 'public' and tablename = 'plugin_api_key_logs'
          and policyname = 'Users can view own API key logs' $$,
    'the API key log SELECT policy still exists'
);

select * from finish();
rollback;
