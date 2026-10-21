import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {webcrypto} from 'node:crypto'
import {createInspectionWorkspace,emptyInspectionState} from '../src/utils/inspectionTemplateWorkspace'
import {createWorksheetWorkspace,emptyWorksheetState} from '../src/utils/inspectionWorksheetWorkspace'
import {createSourceCollectionWorkspace,emptySourceCollectionState} from '../src/utils/inspectionSourceCollectionWorkspace'
import {createReportWorkspace,emptyReportState} from '../src/utils/inspectionQuarterlyReportWorkspace'
import {createApprovalWorkspace,emptyApprovalState} from '../src/utils/changeNoticeApprovalWorkspace'
import {definition,scope as templateScope} from './inspectionTemplate.fixture'
import {payload as worksheetPayload,source,scope as worksheetScope} from './inspectionWorksheet.fixture'
import {payload as collectionPayload,scope as collectionScope} from './inspectionSourceCollection.fixture'
import {payload as reportPayload,scope as reportScope} from './inspectionQuarterlyReport.fixture'
import {row as approvalRow,approval,scope as approvalScope} from './changeNoticeApproval.fixture'
const kinds=['template','worksheet','collection','report','approval'] as const
type Kind=typeof kinds[number]
beforeEach(()=>vi.stubGlobal('crypto',webcrypto));afterEach(()=>vi.unstubAllGlobals())
async function setup(kind:Kind,onKey:(w:any,scope:any)=>string){const request=vi.fn();let w:any,scope:any,submit!:()=>Promise<boolean>,empty:()=>unknown
 const key=()=>onKey(w,scope)
 if(kind==='template'){scope=templateScope;w=createInspectionWorkspace({request},()=>{},key);submit=()=>w.submit('CREATE',{expected_version:0,...definition()});empty=emptyInspectionState}
 else if(kind==='worksheet'){scope=worksheetScope;w=createWorksheetWorkspace({request},()=>{},key);submit=()=>w.submit('CREATE',worksheetPayload(),null,source());empty=emptyWorksheetState}
 else if(kind==='collection'){scope=collectionScope;w=createSourceCollectionWorkspace({request},()=>{},key);submit=()=>w.submit(collectionPayload());empty=emptySourceCollectionState}
 else if(kind==='report'){scope=reportScope;w=createReportWorkspace({request},()=>{},key);submit=()=>w.submit(reportPayload());empty=emptyReportState}
 else{scope=approvalScope;w=createApprovalWorkspace({request},()=>{},key);submit=()=>w.submit('SUBMIT','原审批说明');empty=emptyApprovalState}
 w.reset(scope);if(kind==='approval'){request.mockResolvedValueOnce(approval());expect(await w.pin(approvalRow())).toBe(true);expect(w.prepare()).toBe(true);request.mockClear()}
 return{request,w,scope,submit,empty}
}
for(const kind of kinds)for(const mode of ['reset','replace','dispose'])it(`${kind} does not dispatch old command when key callback ${mode}s scope`,async()=>{const h=await setup(kind,(w,scope)=>{if(mode==='dispose')w.dispose();else w.reset(mode==='replace'?{...scope,actorId:'99000000-0000-4000-8000-000000000099',permissionVersion:scope.permissionVersion+1}:scope);return 'original-scope-key'});expect(await h.submit()).toBe(false);expect(h.request).not.toHaveBeenCalled();expect(h.w.state()).toEqual(h.empty())})
for(const kind of kinds)it(`${kind} old key failure does not contaminate replacement scope`,async()=>{const h=await setup(kind,(w,scope)=>{w.reset({...scope,actorId:'99000000-0000-4000-8000-000000000099',permissionVersion:scope.permissionVersion+1});throw new Error('Old scope key failure')});expect(await h.submit()).toBe(false);expect(h.request).not.toHaveBeenCalled();expect(h.w.state()).toEqual(h.empty())})
