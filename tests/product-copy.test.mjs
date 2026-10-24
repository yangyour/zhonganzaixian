import test from 'node:test';import assert from 'node:assert/strict';
import {inspectProductCopy,checkProductCopy} from '../tools/check-product-copy.mjs';
test('product copy parser catches templates, attributes, statuses and helper error messages',()=>{
 const source=`<script setup lang="ts">const phase='生成中';const info=\`初始化完成\`</script><template><button title="初始化策略">生成文件</button><span>{{ busy?'生成中':'准备报告' }}</span></template>`;
 assert.equal(inspectProductCopy(source,'sample.vue').length,5);
 assert.equal(inspectProductCopy("throw new Error('无法生成标识')",'sample.ts').length,1);
});
test('comments, field names, protocol codes and dynamic user content are not rewritten',()=>{
 assert.deepEqual(inspectProductCopy(`// 初始化生成 is an internal comment\nconst status='GENERATING';const generated_at='2026-10-24';const value=userInput;`,'sample.ts'),[]);
 assert.deepEqual(inspectProductCopy('<template><p>{{ customerProvidedName }}</p><button>准备报告</button></template>','sample.vue'),[]);
});
test('all static product copy uses business action wording',()=>{const result=checkProductCopy();assert.equal(result.status,'PASSED',JSON.stringify(result.findings));assert.ok(result.files>100)});
