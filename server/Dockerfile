# WAR는 CPU 구조와 무관하다 — 빌드 단계는 빌드 머신 구조로 돌려 멀티 아키텍처 빌드에서 에뮬레이션을 피한다
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon bootWar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.war app.war
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.war"]
