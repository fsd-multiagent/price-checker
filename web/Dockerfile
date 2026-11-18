# Build context is the repo root (AC 3.2.1).
FROM openapitools/openapi-generator-cli:v7.27.0 AS generate
WORKDIR /src
COPY api/ api/
RUN docker-entrypoint.sh generate -i api/openapi.yaml -g typescript-fetch -o /out --additional-properties=supportsES6=true,withInterfaces=false

FROM node:24.11.1-bookworm-slim AS build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
COPY --from=generate /out/ src/generated/
RUN npm run build --ignore-scripts

FROM caddy:2.10.2-alpine
COPY web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/web/dist /srv
RUN chown -R 10001:10001 /data /config
USER 10001:10001
EXPOSE 8080
