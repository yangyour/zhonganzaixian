<script setup lang="ts">
import {computed,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {session,contextGeneration,switchingTenant} from '../stores/session'
import {visibleNavigationModules} from '../utils/navigationModules'
const router=useRouter(),route=useRoute()
const navigationVersion=ref(0)
const modules=computed(()=>switchingTenant.value?[]:visibleNavigationModules(session.value,router.getRoutes()))
const scopeKey=computed(()=>`${session.value?.user.id}:${session.value?.tenant.id}:${session.value?.permission_version}:${contextGeneration.value}`)
async function navigate(name:string){if(modules.value.some(module=>module.items.some(item=>item.name===name))){try{await router.push({name})}finally{navigationVersion.value++}}}
</script>
<template>
 <nav v-if="modules.length" :key="scopeKey+':'+navigationVersion" class="module-navigation" aria-label="模块导航">
  <el-menu mode="horizontal" menu-trigger="click" :ellipsis="false" :default-active="String(route.name)" class="module-menu" @select="navigate">
   <el-sub-menu v-for="module in modules" :key="module.id" :index="`module-${module.id}`" popper-class="module-submenu-popper" :show-timeout="100" :hide-timeout="100">
    <template #title>{{module.title}}</template>
    <el-menu-item v-for="item in module.items" :key="item.name" :index="item.name">{{item.title}}</el-menu-item>
   </el-sub-menu>
  </el-menu>
 </nav>
</template>
