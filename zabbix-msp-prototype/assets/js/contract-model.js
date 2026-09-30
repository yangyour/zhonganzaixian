/* Contract interaction model. Local-only demonstration state; no device connections. */
(function (g) {
  'use strict';
  const D=g.DATA, KEY='mop.contract.v1';
  let saved={};try{saved=JSON.parse(localStorage.getItem(KEY)||'{}');}catch(_){/* recover cleanly */}
  const now=()=>D.now, uid=p=>p+'-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,6);
  const tierName=id=>{const t=D.tenants.find(x=>x.id===id);return t?.plan==='基础版'?'基础':t?.tier==='金牌'?'进阶':'标准';};
  const data={tickets:saved.tickets||{},backups:saved.backups||{},sessions:saved.sessions||[],reports:saved.reports||{},audit:saved.audit||[]};
  const seed={id:'ITSM-2026-09201',tenant:'t-huadong',incidentId:null,title:'上海总部会议室网络恢复确认',priority:'P2',status:'pending_customer',sync:'synced',created:now()-3*D.H,updated:now()-D.H,slaDue:now()+D.H,owner:'李娜',external:'ServiceNow',assetName:'sw-core-01',solution:'已检查上联端口并更换故障跳线，会议室网络已恢复，请确认使用情况。',recoverAt:now()-D.H,confirmDue:now()+47*D.H};
  if(!D.tickets.some(t=>t.id===seed.id))D.tickets.push(seed);
  for(const t of D.tickets){Object.assign(t,data.tickets[t.id]||{});t.logs=t.logs||[];t.worklogs=t.worklogs||[];t.reopenCount=t.reopenCount||0;t.tierSnapshot=t.tierSnapshot||{name:tierName(t.tenant),recoverDue:t.slaDue};if(['金牌','银牌'].includes(t.tierSnapshot.name))t.tierSnapshot.name=t.tierSnapshot.name==='金牌'?'进阶':'标准';}
  for(const t of Object.values(data.tickets))if(!D.tickets.some(x=>x.id===t.id))D.tickets.unshift(t);
  const persist=()=>{for(const t of D.tickets)data.tickets[t.id]=t;try{localStorage.setItem(KEY,JSON.stringify(data));}catch(_){throw Error('浏览器存储空间不足，请清理演示数据后再试。');}};
  const scope=(obj,ctx)=>obj&&(ctx.tenant==='all'||obj.tenant===ctx.tenant);
  function check(obj,ctx,roles){if(!scope(obj,ctx))throw Error('对象不存在或无权限');if(roles&&!roles.includes(ctx.role))throw Error('当前角色无权执行此操作');return obj;}
  function audit(action,obj,ctx,detail){const a={id:uid('audit'),ts:now(),tenant:obj.tenant,actor:ctx.name,role:ctx.role,action,object:obj.id,result:'success',ip:'本地演示',trace:uid('trace'),detail};data.audit.unshift(a);data.audit=data.audit.slice(0,200);D.audit.unshift(a);return a;}
  D.audit.unshift(...data.audit);
  function ticket(id,ctx){return check(D.tickets.find(x=>x.id===id),ctx);}
  function log(t,ctx,action,detail){t.updated=now();t.logs.unshift({at:now(),actor:ctx.name,action,detail});audit(action,t,ctx,detail);persist();return t;}
  function createTicket(input,ctx){
    const target=input.tenant||ctx.tenant;if(target==='all'||!D.tenants.some(t=>t.id===target))throw Error('请先选择具体客户');
    check({tenant:target},ctx);
    if(!String(input.title||'').trim())throw Error('请填写工单标题');
    if(input.description?.length>3000)throw Error('故障描述最多3000字');
    const asset=input.assetId?D.assets.find(a=>a.id===input.assetId):null;if(input.assetId&&(!asset||asset.tenant!==target))throw Error('对象不存在或无权限');
    const duplicate=D.tickets.find(t=>t.tenant===target&&t.source==='customer_report'&&input.source==='customer_report'&&t.assetId===(input.assetId||null)&&t.category===input.category&&(Date.now()-(t.submittedAt||0)<30000));if(duplicate)throw Error('您已提交过相同报障：'+duplicate.id);
    const t={id:uid('MOP').toUpperCase(),tenant:target,incidentId:null,title:input.title.trim(),description:input.description||'',source:input.source||'manual',category:input.category||'其他',status:'new',sync:'synced',priority:input.priority||'P3',created:now(),updated:now(),submittedAt:Date.now(),slaDue:now()+4*D.H,owner:'张伟',external:'演示工单',assetId:input.assetId||null,assetName:asset?.name||'设备待确认',contact:input.contact||ctx.name,attachments:input.attachments||[],tierSnapshot:{name:tierName(target),recoverDue:now()+4*D.H},logs:[],worklogs:[],reopenCount:0};
    D.tickets.unshift(t);return log(t,ctx,'ticket_create',t.description);
  }
  function ticketAction(id,action,input,ctx){const t=ticket(id,ctx),staff=['msp','padmin'];
    if(action==='transfer'){check(t,ctx,staff);if(!input.owner||!input.reason?.trim())throw Error('请选择负责人并填写转派原因');const before=t.slaDue;const prev=t.owner;t.owner=input.owner;log(t,ctx,'ticket_transfer',prev+' → '+t.owner+'；'+input.reason);if(t.slaDue!==before)throw Error('SLA时钟不得重置');}
    else if(action==='resolve'){check(t,ctx,staff);if(['closed','pending_customer'].includes(t.status))throw Error('当前状态不能提交解决');if((input.solution||'').trim().length<20)throw Error('处置说明至少填写20字');t.status='pending_customer';t.solution=input.solution;t.recoverAt=now();t.confirmDue=now()+(t.tierSnapshot.name==='进阶'?24:48)*D.H;log(t,ctx,'ticket_resolve',input.solution);}
    else if(action==='confirm'){check(t,ctx,['viewer','tadmin']);if(t.status!=='pending_customer')throw Error('当前工单不在待确认状态');t.status='closed';t.closedAt=now();t.closeMode='customer_confirm';t.confirmation={customer:ctx.name,at:now(),id:uid('confirm')};log(t,ctx,'customer_confirm','客户确认已解决');}
    else if(action==='reject'||action==='reopen'){check(t,ctx,['viewer','tadmin']);if(!input.reason?.trim())throw Error('请填写未解决或重开原因');if(action==='reject'&&t.status!=='pending_customer')throw Error('当前工单不在待确认状态');if(action==='reopen'&&(t.status!=='closed'||now()-(t.closedAt||t.updated)>72*D.H))throw Error('已超过72小时，请新建工单');t.status='in_progress';t.reopenCount++;log(t,ctx,'ticket_reopen',input.reason);}
    else if(action==='worklog'){check(t,ctx,staff);const hours=Number(input.hours);if(!(hours>0&&hours<=24))throw Error('请输入0至24之间的有效工时');t.worklogs.unshift({actor:ctx.name,hours,type:input.type||'远程处置',at:now()});log(t,ctx,'worklog_add',hours+'小时 · '+input.type);}
    else if(action==='accept'){check(t,ctx,staff);if(t.status!=='new')throw Error('当前工单不能接单');t.status='in_progress';t.respondedAt=t.respondedAt||now();t.owner=ctx.name;log(t,ctx,'ticket_accept','开始处理');}
    else throw Error('不支持的操作');return t;
  }
  function backups(ctx){return D.assets.filter(a=>['switch','firewall'].includes(a.type)&&scope(a,ctx)).map((a,i)=>Object.assign({id:a.id,tenant:a.tenant,name:a.name,site:a.site,status:i%6===4?'failed':'success',lastAt:now()-(i+1)*D.H,versions:Math.min(30,12+i),size:13400+i*613,changes:i%3===0?4:0,plan:'每日 02:00',result:i%6===4?'连接超时':'备份完成',runs:[]},data.backups[a.id]||{}));}
  function runBackup(id,ctx){const a=check(D.assets.find(x=>x.id===id),ctx,['msp','padmin']);const b=backups(ctx).find(x=>x.id===id);b.status='success';b.result='备份完成';b.lastAt=now();b.versions++;b.runs.unshift({id:uid('BK'),at:now(),result:'success'});data.backups[id]=b;audit('backup_run',a,ctx,'手动备份演示');persist();return b;}
  function remote(input,ctx){check({tenant:input.tenant},ctx,['msp','padmin']);const asset=check(D.assets.find(a=>a.id===input.assetId),ctx);if(asset.tenant!==input.tenant)throw Error('设备不属于所选客户');if(!input.reason?.trim())throw Error('请填写申请事由');if(!['readonly','readwrite'].includes(input.level))throw Error('访问级别无效');const s={id:uid('RS'),tenant:asset.tenant,assetId:asset.id,assetName:asset.name,applicant:ctx.name,level:input.level,reason:input.reason,created:now(),status:input.level==='readonly'?'approved':'pending',approvers:[],commands:[],duration:60};data.sessions.unshift(s);audit('remote_session_request',s,ctx,input.reason);persist();return s;}
  function remoteAction(id,action,input,ctx){const s=check(data.sessions.find(s=>s.id===id),ctx,['msp','padmin']);
    if(action==='approve'){if(ctx.role!=='padmin')throw Error('当前角色无审批权限');if(s.status!=='pending')throw Error('申请不在待审批状态');if(now()-s.created>30*D.MIN)throw Error('申请已超过30分钟并作废');const who=input.approver;if(!['刘岚','李娜','赵敏'].includes(who))throw Error('请选择审批人');if(who===s.applicant)throw Error('申请人不能审批自己的申请');if(s.approvers.includes(who))throw Error('需要两名不同审批人');s.approvers.push(who);if(s.approvers.length===2)s.status='approved';audit('remote_session_approve',s,{...ctx,name:who},'演示审批身份');}
    else if(action==='start'){if(s.status!=='approved'||(s.level==='readwrite'&&s.approvers.length!==2))throw Error('审批未完成，不能建立会话');if(data.sessions.filter(x=>x.applicant===s.applicant&&x.status==='active').length>=2)throw Error('单工程师最多2个并发会话');s.status='active';s.startedAt=now();s.recording=s.level==='readwrite';audit('remote_session_start',s,ctx,'模拟会话，无真实设备连接');}
    else if(action==='end'){if(s.status!=='active')throw Error('会话未开始');s.status='ended';s.endedAt=now();audit('remote_session_end',s,ctx,'已生成模拟处置记录');}
    else if(action==='command'){if(s.status!=='active')throw Error('会话未开始');const cmd=(input.command||'').trim();if(!cmd)throw Error('请输入命令');if(/\b(reboot|erase|delete|format|write|shutdown)\b/i.test(cmd)){audit('command_blocked',s,ctx,'高危命令拦截');persist();throw Error('高危命令已拦截，请走变更审批');}if(!/^(show|display|ping|help)(\s|$)/i.test(cmd))throw Error('演示会话仅支持 show / display / ping / help');s.commands.push({at:now(),text:cmd.replace(/(password|secret|key)\s+\S+/gi,'$1 ?'),output:'执行完成（模拟结果）'});}
    persist();return s;
  }
  function metrics(ctx){const aa=D.assets.filter(x=>scope(x,ctx)),tt=D.tickets.filter(x=>scope(x,ctx)),ss=D.slaServices.filter(x=>scope(x,ctx)),valid=ss.filter(x=>x.availability!=null),dd=D.deliveries.filter(x=>scope(x,ctx)&&x.at>now()-D.D);return {assets:aa.length,normal:aa.filter(a=>a.status==='ok').length,unknown:aa.filter(a=>a.status==='unknown').length,incidents:D.incidents.filter(x=>scope(x,ctx)&&x.sourceState==='PROBLEM'&&!x.suppressed).length,tickets:tt.length,p1:tt.filter(t=>t.priority==='P1').length,closed:tt.filter(t=>t.status==='closed').length,confirmed:tt.filter(t=>t.closeMode==='customer_confirm').length,autoClosed:tt.filter(t=>t.closeMode==='timeout_auto').length,availability:valid.length===ss.length&&valid.length?valid.reduce((s,v)=>s+v.availability,0)/valid.length:null,delivery:dd.length?dd.filter(x=>x.state==='delivered').length/dd.length*100:null,deliveryCount:dd.length};}
  function report(id,ctx){const tenant=id.split(':')[0];check({tenant},ctx);const r=data.reports[id]||(data.reports[id]={id,tenant,period:'2026-09',type:'月度服务报告',version:'1.0',created:now(),metrics:metrics({tenant}),readAt:null});if(!r.readAt&&['viewer','tadmin'].includes(ctx.role))r.readAt=now();persist();return r;}
  g.MOP={data,now,uid,persist,scope,check,ticket,createTicket,ticketAction,backups,runBackup,remote,remoteAction,metrics,report};
})(window);
