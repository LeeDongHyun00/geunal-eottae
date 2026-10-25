# Packages the boot jar from `./gradlew bootJar` (make image-api). Building the jar outside the image reuses the Gradle
# cache, and the image build downloads nothing but the base image and Ubuntu's updates.
FROM eclipse-temurin:21.0.12.1_1-jre-noble AS layers
WORKDIR /layers
COPY build/libs/api.jar api.jar
RUN java -Djarmode=tools -jar api.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21.0.12.1_1-jre-noble
# Ubuntu security fixes released after the base image was built come in here, so the Trivy gate (make scan-api) fails
# only on findings that have no fix yet. The archives are read over HTTPS because a cloud session's proxy carries
# nothing else; there scripts/docker-build.sh also passes the proxy's CA as the secret `ca`, readable by apt's
# unprivileged downloader.
RUN --mount=type=secret,id=ca,required=false,mode=0444 \
    sed -i 's#http://#https://#' /etc/apt/sources.list.d/ubuntu.sources \
    && if [ -f /run/secrets/ca ]; then echo 'Acquire::https::CaInfo "/run/secrets/ca";' >/etc/apt/apt.conf.d/99ca; fi \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade \
    && rm -rf /var/lib/apt/lists/* /etc/apt/apt.conf.d/99ca \
    && groupadd --system --gid 10001 api && useradd --system --uid 10001 --gid api --no-create-home api
WORKDIR /app
# Dependencies change least often, so their layer comes first and stays cached between releases.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
