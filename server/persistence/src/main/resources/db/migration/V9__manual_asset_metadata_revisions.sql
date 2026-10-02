-- Explicit independent migration for manual, not-yet-onboarded asset metadata only.
-- Does not rewrite V1-V8 or give the runtime permission to change IP/source/monitoring state.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'MOP manual asset metadata requires dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_asset_manual_revision (
  tenant_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  asset_version bigint NOT NULL CHECK(asset_version>=2),
  previous_metadata_version bigint CHECK(previous_metadata_version>=2 AND previous_metadata_version<asset_version),
  actor_id uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  name varchar(100) NOT NULL CHECK(length(btrim(name))>0),
  type varchar(32) NOT NULL CHECK(type IN ('router','switch','firewall','ap','wireless_controller','other')),
  management_ip varchar(45) NOT NULL,
  site_id varchar(64) NOT NULL,
  brand varchar(100) CHECK(brand IS NULL OR length(btrim(brand))>0),
  model varchar(100) CHECK(model IS NULL OR length(btrim(model))>0),
  serial_number varchar(128) CHECK(serial_number IS NULL OR length(btrim(serial_number))>0),
  location varchar(200) CHECK(location IS NULL OR length(btrim(location))>0),
  owner_id uuid,
  business_description varchar(200) CHECK(business_description IS NULL OR length(btrim(business_description))>0),
  purchase_date date CHECK(purchase_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
  warranty_expiry date CHECK(warranty_expiry BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
  overdue_service_date date CHECK(overdue_service_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
  collection_method_note varchar(100) CHECK(collection_method_note IS NULL OR length(btrim(collection_method_note))>0),
  note varchar(2000) NOT NULL CHECK(length(btrim(note))>0),
  before_snapshot jsonb NOT NULL CHECK(jsonb_typeof(before_snapshot)='object'),
  verification_status varchar(24) NOT NULL DEFAULT 'SELF_REPORTED' CHECK(verification_status='SELF_REPORTED'),
  monitoring_applied boolean NOT NULL DEFAULT false CHECK(NOT monitoring_applied),
  cmdb_applied boolean NOT NULL DEFAULT false CHECK(NOT cmdb_applied),
  PRIMARY KEY(tenant_id,asset_id,asset_version),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,site_id) REFERENCES public.mop_site(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id,previous_metadata_version) REFERENCES public.mop_asset_manual_revision(tenant_id,asset_id,asset_version),
  CHECK(before_snapshot ?& ARRAY['asset','metadata']),
  CHECK(jsonb_typeof(before_snapshot->'asset')='object'),
  CHECK((before_snapshot->'asset') @> jsonb_build_object('id',asset_id,'tenant_id',tenant_id,'version',asset_version-1,'management_ip',management_ip,
    'source','manual','lifecycle_status','DRAFT','status','unknown','sampled_at',NULL,'availability','UNKNOWN','sync_state','PENDING_ONBOARDING')),
  CHECK((previous_metadata_version IS NULL AND before_snapshot->'metadata'='null'::jsonb)
    OR (previous_metadata_version IS NOT NULL AND jsonb_typeof(before_snapshot->'metadata')='object'))
);
CREATE TABLE public.mop_asset_manual_details (
  tenant_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  revision_version bigint NOT NULL CHECK(revision_version>=2),
  PRIMARY KEY(tenant_id,asset_id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id,revision_version) REFERENCES public.mop_asset_manual_revision(tenant_id,asset_id,asset_version) DEFERRABLE INITIALLY DEFERRED
);

REVOKE ALL ON public.mop_asset_manual_revision,public.mop_asset_manual_details FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_asset_manual_revision,public.mop_asset_manual_details TO mop_test_runtime;
GRANT UPDATE(revision_version) ON public.mop_asset_manual_details TO mop_test_runtime;
GRANT UPDATE(name,type,site_id,version,updated_at,updated_by) ON public.mop_asset TO mop_test_runtime;
-- No historical UPDATE/DELETE, IP/tenant/source/status mutation, default date or monitoring/CMDB write.
