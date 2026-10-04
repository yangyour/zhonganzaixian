"""Read-only full-row equality of the 48 reviewed pre-V23 project tables; no credentials or row output."""
from pathlib import Path
import importlib.util,json
spec=importlib.util.spec_from_file_location('source_collection_snapshot_base',Path(__file__).with_name('contact-import-fix-migration-snapshot.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
base.BEFORE=base.LOCAL/'inspection-source-v23-business-before.json'
base.AFTER=base.LOCAL/'inspection-source-v23-business-after.json'
base.TABLES=('mop_asset', 'mop_asset_dependency_edge', 'mop_asset_dependency_graph', 'mop_asset_dependency_revision', 'mop_asset_export_attempt', 'mop_asset_export_job', 'mop_asset_import_artifact', 'mop_asset_import_job', 'mop_asset_import_mapping', 'mop_asset_import_operation', 'mop_asset_import_source', 'mop_asset_manual_details', 'mop_asset_manual_revision', 'mop_audit', 'mop_capacity_scenario_draft', 'mop_configuration', 'mop_configuration_revision', 'mop_configuration_revision_history', 'mop_contact_import_job', 'mop_contact_import_result', 'mop_contact_tenant_guard', 'mop_customer_contact', 'mop_customer_contact_revision', 'mop_file', 'mop_file_grant', 'mop_idempotency', 'mop_inspection_template', 'mop_inspection_template_item', 'mop_inspection_template_revision', 'mop_inspection_worksheet', 'mop_inspection_worksheet_revision', 'mop_manual_incident_registration', 'mop_manual_recovery_confirmation', 'mop_membership', 'mop_operation', 'mop_outbox', 'mop_photo_job', 'mop_site', 'mop_tenant', 'mop_ticket', 'mop_ticket_comment', 'mop_ticket_history', 'mop_ticket_internal_note', 'mop_user', 'mop_working_calendar_draft', 'mop_working_calendar_revision', 'mop_worklog', 'mop_worklog_revision')
if __name__=='__main__':
    try:base.main()
    except Exception as failure:
        print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}));raise SystemExit(1)
