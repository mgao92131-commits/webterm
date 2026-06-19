<template>
  <router-view v-slot="{ Component }">
    <transition name="fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<script setup lang="ts">
// 这里可以做一些全局性的初始化设置
</script>

<style>
/* 我们可以利用 index.css 里的动画样式 */
</style>
