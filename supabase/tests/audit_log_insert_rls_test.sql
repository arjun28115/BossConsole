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
--   NOT COVERED by the policy assertions, and worth stating so nobody assumes
--   otherwise: service_role bypasses RLS entirely, so the plugin_api_key_logs
--   policy scoped `TO service_role` is belt and braces and asserting it proves
--   nothing about the real write path. log_api_key_action() is SECURITY
--   DEFINER and would keep working with no policy at all. It is asserted below
--   only so that a future change which drops the definer attribute does not
--   silently lose the ability to write. The RPC's EXECUTE grants (PUBLIC,
--   anon, authenticated revoked; service_role kept) ARE asserted, because that
--   is what actually stops a client from calling the definer writer.

begin;
select plan(20);

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
-- secret_access_log: the unconditional INSERT policy is gone, and no
-- client-scoped replacement remains - with no INSERT privilege, no client
-- policy could ever fire, so a leftover one would be misleading dead code.
-- ---------------------------------------------------------------------------

select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'secret_access_log'
         and policyname = 'secret_access_log_insert' $$,
    'no INSERT policy remains on secret_access_log'
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
    not has_table_privilege('authenticated', 'public.secret_access_log', 'INSERT'),
    'authenticated cannot insert into the secret audit log; every writer is SECURITY DEFINER'
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

-- The definer writer must not be callable by clients: EXECUTE is granted to
-- PUBLIC by default and to anon/authenticated via the schema-wide default
-- privileges, so all three revokes are asserted. Without this, the RPC forges
-- rows for any api_key_id even with the table locked.
select ok(
    not has_function_privilege('anon', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'anon cannot execute the API key log RPC'
);

select ok(
    not has_function_privilege('authenticated', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'authenticated cannot execute the API key log RPC either'
);

select ok(
    has_function_privilege('service_role', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'service_role can still execute the API key log RPC'
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
