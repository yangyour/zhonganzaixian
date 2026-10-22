<script setup lang="ts">
import NotificationChannelAction from './NotificationChannelAction.vue'
import type {ChannelActivity} from '../utils/notificationDelivery'
defineProps<{messageId:string;ownerScope?:string;parentBusy?:boolean}>()
const emit=defineEmits<{activity:[value:ChannelActivity]}>()
</script>
<template><NotificationChannelAction channel="wecom" :message-id="messageId" :owner-scope="ownerScope" :parent-busy="parentBusy" @activity="emit('activity',$event)" /></template>
