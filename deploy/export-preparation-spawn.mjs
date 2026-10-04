// Windows detached child with file handles, so PowerShell's redirected-stream
// callbacks cannot keep a supposedly asynchronous launcher waiting for the JVM.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {readFileSync,openSync,closeSync,lstatSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname,resolve,relative,basename,join} from 'node:path';
import {fileURLToPath} from 'node:url';
let out,err;
try{
 assert.equal(process.platform,'win32');assert.equal(process.argv.length,2);
 const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');const input=JSON.parse(readFileSync(0,'utf8').replace(/^\uFEFF/,''));
 assert.equal(input.root,root);assert.match(input.job,/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
 const artifact=resolve(input.artifact),run=dirname(artifact);assert.equal(dirname(run),join(root,'.local','export-preparation'));assert.match(basename(run),/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);assert.match(basename(artifact),/^[0-9a-f]{64}\.jar$/);
 let checked=artifact;while(true){assert.equal(lstatSync(checked).isSymbolicLink(),false);if(checked===root)break;checked=dirname(checked);assert.ok(relative(root,checked)!=='..')}
 assert.equal(createHash('sha256').update(readFileSync(artifact)).digest('hex')+'.jar',basename(artifact));assert.equal(basename(input.java).toLowerCase(),'java.exe');assert.match(input.java,/^[A-Za-z]:\\/);
 const osKeys=['PATH','SystemRoot','WINDIR','SystemDrive','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA','ProgramData','ProgramFiles','ProgramFiles(x86)','PROCESSOR_ARCHITECTURE','ComSpec','PATHEXT','OS'];
 const keys=[...osKeys,'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE','MOP_EXPORT_PREPARATION_WORKER_ENABLED','MOP_EXPORT_PREPARATION_WORKER_ID','MOP_EXPORT_PREPARATION_LEASE_MILLIS'];
 const env=input.environment;assert.ok(env&&typeof env==='object'&&!Array.isArray(env));assert.ok(Object.keys(env).every(k=>keys.includes(k)&&typeof env[k]==='string'&&!env[k].includes('\0')));
 assert.match(env.SPRING_DATASOURCE_URL,/^jdbc:postgresql:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}\/mop_test$/);assert.equal(env.SPRING_DATASOURCE_USERNAME,'mop_test_runtime');assert.ok(env.SPRING_DATASOURCE_PASSWORD?.trim());assert.equal(env.SPRING_PROFILES_ACTIVE,'remote_test');assert.equal(env.MOP_EXPORT_PREPARATION_WORKER_ENABLED,'true');assert.match(env.MOP_EXPORT_PREPARATION_WORKER_ID,/^[A-Za-z0-9._:-]{1,64}$/);assert.match(env.MOP_EXPORT_PREPARATION_LEASE_MILLIS,/^[1-9][0-9]{0,5}$/);assert.ok(Number(env.MOP_EXPORT_PREPARATION_LEASE_MILLIS)>=1000&&Number(env.MOP_EXPORT_PREPARATION_LEASE_MILLIS)<=600000);
 out=openSync(join(run,'stdout.log'),'wx');err=openSync(join(run,'stderr.log'),'wx');
 const child=spawn(input.java,['-jar',artifact,'--prepare-one-without-publication',input.job],{cwd:root,env,detached:true,windowsHide:true,stdio:['ignore',out,err]});
 child.once('error',()=>{console.error('Owned export JVM spawn failed; details redacted');process.exitCode=1});assert.ok(child.pid);child.unref();console.log(JSON.stringify({id:child.pid,parent_id:process.pid}));
}catch{console.error('Owned export spawn configuration failed; details redacted');process.exitCode=1}
finally{if(out!==undefined)closeSync(out);if(err!==undefined)closeSync(err)}
