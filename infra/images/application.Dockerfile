ARG JAVA_IMAGE=eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf
FROM ${JAVA_IMAGE}
ARG SERVICE
ARG REVISION
ARG JAR_SHA256
ARG AGENT_SHA256
LABEL org.opencontainers.image.title="Cutover ${SERVICE}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.revision="${REVISION}" \
      dev.cutover.jar-sha256="${JAR_SHA256}" \
      dev.cutover.agent-sha256="${AGENT_SHA256}"
WORKDIR /app
COPY apps/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT-exec.jar /app/app.jar
COPY .local/assets/opentelemetry-javaagent.jar /app/telemetry.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true"
EXPOSE 8080 8443 9091
ENTRYPOINT ["java","-jar","/app/app.jar"]
