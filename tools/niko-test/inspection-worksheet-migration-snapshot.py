"""Read-only equality check around the explicitly selected V22 migration; no credential or row output."""
from pathlib import Path
import importlib.util, json
spec = importlib.util.spec_from_file_location('inspection_migration_snapshot_base', Path(__file__).with_name('contact-import-fix-migration-snapshot.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.BEFORE = base.LOCAL / 'inspection-v22-business-before.json'
base.AFTER = base.LOCAL / 'inspection-v22-business-after.json'
base.TABLES = (*base.TABLES, 'mop_asset_export_job', 'mop_asset_export_attempt', 'mop_inspection_template', 'mop_inspection_template_revision', 'mop_inspection_template_item')
if __name__ == '__main__':
    try:
        base.main()
    except Exception as failure:
        print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}))
        raise SystemExit(1)
