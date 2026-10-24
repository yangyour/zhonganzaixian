import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'apps/web/package.json'));
const ts=require('typescript');
const sfc=require('@vue/compiler-sfc');
const dom=require('@vue/compiler-dom');
const forbidden=/初始化|生成/u;
/** Inspect static application copy, not comments, backend codes or user-provided/runtime content. */
export function inspectProductCopy(source,filename){
 const findings=[];
 const inspect=(text,line)=>{if(forbidden.test(text))findings.push({file:filename,line,text:text.replace(/\s+/g,' ').slice(0,160)})};
 function script(text,offset=0){
  const parsed=ts.createSourceFile(filename,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);
  function visit(node){if(ts.isStringLiteralLike(node)||[ts.SyntaxKind.TemplateHead,ts.SyntaxKind.TemplateMiddle,ts.SyntaxKind.TemplateTail].includes(node.kind))inspect(node.text,offset+parsed.getLineAndCharacterOfPosition(node.getStart(parsed)).line+1);ts.forEachChild(node,visit)}
  visit(parsed);
 }
 if(filename.endsWith('.vue')){
  const {descriptor,errors}=sfc.parse(source,{filename});if(errors.length)throw Error('Cannot parse product component: '+filename);
  for(const block of [descriptor.script,descriptor.scriptSetup])if(block)script(block.content,block.loc.start.line-1);
  if(descriptor.template){
   const offset=descriptor.template.loc.start.line-1;
   function visit(node){
    if(node.type===dom.NodeTypes.TEXT)inspect(node.content,offset+node.loc.start.line);
    if(node.type===dom.NodeTypes.INTERPOLATION)script(node.content.content,offset+node.loc.start.line-1);
    if(node.type===dom.NodeTypes.ELEMENT)for(const prop of node.props){if(prop.type===dom.NodeTypes.ATTRIBUTE&&prop.value)inspect(prop.value.content,offset+prop.loc.start.line);else if(prop.type===dom.NodeTypes.DIRECTIVE&&prop.exp)script(prop.exp.content,offset+prop.loc.start.line-1)}
    for(const child of node.children??[])visit(child);
   }
   visit(dom.parse(descriptor.template.content));
  }
 }else script(source);
 return findings;
}
export function checkProductCopy(directory=path.join(root,'apps/web/src')){
 const files=[];function walk(dir){for(const item of fs.readdirSync(dir,{withFileTypes:true})){const full=path.join(dir,item.name);if(item.isDirectory())walk(full);else if(/\.(vue|ts)$/.test(item.name))files.push(full)}}walk(directory);
 const findings=files.flatMap(file=>inspectProductCopy(fs.readFileSync(file,'utf8'),path.relative(root,file)));
 return {status:findings.length?'FAILED':'PASSED',files:files.length,scope:'STATIC_PRODUCT_COPY_ONLY_NOT_USER_DATA_OR_PROTOCOL_CODES',findings};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{const result=checkProductCopy();console.log(JSON.stringify(result));if(result.findings.length)process.exitCode=1}catch(error){console.error(error.message);process.exitCode=1}
}
