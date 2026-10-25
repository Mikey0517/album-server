# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ffmpeg tzdata curl sqlite

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseZGC"

WORKDIR /app
COPY --from=build /build/target/album-server.jar /app/app.jar

RUN addgroup -g 1000 app && adduser -u 1000 -G app -s /sbin/nologin -D app \
    && mkdir -p /data/media/thumbs \
    && chown -R app:app /app /data
USER app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
