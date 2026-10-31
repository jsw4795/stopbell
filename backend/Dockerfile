FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a

WORKDIR /app
COPY build/libs/stopbell.jar /app/stopbell.jar
USER 10001:10001
ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=production \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=60s
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/stopbell.jar"]
