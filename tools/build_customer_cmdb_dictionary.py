"""Deterministic, read-only extraction of the owner's pinned CMDB workbook.
No Excel execution, network, database mutation or provider calls.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'doc/customer/2026-10-21/运维托管平台_CMDB_CI类型与属性字典_V1.0.xlsx'
SOURCE_BLOB = 'e603591f8621136da5662cf2f57f7ba518fc7328'
OUTPUTS = ['contracts/customer-cmdb-dictionary-v1.json',
           'server/adapters/src/main/resources/cmdb/customer-cmdb-dictionary-v1.json']
NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def read_sheets(data: bytes) -> dict[str, list[dict]]:
    if len(data) > 4 * 1024 * 1024:
        raise ValueError('Workbook exceeds source extraction budget')
    with zipfile.ZipFile(io.BytesIO(data)) as book:
        names = book.namelist()
        if len(names) != len(set(names)) or len(names) > 256:
            raise ValueError('Duplicate or excessive archive entries')
        if sum(f.file_size for f in book.infolist()) > 8 * 1024 * 1024:
            raise ValueError('Expanded workbook exceeds budget')
        for name in names:
            if '..' in PurePosixPath(name).parts or name.startswith('/'):
                raise ValueError('Unsafe archive path')
            if 'externalLinks/' in name or name.endswith('vbaProject.bin'):
                raise ValueError('External links or macros are not source data')
        def xml(name):
            raw = book.read(name)
            if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                raise ValueError('XML declarations not permitted')
            return ET.fromstring(raw)
        shared = []
        if 'xl/sharedStrings.xml' in names:
            shared = [''.join(n.itertext()) for n in xml('xl/sharedStrings.xml')]
        rels = {}
        for item in xml('xl/_rels/workbook.xml.rels'):
            if item.get('TargetMode') == 'External':
                raise ValueError('External workbook relationship')
            target = item.get('Target', '')
            target = target.lstrip('/') if target.startswith('/xl/') else 'xl/' + target
            if '..' in PurePosixPath(target).parts:
                raise ValueError('Unsafe relationship')
            rels[item.get('Id')] = target
        result = {}
        for sheet in xml('xl/workbook.xml').find('m:sheets', NS):
            name = sheet.get('name')
            if name in result:
                raise ValueError('Duplicate sheet name')
            root = xml(rels[sheet.get('{'+REL+'}id')])
            rows = []
            for row in root.findall('.//m:sheetData/m:row', NS):
                cells = {}
                for cell in row:
                    ref = cell.get('r', '')
                    match = re.fullmatch(r'([A-Z]+)([1-9][0-9]*)', ref)
                    if not match or match.group(1) in cells:
                        raise ValueError('Invalid or duplicate cell reference')
                    if cell.find('m:f', NS) is not None:
                        raise ValueError('Formula is not approved source data')
                    kind = cell.get('t')
                    value = cell.find('m:v', NS)
                    inline = cell.find('m:is', NS)
                    text = ''.join(inline.itertext()) if kind == 'inlineStr' and inline is not None else value.text if value is not None else ''
                    if kind == 's': text = shared[int(text)]
                    cells[match.group(1)] = text or ''
                rows.append({'row': int(row.get('r')), 'cells': cells})
            result[name] = rows
        return result


def catalog(data: bytes) -> dict:
    blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if blob != SOURCE_BLOB:
        raise ValueError('Source workbook differs from the explicitly selected customer revision')
    sheets = read_sheets(data)
    def table(sheet, columns, predicate=lambda c: bool(c.get('A'))):
        result = []
        for row in sheets[sheet][1:]:
            cells = row['cells']
            if predicate(cells):
                result.append({**{key: cells.get(col, '') for col, key in columns.items()},
                               'source': {'sheet': sheet, 'row': row['row']}})
        return result
    l1 = table('01_CI类型_L1主类型', {'A':'code','B':'label','H':'declared_subtypes'})
    l2 = table('02_CI类型_L2子类型', {'A':'code','B':'label','C':'parent','D':'monitoring_type_note',
        'E':'monitoring_groups_note','F':'excluded_groups_note','G':'billing_weight',
        'H':'backup_plan_note','I':'inspection_template_note','J':'configuration_backup_note','K':'priority_tags_note'},
        lambda c: bool(re.fullmatch(r'[a-z]+_[a-z]+',c.get('A',''))))
    attrs = table('04_属性字典', dict(zip('ABCDEFGHIJKLM', ['group','code','label','data_type','required_note','uniqueness_note',
        'value_source_note','validation_note','description','derived_read_only_note','spec_field','ledger_field','import_field'])))
    enums = table('05_枚举字典', dict(zip('ABCDEF',['field','enum_name','code','label','constraint_note','description'])))
    relations = table('06_关系类型字典', dict(zip('ABCDEFGH',['code','label','direction_note','cardinality_note','constraint_note','suppression_note','requirement','description'])),
        lambda c: bool(re.fullmatch(r'[a-z]+_[a-z]+',c.get('A',''))))
    states = table('07_状态机', dict(zip('ABCDEFG',['code','label','meaning','actions_note','billing_note','next_condition_note','description'])),
        lambda c: bool(re.fullmatch(r'[a-z_]+',c.get('A',''))))
    codes={row['code'] for row in l1}
    if len(codes)!=9 or len(l1)!=9 or len(l2)!=16 or any(row['parent'] not in codes for row in l2):
        raise ValueError('Unexpected customer taxonomy shape')
    for rows in [l1,l2,attrs,relations,states]:
        if len({row['code'] for row in rows})!=len(rows):raise ValueError('Duplicate dictionary code')
    unresolved=[{'code':'L2_COUNT_CONFLICT','locations':[{'sheet':'02_CI类型_L2子类型','row':18},{'sheet':'04_属性字典','row':6}],
        'declared':15,'observed':len(l2),'resolution':None},
        {'code':'MONITORING_TYPE_MAPPING_PENDING','subtype':'fw_adc','locations':[{'sheet':'02_CI类型_L2子类型','row':8}], 'resolution':None},
        {'code':'IMPORT_COLLECTION_METHOD_CONFLICT','provided':'SSH','locations':[{'sheet':'11_导入模板','row':3}],
         'allowed_codes':[e['code'] for e in enums if e['field']=='collect_method'],'resolution':None},
        {'code':'NOTE_REQUIREDNESS_CONFLICT','locations':[{'sheet':'04_属性字典','row':57}],
         'existing_contract':'AssetImportSchema:NOTE always required','customer_dictionary':'note optional','resolution':None}]
    return {'schema_version':1,'catalog_version':'customer-cmdb-v1-20261021','scope':'CUSTOMER_SOURCE_CATALOG_NOT_RUNTIME_PUBLICATION',
        'source':{'path':SOURCE,'git_blob_sha':blob,'sha256':hashlib.sha256(data).hexdigest(),'sheets':len(sheets)},
        'activated':False,'production_approved':False,'primary_types':l1,'subtypes':l2,'attributes':attrs,'enum_values':enums,
        'relation_types':relations,'lifecycle_states':states,
        'import_columns':sheets['11_导入模板'][0]['cells'],
        'source_sections':{k:v for k,v in sheets.items() if k.startswith(('03','06','07','08','09','10','12','13'))},
        'unresolved':unresolved}


def render(data):
    return json.dumps(catalog(data),ensure_ascii=False,indent=2)+'\n'


def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');p.add_argument('--check',action='store_true');args=p.parse_args()
    if args.write==args.check:p.error('Choose exactly --write or --check')
    output=render((ROOT/SOURCE).read_bytes())
    for name in OUTPUTS:
        path=ROOT/name
        if args.write:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(output)
        elif not path.exists() or path.read_text()!=output:raise SystemExit('CMDB generated catalog is stale: '+name)
    value=json.loads(output)
    print(json.dumps({'result':'PASS','l1':len(value['primary_types']),'l2':len(value['subtypes']),
        'attributes':len(value['attributes']),'issues':len(value['unresolved']),'activated':False}))

if __name__=='__main__':main()
