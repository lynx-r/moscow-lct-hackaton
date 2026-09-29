<script setup lang="ts">
const tracingDebugStore = useTracingDebugStore()
const { isCalculating, data, debugDataFileUrls } = storeToRefs(tracingDebugStore)
const { startTracing } = tracingDebugStore
</script>

<template>
  <div class="flex">
    <!-- Левая панель (30%) -->
    <DebugSidebarControls :is-calculating @start-tracing="startTracing" />

    <!-- Правая часть (70%) -->
    <DebugMapViewer :data :debug-data-file-urls />
  </div>
</template>
