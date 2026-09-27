# Используем стабильную версию Node.js
FROM node:24-alpine AS base

ENV PNPM_HOME="/pnpm"
ENV PATH="$PNPM_HOME:$PATH"

RUN npm install -g pnpm@12.4.2

# Install dependencies only when needed
FROM base AS builder

# Check https://github.com/nodejs/docker-node/tree/b4117f9333da4138b03a546ec926ef50a31506c3#nodealpine to understand why libc6-compat might be needed.
RUN apk add --no-cache libc6-compat

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем файлы манифестов для установки зависимостей
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
COPY package*.json ./

# 2. Кэшируем зависимости обычным слоем Docker.
RUN pnpm config set store-dir /pnpm/store && \
    pnpm install --frozen-lockfile

# 3. Копируем исходный код только ПОСЛЕ установки зависимостей
COPY . ./

# Переменный которые "запекутся" в Vue
RUN mv .env.public ./.env

# Local Compose overrides the API URL without changing production defaults.
ARG ROUTING_API_BASE_URL
RUN if [ -n "$ROUTING_API_BASE_URL" ]; then \
      VITE_API_BASE_URL="$ROUTING_API_BASE_URL" pnpm build; \
    else pnpm build; fi

# Этап 2: Продакшн (Production)
FROM caddy:latest AS production-stage
COPY caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=builder /app/dist /usr/share/caddy