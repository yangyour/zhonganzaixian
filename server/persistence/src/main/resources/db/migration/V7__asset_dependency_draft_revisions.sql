-- Independent explicit test migration only; API/worker startup must never apply it.
-- Draft relationships are not published monitoring rules and do not infer real impact.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'MOP dependency drafts require dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_asset_dependency_graph (
  tenant_id uuid PRIMARY KEY REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
);
CREATE TABLE public.mop_asset_dependency_revision (
  tenant_id uuid NOT NULL REFERENCES public.mop_asset_dependency_graph(tenant_id),
  version bigint NOT NULL CHECK(version>=1),
  created_by uuid NOT NULL, created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  lifecycle_status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(lifecycle_status='DRAFT'),
  decision_status varchar(16) NOT NULL DEFAULT 'pending' CHECK(decision_status='pending'),
  monitoring_applied boolean NOT NULL DEFAULT false CHECK(NOT monitoring_applied),
  PRIMARY KEY(tenant_id,version),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
ALTER TABLE public.mop_asset_dependency_graph ADD FOREIGN KEY(tenant_id,version)
  REFERENCES public.mop_asset_dependency_revision(tenant_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE public.mop_asset_dependency_edge (
  tenant_id uuid NOT NULL, revision_version bigint NOT NULL,
  parent_id uuid NOT NULL, child_id uuid NOT NULL,
  proposed_dependency_type varchar(64), proposed_suppression_scope varchar(128), proposed_enabled boolean,
  PRIMARY KEY(tenant_id,revision_version,parent_id,child_id),
  FOREIGN KEY(tenant_id,revision_version) REFERENCES public.mop_asset_dependency_revision(tenant_id,version),
  FOREIGN KEY(tenant_id,parent_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,child_id) REFERENCES public.mop_asset(tenant_id,id),
  CHECK(parent_id<>child_id),
  CHECK(proposed_dependency_type IS NULL OR length(btrim(proposed_dependency_type))>0),
  CHECK(proposed_suppression_scope IS NULL OR length(btrim(proposed_suppression_scope))>0)
);
CREATE INDEX mop_asset_dependency_edge_child ON public.mop_asset_dependency_edge(tenant_id,revision_version,child_id);

REVOKE ALL ON public.mop_asset_dependency_graph,public.mop_asset_dependency_revision,public.mop_asset_dependency_edge FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_asset_dependency_graph,public.mop_asset_dependency_revision,public.mop_asset_dependency_edge TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_asset_dependency_graph TO mop_test_runtime;
-- No historical UPDATE, DELETE, TRUNCATE, bucket operation or monitoring rule publication.
