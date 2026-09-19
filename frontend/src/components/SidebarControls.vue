<script setup lang="ts">
import { ref } from 'vue'
// Импорт иконок для красоты
import { useTracingStore } from '@/stores/tracing'
import { AlertCircle, DollarSign, Network, Play } from '@lucide/vue'

const tracingStore = useTracingStore()
const { trace, traceMock } = tracingStore

const geojsonFile = ref<File>()

// Состояние для выбранного варианта (по умолчанию Вариант 1)
const selectedVariant = ref('variant-1')

// Флаг загрузки (имитация расчета)
const isCalculating = ref(false)

// Тестовые данные для информационного блока (в реальном проекте придут из store или emit)
const summaryData = ref({
  totalCost: 1250000,
  networkLength: 3450,
  failedOksIds: ['OKS-0024', 'OKS-1152', 'OKS-0981', 'OKS-4412', 'OKS-7721'],
})

// Функция обработки клика по кнопке расчета
const onStartTracing = async () => {
  isCalculating.value = true

  const file = geojsonFile.value

  try {
    if (!file) {
      await traceMock()
    } else {
      await trace(file)
    }
  } catch (error) {
    throw error
  } finally {
    isCalculating.value = false
  }
}

const onFileUpload = (event: Event) => {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (files && files.length > 0) {
    geojsonFile.value = files[0]
    console.log('Загружен geojson')
  }
}

// Форматирование валюты (рубли)
const formatCurrency = (value) => {
  return new Intl.NumberFormat('ru-RU', {
    style: 'currency',
    currency: 'RUB',
    maximumFractionDigits: 0,
  }).format(value)
}
</script>

<template>
  <aside
    class="w-[30%] h-screen border-r bg-background p-6 flex flex-col gap-6 justify-between select-none"
  >
    <!-- Верхняя часть панели управления -->
    <div class="flex flex-col gap-6">
      <div>
        <h2 class="text-xl font-semibold tracking-tight">Управление трассировкой</h2>
        <p class="text-sm text-muted-foreground mt-1">Настройте параметры и запустите расчет</p>
      </div>

      <div class="grid w-full max-w-sm items-center gap-1.5">
        <Label for="geojson">Исходный GeoJson</Label>
        <Input id="geojson" type="file" @change="onFileUpload" />
      </div>

      <!-- Блок выбора вариантов -->
      <div class="space-y-2">
        <label class="text-sm font-medium text-foreground">Выберите вариант</label>
        <ToggleGroup
          v-model="selectedVariant"
          type="single"
          variant="outline"
          class="justify-start w-full gap-2"
        >
          <ToggleGroupItem value="variant-1" class="flex-1"> Вариант 1 </ToggleGroupItem>
          <ToggleGroupItem value="variant-2" class="flex-1"> Вариант 2 </ToggleGroupItem>
          <ToggleGroupItem value="variant-3" class="flex-1"> Вариант 3 </ToggleGroupItem>
        </ToggleGroup>
      </div>

      <!-- Кнопка запуска расчета -->
      <Button
        @click="onStartTracing"
        :disabled="isCalculating"
        class="w-full font-medium"
        size="lg"
      >
        <Play v-if="!isCalculating" class="mr-2 h-4 w-4 fill-current" />
        <span v-if="isCalculating">Расчет...</span>
        <span v-else>Запустить расчет трассировки</span>
      </Button>
    </div>

    <!-- Информационный блок с результатами (Card из shadcn) -->
    <Card class="flex-1 min-h-0 flex flex-col mt-4">
      <CardHeader class="pb-3">
        <CardTitle class="text-base font-semibold flex items-center gap-2">
          Результаты расчета
        </CardTitle>
      </CardHeader>

      <CardContent class="flex-1 flex flex-col gap-4 text-sm min-h-0 overflow-hidden pb-4">
        <!-- Стоимость -->
        <div class="flex items-center justify-between border-b pb-2">
          <span class="text-muted-foreground flex items-center gap-1.5">
            <DollarSign class="h-4 w-4 text-emerald-500" /> Общая стоимость:
          </span>
          <span class="font-semibold text-emerald-600 dark:text-emerald-400">
            {{ formatCurrency(summaryData.totalCost) }}
          </span>
        </div>

        <!-- Длина сетей -->
        <div class="flex items-center justify-between border-b pb-2">
          <span class="text-muted-foreground flex items-center gap-1.5">
            <Network class="h-4 w-4 text-blue-500" /> Длина новых сетей:
          </span>
          <span class="font-semibold"> {{ summaryData.networkLength }} м </span>
        </div>

        <!-- Список не подключенных ОКС -->
        <div class="flex-1 flex flex-col gap-2 min-h-0">
          <span
            class="text-muted-foreground flex items-center gap-1.5 font-medium text-destructive"
          >
            <AlertCircle class="h-4 w-4" /> Не удалось подключить ({{
              summaryData.failedOksIds.length
            }}):
          </span>

          <!-- Скролл-зона из shadcn на случай большого списка ID -->
          <ScrollArea class="flex-1 border rounded-md p-2 bg-muted/40">
            <div class="flex flex-wrap gap-1.5">
              <Badge
                v-for="id in summaryData.failedOksIds"
                :key="id"
                variant="outline"
                class="bg-background text-xs font-mono font-normal"
              >
                {{ id }}
              </Badge>
            </div>
          </ScrollArea>
        </div>
      </CardContent>
    </Card>
  </aside>
</template>
