-- Additive compatibility for concurrently reviewed identity primitives. V27 remains byte-frozen.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Identity compatibility requires the dedicated migration identity';
  END IF;
END $$;
INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES('padmin','tenants:read_inventory');
UPDATE public.mop_project_role SET permissions=(SELECT array_agg(p ORDER BY p) FROM unnest(permissions||ARRAY['tenants:read_inventory']) p),version=version+1,updated_at=statement_timestamp()
WHERE builtin AND base_role='padmin';
ALTER TABLE public.mop_local_account DROP CONSTRAINT mop_local_account_password_hash_check;
ALTER TABLE public.mop_local_account ADD CONSTRAINT mop_local_account_password_hash_check
  CHECK(password_hash ~ '^(\{pbkdf2-sha256-v1\}[0-9a-f]{96}|\{mop-argon2id-v1\}\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43})$');
ALTER TABLE public.mop_local_password_history DROP CONSTRAINT mop_local_password_history_password_hash_check;
ALTER TABLE public.mop_local_password_history ADD CONSTRAINT mop_local_password_history_password_hash_check
  CHECK(password_hash ~ '^(\{pbkdf2-sha256-v1\}[0-9a-f]{96}|\{mop-argon2id-v1\}\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43})$');
