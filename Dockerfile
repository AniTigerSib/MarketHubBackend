# syntax=docker/dockerfile:1
# Shared image for every service. Pick one with: --build-arg SERVICE=<module-name>
# Needs BuildKit (docker buildx) for the cache mounts below.
ARG SERVICE

FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY docker/maven-mirror.init.gradle /root/.gradle/init.d/
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY user-service user-service
COPY auth-service auth-service
COPY listing-service listing-service
COPY gateway gateway
# The Gradle distribution and downloaded dependencies live in a build cache shared by all
# service images and kept between builds, so each file is downloaded only once.
# "locked" makes parallel image builds take turns: Gradle's own cache locking can't
# coordinate across separate build containers and times out.
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
	--mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
	./gradlew --no-daemon :${SERVICE}:bootJar -x test \
	&& cp ${SERVICE}/build/libs/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home app
USER app
COPY --from=build /app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
