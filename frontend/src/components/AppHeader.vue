<script setup lang="ts"></script>

<template>
  <header
    class="sticky top-0 z-50 w-full border-b bg-background/95 backdrop-blur supports-backdrop-filter:bg-background/60"
  >
    <div class="container flex h-14 max-w-screen-2xl items-center justify-between">
      <!-- Логотип и Главное меню -->
      <div class="flex items-center gap-6 md:gap-10">
        <a href="/" class="flex items-center space-x-2 font-bold text-lg tracking-tight">
          <span class="bg-primary text-primary-foreground px-2 py-0.5 rounded-md">LCT</span>
          <span>Ultra</span>
        </a>

        <nav class="hidden md:flex items-center space-x-6 text-sm font-medium">
          <a href="/" class="transition-colors hover:text-foreground text-foreground/60"
            >Главное решение</a
          >
          <a href="/alt" class="transition-colors hover:text-foreground text-foreground/60"
            >Альтернативное решение</a
          >
        </nav>
      </div>

      <!-- Правая часть: Кнопки действий -->
      <div class="flex items-center space-x-4">
        <nav class="flex items-center space-x-2">
          <ModeToggle />
        </nav>
      </div>
    </div>
  </header>
</template>
