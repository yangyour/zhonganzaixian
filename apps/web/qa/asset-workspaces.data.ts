// Pure synthetic fixtures shared with focused regressions; no account, credential or device data.
export * as metadata from '../tests/fixtures/assetMetadata'
export * as warranty from '../tests/fixtures/manualWarranty'
export * as dependency from '../tests/fixtures/assetDependency'
export * as metadataQuery from '../tests/fixtures/assetMetadataQuery'
export * as dateQuery from '../tests/fixtures/assetDateQuery'
import * as dependency from '../tests/fixtures/assetDependency'
import * as metadata from '../tests/fixtures/assetMetadata'
import type {Asset} from '../src/api/types'

export function dependencyGraph(){return dependency.draft(26,Array.from({length:26},(_,i)=>dependency.edge(dependency.a,metadata.id(100+i))))}
export function dependencyAsset(){return {...metadata.current().asset,id:dependency.a,tenant_id:dependency.context.tenantId} as Asset}
export function dependencyCandidates(){return {items:[dependencyAsset(),{...dependencyAsset(),id:dependency.b,name:'合成关联设备'}],has_more:false,next_cursor:null,source:'postgresql',page_size_limit:100,page_size_limit_kind:'test_technical_limit'}}
