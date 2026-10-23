"""Check complete and unique contract coverage without asserting implementation or acceptance."""
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
def check():
    source=json.loads((ROOT/'doc/development/evidence/contract_requirements.json').read_text(encoding='utf-8'))
    checklist=json.loads((ROOT/'docs/implementation-gap-checklist-20261023.json').read_text(encoding='utf-8'))
    expected={r['id'] for r in source['requirements']}
    requirements=checklist['requirements'];tasks=checklist['tasks']
    assert len(expected)==128 and len(requirements)==128 and {r['id'] for r in requirements}==expected, 'Contract coverage mismatch'
    ids={t['id'] for t in tasks}
    assert len(ids)==len(tasks), 'Duplicate gap ID'
    owners={'integration','assets','monitoring','notifications','backups','reports','production'}
    states={'OPEN','IN_PROGRESS','CODE_COMPLETE_NEEDS_VERIFICATION','VERIFIED','CUSTOMER_GATED','DEFERRED_MOBILE'}
    for task in tasks:
        assert task['owner'] in owners and task['status'] in states, 'Unknown owner/status'
        assert task['completion_criterion'] and set(task['requirement_ids'])<=expected and task['requirement_ids'], 'Unmapped task'
        if task['status']=='VERIFIED':
            assert task['evidence'] and task['commits'], 'Verified task lacks evidence'
        for commit in task['commits']:
            assert re.fullmatch(r'[a-f0-9]{7,40}',commit), 'Invalid commit reference'
        for reference in task['evidence']:
            target=(ROOT/reference).resolve()
            assert target.is_relative_to(ROOT.resolve()) and target.is_file() and 'private' not in target.parts, 'Invalid evidence reference'
    for requirement in requirements:
        assert requirement['owner'] in owners and requirement['gap_ids'], 'Unassigned requirement'
        assert set(requirement['gap_ids'])=={t['id'] for t in tasks if requirement['id'] in t['requirement_ids']}, 'Coverage drift'
        assert requirement['formal_acceptance']=='NOT_RUN', 'Formal acceptance must remain in the authoritative contract tracker'
    print(json.dumps({'status':'PASSED','requirements':128,'tasks':len(tasks),'unassigned':0,'completion_verified':False}))
if __name__=='__main__':check()
