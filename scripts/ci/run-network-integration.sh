#!/usr/bin/env bash
# Creates a disposable, offline-mode Velocity test network and proves that a
# Bots4Velo client can authenticate, reach PLAY, change backend, receive signed
# Paper policy acknowledgements and recover after controlled backend/proxy
# outages. Modern targets also prove AuthMeUI's rules and registration dialogs.
# It is intentionally self-contained: Paper/Velocity/AuthMe/AuthMeUI binaries
# are downloaded at runtime and are never committed to this repository.
set -Eeuo pipefail

MINECRAFT_VERSION="${1:?usage: run-network-integration.sh <minecraft-version> <protocol-id>}"
PROTOCOL_ID="${2:?usage: run-network-integration.sh <minecraft-version> <protocol-id>}"
WORK_ROOT="${WORK_ROOT:-$PWD/.integration-network/$MINECRAFT_VERSION}"
VELOCITY_PLUGIN_JAR="${VELOCITY_PLUGIN_JAR:-$PWD/build/libs/bots4velo-[0-9]*.jar}"
PAPER_COMPANION_JAR="${PAPER_COMPANION_JAR:-$PWD/build/libs/bots4velo-paper-*.jar}"
PAPER_JAVA="${PAPER_JAVA:-${JAVA_HOME:-}/bin/java}"
VELOCITY_JAVA="${VELOCITY_JAVA:-$PAPER_JAVA}"
PYTHON="${PYTHON:-python3}"
USER_AGENT="bots4velo-integration/3.0.6 (https://github.com/EllanServer/Bots4Velo)"
AUTHMEUI_VERSION="1.3.4"
AUTHMEUI_URL="https://cdn.modrinth.com/data/xwRjZuDG/versions/8tYeXZL1/AuthMeUI-1.3.4.jar"
AUTHMEUI_SHA256="562aff394d756326850cb2fc7ef1a2bcc1082c22310bc5a66ce06ae926fa0475"
BACKEND_SHARED_SECRET="${BACKEND_SHARED_SECRET:-Bots4Velo-CI-Backend-Control-Key-2026-0001}"
PROXY_PORT="${PROXY_PORT:-25590}"
LOBBY_PORT="${LOBBY_PORT:-25591}"
AFK_PORT="${AFK_PORT:-25592}"
PAPER_START_TIMEOUT="${PAPER_START_TIMEOUT:-300}"
PIDS=()
AUTHMEUI_FAILURE_FIXTURE_SELECTOR="RulesDeclined"
INTEGRATION_LOGIN_COMMAND='login {password}'
INTEGRATION_REGISTER_COMMAND='register {password} {password}'
if [[ "$MINECRAFT_VERSION" == "1.16.5" ]]; then
  # AuthMeUI cannot run on this legacy target. Keep its deliberate rules
  # rejection fixture out of the selector while preserving chat registration.
  AUTHMEUI_FAILURE_FIXTURE_SELECTOR="@tag:ci-authmeui-only"
fi

log() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

artifact_identity() {
  local label="$1" file="$2" bytes sha256
  bytes="$(wc -c < "$file" | tr -d '[:space:]')"
  sha256="$(sha256sum "$file" | awk '{print $1}')"
  printf 'ARTIFACT %s | bytes=%s | sha256=%s | path=%s\n' \
    "$label" "$bytes" "$sha256" "$file" | tee -a "$WORK_ROOT/artifact-manifest.log"
}

record_resolution() {
  printf 'RESOLVED %s | url=%s\n' "$1" "$2" | tee -a "$WORK_ROOT/artifact-manifest.log"
}

# GitHub's Ubuntu runners use a native Linux JDK. WSL cannot reliably probe
# loopback ports opened by a Windows java.exe child, so fail immediately with
# an actionable message instead of spending the Paper startup timeout waiting
# for a port that the shell cannot reach. Git Bash/MINGW is unaffected.
if [[ "$(uname -s)" == "Linux" && "$PAPER_JAVA" == *.exe ]]; then
  die "PAPER_JAVA must be a native Linux JDK when running under Linux/WSL; install Java in WSL or run this script from Git Bash."
fi
[[ -x "$PAPER_JAVA" ]] || die "PAPER_JAVA is not executable: $PAPER_JAVA"
if [[ "$(uname -s)" == "Linux" && "$VELOCITY_JAVA" == *.exe ]]; then
  die "VELOCITY_JAVA must be a native Linux JDK when running under Linux/WSL."
fi
[[ -x "$VELOCITY_JAVA" ]] || die "VELOCITY_JAVA is not executable: $VELOCITY_JAVA"

cleanup() {
  local process
  for process in "${PIDS[@]:-}"; do
    kill "$process" 2>/dev/null || true
  done
}
trap cleanup EXIT

stop_tracked_process() {
  local target="$1" process
  local retained=()
  kill "$target" 2>/dev/null || true
  wait "$target" 2>/dev/null || true
  for process in "${PIDS[@]:-}"; do
    [[ "$process" == "$target" ]] || retained+=("$process")
  done
  PIDS=("${retained[@]}")
}

wait_for_port() {
  local port="$1" timeout="$2"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "Port $port did not start within ${timeout}s"
}

wait_for_log() {
  local file="$1" pattern="$2" timeout="$3"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    grep -Eq "$pattern" "$file" 2>/dev/null && return 0
    sleep 1
  done
  tail -n 160 "$file" 2>/dev/null || true
  die "Did not find /$pattern/ in $file within ${timeout}s"
}

wait_for_log_or_failure() {
  local file="$1" success_pattern="$2" failure_pattern="$3" timeout="$4" label="$5"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    if [[ -f "$file" ]] && grep -Eq "$failure_pattern" "$file"; then
      tail -n 160 "$file" 2>/dev/null || true
      die "$label reported a startup failure matching /$failure_pattern/"
    fi
    if [[ -f "$file" ]] && grep -Eq "$success_pattern" "$file"; then
      return 0
    fi
    sleep 1
  done
  tail -n 160 "$file" 2>/dev/null || true
  die "Did not find $label readiness /$success_pattern/ in $file within ${timeout}s"
}

wait_for_new_log() {
  local file="$1" pattern="$2" previous_lines="$3" timeout="$4"
  local end=$((SECONDS + timeout))
  local first_new_line=$((previous_lines + 1))
  while (( SECONDS < end )); do
    if [[ -f "$file" ]] && tail -n "+$first_new_line" "$file" 2>/dev/null | grep -Eq "$pattern"; then
      return 0
    fi
    sleep 1
  done
  tail -n 160 "$file" 2>/dev/null || true
  die "Did not find a new /$pattern/ in $file within ${timeout}s"
}

last_matching_log_line() {
  local file="$1" pattern="$2"
  grep -nE "$pattern" "$file" | tail -n 1 | cut -d: -f1
}

download_fill_artifact() {
  local project="$1" version="$2" destination="$3" response="" url attempt
  # Four matrix jobs can hit Fill at nearly the same time. A manual retry loop
  # also covers HTTP 403 responses, which curl does not classify as transient.
  for attempt in 1 2 3 4 5 6; do
    if response="$(curl --fail --connect-timeout 20 --max-time 90 --silent --show-error \
      -H "User-Agent: $USER_AGENT" \
      "https://fill.papermc.io/v3/projects/$project/versions/$version/builds")" && [[ -n "$response" ]]; then
      break
    fi
    response=""
    sleep $((attempt * 3))
  done
  [[ -n "$response" ]] || die "Could not download $project $version build metadata after 6 attempts"
  url="$("$PYTHON" -c '
import json
import sys
for build in json.load(sys.stdin):
    if build.get("channel") == "STABLE":
        print(build["downloads"]["server:default"]["url"])
        break
' <<<"$response")"
  [[ -n "$url" ]] || die "No stable $project build exists for $version"
  record_resolution "$project $version" "$url"
  curl --fail --retry 5 --retry-all-errors --connect-timeout 20 --max-time 240 --location --silent --show-error -H "User-Agent: $USER_AGENT" \
    --output "$destination" "$url"
  artifact_identity "$project $version" "$destination"
}

download_authme() {
  local release="$1" suffix="$2" release_url url
  if [[ -n "${AUTHME_JAR:-}" ]]; then
    [[ -f "$AUTHME_JAR" ]] || die "AUTHME_JAR does not exist: $AUTHME_JAR"
    cp "$AUTHME_JAR" "$WORK_ROOT/lobby/plugins/AuthMe.jar"
    artifact_identity "AuthMe $release" "$WORK_ROOT/lobby/plugins/AuthMe.jar"
    return
  fi
  release_url="https://api.github.com/repos/AuthMe/AuthMeReloaded/releases/tags/$release"
  url="$(curl --fail --retry 3 --connect-timeout 20 --max-time 90 --silent --show-error -H "User-Agent: $USER_AGENT" "$release_url" | \
    "$PYTHON" -c '
import json
import sys
suffix = sys.argv[1]
for asset in json.load(sys.stdin).get("assets", []):
    if asset.get("name", "").endswith(suffix):
        print(asset["browser_download_url"])
        break
' "$suffix")"
  [[ -n "$url" ]] || die "AuthMe $release asset ending in $suffix was not found"
  record_resolution "AuthMe $release" "$url"
  curl --fail --retry 3 --connect-timeout 20 --max-time 180 --location --silent --show-error -H "User-Agent: $USER_AGENT" \
    --output "$WORK_ROOT/lobby/plugins/AuthMe.jar" "$url"
  artifact_identity "AuthMe $release" "$WORK_ROOT/lobby/plugins/AuthMe.jar"
}

install_authmeui() {
  local destination="$WORK_ROOT/lobby/plugins/AuthMeUI.jar" actual_hash
  if [[ -n "${AUTHMEUI_JAR:-}" ]]; then
    [[ -f "$AUTHMEUI_JAR" ]] || die "AUTHMEUI_JAR does not exist: $AUTHMEUI_JAR"
    cp "$AUTHMEUI_JAR" "$destination"
  else
    curl --fail --retry 5 --retry-all-errors --connect-timeout 20 --max-time 180 \
      --location --silent --show-error -H "User-Agent: $USER_AGENT" \
      --output "$destination" "$AUTHMEUI_URL"
  fi

  actual_hash="$(sha256sum "$destination" | awk '{print $1}')"
  [[ "$actual_hash" == "$AUTHMEUI_SHA256" ]] || \
    die "AuthMeUI $AUTHMEUI_VERSION SHA-256 mismatch: expected $AUTHMEUI_SHA256, got $actual_hash"
  artifact_identity "AuthMeUI $AUTHMEUI_VERSION" "$destination"

  write_authmeui_config true false
}

write_authmeui_config() {
  local use_configuration_phase="${1:?AuthMeUI mode must be true or false}"
  local respect_authme_sessions="${2:-false}"
  [[ "$use_configuration_phase" == "true" || "$use_configuration_phase" == "false" ]] || \
    die "AuthMeUI configuration-phase mode must be true or false, got: $use_configuration_phase"
  [[ "$respect_authme_sessions" == "true" || "$respect_authme_sessions" == "false" ]] || \
    die "AuthMeUI session-respect mode must be true or false, got: $respect_authme_sessions"

  mkdir -p "$WORK_ROOT/lobby/plugins/AuthMeUI"
  cat > "$WORK_ROOT/lobby/plugins/AuthMeUI/config.yml" <<EOF
dialogs:
  allow-escape-close: false
  button-columns: 2
  input-width: 150
  use-configuration-phase: $use_configuration_phase
  configuration-phase-timeout: 60
  configuration-phase-respect-authme-sessions: $respect_authme_sessions
  configuration-phase-fastlogin-compatibility: false
  configuration-phase-deferred-login-check-delay-ticks: 40
login-dialog:
  title: "<white><bold>CI Login</bold></white>"
  password-label: "Password"
  submit-button: "<green>Sign In</green>"
  forgot-button-enabled: false
  cancel-button-enabled: false
register-dialog:
  title: "<white><bold>CI Registration</bold></white>"
  password-label: "Password"
  confirm-label: "Confirm Password"
  email-label: "Email Address"
  submit-button: "<green>Register</green>"
rules-dialog:
  enabled: true
  title: "<white><bold>CI Rules</bold></white>"
  body:
    - "<gray>Bots4Velo must accept this test rule before registration.</gray>"
  agreement:
    enabled: true
    # Deliberately differs from AuthMeUI's default to prove dynamic field parsing.
    checkbox-key: "ci_rules_accepted"
    label: "<gray>I accept the CI rules</gray>"
  confirm-button: "<green>I Accept</green>"
metrics:
  enabled: false
EOF
}

write_authme_config() {
  local sessions_enabled="${1:-false}"
  [[ "$sessions_enabled" == "true" || "$sessions_enabled" == "false" ]] || \
    die "AuthMe session mode must be true or false, got: $sessions_enabled"
  # AuthMe 6 has its own Dialog implementation. Keep it disabled so the test
  # proves TejasLamba2006/AuthMeUI rather than accepting either provider by
  # accident. ConfigMe fills every omitted AuthMe property from its defaults.
  mkdir -p "$WORK_ROOT/lobby/plugins/AuthMe"
  cat > "$WORK_ROOT/lobby/plugins/AuthMe/config.yml" <<EOF
settings:
  sessions:
    enabled: $sessions_enabled
  messagesLanguage: en
  registration:
    dialog:
      preJoin:
        enable: false
      postJoin:
        enable: false
EOF
}

disable_authme_dialogs() {
  write_authme_config false
}

reset_authme_fixture_state() {
  local work_root_real authme_directory authme_directory_real database_file port
  for port in "$PROXY_PORT" "$LOBBY_PORT" "$AFK_PORT"; do
    if (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1; then
      die "Port $port is already listening; refusing to reset a possibly active AuthMe fixture"
    fi
  done
  mkdir -p "$WORK_ROOT/lobby/plugins/AuthMe"
  work_root_real="$(cd "$WORK_ROOT" && pwd -P)"
  authme_directory="$WORK_ROOT/lobby/plugins/AuthMe"
  authme_directory_real="$(cd "$authme_directory" && pwd -P)"

  # This is deliberately an exact path check followed by an explicit file
  # list. Never recursively remove WORK_ROOT or the AuthMe plugin directory.
  [[ "$authme_directory_real" == "$work_root_real/lobby/plugins/AuthMe" ]] || \
    die "Refusing to reset AuthMe fixture state outside WORK_ROOT: $authme_directory_real"
  for database_file in authme.db authme.db-journal authme.db-wal authme.db-shm; do
    rm -f -- "$authme_directory_real/$database_file"
  done
}

start_paper() {
  local name="$1" port="$2"
  local directory="$WORK_ROOT/$name"
  local shared_mojang_cache="$WORK_ROOT/lobby/cache/mojang_${MINECRAFT_VERSION}.jar"
  local target_mojang_cache="$directory/cache/mojang_${MINECRAFT_VERSION}.jar"
  mkdir -p "$directory/plugins/Bots4VeloPaper"
  cp "$WORK_ROOT/paper.jar" "$directory/paper.jar"
  cp "$PAPER_COMPANION_JAR" "$directory/plugins/bots4velo-paper.jar"
  # Paper otherwise downloads the same Mojang server JAR once per backend.
  # Reuse the lobby's validated ZIP for the AFK fixture and lobby restarts.
  if [[ -f "$shared_mojang_cache" && "$shared_mojang_cache" != "$target_mojang_cache" ]] \
      && "$PYTHON" -m zipfile -t "$shared_mojang_cache" >/dev/null 2>&1; then
    mkdir -p "$directory/cache"
    cp "$shared_mojang_cache" "$target_mojang_cache"
  fi
  cat > "$directory/plugins/Bots4VeloPaper/config.yml" <<EOF
shared-secret: "$BACKEND_SHARED_SECRET"
maximum-clock-skew-seconds: 60
replay-retention-seconds: 300
maximum-replay-entries: 4096
maximum-message-bytes: 16384
cancel-damage-events: true
log-rejected-messages: true
EOF
  printf 'eula=true\n' > "$directory/eula.txt"
  cat > "$directory/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$port
online-mode=false
enforce-secure-profile=false
enable-status=true
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=10
motd=Bots4Velo CI $name $MINECRAFT_VERSION
# Bots4Velo acknowledges this URL without downloading it. The same offer is
# intentionally sent after a proxy restart to exercise duplicate pack handling.
resource-pack=https://example.invalid/bots4velo-ci.zip
resource-pack-sha1=da39a3ee5e6b4b0d3255bfef95601890afd80709
require-resource-pack=false
EOF
  (
    cd "$directory"
    exec "$PAPER_JAVA" -Xms256M -Xmx768M -jar paper.jar --nogui
  ) > "$directory/console.log" 2>&1 &
  local paper_pid="$!"
  PIDS+=("$paper_pid")
  if [[ "$name" == "lobby" ]]; then
    LOBBY_PID="$paper_pid"
  else
    AFK_PID="$paper_pid"
  fi
  wait_for_port "$port" "$PAPER_START_TIMEOUT"
  wait_for_log "$directory/console.log" 'Done \(' "$PAPER_START_TIMEOUT"
  wait_for_log "$directory/console.log" \
    'Listening for authenticated Bots4Velo policies on bots4velo:control' "$PAPER_START_TIMEOUT"
}

start_velocity() {
  local velocity_directory="$WORK_ROOT/velocity"
  mkdir -p "$velocity_directory/plugins/bots4velo"
  cp "$WORK_ROOT/velocity.jar" "$velocity_directory/velocity.jar"
  cp "$VELOCITY_PLUGIN_JAR" "$velocity_directory/plugins/bots4velo.jar"
  cat > "$velocity_directory/velocity.toml" <<EOF
config-version = "2.8"
bind = "127.0.0.1:$PROXY_PORT"
motd = "Bots4Velo $MINECRAFT_VERSION integration"
show-max-players = 20
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
[servers]
lobby = "127.0.0.1:$LOBBY_PORT"
afk = "127.0.0.1:$AFK_PORT"
try = ["lobby"]
[forced-hosts]
[advanced]
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
EOF
  cat > "$velocity_directory/plugins/bots4velo/config.yml" <<EOF
proxy:
  address: "127.0.0.1"
  port: $PROXY_PORT
  virtual-host: "localhost"
  virtual-port: $PROXY_PORT
  protocol-version: "$MINECRAFT_VERSION"
runtime:
  auto-start-delay-ms: 1000
  maximum-bots: 2
  spawn-interval-ms: 250
  command-interval-ms: 100
  resource-pack-mode: "ACCEPT_WITHOUT_DOWNLOAD"
  backend-control:
    enabled: true
    secret: "$BACKEND_SHARED_SECRET"
    secret-env: ""
    timeout-ms: 5000
  reconnect:
    initial-delay-ms: 500
    maximum-delay-ms: 2000
    multiplier: 1.0
    jitter: 0.0
    maximum-attempts: 6
  # Start a second modern-only bot after IntegrationBot. It deliberately
  # declines AuthMeUI's rules and must fail closed without submitting them.
  schedules:
    - id: "start-rules-declined-fixture"
      action: "start"
      selector: "${AUTHMEUI_FAILURE_FIXTURE_SELECTOR}"
      initial-delay-ms: 15000
      interval-ms: 3600000
    # A second automatic start must not override the fail-closed authentication
    # state produced by the rules fixture.
    - id: "retry-rules-declined-fixture"
      action: "start"
      selector: "${AUTHMEUI_FAILURE_FIXTURE_SELECTOR}"
      initial-delay-ms: 20000
      interval-ms: 3600000
  # Keeps the integration bot assigned to AFK. This deliberately retries the
  # assignment after a backend/network interruption so the test can prove the
  # bot returns to the recovered backend rather than merely proving its port
  # opens again.
  presence-rules:
    - id: "maintain-afk"
      server: "afk"
      selector: "IntegrationBot"
      minimum-bots: 1
      maximum-humans: 10
      interval-ms: 1000
bots:
  IntegrationBot:
    enabled: true
    username: "B4VCI_${PROTOCOL_ID}"
    password: "Bots4VeloTest"
    target-server: "afk"
    protocol-detection-server: "afk"
    server-switch-command: "server {server}"
    server-switch-delay-ms: 500
    server-switch-maximum-attempts: 6
    player-state:
      afk-preset: "FARM"
      invulnerable: "ENABLED"
      game-mode: "SURVIVAL"
      # Gives the shell enough time to start observing the recovered AFK
      # connection before the signed policy acknowledgement is emitted.
      apply-delay-ms: 3000
      respawn-point:
        mode: "CURRENT"
    auth:
      mode: "AUTO"
      login-command: "$INTEGRATION_LOGIN_COMMAND"
      register-command: "$INTEGRATION_REGISTER_COMMAND"
      login-delay-ms: 250
      # AuthMe 5.6 legacy throttles a registration immediately following /login.
      fallback-register-delay-ms: 1500
      # AuthMe can emit a session-success message in the same tick as PLAY.
      # Use the production default settle period before the first cross-server move.
      after-auth-delay-ms: 1500
      timeout-ms: 30000
      login-prompts: ["(?i)(please login|/login)"]
      register-prompts: ["(?i)(please register|/register)"]
      success-messages: ["(?i)(account registered successfully|successfully registered|logged in successfully|login successful|successful login|successfully logged|logged-in due to session reconnection)"]
      failure-messages: ["(?i)(incorrect password|captcha|2fa|verification code|banned)"]
      authmeui:
        accept-rules: true
        registration-email: ""
        # AuthMeUI presents its post-join Dialog about one second after PLAY.
        # Keep real /login and /register commands configured so this fixture
        # proves the modern UI grace period prevents an early chat fallback.
        ui-detection-grace-ms: 3000
  RulesDeclined:
    enabled: false
    username: "B4VDecline${PROTOCOL_ID}"
    password: "Bots4VeloTest"
    auth:
      # This proves rules are never silently accepted when the operator opts out.
      mode: "AUTO"
      login-command: "b4vnoop"
      register-command: "b4vnoop"
      login-delay-ms: 5000
      fallback-register-delay-ms: 5000
      after-auth-delay-ms: 0
      timeout-ms: 30000
      login-prompts: ["(?i)(please login|/login)"]
      register-prompts: ["(?i)(please register|/register)"]
      success-messages: []
      failure-messages: []
      authmeui:
        accept-rules: false
        registration-email: ""
EOF
  (
    cd "$velocity_directory"
    exec "$VELOCITY_JAVA" -Xms256M -Xmx768M -jar velocity.jar
  ) > "$velocity_directory/console.log" 2>&1 &
  PIDS+=("$!")
  VELOCITY_PID="$!"
  wait_for_port "$PROXY_PORT" 60
  wait_for_log "$velocity_directory/console.log" 'bots4velo initialized' 60
}

wait_for_auth_stack() {
  local authmeui_mode="${1:-}"
  local lobby_log="$WORK_ROOT/lobby/console.log"
  wait_for_log_or_failure "$lobby_log" \
    'AuthMe .* successfully enabled!' \
    "Could not load plugin 'AuthMe\\.jar'|Error occurred while enabling AuthMe" \
    "$PAPER_START_TIMEOUT" 'AuthMe'
  if [[ -n "$authmeui_mode" ]]; then
    wait_for_log_or_failure "$lobby_log" \
      '\[AuthMeUI\] Plugin enabled successfully!' \
      "Could not load plugin 'AuthMeUI\\.jar'|Failed to connect to AuthMe|Error occurred while enabling AuthMeUI" \
      "$PAPER_START_TIMEOUT" 'AuthMeUI'
    wait_for_log "$lobby_log" "$authmeui_mode" "$PAPER_START_TIMEOUT"
  fi
}

restart_velocity() {
  stop_tracked_process "$VELOCITY_PID"
  start_velocity
}

restart_lobby_with_authmeui_post_join() {
  stop_tracked_process "$LOBBY_PID"
  # The successful post-join login creates the session exercised by the next
  # pre-join restart.
  write_authme_config true
  write_authmeui_config false false
  start_paper lobby "$LOBBY_PORT"
  wait_for_auth_stack 'Mode: In-Game \(post-join authentication\)'
}

restart_lobby_with_authmeui_pre_join_session() {
  stop_tracked_process "$LOBBY_PID"
  write_authme_config true
  write_authmeui_config true true
  start_paper lobby "$LOBBY_PORT"
  wait_for_auth_stack 'Mode: Configuration Phase \(pre-join authentication\)'
}

mkdir -p "$WORK_ROOT/lobby/plugins" "$WORK_ROOT/afk"
: > "$WORK_ROOT/artifact-manifest.log"
# A previous local run leaves the registered CI account in AuthMe's SQLite
# database. Reset only that disposable fixture database so every invocation
# proves RULES -> REGISTER -> PLAY rather than silently taking the LOGIN path.
reset_authme_fixture_state
shopt -s nullglob
mapfile -t velocity_plugin_candidates < <(compgen -G "$VELOCITY_PLUGIN_JAR" || true)
(( ${#velocity_plugin_candidates[@]} == 1 )) || \
  die "Expected exactly one Velocity plugin JAR from $VELOCITY_PLUGIN_JAR"
VELOCITY_PLUGIN_JAR="${velocity_plugin_candidates[0]}"
mapfile -t paper_companion_candidates < <(compgen -G "$PAPER_COMPANION_JAR" || true)
(( ${#paper_companion_candidates[@]} == 1 )) || \
  die "Expected exactly one Paper companion JAR from $PAPER_COMPANION_JAR"
PAPER_COMPANION_JAR="${paper_companion_candidates[0]}"
artifact_identity "Bots4Velo Velocity plugin" "$VELOCITY_PLUGIN_JAR"
artifact_identity "Bots4Velo Paper companion" "$PAPER_COMPANION_JAR"

log "Downloading Paper $MINECRAFT_VERSION and Velocity"
if [[ -n "${PAPER_JAR:-}" ]]; then
  [[ -f "$PAPER_JAR" ]] || die "PAPER_JAR does not exist: $PAPER_JAR"
  cp "$PAPER_JAR" "$WORK_ROOT/paper.jar"
  artifact_identity "paper $MINECRAFT_VERSION" "$WORK_ROOT/paper.jar"
else
  download_fill_artifact paper "$MINECRAFT_VERSION" "$WORK_ROOT/paper.jar"
fi
if [[ -n "${VELOCITY_JAR:-}" ]]; then
  [[ -f "$VELOCITY_JAR" ]] || die "VELOCITY_JAR does not exist: $VELOCITY_JAR"
  cp "$VELOCITY_JAR" "$WORK_ROOT/velocity.jar"
  artifact_identity "velocity 3.5.0-SNAPSHOT" "$WORK_ROOT/velocity.jar"
else
  download_fill_artifact velocity "3.5.0-SNAPSHOT" "$WORK_ROOT/velocity.jar"
fi

case "$MINECRAFT_VERSION" in
  # AuthMe 6.x legacy builds require Java 17, while Paper 1.16.5 supports Java 16 at most.
  # AuthMe 5.6.0 provides the matching legacy build and supports Java 8 through 21.
  1.16.5) download_authme '5.6.0' '-legacy.jar' ;;
  *)      download_authme '6.0.0' '-Paper.jar' ;;
esac

if [[ "$MINECRAFT_VERSION" != "1.16.5" ]]; then
  disable_authme_dialogs
  install_authmeui
fi

log "Starting authentication lobby and two Paper backends"
start_paper lobby "$LOBBY_PORT"
if [[ "$MINECRAFT_VERSION" == "1.16.5" ]]; then
  wait_for_auth_stack
else
  wait_for_auth_stack 'Mode: Configuration Phase \(pre-join authentication\)'
fi
start_paper afk "$AFK_PORT"

log "Starting Velocity and Bots4Velo"
start_velocity
VELOCITY_LOG="$WORK_ROOT/velocity/console.log"
wait_for_log "$VELOCITY_LOG" 'entered PLAY' 90
if [[ "$MINECRAFT_VERSION" == "1.16.5" ]]; then
  wait_for_log "$VELOCITY_LOG" 'Bot IntegrationBot submitting its registration command' 90
else
  wait_for_log "$VELOCITY_LOG" 'AUTHME_UI RULES' 90
  wait_for_log "$VELOCITY_LOG" 'AUTHME_UI REGISTER' 90
  if grep -Eq 'Bot IntegrationBot submitting its (login|registration) command' "$VELOCITY_LOG"; then
    die "Modern AuthMeUI pre-join fixture unexpectedly used chat-command authentication"
  fi
fi
wait_for_log "$VELOCITY_LOG" "B4VCI_${PROTOCOL_ID} -> afk has connected" 90
wait_for_log "$VELOCITY_LOG" 'resource pack: SUCCESSFULLY_LOADED' 90
wait_for_log "$WORK_ROOT/afk/console.log" "B4VCI_${PROTOCOL_ID} joined the game" 90
wait_for_log "$VELOCITY_LOG" \
  'Paper backend control APPLY_POLICY_EXT for bot IntegrationBot on afk: OK' 90
if [[ "$MINECRAFT_VERSION" != "1.16.5" ]]; then
  wait_for_log "$VELOCITY_LOG" \
    'Bot RulesDeclined stopped at AUTHME_UI RULES.*rules acceptance is disabled by auth.authmeui.accept-rules' 90
  sleep 7
  rules_declined_connections="$(grep -Ec 'Bot RulesDeclined .* connecting to' "$VELOCITY_LOG" || true)"
  [[ "$rules_declined_connections" == "1" ]] || \
    die "Automatic schedules restarted the failed AuthMeUI rules fixture $rules_declined_connections times"
fi

log "Fault test: interrupt AFK backend, then prove the presence rule restores the bot"
velocity_log_lines=$(wc -l < "$VELOCITY_LOG")
policy_replay_log_lines="$velocity_log_lines"
stop_tracked_process "$AFK_PID"
sleep 2
start_paper afk "$AFK_PORT"
wait_for_log "$WORK_ROOT/afk/console.log" 'Done \(' "$PAPER_START_TIMEOUT"
wait_for_log "$WORK_ROOT/afk/console.log" "B4VCI_${PROTOCOL_ID} joined the game" 90
wait_for_new_log "$VELOCITY_LOG" "B4VCI_${PROTOCOL_ID} -> afk has connected" "$velocity_log_lines" 90
wait_for_new_log "$VELOCITY_LOG" \
  'Paper backend control APPLY_POLICY_EXT for bot IntegrationBot on afk: OK' "$policy_replay_log_lines" 90

if [[ "$MINECRAFT_VERSION" != "1.16.5" ]]; then
  log "Authentication mode test: restart lobby with AuthMeUI post-join enabled"
  restart_lobby_with_authmeui_post_join
  log "Fault test: restart Velocity; enabled bot must return through post-join LOGIN to PLAY"
else
  log "Fault test: restart Velocity; enabled bot must return to PLAY"
fi

restart_velocity
wait_for_log "$VELOCITY_LOG" 'entered PLAY' 90
if [[ "$MINECRAFT_VERSION" != "1.16.5" ]]; then
  post_join_play_line="$(last_matching_log_line "$VELOCITY_LOG" 'Bot IntegrationBot entered PLAY')"
  wait_for_new_log "$VELOCITY_LOG" \
    'Bot IntegrationBot submitted AUTHME_UI LOGIN' "$post_join_play_line" 90
  post_join_submit_line="$(last_matching_log_line \
    "$VELOCITY_LOG" 'Bot IntegrationBot submitted AUTHME_UI LOGIN')"
  wait_for_new_log "$VELOCITY_LOG" \
    'Bot IntegrationBot matched an authentication success message' "$post_join_submit_line" 90
  post_join_success_line="$(last_matching_log_line \
    "$VELOCITY_LOG" 'Bot IntegrationBot matched an authentication success message')"
  wait_for_new_log "$VELOCITY_LOG" \
    "B4VCI_${PROTOCOL_ID} -> afk has connected" "$post_join_success_line" 90
  if grep -Eq 'Bot IntegrationBot submitting its (login|registration) command' "$VELOCITY_LOG"; then
    die "Modern AuthMeUI post-join fixture unexpectedly used chat-command authentication"
  fi
fi
wait_for_log "$VELOCITY_LOG" 'resource pack: SUCCESSFULLY_LOADED' 90
if [[ "$MINECRAFT_VERSION" == "1.16.5" ]]; then
  wait_for_log "$VELOCITY_LOG" "B4VCI_${PROTOCOL_ID} -> afk has connected" 90
fi
wait_for_log "$VELOCITY_LOG" \
  'Paper backend control APPLY_POLICY_EXT for bot IntegrationBot on afk: OK' 90

if [[ "$MINECRAFT_VERSION" != "1.16.5" ]]; then
  log "Authentication mode test: honor an AuthMe session during AuthMeUI pre-join"
  restart_lobby_with_authmeui_pre_join_session
  session_afk_log_lines="$(wc -l < "$WORK_ROOT/afk/console.log")"
  restart_velocity
  wait_for_log "$VELOCITY_LOG" 'Bot IntegrationBot entered PLAY' 90
  session_play_line="$(last_matching_log_line "$VELOCITY_LOG" 'Bot IntegrationBot entered PLAY')"
  # AuthMeUI deliberately defers a resumable AuthMe session to the join path.
  # The client therefore reaches PLAY first, then receives AuthMe's exact
  # session-success message; only that evidence may release server switching.
  wait_for_new_log "$VELOCITY_LOG" \
    'Bot IntegrationBot matched an authentication success message' "$session_play_line" 90
  session_success_line="$(last_matching_log_line \
    "$VELOCITY_LOG" 'Bot IntegrationBot matched an authentication success message')"
  wait_for_log "$VELOCITY_LOG" \
    'resource pack: SUCCESSFULLY_LOADED' 90
  wait_for_new_log "$VELOCITY_LOG" \
    "B4VCI_${PROTOCOL_ID} -> afk has connected" "$session_success_line" 90
  wait_for_new_log "$WORK_ROOT/afk/console.log" \
    "B4VCI_${PROTOCOL_ID} joined the game" "$session_afk_log_lines" 90
  if grep -Eq 'Bot IntegrationBot (submitted AUTHME_UI|submitting its (login|registration) command)' \
      "$VELOCITY_LOG"; then
    die "AuthMeUI session-respect fixture unexpectedly submitted credentials"
  fi
  wait_for_new_log "$VELOCITY_LOG" \
    'Paper backend control APPLY_POLICY_EXT for bot IntegrationBot on afk: OK' "$session_success_line" 90
fi

log "Integration passed: Minecraft $MINECRAFT_VERSION / protocol $PROTOCOL_ID"
