# Builds the `mix release` and runs it on Debian slim as a non-root user (infra.md §3). The builder's Elixir and OTP
# match .tool-versions (make lint-gateway checks), and its Debian snapshot matches the runtime image.
FROM hexpm/elixir:1.20.4-erlang-29.1.2-debian-trixie-20261020-slim AS build
# A cloud session's proxy re-signs TLS, and scripts/docker-build.sh passes its CA as the secret `ca`. This stage is
# thrown away, so trusting the CA here leaves nothing in the image.
RUN --mount=type=secret,id=ca,required=false \
    if [ -f /run/secrets/ca ]; then cat /run/secrets/ca >>/etc/ssl/certs/ca-certificates.crt; fi
ENV MIX_ENV=prod
WORKDIR /build
RUN mix local.hex --force && mix local.rebar --force
COPY mix.exs mix.lock ./
COPY config config
RUN mix deps.get --only prod --check-locked && mix deps.compile
COPY lib lib
RUN mix release

FROM debian:trixie-20261020-slim
# Debian security fixes released after the base image was built come in here, so the Trivy gate (make scan-gateway)
# fails only on findings that have no fix yet. A cloud session's proxy carries only HTTPS: there
# scripts/docker-build.sh passes the proxy's CA as the secret `ca`, readable by apt's unprivileged downloader, and the
# archive is read over HTTPS. Elsewhere apt keeps Debian's HTTP mirrors, as the slim image has no CA bundle yet.
RUN --mount=type=secret,id=ca,required=false,mode=0444 \
    if [ -f /run/secrets/ca ]; then \
      sed -i 's#http://#https://#' /etc/apt/sources.list.d/debian.sources; \
      echo 'Acquire::https::CaInfo "/run/secrets/ca";' >/etc/apt/apt.conf.d/99ca; \
    fi \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade \
    && DEBIAN_FRONTEND=noninteractive apt-get -y install --no-install-recommends \
      ca-certificates curl libncurses6 libstdc++6 openssl \
    && rm -rf /var/lib/apt/lists/* /etc/apt/apt.conf.d/99ca \
    && groupadd --system --gid 10001 gateway && useradd --system --uid 10001 --gid gateway --no-create-home gateway
# C.UTF-8 is built into glibc, so no locales package is needed. Nodes never talk to each other (gateway.md §5), so the
# release starts without Erlang distribution, and its scratch files go to /tmp so the root file system can be
# read-only.
ENV LANG=C.UTF-8 RELEASE_DISTRIBUTION=none RELEASE_TMP=/tmp
WORKDIR /app
COPY --from=build /build/_build/prod/rel/gateway ./
USER 10001:10001
EXPOSE 4000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
  CMD curl -fsS http://localhost:4000/healthz || exit 1
ENTRYPOINT ["/app/bin/gateway"]
CMD ["start"]
