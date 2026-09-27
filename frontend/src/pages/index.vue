<script setup lang="ts">
import { useTracingStore } from '@/stores/tracing'
import { storeToRefs } from 'pinia'

const tracingStore = useTracingStore()
const { data } = storeToRefs(tracingStore)
</script>

<template>
  <div class="flex">
    <!-- Левая панель (30%) -->
    <SidebarControls />

    <!-- Правая часть (70%) -->
    <MapViewer :data />
  </div>
</template>
