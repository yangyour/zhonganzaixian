-- Explicit additive migration for self-reported manual warranty facts.
-- V1-V14 bytes, existing history values and original idempotency receipts are not rewritten.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Manual warranty details require the dedicated test database and migration role';
  END IF;
END $$;

ALTER TABLE public.mop_asset_manual_revision
  ADD COLUMN warranty_start date,
  ADD COLUMN warranty_supplier varchar(200),
  ADD COLUMN warranty_details_declared boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT mop_warranty_start_range CHECK(warranty_start IS NULL OR warranty_start BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
  ADD CONSTRAINT mop_warranty_supplier_text CHECK(warranty_supplier IS NULL OR (
    char_length(warranty_supplier) BETWEEN 1 AND 200
    AND warranty_supplier !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND warranty_supplier !~ U&'[\0001-\001F\007F-\009F]')),
  ADD CONSTRAINT mop_warranty_declaration_fields CHECK(warranty_details_declared OR (warranty_start IS NULL AND warranty_supplier IS NULL)),
  ADD CONSTRAINT mop_warranty_dates_order CHECK(warranty_start IS NULL OR warranty_expiry IS NULL OR warranty_start<=warranty_expiry);

-- Old binaries omit the new fields. After any explicit declaration, a legacy default insert
-- must fail rather than forget recorded facts. A modern explicit clear retains declared=true.
CREATE FUNCTION public.mop_warranty_declaration_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF NOT NEW.warranty_details_declared AND EXISTS (
    SELECT 1 FROM public.mop_asset_manual_revision r
    WHERE r.tenant_id=NEW.tenant_id AND r.asset_id=NEW.asset_id AND r.warranty_details_declared
  ) THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Recorded warranty details require an explicit preserving writer';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.mop_warranty_declaration_guard() FROM PUBLIC,mop_test_runtime;
GRANT EXECUTE ON FUNCTION public.mop_warranty_declaration_guard() TO mop_test_runtime;
CREATE TRIGGER mop_asset_warranty_declaration_guard
  BEFORE INSERT ON public.mop_asset_manual_revision
  FOR EACH ROW EXECUTE FUNCTION public.mop_warranty_declaration_guard();
-- Existing SELECT/INSERT and immutable-history restrictions remain; no new table mutation grant.
-- The API must still lock the asset, reauthorize, preserve omitted facts and commit audit/receipt atomically.
