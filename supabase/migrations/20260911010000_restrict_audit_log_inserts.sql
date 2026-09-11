-- Stop two audit logs accepting forged rows from any client.
--
-- `secret_access_log` and `plugin_api_key_logs` are the audit trails for secret
-- access and for plugin-store API key usage. Both had an INSERT policy whose
-- predicate was `WITH CHECK (true)` and, crucially, no `TO` clause. A policy
-- with no `TO` clause applies to PUBLIC, which includes `anon` and
-- `authenticated`.
--
-- Both tables are also writable by those roles:
--   * secret_access_log has an explicit `GRANT ALL ... TO anon, authenticated`
--     in 20251023000014_grants.sql.
--   * plugin_api_key_logs has no explicit grant, but the same migration sets
--     `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES TO anon, authenticated`,
--     and that table is created later, so it inherits the grant.
--
-- The anon key ships in the desktop client, so the practical effect is that
-- anybody can append arbitrary rows to either log:
--
--   1. Misattribution. `secret_access_log.user_id` is unconstrained, so a row
--      claiming that another user viewed or shared a secret can be inserted at
--      will. The table's own SELECT policy then shows that row to the named
--      user and to every admin, as though it were real.
--   2. Cover. A real access event can be buried under forged entries, which is
--      the failure mode an audit log exists to prevent.
--   3. Forged API-key history. plugin_api_key_logs rows can be attributed to
--      any existing api_key_id, including another user's.
--
-- Neither policy comment described this. plugin_api_key_logs' policy is even
-- named "Service role can insert API key logs", which is what it was meant to
-- be and not what it did.
--
-- No legitimate writer is affected:
--   * Every function that writes secret_access_log
--     (20251023000004_secret_functions.sql, 20260802000000_secrets_org_ownership.sql,
--     20260809000000_secret_read_for_user_role.sql) inserts `auth.uid()` as
--     user_id, which the new predicate permits.
--   * plugin_api_key_logs is written only by log_api_key_action(), which is
--     SECURITY DEFINER and granted to service_role alone, so it bypasses RLS
--     and needs no INSERT policy at all.

-- ---------------------------------------------------------------------------
-- secret_access_log
-- ---------------------------------------------------------------------------

-- anon has no reason to touch a secret audit trail in any way.
REVOKE ALL ON TABLE public.secret_access_log FROM anon;

-- authenticated keeps SELECT (policy "secret_access_log_select") and INSERT
-- (the logging functions run as the caller). Nothing has ever been allowed to
-- amend or erase an audit row; the grant now says so too.
REVOKE UPDATE, DELETE ON TABLE public.secret_access_log FROM authenticated;

DROP POLICY IF EXISTS "secret_access_log_insert" ON public.secret_access_log;

CREATE POLICY "secret_access_log_insert" ON public.secret_access_log
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE public.secret_access_log IS
    'Audit log for all secret access and sharing operations. Append-only: a '
    'signed-in user may log an operation as themselves, and nothing may update '
    'or delete a row. service_role bypasses RLS for retention work.';

-- ---------------------------------------------------------------------------
-- plugin_api_key_logs
-- ---------------------------------------------------------------------------

REVOKE ALL ON TABLE public.plugin_api_key_logs FROM anon;

-- authenticated keeps SELECT so "Users can view own API key logs" still
-- resolves. Writing is the Edge Function's job, through a definer function.
REVOKE INSERT, UPDATE, DELETE ON TABLE public.plugin_api_key_logs FROM authenticated;

DROP POLICY IF EXISTS "Service role can insert API key logs" ON public.plugin_api_key_logs;

CREATE POLICY "Service role can insert API key logs" ON public.plugin_api_key_logs
    FOR INSERT TO service_role
    WITH CHECK (true);

COMMENT ON TABLE public.plugin_api_key_logs IS
    'Plugin store: audit log for API key usage. Written only by '
    'log_api_key_action() (SECURITY DEFINER, service_role). Key owners may read '
    'their own rows; nobody may amend or erase one.';
