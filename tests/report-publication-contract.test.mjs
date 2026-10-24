import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
const c=JSON.parse(fs.readFileSync(new URL('../contracts/local-api.openapi.json',import.meta.url),'utf8'));
test('report creation only names a frozen source and never accepts caller output or policy',()=>{const s=c.paths['/report-publications'].post.requestBody.content['application/json'].schema;assert.equal(s.additionalProperties,false);assert.deepEqual(s.required,['source_id','expected_source_sha256']);});
test('report actions retain current command and CSRF requirements without public render-result API',()=>{const p=c.paths['/report-publications/{id}/actions'].post;assert.ok(p.parameters.some(x=>x.name==='Idempotency-Key'&&x.required));assert.ok(p.parameters.some(x=>x.name==='X-CSRF-Token'&&x.required));assert.deepEqual(p.requestBody.content['application/json'].schema.properties.action.enum,['RENDER','APPROVE','RETURN','PUBLISH','RESEND']);assert.equal(p['x-mop-external-dispatch'],'DISABLED');});
test('report publication and reading are never customer acceptance',()=>{const p=c.components.schemas.ReportPublicationResult.properties;assert.equal(p.customer_acceptance_confirmed.const,false);assert.equal(p.external_dispatch_enabled.const,false);assert.ok(c.paths['/report-publications/{id}/read'].post);assert.ok(c.paths['/report-publications/{id}/content/{format}'].get.parameters.some(x=>x.name==='If-Match'&&x.required));});
test('revision command accepts only the parent version and explicit frozen source, with CSRF and idempotency',()=>{
 const operation=c.paths['/report-publications/{id}/revisions'].post;
 const body=operation.requestBody.content['application/json'].schema;
 assert.equal(body.additionalProperties,false);
 assert.deepEqual(body.required,['expected_version','source_id','expected_source_sha256','reason']);
 assert.ok(operation.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));
 assert.ok(operation.parameters.some(p=>p.name==='X-CSRF-Token'&&p.required));
 assert.equal(operation.responses[200].content['application/json'].schema.properties.data.$ref,'#/components/schemas/ReportPublicationSummary');
 assert.equal(operation['x-mop-external-dispatch'],'DISABLED');
});
