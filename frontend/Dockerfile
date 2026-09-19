# Используем стабильную версию Node.js
FROM node:24-alpine AS base

ENV PNPM_HOME="/pnpm"
ENV PATH="$PNPM_HOME:$PATH"

RUN corepack enable

# Install dependencies only when needed
FROM base AS builder

# Check https://github.com/nodejs/docker-node/tree/b4117f9333da4138b03a546ec926ef50a31506c3#nodealpine to understand why libc6-compat might be needed.
RUN apk add --no-cache libc6-compat

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем файлы манифестов для установки зависимостей
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY package*.json ./

# 2. Используем cache mount для папки pnpm store. 
RUN --mount=type=cache,id=pnpm,target=/pnpm/store \
    pnpm config set store-dir /pnpm/store && \
    pnpm install --frozen-lockfile

# 3. Копируем исходный код только ПОСЛЕ установки зависимостей
COPY . ./

# Переменный которые "запекутся" в Vue
RUN mv .env.public ./.env

# Сборка через фильтр pnpm
RUN pnpm build-only

# Этап 2: Продакшн (Production)
FROM caddy:latest AS production-stage
COPY caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=builder /app/dist /usr/share/caddy