<script setup lang="ts">
const tracingDebugStore = useTracingDebugStore()
const { isCalculating, data } = storeToRefs(tracingDebugStore)
const { startTracing } = tracingDebugStore
</script>

<template>
  <div class="flex">
    <!-- Левая панель (30%) -->
    <DebugSidebarControls :is-calculating @start-tracing="startTracing" />

    <!-- Правая часть (70%) -->
    <DebugMapViewer :data />
  </div>
</template>
