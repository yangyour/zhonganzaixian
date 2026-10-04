"""Deterministic contract/tracker index. Checks declarations and links, never certifies acceptance."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

REQUIREMENTS = Path('doc/development/evidence/contract_requirements.json')
TRACKER = Path('doc/development/07_合同逐项开发追踪.md')
OUTPUT = Path('docs/contract-progress.json')
ID = r'M[1-9][0-9]*-(?:0|[1-9][0-9]*)'
STATUSES = {'NOT_RUN', 'IN_PROGRESS', 'BLOCKED', 'FAIL', 'PASS', 'DONE', 'NOT_APPLICABLE'}

class ProgressError(ValueError):
    pass

def visible_lines(text: str) -> list[str]:
    lines, fence = [], None
    for line in text.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if marker:
            value = marker.group(1)
            if fence is None:
                fence = value
            elif value[0] == fence[0] and len(value) >= len(fence):
                fence = None
            continue
        if fence is None:
            lines.append(line)
    if fence is not None:
        raise ProgressError('Unclosed code fence in tracker')
    text = re.sub(r'<!--.*?-->', '', '\n'.join(lines), flags=re.S)
    if '<!--' in text or '-->' in text:
        raise ProgressError('Unbalanced HTML comment in tracker')
    return text.splitlines()

def local_references(note: str, requirement_id: str, root: Path) -> tuple[list[str], int]:
    local, external = set(), 0
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', note):
        try:
            value = urlsplit(target)
            if value.username is not None or value.password is not None:
                raise ProgressError(f'{requirement_id}: credential-bearing reference is not supported')
        except ValueError as error:
            raise ProgressError(f'{requirement_id}: invalid reference') from error
        if value.scheme in {'http', 'https'} or not value.scheme and value.netloc:
            external += 1
            continue
        if value.scheme:
            raise ProgressError(f'{requirement_id}: unsupported reference scheme')
        path = unquote(value.path)
        if '\\' in path or any(ord(c) < 32 for c in path):
            raise ProgressError(f'{requirement_id}: invalid local reference')
        candidate = (root / TRACKER.parent / path).resolve() if path else (root / TRACKER).resolve()
        if not candidate.is_relative_to(root):
            raise ProgressError(f'{requirement_id}: reference leaves repository')
        relative = candidate.relative_to(root)
        if relative.parts[:1] == ('.local',) or relative.parts[:2] == ('deploy', 'private'):
            raise ProgressError(f'{requirement_id}: private runtime evidence cannot be indexed')
        if not candidate.is_file():
            raise ProgressError(f'{requirement_id}: local evidence reference is missing')
        local.add(relative.as_posix())
    return sorted(local), external

def build_report(document: dict, tracker: str, root: Path, expected_count: int = 128) -> dict:
    root = root.resolve()
    if not isinstance(document, dict):
        raise ProgressError('Extracted contract document must be an object')
    requirements = document.get('requirements')
    if not isinstance(requirements, list) or len(requirements) != expected_count or type(document.get('requirement_count')) is not int or document.get('requirement_count') != expected_count:
        raise ProgressError('Extracted contract count does not match the explicit scope')
    by_id = {}
    for item in requirements:
        if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not re.fullmatch(ID, item['id']):
            raise ProgressError('Invalid extracted requirement identity')
        if item['id'] in by_id:
            raise ProgressError('Duplicate extracted requirement identity')
        if any(not isinstance(item.get(k), str) or not item[k].strip() for k in ['title', 'scope']):
            raise ProgressError('Missing extracted requirement metadata')
        if not isinstance(item.get('source'), dict) or not isinstance(item['source'].get('locator'), str):
            raise ProgressError('Missing extracted source locator')
        by_id[item['id']] = item
    rows, sections, statuses, current = {}, set(), {}, None
    for line in visible_lines(tracker):
        if re.match(r'^\|\s*'+ID+r'\s*\|', line):
            cells = [s.strip().replace('\\|', '|') for s in re.split(r'(?<!\\)\|', line)[1:-1]]
            if len(cells) != 8 or cells[0] not in by_id or cells[0] in rows:
                raise ProgressError('Unknown, duplicate or malformed requirement table row')
            item = by_id[cells[0]]
            if cells[1:5] != [item['title'], item['scope'], item['source']['locator'], 'TC-'+item['id']]:
                raise ProgressError(f"{item['id']}: table metadata differs from extracted contract")
            rows[cells[0]] = cells[7]
        heading = re.match(r'^###\s+TC-('+ID+r')(?:\s|$)', line)
        if heading:
            current = heading.group(1)
            if current not in by_id or current in sections:
                raise ProgressError('Unknown or duplicate acceptance section')
            sections.add(current)
        elif re.match(r'^#{1,6}\s', line):
            current = None
        elif current and line.startswith('执行状态：'):
            status = re.match(r'^执行状态：([A-Z_]+)(?:；|;|$)', line)
            if not status or status.group(1) not in STATUSES or current in statuses:
                raise ProgressError(f'{current}: invalid or duplicate acceptance status')
            statuses[current] = status.group(1)
    expected = set(by_id)
    if set(rows) != expected or sections != expected or set(statuses) != expected:
        raise ProgressError('Tracker table and acceptance sections must cover the complete extracted scope')
    items, modules = [], {}
    for item in requirements:
        requirement_id = item['id']
        refs, external = local_references(rows[requirement_id], requirement_id, root)
        noted = bool(rows[requirement_id].strip())
        items.append({'id': requirement_id, 'title': item['title'], 'contract_scope': item['scope'], 'has_progress_note': noted, 'local_references': refs, 'external_references_unchecked': external, 'declared_acceptance_status': statuses[requirement_id]})
        module = requirement_id.split('-')[0]
        group = modules.setdefault(module, {'requirements': 0, 'rows_with_progress_notes': 0, 'declared_acceptance_status_counts': {}})
        group['requirements'] += 1
        group['rows_with_progress_notes'] += int(noted)
        counts = group['declared_acceptance_status_counts']
        counts[statuses[requirement_id]] = counts.get(statuses[requirement_id], 0) + 1
    counts = dict(sorted(Counter(statuses.values()).items()))
    incomplete = any(counts.get(s, 0) for s in ['NOT_RUN', 'IN_PROGRESS', 'BLOCKED', 'FAIL'])
    return {'schema_version': 1, 'scope': 'EXTRACTED_CONTRACT_AND_TRACKER_DECLARATIONS_ONLY', 'requirement_count': expected_count, 'rows_with_progress_notes': sum(x['has_progress_note'] for x in items), 'rows_without_progress_notes': sum(not x['has_progress_note'] for x in items), 'declared_acceptance_status_counts': counts, 'project_declaration_state': 'ACCEPTANCE_INCOMPLETE' if incomplete else 'DECLARATIONS_REQUIRE_EVIDENCE_REVIEW', 'completion_verified': False, 'progress_notes_are_completed_requirements': False, 'test_counts_are_contract_completion_percentage': False, 'linked_evidence_contents_or_authenticity_verified': False, 'external_references_fetched': False, 'modules': modules, 'requirements': items}

def generate(root: Path) -> str:
    sources = {}
    for path in [REQUIREMENTS, TRACKER]:
        full = root / path
        if full.stat().st_size > 2 * 1024 * 1024:
            raise ProgressError('Source exceeds technical read limit')
        sources[path.as_posix()] = full.read_bytes()
    report = build_report(json.loads(sources[REQUIREMENTS.as_posix()]), sources[TRACKER.as_posix()].decode('utf-8'), root)
    report['source_sha256'] = {path: hashlib.sha256(content).hexdigest() for path, content in sources.items()}
    return json.dumps(report, ensure_ascii=False, indent=2) + '\n'

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--write', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        expected = generate(root)
        if args.write:
            (root / OUTPUT).write_text(expected, encoding='utf-8')
        elif not (root / OUTPUT).is_file() or (root / OUTPUT).read_text(encoding='utf-8') != expected:
            raise ProgressError('Contract progress index is stale; run python3 tools/contract_progress.py --write')
        data = json.loads(expected)
        print(json.dumps({'index_consistent': True, 'requirements': data['requirement_count'], 'rows_with_progress_notes': data['rows_with_progress_notes'], 'declared_acceptance_status_counts': data['declared_acceptance_status_counts'], 'completion_verified': False}, ensure_ascii=False))
        return 0
    except (ProgressError, OSError, ValueError, UnicodeError) as error:
        detail = str(error) if isinstance(error, ProgressError) else 'Source input could not be read or decoded'
        print(json.dumps({'index_consistent': False, 'completion_verified': False, 'error': detail}, ensure_ascii=False))
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
