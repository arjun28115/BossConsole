-- pgTAP tests for completed_authentications access (20260911000000).
-- Run with: supabase test db
--
-- completed_authentications holds live access and refresh tokens for the
-- cross-device login handoff. Before BossConsole#528 its anon policies were
-- guarded by `session_id IS NOT NULL`, which is TRUE on every real row, so any
-- holder of the public anon key could read every in-flight login's tokens,
-- delete them all, or pre-seed a victim's session with attacker-controlled ones.
--
-- These assertions are about the absence of access, which is exactly the kind of
-- thing that is easy to reintroduce: a later migration adding a convenience
-- policy "so the client can poll directly" would restore the hole without
-- failing anything else in the suite.

begin;
select plan(11);

-- 1-4: no policy remains for either client role, service_role keeps its own,
-- and RLS is still enabled.
select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'completed_authentications'
         and 'anon' = any(roles) $$,
    'anon holds no policy on completed_authentications'
);

select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'completed_authentications'
         and 'authenticated' = any(roles) $$,
    'authenticated holds no policy on completed_authentications'
);

-- The service-role policy is the live path and must survive. If this fails, the
-- migration went too far and cross-device login is broken.
select isnt_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'completed_authentications'
         and 'service_role' = any(roles) $$,
    'service_role keeps its policy, so the Edge Function flow still works'
);

select ok(
    (select relrowsecurity from pg_class
      where oid = 'public.completed_authentications'::regclass),
    'row level security is still enabled on the table'
);

-- 5-11: no table privilege either, so the gate does not rest on RLS alone.
-- has_table_privilege is checked per verb rather than via ALL, so a partial
-- regrant of a single verb cannot pass.
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'SELECT'),
    'anon cannot SELECT the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'INSERT'),
    'anon cannot INSERT into the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'UPDATE'),
    'anon cannot UPDATE the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'DELETE'),
    'anon cannot DELETE from the token table'
);

select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'SELECT'),
    'authenticated cannot SELECT the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'INSERT'),
    'authenticated cannot INSERT into the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'DELETE'),
    'authenticated cannot DELETE from the token table'
);

select * from finish();
rollback;
