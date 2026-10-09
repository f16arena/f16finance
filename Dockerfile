# Сборка веба Orda для своего сервера (см. deploy/vps/README.md). Vercel этот файл не использует.
# NEXT_PUBLIC_* вшиваются в бандл на этапе сборки, поэтому build-аргументы обязательны.

FROM node:22-bookworm-slim AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_SUPABASE_URL
ARG NEXT_PUBLIC_SUPABASE_ANON_KEY
ARG NEXT_PUBLIC_APP_URL
ARG NEXT_PUBLIC_SITE_NAME
ARG NEXT_PUBLIC_PRODUCT_MARK
ARG GITHUB_SHA
ENV NEXT_OUTPUT_STANDALONE=1 \
    NEXT_TELEMETRY_DISABLED=1 \
    NEXT_PUBLIC_SUPABASE_URL=$NEXT_PUBLIC_SUPABASE_URL \
    NEXT_PUBLIC_SUPABASE_ANON_KEY=$NEXT_PUBLIC_SUPABASE_ANON_KEY \
    NEXT_PUBLIC_APP_URL=$NEXT_PUBLIC_APP_URL \
    NEXT_PUBLIC_SITE_NAME=$NEXT_PUBLIC_SITE_NAME \
    NEXT_PUBLIC_PRODUCT_MARK=$NEXT_PUBLIC_PRODUCT_MARK \
    GITHUB_SHA=$GITHUB_SHA
RUN pnpm build

FROM node:22-bookworm-slim AS run
WORKDIR /app
# Chromium для PDF-отчётов (CHROMIUM_PATH) + шрифты с кириллицей.
RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium fonts-noto-core fonts-dejavu-core ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    CHROMIUM_PATH=/usr/bin/chromium \
    PORT=3000 \
    HOSTNAME=0.0.0.0
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
USER node
EXPOSE 3000
CMD ["node", "server.js"]
