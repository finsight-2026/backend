FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 finsight \
    && useradd --no-create-home --uid 10001 --gid finsight finsight
WORKDIR /app
COPY --chown=10001:10001 build/libs/finsight-0.0.1-SNAPSHOT.jar /app/finsight.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
    CMD ["curl", "--fail", "--silent", "http://127.0.0.1:8080/actuator/health"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/finsight.jar"]
