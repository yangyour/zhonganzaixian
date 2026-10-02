"""Create fictional OOXML inputs only, in the ignored import verification directory.

No database, service, remote access, credentials, or third-party spreadsheet dependency.
Use: python tools/generate-asset-import-xlsx-fixture.py --run-id <UUID>
Then pass the printed run UUID and ignored fixture to asset-import-http-verification.mjs.
"""
import argparse
import hashlib
import json
import uuid
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
HEADERS = ["设备名称", "设备类型", "IP或域名", "品牌", "型号", "序列号", "站点", "位置", "负责人", "所属业务", "采购日期", "维保到期日", "超期服役日期", "采集方式", "备注", "设备名称", "MOP-FAKE忽略列"]
MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def text_cell(column, row, value):
    return f'<c r="{column}{row}" t="inlineStr"><is><t xml:space="preserve">{escape(value)}</t></is></c>'


def workbook(run_id, formula=False):
    values = [f"MOP-FAKE-{run_id}-XLSX", "MOP-FAKE-unapproved-type", "192.0.2.20", "MOP-FAKE-brand", "MOP-FAKE-model", "MOP-FAKE-SN", "MOP-FAKE-site", "MOP-FAKE-location", "MOP-FAKE-owner", "MOP-FAKE-business", None, "", "", "MOP-FAKE-unapproved-method", "MOP-FAKE原始备注\n第二行", "MOP-FAKE-duplicate-name-ignore", "MOP-FAKE-extra-ignore"]
    header = ''.join(text_cell(chr(65 + index), 2, value) for index, value in enumerate(HEADERS))
    data = ''.join('<c r="K3" t="n" s="1"><v>1.25</v></c>' if index == 10 else text_cell(chr(65 + index), 3, value) for index, value in enumerate(values))
    cover = '<c r="A1" t="n"><f>1+1</f><v>2</v></c>' if formula else text_cell('A', 1, f'MOP-FAKE-{run_id}-cover')
    return {
        '[Content_Types].xml': '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
        '_rels/.rels': f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        'xl/workbook.xml': f'<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="{MAIN}" xmlns:r="{REL}"><workbookPr date1904="1"/><sheets><sheet name="MOP-FAKE Cover" sheetId="1" r:id="rId1"/><sheet name="MOP-FAKE Raw" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>',
        'xl/_rels/workbook.xml.rels': f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{REL}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{REL}/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="{REL}/styles" Target="styles.xml"/></Relationships>',
        'xl/styles.xml': f'<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="{MAIN}"><numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>',
        'xl/worksheets/sheet1.xml': f'<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{MAIN}"><sheetData><row r="1">{cover}</row></sheetData></worksheet>',
        'xl/worksheets/sheet2.xml': f'<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{MAIN}"><sheetData><row r="1">{text_cell("A", 1, f"MOP-FAKE-{run_id}-raw-title")}</row><row r="2">{header}</row><row r="3">{data}</row></sheetData></worksheet>',
    }


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--run-id', default=str(uuid.uuid4()))
    run_id = str(uuid.UUID(args.parse_args().run_id))
    parent = ROOT / 'deploy' / 'private' / 'qa-import-fixtures'
    destination = parent / run_id
    if parent.is_symlink() or destination.is_symlink() or not destination.resolve().is_relative_to(parent.resolve()):
        raise ValueError('fixture_destination_invalid')
    destination.mkdir(parents=True, exist_ok=True)
    files = {}
    for kind in ('valid', 'formula'):
        path = destination / f'MOP-FAKE-{run_id}-{kind}.xlsx'
        if path.exists():
            raise ValueError('fixture_already_exists_use_new_run_id')
        with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for name, xml in workbook(run_id, kind == 'formula').items():
                entry = zipfile.ZipInfo(name, date_time=(2026, 10, 2, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, xml.encode('utf-8'))
        files[kind] = {'name': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    manifest = {'run_id': run_id, 'synthetic_marker': 'MOP-FAKE', 'generator': 'python_stdlib_zipfile', 'files': files, 'expected': {'sheets': [{'index': 0, 'name': 'MOP-FAKE Cover', 'hidden': False}, {'index': 1, 'name': 'MOP-FAKE Raw', 'hidden': True}], 'sheet_index': 1, 'header_row': 2, 'date_epoch': 'MAC_1904', 'serial_column_index': 10, 'serial_lexical': '1.25', 'serial_style_index': 1, 'serial_format_code': 'yyyy-mm-dd hh:mm', 'serial_display': '1904-01-02 06:00'}}
    (destination / 'fixture-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'synthetic_fixtures_created', 'run_id': run_id, 'fixture': f'deploy/private/qa-import-fixtures/{run_id}/{files["valid"]["name"]}', 'credential_output': 'none'}))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # No caught exception/path/body is printed.
        print(json.dumps({'status': 'fixture_generation_failed', 'reason': 'invalid_arguments_or_existing_destination', 'credential_output': 'none'}))
        raise SystemExit(1)
