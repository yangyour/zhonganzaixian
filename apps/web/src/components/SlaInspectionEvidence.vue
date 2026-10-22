<script setup lang="ts">
import {computed,nextTick,onUnmounted,ref,useId,watch} from 'vue'
import type {TicketSlaInspection} from '../utils/serviceTiers'
import {displayTime} from '../utils/display'
const props=defineProps<{inspection:TicketSlaInspection}>()
type SectionKey='tasks'|'missing'|'references'
type EvidenceSection={key:SectionKey;title:string;columns:string[];rows:string[][];empty:string;guidance:string}
const size=25,pages=ref<Record<SectionKey,number>>({tasks:0,missing:0,references:0}),prefix=useId()
const regions=new Map<SectionKey,HTMLElement>();let epoch=0,alive=true
const kindLabels:Record<string,string>={SLA:'SLA 提醒',CONFIRMATION_REMINDER:'客户确认提醒',AUTO_CLOSE:'自动关闭核查'}
const outcomeLabels:Record<string,string>={EMITTED:'已生成内部事件（非外部送达）',OBSOLETE:'已过时',AUTO_CLOSED:'已自动关闭工单'}
const missingLabels:Record<string,string>={response:'响应时限',recovery:'恢复时限',confirmation:'客户确认期限'}
const sections=computed<EvidenceSection[]>(()=>[
 {key:'tasks',title:'耐久时钟任务',columns:['任务键','任务类型','计划时间','处理回执','完成时间'],rows:props.inspection.clock_tasks.map(t=>[t.clock_key,kindLabels[t.kind]??t.kind,displayTime(t.due_at),t.outcome===null?'未见处理回执':outcomeLabels[t.outcome]??t.outcome,t.completed_at===null?'未见完成时间':displayTime(t.completed_at)]),empty:'此快照未返回时钟任务，不表示通知已经送达，也不能据此判断后台处理器是否运行。',guidance:'仅核对本次接口返回的内部任务。EMITTED 不表示邮件、企业微信或短信送达；未见回执不等于执行失败。'},
 {key:'missing',title:'快照缺失配置',columns:['配置项','原始键','缺失原因'],rows:Object.entries(props.inspection.sla_missing).map(([key,value])=>[missingLabels[key]??key,key,value]),empty:'此快照未报告缺失配置，不表示其他配置已经就绪或生产验收完成。',guidance:'缺失项保持未知，不在这里补费用、时限或审批默认值。修订配置不会自动改写本工单已冻结的 SLA。'},
 {key:'references',title:'冻结配置来源',columns:['配置键','来源类型','发布 ID','版本','定义摘要 SHA-256'],rows:props.inspection.sla_references.map(r=>[r.key,r.source==='CONTRACT_DEFAULT'?'合同默认定义快照':'运行配置发布快照',r.release_id===null?'无发布 ID（合同默认快照）':r.release_id,String(r.version),r.definition_hash]),empty:'此快照未返回冻结来源引用，不补造发布 ID 或定义摘要。',guidance:'这里只显示接口返回的冻结来源与摘要，不能据此确认客户已批准、外部证据已认证或新配置已经生效。'}
])
const count=(s:EvidenceSection)=>Math.max(1,Math.ceil(s.rows.length/size))
const rows=(s:EvidenceSection)=>s.rows.slice(pages.value[s.key]*size,(pages.value[s.key]+1)*size)
const range=(s:EvidenceSection)=>s.rows.length?`第 ${pages.value[s.key]+1} / ${count(s)} 页，显示 ${pages.value[s.key]*size+1}—${Math.min((pages.value[s.key]+1)*size,s.rows.length)} / ${s.rows.length} 条`:'本次返回 0 条'
function setRegion(key:SectionKey,el:unknown){if(el instanceof HTMLElement)regions.set(key,el);else regions.delete(key)}
watch(()=>[props.inspection,props.inspection.ticket_id,props.inspection.workflow_version,props.inspection.observed_at],()=>{epoch++;pages.value={tasks:0,missing:0,references:0}},{flush:'sync'})
async function go(key:SectionKey,target:number){const section=sections.value.find(s=>s.key===key);if(!alive||!section||!Number.isInteger(target)||target<0||target>=count(section)||target===pages.value[key])return;pages.value[key]=target;const turn=++epoch;await nextTick();if(alive&&turn===epoch)regions.get(key)?.focus()}
onUnmounted(()=>{alive=false;epoch++;regions.clear()})
</script>
<template>
 <section class="sla-evidence" aria-label="工单 SLA 核对明细">
  <p class="snapshot">本次核验工单：{{inspection.ticket_id}} · 流程版本 {{inspection.workflow_version}} · 观察时间 {{displayTime(inspection.observed_at)}}。分页仅浏览已返回的本地快照，不会发送或重试后台任务。</p>
  <section v-for="section in sections" :key="section.key" class="evidence-section" :aria-labelledby="`${prefix}-${section.key}-title`">
   <h3 :id="`${prefix}-${section.key}-title`">{{section.title}}</h3>
   <p>{{section.guidance}}</p>
   <p :id="`${prefix}-${section.key}-range`" role="status">{{range(section)}} · 每页最多 {{size}} 条</p>
   <p v-if="!section.rows.length" class="empty">{{section.empty}}</p>
   <template v-else>
    <div :ref="el=>setRegion(section.key,el)" class="evidence-scroll" tabindex="0" role="region" :aria-label="`${section.title}，可横向滚动`" :aria-describedby="`${prefix}-${section.key}-range`">
     <table><caption>{{section.title}} · 本次已返回明细</caption><thead><tr><th scope="col">序号</th><th v-for="column in section.columns" :key="column" scope="col">{{column}}</th></tr></thead><tbody><tr v-for="(row,index) in rows(section)" :key="pages[section.key]*size+index"><td>{{pages[section.key]*size+index+1}}</td><td v-for="(cell,column) in row" :key="column">{{cell}}</td></tr></tbody></table>
    </div>
    <nav v-if="count(section)>1" :aria-label="`${section.title}本地分页`">
     <button type="button" :disabled="pages[section.key]===0" @click="go(section.key,0)">{{section.title}}首页</button>
     <button type="button" :disabled="pages[section.key]===0" @click="go(section.key,pages[section.key]-1)">上一页{{section.title}}</button>
     <button type="button" :disabled="pages[section.key]+1>=count(section)" @click="go(section.key,pages[section.key]+1)">下一页{{section.title}}</button>
     <button type="button" :disabled="pages[section.key]+1>=count(section)" @click="go(section.key,count(section)-1)">{{section.title}}末页</button>
    </nav>
   </template>
  </section>
 </section>
</template>
<style scoped>
.sla-evidence,.evidence-section{min-width:0}.snapshot,.sla-evidence p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.evidence-section{margin-top:18px;border-top:1px solid #dce4ee;padding-top:12px}.evidence-section h3{font-size:15px}.evidence-scroll{overflow:auto;max-width:100%;border:1px solid #dce4ee}.evidence-scroll table{border-collapse:collapse;width:100%;min-width:680px}.evidence-scroll caption,.evidence-scroll th,.evidence-scroll td{padding:10px;text-align:left;vertical-align:top;overflow-wrap:anywhere;font-size:13px;line-height:1.6}.evidence-scroll th,.evidence-scroll td{border-bottom:1px solid #cbd5e1;max-width:320px}.evidence-scroll caption{font-weight:600}.evidence-scroll thead{background:#eef4fa}.sla-evidence nav{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.sla-evidence button{min-height:44px;padding:8px 12px;font:inherit;color:#24374a;background:#fff;border:1px solid #dce4ee;border-radius:4px;cursor:pointer}.sla-evidence button:disabled{opacity:.6;cursor:default}.sla-evidence :focus-visible{outline:3px solid #176bb5;outline-offset:3px}
</style>
