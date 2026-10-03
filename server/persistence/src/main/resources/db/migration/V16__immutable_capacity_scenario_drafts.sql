-- Explicit independent additive migration. This stores an immutable proposal, not approved/measured capacity.
-- V1-V15 and prior business records/receipts are not rewritten. API/Worker never invoke this migration.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Capacity scenario drafts require dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_capacity_scenario_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  actor_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CONSTRAINT mop_capacity_draft_version CHECK(version=1),
  label varchar(100) NOT NULL CONSTRAINT mop_capacity_draft_label CHECK(char_length(label) BETWEEN 1 AND 100
    AND label !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND label !~ U&'[\0001-\001F\007F-\009F]'),
  note text NOT NULL CONSTRAINT mop_capacity_draft_note CHECK(char_length(note) BETWEEN 1 AND 2000
    AND note !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  calculation jsonb NOT NULL CONSTRAINT mop_capacity_draft_calculation CHECK(jsonb_typeof(calculation)='object'
    AND calculation ?& ARRAY['inputs','model','proxy_buffer']
    AND calculation-ARRAY['inputs','model','proxy_buffer']='{}'::jsonb
    AND jsonb_typeof(calculation->'inputs') IN ('object','null')
    AND jsonb_typeof(calculation->'model') IN ('object','null')
    AND jsonb_typeof(calculation->'proxy_buffer') IN ('object','null')
    AND octet_length(calculation::text)<=32768),
  calculation_sha256 char(64) NOT NULL CONSTRAINT mop_capacity_draft_calculation_digest CHECK(calculation_sha256 ~ '^[0-9a-f]{64}$' AND calculation_sha256=encode(sha256(convert_to(calculation::text,'UTF8')),'hex')),
  snapshot jsonb NOT NULL CONSTRAINT mop_capacity_draft_snapshot CHECK(jsonb_typeof(snapshot)='object'
    AND snapshot ?& ARRAY['capacity_formula_version','buffer_formula_version','capacity','proxy_buffer']
    AND snapshot-ARRAY['capacity_formula_version','buffer_formula_version','capacity','proxy_buffer']='{}'::jsonb
    AND jsonb_typeof(snapshot->'capacity')='object' AND jsonb_typeof(snapshot->'proxy_buffer')='object'
    AND octet_length(snapshot::text)<=32768),
  snapshot_sha256 char(64) NOT NULL CONSTRAINT mop_capacity_draft_snapshot_digest CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$' AND snapshot_sha256=encode(sha256(convert_to(snapshot::text,'UTF8')),'hex')),
  capacity_formula_version varchar(64) NOT NULL DEFAULT 'contract-m10-7-capacity-v1' CONSTRAINT mop_capacity_draft_capacity_formula CHECK(capacity_formula_version='contract-m10-7-capacity-v1'),
  buffer_formula_version varchar(64) NOT NULL DEFAULT 'contract-m4-15-buffer-v1' CONSTRAINT mop_capacity_draft_buffer_formula CHECK(buffer_formula_version='contract-m4-15-buffer-v1'),
  benchmark_reference varchar(160),
  benchmark_title varchar(200),
  benchmark_sha256 char(64),
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CONSTRAINT mop_capacity_draft_recorded_at CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  lifecycle_status varchar(16) NOT NULL DEFAULT 'DRAFT' CONSTRAINT mop_capacity_draft_lifecycle CHECK(lifecycle_status='DRAFT'),
  model_approval_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' CONSTRAINT mop_capacity_draft_model_approval CHECK(model_approval_status='UNVERIFIED'),
  measurement_verified boolean NOT NULL DEFAULT false CONSTRAINT mop_capacity_draft_measurement CHECK(NOT measurement_verified),
  poc_executed boolean NOT NULL DEFAULT false CONSTRAINT mop_capacity_draft_poc CHECK(NOT poc_executed),
  effective boolean NOT NULL DEFAULT false CONSTRAINT mop_capacity_draft_effective CHECK(NOT effective),
  record_source varchar(40) NOT NULL DEFAULT 'EXPLICIT_CAPACITY_DRAFT' CONSTRAINT mop_capacity_draft_source CHECK(record_source='EXPLICIT_CAPACITY_DRAFT'),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CONSTRAINT mop_capacity_draft_snapshot_versions CHECK((snapshot->>'capacity_formula_version') IS NOT DISTINCT FROM capacity_formula_version AND (snapshot->>'buffer_formula_version') IS NOT DISTINCT FROM buffer_formula_version),
  CONSTRAINT mop_capacity_draft_benchmark_group CHECK((benchmark_reference IS NULL AND benchmark_title IS NULL AND benchmark_sha256 IS NULL) OR (benchmark_reference IS NOT NULL AND benchmark_title IS NOT NULL AND benchmark_sha256 IS NOT NULL)),
  CONSTRAINT mop_capacity_draft_benchmark_text CHECK(benchmark_reference IS NULL OR (
    char_length(benchmark_reference) BETWEEN 14 AND 160 AND benchmark_reference ~ '^document-ref:[A-Za-z0-9._:-]+$'
    AND benchmark_reference !~ '^document-ref:[A-Za-z]:.*'
    AND benchmark_reference !~* 'token|password|secret|credential|private[_:-]?key'
    AND char_length(benchmark_title) BETWEEN 1 AND 200
    AND benchmark_title !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND benchmark_title !~ U&'[\0001-\001F\007F-\009F]' AND benchmark_sha256 ~ '^[0-9a-f]{64}$')),
  CONSTRAINT mop_capacity_draft_capacity_snapshot CHECK(
    (snapshot->'capacity') ?& ARRAY['status','model_version','outputs','missing_inputs']
    AND (snapshot->'capacity')-ARRAY['status','model_version','outputs','missing_inputs']='{}'::jsonb
    AND jsonb_typeof(snapshot->'capacity'->'missing_inputs')='array'
    AND ((snapshot->'capacity'->>'status'='ESTIMATED' AND jsonb_typeof(snapshot->'capacity'->'model_version')='string' AND jsonb_typeof(snapshot->'capacity'->'outputs')='object' AND jsonb_array_length(CASE WHEN jsonb_typeof(snapshot->'capacity'->'missing_inputs')='array' THEN snapshot->'capacity'->'missing_inputs' ELSE '[]'::jsonb END)=0)
      OR (snapshot->'capacity'->>'status'='UNRESOLVED' AND jsonb_typeof(snapshot->'capacity'->'model_version') IN ('string','null') AND jsonb_typeof(snapshot->'capacity'->'outputs')='null' AND jsonb_array_length(CASE WHEN jsonb_typeof(snapshot->'capacity'->'missing_inputs')='array' THEN snapshot->'capacity'->'missing_inputs' ELSE '[]'::jsonb END)>0))
    AND jsonb_typeof(snapshot->'capacity'->'status')='string'),
  CONSTRAINT mop_capacity_draft_buffer_snapshot CHECK(
    (snapshot->'proxy_buffer') ?& ARRAY['status','estimated_bytes','missing_inputs']
    AND (snapshot->'proxy_buffer')-ARRAY['status','estimated_bytes','missing_inputs']='{}'::jsonb
    AND jsonb_typeof(snapshot->'proxy_buffer'->'missing_inputs')='array'
    AND ((snapshot->'proxy_buffer'->>'status'='ESTIMATED' AND jsonb_typeof(snapshot->'proxy_buffer'->'estimated_bytes')='number' AND jsonb_array_length(CASE WHEN jsonb_typeof(snapshot->'proxy_buffer'->'missing_inputs')='array' THEN snapshot->'proxy_buffer'->'missing_inputs' ELSE '[]'::jsonb END)=0)
      OR (snapshot->'proxy_buffer'->>'status'='UNRESOLVED' AND jsonb_typeof(snapshot->'proxy_buffer'->'estimated_bytes')='null' AND jsonb_array_length(CASE WHEN jsonb_typeof(snapshot->'proxy_buffer'->'missing_inputs')='array' THEN snapshot->'proxy_buffer'->'missing_inputs' ELSE '[]'::jsonb END)>0))
    AND jsonb_typeof(snapshot->'proxy_buffer'->'status')='string')
);
CREATE INDEX mop_capacity_scenario_order ON public.mop_capacity_scenario_draft(tenant_id,recorded_at DESC,id DESC);
REVOKE ALL ON public.mop_capacity_scenario_draft FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_capacity_scenario_draft TO mop_test_runtime;
-- Fingerprints bind stored JSONB equality, not authenticity. Structured JSON is not arithmetic validation.
-- The repository must reauthorize padmin before replay, recompute/compare fixed-version results on reads,
-- and atomically append the scenario, audit and original idempotency receipt without publishing configuration.
