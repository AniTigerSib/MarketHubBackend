# Shared image for every service. Pick one with: --build-arg SERVICE=<module-name>
ARG SERVICE

FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
# Build files first, so the dependency download layer stays cached until they change
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY user-service/build.gradle user-service/
COPY auth-service/build.gradle auth-service/
COPY gateway/build.gradle gateway/
RUN ./gradlew --no-daemon :${SERVICE}:dependencies --configuration runtimeClasspath > /dev/null

COPY user-service user-service
COPY auth-service auth-service
COPY gateway gateway
RUN ./gradlew --no-daemon :${SERVICE}:bootJar -x test \
	&& cp ${SERVICE}/build/libs/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home app
USER app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
