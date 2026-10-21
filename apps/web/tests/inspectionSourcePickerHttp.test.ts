// Saved responses from an actually started disposable PG17/API with synthetic accounts.
// This replay is not a new HTTP request or a browser claim.
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {readFileSync} from 'node:fs'
import {webcrypto} from 'node:crypto'
import {readWorksheetPage,readWorksheetDetail} from '../src/utils/inspectionWorksheets'
import {readInspectionSourceRevision} from '../src/utils/inspectionSourceRevision'
const capture=JSON.parse(readFileSync('../../fixtures/source-picker-http-synthetic-20261021.json','utf8'))
beforeEach(()=>vi.stubGlobal('crypto',webcrypto));afterEach(()=>vi.unstubAllGlobals())
it('actual first and continuation pages satisfy strict frontend scope cursor and row contracts',()=>{expect(capture.synthetic_only).toBe(true);const first=readWorksheetPage(capture.first_page,capture.context,'CURRENT','');expect(first.items).toHaveLength(15);const second=readWorksheetPage(capture.second_page,capture.context,'CURRENT','',{time:first.next_before_created_at!,id:first.next_before_id!});expect(second.items).toHaveLength(1);expect(second.has_more).toBe(false);expect(new Set([...first.items,...second.items].map(r=>r.id)).size).toBe(16);expect(first.items[5]!.id).toBe(capture.selected.id)})
it('actual selected historical source remains exact after a later manual revision',async()=>{const selected=capture.selected;const source=await readInspectionSourceRevision(capture.source,capture.context,{worksheetId:selected.id,version:selected.version,contentSha256:selected.content_sha256});const current=await readWorksheetDetail(capture.current_after_manual_revision,capture.context);expect(source.version).toBe(1);expect(current.version).toBe(2);expect(current.content_sha256).not.toBe(source.content_sha256);expect(source.active_scan_performed).toBe(false);await expect(readInspectionSourceRevision(capture.source,capture.context,{worksheetId:current.id,version:current.version,contentSha256:current.content_sha256})).rejects.toThrow()})
