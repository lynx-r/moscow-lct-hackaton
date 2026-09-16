<script setup lang="ts">
import MapViewer from '@/components/MapViewer.vue'
import SidebarControls from '@/components/SidebarControls.vue'
</script>

<template>
  <div class="flex">
    <!-- Левая панель (30%) -->
    <SidebarControls />

    <!-- Правая часть (70%) -->
    <MapViewer />
  </div>
</template>
