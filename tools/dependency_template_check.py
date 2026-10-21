"""Offline example-shape check. Never reads secrets, publishes settings or certifies readiness."""
import argparse
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FILES={
 'backup-execution':['backup_execution_enabled'],
 'monitoring-onboarding':['device_onboarding_enabled'],
 'remote-session':['bastion_session_enabled'],
 'report-publication':['formal_publication_enabled','client_delivery_enabled'],
 'operational-finance':['official_financial_output_enabled','billing_submission_enabled'],
 'tenant-governance':['tenant_provisioning_enabled','retention_deletion_enabled'],
}
class TemplateError(ValueError): pass

def check_template(name,data,settings):
    def require(condition,message):
        if not condition: raise TemplateError(f'{name}: {message}')
    require(name in FILES,'unrecognized example')
    require(isinstance(data,dict),'object required')
    require(data.get('schema_version')==1 and type(data.get('schema_version')) is int,'schema version required')
    require(data.get('purpose')=='dependency_shape_only_not_a_runtime_publication','wrong purpose')
    require(data.get('production_approved') is False,'example cannot approve production')
    require('tenant_id' in data and data['tenant_id'] is None,'example must not target a tenant')
    for flag in FILES[name]: require(data.get(flag) is False,'execution or publication flags must be explicitly false')
    questions=data.get('question_ids')
    require(isinstance(questions,list) and all(isinstance(q,str) and q in {f'Q{i:02d}' for i in range(1,32)} for q in questions) and len(questions)==len(set(questions)),'invalid question references')
    publications=data.get('runtime_publications');fixed=data.get('fixed_registry_keys')
    require(isinstance(publications,dict) and isinstance(fixed,list) and all(isinstance(k,str) for k in fixed) and len(fixed)==len(set(fixed)),'invalid registry groups')
    for key,value in publications.items():
        require(key in settings,'unknown runtime key')
        require(settings[key]['contract_value_kind']!='fixed_constraint','fixed constraint cannot be an editable slot')
        require(value is None,'unresolved runtime slot must remain null')
    for key in fixed:
        require(key in settings and settings[key]['contract_value_kind']=='fixed_constraint','fixed reference must name an actual fixed constraint')
        require(key not in publications,'fixed and editable groups overlap')
    require(isinstance(data.get('notes'),str) and bool(data['notes'].strip()),'scope note required')
    known={'schema_version','purpose','question_ids','production_approved','tenant_id','runtime_publications','fixed_registry_keys','notes',*FILES[name]}
    refs=[k for k in data if k not in known]
    require(bool(refs),'dependency references required')
    for key in refs:require(key.endswith('_reference') and data[key] is None,'reference slots must be null; unknown fields and supplied secrets are not accepted')
    return {'module':name,'unresolved_reference_slots':len(refs),'unpublished_runtime_slots':len(publications),'fixed_registry_keys':fixed,'question_ids':questions,'ready':False,'execution_authorized':False}

def report(root=ROOT):
    settings={r['key']:r for r in json.loads((root/'contracts/config-baseline.json').read_text())['settings']}
    rows=[check_template(name,json.loads((root/'deploy/examples'/f'{name}-dependencies.json').read_text()),settings) for name in FILES]
    return {'scope':'OFFLINE_EXAMPLE_SHAPE_NOT_RUNTIME_READINESS','shape_valid':True,'ready':False,'production_approved':False,'external_connections_checked':False,'modules':rows}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try: print(json.dumps(report(),ensure_ascii=False,indent=2))
    except (TemplateError,KeyError,TypeError,json.JSONDecodeError) as error:
        raise SystemExit('Dependency example shape check failed; no runtime action performed: '+str(error))
