-- Additional test identity for two-person internal review; never a customer approval.
DO $$ BEGIN IF current_database() <> 'mop_test' THEN RAISE EXCEPTION 'MOP test migrations require dedicated mop_test database'; END IF; END $$;

INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code)
VALUES (
  md5('mop-fixture-user-reviewer')::uuid,
  'test_oidc',
  'http://127.0.0.1:18110/realms/mop-test|aae96a1d-7cc0-4b37-9370-6741d93865fd',
  '配置复核管理员（测试身份）',
  'padmin'
);

INSERT INTO public.mop_membership(tenant_id,user_id,role_code)
SELECT id,md5('mop-fixture-user-reviewer')::uuid,'padmin'
FROM public.mop_tenant
WHERE id IN ('55000000-0000-4000-8000-000000000001','55000000-0000-4000-8000-000000000002');
