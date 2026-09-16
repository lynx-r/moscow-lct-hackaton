# Установка окружение Docker

1. Установите и запустите Docker `https://docs.docker.com/engine/install/`

1. Установите docker-compose 1.29.2 с `https://github.com/docker/compose/releases/tag/1.29.2`

## Установка переменных окружения

1. Скопируйте файл `backend/.env.example` в `backend/.env`

1. Скопируйте файл `database/.env.example` в `database/.env`

1. Задайте переменные окружения в `backend/.env` и `database/.env`

## Локальный docker-compose-local.yml

### Запуск 

1. Перейдите в директорию `source/docker`

1. Запустите приложение через docker-compose

```
docker-compose -f docker-compose-local.yml up -d --build     
```

### Остановка

```
docker-compose -f docker-compose-local.yml down
```

### Остановка и удаление данных и контейнеров

```
docker-compose -f docker-compose-local.yml down -v --remove-orphans 
```