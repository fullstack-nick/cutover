ARG PROXY_IMAGE=nginxinc/nginx-unprivileged:1.30.0-alpine@sha256:808f7846d21a9c94cf53833e8807a00a33fd0b65cc47fb05b79efe366c2d201f
FROM ${PROXY_IMAGE}
ARG REVISION
ARG CONTENT_SHA256
LABEL org.opencontainers.image.title="Cutover operations console" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.revision="${REVISION}" \
      dev.cutover.console-sha256="${CONTENT_SHA256}"
COPY --chown=101:101 apps/operations-console/dist/ /usr/share/nginx/html/
USER 101:101
