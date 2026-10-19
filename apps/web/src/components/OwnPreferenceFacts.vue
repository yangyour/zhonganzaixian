<script setup lang="ts">
import type {OwnPreferenceSnapshot,OwnPreferenceRevision} from '../utils/ownNotificationPreferences'
defineProps<{value:OwnPreferenceSnapshot|OwnPreferenceRevision}>()
const levels:Record<string,string>={P1_ONLY:'仅请求P1',P1_P2:'请求P1与P2',ALL:'请求全部等级'}
const channels:Record<string,string>={EMAIL:'邮件',SMS:'短信',DINGTALK:'钉钉',WECHAT_WORK:'企业微信',WEBHOOK:'Webhook',VOICE:'语音'}
</script>
<template>
 <dl class="preference-facts"><dt>请求等级</dt><dd>{{value.requested_level===null?'未指定':levels[value.requested_level]}}</dd><dt>请求渠道</dt><dd>{{value.requested_channels===null?'未指定':value.requested_channels.length?value.requested_channels.map(c=>channels[c]).join('、'):'明确空集合（P1 告警仍按平台保底规则派发）'}}</dd><dt>免打扰时段</dt><dd>{{value.requested_quiet_window===null?'未设置免打扰':value.requested_quiet_window.enabled===null?'保留待定状态':value.requested_quiet_window.enabled===false?'已关闭免打扰':`${value.requested_quiet_window.start} 至 ${value.requested_quiet_window.end}${value.requested_quiet_window.start!>value.requested_quiet_window.end!?'（跨午夜）':''}`}}</dd><dt>业务时区</dt><dd>Asia/Shanghai</dd><dt>生效状态</dt><dd class="pending">未生效 · 待通知策略启用</dd><dt>接收人与派发</dt><dd>待绑定实际接收渠道，当前未触发通知派发</dd><dt>内容指纹</dt><dd class="fingerprint">{{value.content_sha256}}</dd></dl>
</template>
<style scoped>
.preference-facts{display:grid;grid-template-columns:110px minmax(0,1fr);gap:8px 14px;line-height:1.7;margin:14px 0;font-variant-numeric:tabular-nums}.preference-facts dt{color:#607086;padding:4px 0}.preference-facts dd{margin:0;padding:4px 0;overflow-wrap:anywhere;border-bottom:1px solid #f0f4f9}.fingerprint{font:12px/1.6 "Cascadia Code",Consolas,monospace;align-self:center}.pending{color:#975215;font-weight:500}@media(max-width:520px){.preference-facts{grid-template-columns:1fr;gap:4px}.preference-facts dd{margin-bottom:10px}}
</style>
