# Локальный docker-compose-local.yml для docker-compose 1.29.2

## Запуск

1. Перейдите в директорию `source/docker`

1. Запустите приложение через docker-compose

```
docker-compose -f docker-compose-local.yml up -d --build
```

## Остановка

```
docker-compose -f docker-compose-local.yml down
```

## Остановка и удаление данных и контейнеров

```
docker-compose -f docker-compose-local.yml down -v --remove-orphans
```

# Локальный docker-compose-local-5.yml для docker-compose 5.x

Запускает только контейнер PostgreSQL с открытым наружу портом `5432`. Для удобства,
чтобы не устанавливать PostgreSQL через пакетный менеджер в GNU/Linux или установщик в Windows.

## Запуск

1. Перейдите в директорию `source/docker`

1. Запустите приложение через docker-compose

```
docker compose -f docker-compose-local-5.yml up -d
```

## Остановка

```
docker compose -f docker-compose-local-5.yml down
```

## Остановка и удаление данных и контейнеров

```
docker compose -f docker-compose-local-5.yml down -v --remove-orphans
```
