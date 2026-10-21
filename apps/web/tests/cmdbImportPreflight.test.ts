import {describe,it,expect} from 'vitest'
import Papa from 'papaparse'
import fixture from '../../../fixtures/cmdb-type-catalog-controller.json'
import {readCmdbTypeCatalog} from '../src/utils/cmdbTypeCatalog'
import {CMDB_IMPORT_COLUMNS,cmdbBlankCsv,preflightCmdbCsv} from '../src/utils/cmdbImportPreflight'
const catalog=readCmdbTypeCatalog(fixture.data,fixture.data.tenant_id)
const row=()=>{const v=Array<string>(33).fill('');v[0]='合成交换机';v[1]='SW';v[2]='192.0.2.1';v[16]='sw_core';return v}
const csv=(rows:string[][])=>Papa.unparse([CMDB_IMPORT_COLUMNS.map(c=>c.label),...rows])
describe('CMDB 33-column local source preflight',()=>{
 it('never authorizes import even for syntactically valid records',()=>{const r=preflightCmdbCsv(csv([row()]),catalog);expect(r.errorRows).toBe(0);expect(r.canImport).toBe(false);expect(r.databaseChecks).toBe('NOT_RUN')})
 it('blank template has exactly source columns and no asset data',()=>{const p=Papa.parse<string[]>(cmdbBlankCsv());expect(p.data[0]).toEqual(CMDB_IMPORT_COLUMNS.map(c=>c.label));expect(()=>preflightCmdbCsv(cmdbBlankCsv(),catalog)).toThrow('没有数据')})
 it('preserves quoted comma, newline, Unicode and leading zeros as text',()=>{const v=row();v[0]='合成,设备😀\n第二行';v[5]='000001';expect(preflightCmdbCsv(csv([v]),catalog).rows[0]).toEqual(v)})
 it('rejects old, duplicate and reordered headers',()=>{for(const h of [CMDB_IMPORT_COLUMNS.slice(0,15).map(c=>c.label),Array(33).fill('设备名称*'),CMDB_IMPORT_COLUMNS.map(c=>c.label).reverse()])expect(()=>preflightCmdbCsv(Papa.unparse([h,row()]),catalog)).toThrow('表头')})
 it('rejects oversized content, NUL and unclosed quoted records',()=>{for(const t of ['a'.repeat(1048577),'\u0000','"unterminated'])expect(()=>preflightCmdbCsv(t,catalog)).toThrow()})
 it('accepts 500 records but rejects 501 rather than truncating silently',()=>{expect(preflightCmdbCsv(csv(Array.from({length:500},row)),catalog).records).toBe(500);expect(()=>preflightCmdbCsv(csv(Array.from({length:501},row)),catalog)).toThrow('500')})
 it('locates short rows and required values without dropping internal blanks',()=>{const v=row();v[0]='';const r=preflightCmdbCsv(csv([v,[''],row()]),catalog);expect(r.findings).toContainEqual(expect.objectContaining({record:2,column:0,code:'REQUIRED'}));expect(r.findings).toContainEqual(expect.objectContaining({record:3,code:'COLUMN_COUNT'}));expect(r.records).toBe(3)})
 it('checks parent relationships, unknown type and pending monitoring mapping',()=>{const v=row();v[16]='fw_adc';expect(preflightCmdbCsv(csv([v]),catalog).findings.some(f=>f.code==='SUBTYPE_PARENT_MISMATCH')).toBe(true);v[1]='FW';expect(preflightCmdbCsv(csv([v]),catalog).findings.some(f=>f.code==='MAPPING_PENDING')).toBe(true);v[1]='OTHER';expect(preflightCmdbCsv(csv([v]),catalog).errorRows).toBe(1)})
 it('does not silently accept SSH or invent a note requiredness resolution',()=>{const v=row();v[13]='SSH';const r=preflightCmdbCsv(csv([v]),catalog);expect(r.findings.some(f=>f.code==='COLLECTION_PENDING')).toBe(true);expect(r.findings.some(f=>f.column===14)).toBe(false)})
 it('invalid date is an error; ambiguous format remains a warning',()=>{const v=row();v[10]='2025-02-29';v[11]='02/03/2026';const r=preflightCmdbCsv(csv([v]),catalog);expect(r.findings.map(f=>f.code)).toEqual(expect.arrayContaining(['DATE_INVALID','DATE_FORMAT_PENDING']))})
 it('preserves whitespace/formula-like text and flags exact duplicate records',()=>{const v=row();v[0]=' =SUM(1,2) ';const r=preflightCmdbCsv(csv([v,v]),catalog);expect(r.rows[0]![0]).toBe(v[0]);expect(r.findings.map(f=>f.code)).toEqual(expect.arrayContaining(['WHITESPACE','SPREADSHEET_FORMULA_RISK','EXACT_DUPLICATE_IN_FILE']))})
 it('rejects oversized cell/column structures before building a large preview',()=>{const v=row();v[0]='x'.repeat(2049);expect(()=>preflightCmdbCsv(csv([v]),catalog)).toThrow('2048');expect(()=>preflightCmdbCsv(csv([Array(65).fill('x')]),catalog)).toThrow('列数异常')})
 it('accepts a 500-record file with its normal trailing newline',()=>{expect(preflightCmdbCsv(csv(Array.from({length:500},row))+'\r\n',catalog).records).toBe(500)})
 it('refuses catalog/schema version drift',()=>{expect(()=>preflightCmdbCsv(csv([row()]),{...catalog,catalog_hash:'0'.repeat(64)})).toThrow('同一版本')})
})
