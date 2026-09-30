(function(global){
  'use strict';
  const categories=['网络运维','监控接入','服务器','数据库','应用服务','服务管理'];
  const clone=v=>JSON.parse(JSON.stringify(v));
  function inScope(article,ctx){return !article.tenant||article.tenant===ctx.tenant||(['padmin','msp'].includes(ctx.role)&&ctx.tenant==='all');}
  function visible(articles,ctx){return articles.filter(a=>inScope(a,ctx)&&a.status!=='deleted'&&(a.status==='published'||a.author===ctx.user));}
  function canEdit(article,ctx){return ctx.role!=='viewer'&&article.author===ctx.user&&inScope(article,ctx);}
  function validateArticle(form,ctx){
    if(ctx.role==='viewer')throw new Error('当前角色只读，不能维护文章');
    if(!String(form.title||'').trim())throw new Error('请填写文章标题');
    if(String(form.title).length>100)throw new Error('文章标题不能超过 100 个字');
    if(!String(form.content||'').trim())throw new Error('请填写文章正文');
    if(String(form.content).length>50000)throw new Error('文章正文不能超过 50,000 个字');
    if(!categories.includes(form.category)||!['normal','service'].includes(form.type))throw new Error('文章分类或类型无效');
    if(form.tenant&&form.tenant!==ctx.tenant&&!(['padmin','msp'].includes(ctx.role)&&ctx.tenant==='all'))throw new Error('只能在当前客户范围内维护文章');
    if(!form.tenant&&ctx.role!=='padmin')throw new Error('共享知识需由平台管理员发布');
    if(!Array.isArray(form.tags)||form.tags.length>8||form.tags.some(t=>typeof t!=='string'||t.length>24))throw new Error('标签最多 8 个，每个不超过 24 字');
    return form;
  }
  function queryTokens(query){
    const q=String(query||'').toLowerCase();const tokens=new Set(q.match(/[a-z][a-z0-9_./-]*/g)||[]);
    const terms=['网络故障','网络','丢包','端口','交换机','防火墙','专线','光模块','vpn','proxy','离线','采集延迟','采集','agent','接入','资产','磁盘','空间','inode','linux','windows','cpu','内存','mysql','慢查询','连接池','连接','redis','淘汰','缓存','sla','未知数据','未知','可用率','报告','审批','故障处理','虚拟机','拓扑','服务请求','api','响应时间','证书','dns','tls'];
    terms.forEach(t=>{if(q.includes(t))tokens.add(t)});
    return [...tokens];
  }
  function retrieve(articles,ctx,query){
    const tokens=queryTokens(query);if(!tokens.length)return [];
    return visible(articles,ctx).filter(a=>a.status==='published').map(article=>{
      const title=(article.title+' '+article.tags.join(' ')).toLowerCase(),content=article.content.toLowerCase();
      const score=tokens.reduce((s,t)=>s+(title.includes(t)?3:content.includes(t)?1:0),0);
      const sections=article.sections?.length?article.sections:article.content.split(/\n\s*\n/).map(text=>({heading:'文章内容',text}));
      const procedureQuestion=/排查|检查|处理|如何|怎么/.test(query);
      const ranked=sections.map(section=>({...section,score:tokens.reduce((s,t)=>s+(section.text.toLowerCase().includes(t)?1:0),0)+(procedureQuestion&&/步骤|流程|处置/.test(section.heading)?2:0)})).sort((a,b)=>b.score-a.score);
      return {article,score,excerpt:ranked[0]?.text||article.content,heading:ranked[0]?.heading||'文章内容'};
    }).filter(h=>h.score>0).sort((a,b)=>b.score-a.score).slice(0,4);
  }
  const api={categories,clone,visible,inScope,canEdit,validateArticle,retrieve,queryTokens};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else global.KnowledgeModel=api;
})(typeof window!=='undefined'?window:globalThis);
