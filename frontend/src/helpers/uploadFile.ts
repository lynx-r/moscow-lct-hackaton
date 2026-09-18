import apiClient from '@/api/client'

export const uploadFile = async (url: string, file: Blob) => {
  // 1. Создаем объект FormData
  const formData = new FormData()

  // 2. Добавляем файл (первый аргумент — ключ, который ожидает бэкенд)
  formData.append('file', file)

  // 3. Отправляем POST-запрос
  const response = await apiClient.post(url, formData)

  return response.data
}
