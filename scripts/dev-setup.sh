#!/usr/bin/env bash
# Install the toolchain the parts need. Safe to rerun: finished steps are skipped.
# Claude Code cloud sessions run it from the environment's setup script. Elsewhere it installs what it can and
# points at mise or asdf (.tool-versions) for the rest.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${GEUNAL_TOOLS_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/geunal-tools}"
in_cloud="${CLAUDE_CODE_REMOTE:-false}"

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }

pinned() { awk -v tool="$1" '$1 == tool { print $2 }' "$repo_root/.tool-versions"; }

os_field() { sed -n "s/^$1=//p" /etc/os-release | tr -d '"'; }

# Link into a directory PATH already holds, so no shell profile has to change.
link_dir() {
  if [ -w /usr/local/bin ]; then
    echo /usr/local/bin
  else
    mkdir -p "$HOME/.local/bin"
    echo "$HOME/.local/bin"
  fi
}

# builds.txt lines read "<name> <git sha> <built at> <sha256>"; setup-beam on CI downloads from the same index.
download_verified() {
  local url="$1" index="$2" name="$3" dest="$4" want got
  want="$(curl -fsSL "$index" | awk -v name="$name" '$1 == name { print $4 }')"
  if [ -z "$want" ]; then
    warn "$name is not listed in $index"
    return 1
  fi
  curl -fsSL "$url" -o "$dest"
  got="$(sha256sum "$dest" | awk '{ print $1 }')"
  if [ "$want" != "$got" ]; then
    warn "checksum mismatch for $url"
    return 1
  fi
}

install_beam() {
  local otp elixir flavor="" otp_dir elixir_dir tmp bin tool
  otp="$(pinned erlang)"
  elixir="$(pinned elixir)"

  # builds.hex.pm publishes OTP per Ubuntu release; other systems install .tool-versions with mise or asdf.
  if [ "$(uname -sm)" = "Linux x86_64" ] && [ -r /etc/os-release ] && [ "$(os_field ID)" = ubuntu ]; then
    flavor="ubuntu-$(os_field VERSION_ID)"
  fi
  if [ -z "$flavor" ]; then
    log "Erlang/OTP $otp and Elixir $elixir: install them from .tool-versions with mise or asdf"
    return 0
  fi

  otp_dir="$tools_dir/otp/OTP-$otp"
  elixir_dir="$tools_dir/elixir/v$elixir"
  tmp="$(mktemp -d)"

  # A marker file, written last, tells a finished install from one an earlier run left halfway.
  if [ ! -e "$otp_dir/.installed" ]; then
    log "Installing Erlang/OTP $otp ($flavor)"
    rm -rf "$otp_dir"
    download_verified "https://builds.hex.pm/builds/otp/$flavor/OTP-$otp.tar.gz" \
      "https://builds.hex.pm/builds/otp/$flavor/builds.txt" "OTP-$otp" "$tmp/otp.tar.gz"
    mkdir -p "$otp_dir"
    tar -xzf "$tmp/otp.tar.gz" -C "$otp_dir" --strip-components=1
    "$otp_dir/Install" -minimal "$otp_dir" >/dev/null
    touch "$otp_dir/.installed"
  fi

  if [ ! -e "$elixir_dir/.installed" ]; then
    log "Installing Elixir $elixir"
    rm -rf "$elixir_dir"
    download_verified "https://builds.hex.pm/builds/elixir/v$elixir.zip" \
      "https://builds.hex.pm/builds/elixir/builds.txt" "v$elixir" "$tmp/elixir.zip"
    mkdir -p "$elixir_dir"
    unzip -q "$tmp/elixir.zip" -d "$elixir_dir"
    touch "$elixir_dir/.installed"
  fi
  rm -rf "$tmp"

  bin="$(link_dir)"
  for tool in erl erlc escript; do ln -sf "$otp_dir/bin/$tool" "$bin/$tool"; done
  # Wrappers rather than links: cloud sessions have no UTF-8 locale, and Elixir wants UTF-8 file name encoding (+fnu).
  # rm first, because writing through an old link would overwrite Elixir's own script.
  for tool in elixir elixirc iex mix; do
    rm -f "$bin/$tool"
    cat >"$bin/$tool" <<EOF
#!/bin/sh
export ELIXIR_ERL_OPTIONS="\${ELIXIR_ERL_OPTIONS:-+fnu}"
exec "$elixir_dir/bin/$tool" "\$@"
EOF
    chmod +x "$bin/$tool"
  done
  case ":$PATH:" in
    *":$bin:"*) ;;
    *) warn "add $bin to PATH" ;;
  esac

  "$bin/mix" local.hex --force --if-missing >/dev/null
  "$bin/mix" local.rebar --force --if-missing >/dev/null
}

# Only cloud sessions need this: their egress gets intermittent 404 and 429 answers from Maven Central.
setup_maven_mirror() {
  local init_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
  if [ "$in_cloud" != true ]; then
    return 0
  fi
  log "Pointing Gradle at a Maven Central mirror (cloud session)"
  mkdir -p "$init_dir"
  cp "$repo_root/scripts/cloud/maven-mirror.gradle" "$init_dir/geunal-maven-mirror.gradle"
}

start_docker() {
  if ! "$repo_root/scripts/ensure-docker.sh"; then
    warn "Docker is not available; targets that need it fail until it is"
  fi
}

version_of() {
  case "$1" in
    docker) docker --version ;;
    cargo) cargo --version ;;
    java) java -version 2>&1 | sed -n '/version/{p;q;}' ;;
    erl) erl -noshell -eval '{ok, V} = file:read_file(filename:join([code:root_dir(), "releases",
      erlang:system_info(otp_release), "OTP_VERSION"])), io:put_chars(V), halt().' ;;
    elixir) elixir --short-version ;;
  esac
}

summary() {
  local tool
  log "Toolchain"
  for tool in docker cargo java erl elixir; do
    if command -v "$tool" >/dev/null 2>&1; then
      printf '  %-7s %s\n' "$tool" "$(version_of "$tool")"
    else
      printf '  %-7s missing\n' "$tool"
    fi
  done
}

install_beam
setup_maven_mirror
start_docker
summary
