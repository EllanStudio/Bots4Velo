# Bots4Velo

[中文文档 / Chinese documentation](README.zh-CN.md)

Bots4Velo is a multi-protocol, headless Minecraft client that runs inside Velocity. It keeps bots online, handles authentication, switches backend servers, runs AFK behaviors, and can optionally use a Paper companion to manage invulnerability, game mode, and respawn points.

The default configuration is safe: **a fresh installation starts with zero bots**. Administrators or authorized players create bots with `/vbot create`; definitions are stored in `managed-bots.yml`, while passwords stay in a separate secrets file or environment variable.

## Screenshots

These screenshots show real AFK use cases: a bot staying online inside a protected enclosure and inside a glass shelter.

<p align="center">
  <img src="docs/images/afk-enclosure.png" alt="Bot staying online in a nether-brick AFK enclosure" width="48%" />
  <img src="docs/images/afk-glass-shelter.png" alt="Bot staying online in a glass AFK shelter" width="48%" />
</p>

The screenshots are illustrative only. Server-authoritative invulnerability, game mode, and respawn points require the optional Paper companion.

## Supported protocols

| Configuration value | Protocol | Adapter |
| --- | ---: | --- |
| `1.16.5` | 754 | Legacy Login/Play |
| `1.21.11` | 774 | Modern Login/Configuration/Play |
| `26.1.2` | 775 | Modern Login/Configuration/Play |
| `26.2` | 776 | Modern Login/Configuration/Play |
| `26.3` | 777 | Modern Login/Configuration/Play |

`protocol-version: "AUTO"` pings the bot's target backend before selecting an adapter. Backend names must exactly match Velocity's `[servers]` entries, including case.

## Installation

### Bot connections only

1. Put the latest `bots4velo.jar` release in Velocity's `plugins` directory.
2. Start Velocity once. It creates:

   - `plugins/bots4velo/config.yml`
   - `plugins/bots4velo/secrets.yml.example`
   - `plugins/bots4velo/managed-bots.yml` after the first bot is created

3. Make sure the target backends are registered in Velocity's `[servers]` section.
4. Restart or reload Bots4Velo before creating bots.

### Server-side player controls

To use invulnerability, game mode, AFK properties, or respawn points:

1. Install the matching `bots4velo-paper.jar` release in the `plugins` directory of every target Paper backend.
2. Use the same shared secret on Velocity and every Paper backend. The decoded secret must be at least 32 bytes.
3. Prefer an environment variable:

```yaml
# Velocity: plugins/bots4velo/config.yml
runtime:
  backend-control:
    enabled: true
    secret: ""
    secret-env: "BOTS4VELO_BACKEND_SECRET"
```

```yaml
# Paper: plugins/Bots4VeloPaper/config.yml
shared-secret-env: "BOTS4VELO_BACKEND_SECRET"
shared-secret: ""
```

The environment variable takes precedence over the YAML fallback. Restart Velocity and all Paper backends after changing the secret.

## Default configuration and first startup

The bundled `config.yml` intentionally contains:

```yaml
templates: {}
bots: {}
```

There are no static bots, and installing the plugin does not log into any account. Never put real passwords directly in `config.yml` or `managed-bots.yml`.

This default only applies to newly generated configuration. Upgrading an existing instance does not delete its configured bots.

### 1. Prepare a password

Copy the example to:

```text
plugins/bots4velo/secrets.yml
```

Example:

```yaml
passwords:
  farm01: "replace-with-a-strong-password"
```

`secrets.yml` is ignored by Git and must not be committed. Production deployments can use a Velocity process environment variable instead.

### 2. Create a bot

Run this in the Velocity console or in chat as a player with permission:

```text
/vbot create Farm01 AFK_Farm01 secret:farm01 survival
```

Arguments:

- `Farm01`: bot ID; letters, digits, `_`, and `-` are allowed;
- `AFK_Farm01`: offline-mode player name, 3–16 characters;
- `secret:farm01`: reads `passwords.farm01` from `secrets.yml`;
- `survival`: the registered Velocity backend name.

The bot is written to `managed-bots.yml` and queued for startup. The global `spawn-interval-ms` limits bursts of connections.

For a bot that does not need authentication:

```text
/vbot create Observer AFK_Observer - lobby
```

The `-` credential selects `NONE` authentication. Do not use it on an AuthMe/AuthMeUI server, or the bot will intentionally ignore the authentication UI.

### 3. Use an environment variable

```text
/vbot create Farm02 AFK_Farm02 env:BOTS4VELO_FARM02_PASSWORD survival
```

`BOTS4VELO_FARM02_PASSWORD` must be present in the Velocity process environment. Windows services and launch scripts should set it before starting Velocity.

### 4. Remove a bot

```text
/vbot remove Farm01
```

`remove` deletes only bots created with `/vbot create`. Static bots must be removed from `config.yml`.

## Permissions

Bots4Velo does not grant every player control by default. With LuckPerms, grant only what each role needs:

```text
/lp user <player> permission set bots4velo.view true
/lp user <player> permission set bots4velo.control true
/lp user <player> permission set bots4velo.create true
/lp user <player> permission set bots4velo.reload true
```

| Permission | Purpose |
| --- | --- |
| `bots4velo.view` | Lists, status, queue, diagnostics, and history |
| `bots4velo.control` | Start, stop, reconnect, switch, move, and behavior control |
| `bots4velo.create` | Create and remove managed bots |
| `bots4velo.reload` | Validate and reload configuration |
| `bots4velo.admin` | All permissions |

To let players create bots only, grant `bots4velo.create`. Add `view` or `control` when they also need monitoring or operation access.

## Common commands

```text
/vbot help [page]
/vbot list [id|selector] [--page <page>]
/vbot queue [id|selector] [--page <page>]
/vbot status <id>
/vbot monitor [id]
/vbot history <id>
/vbot doctor [id|selector]
/vbot servers

/vbot create <id> <username> <secret:name|env:NAME|-> [target-server|-]
/vbot remove <id>
/vbot start <id|selector>
/vbot stop <id|selector>
/vbot reconnect <id|selector>
/vbot server <id|selector> <server>
/vbot movehere <id|selector>
/vbot command <id|selector> <command...>

/vbot behavior <id|selector> <start|pause|status>
/vbot afk <id|selector> status
/vbot afk <id|selector> preset <safe|farm|normal>
/vbot invulnerable <id|selector> <on|off|keep>
/vbot gamemode <id|selector> <survival|creative|adventure|spectator|unchanged>
/vbot spawnpoint <id|selector> <current|worldspawn|clear>
/vbot spawnpoint <id|selector> set <world> <x> <y> <z> [yaw]
/vbot respawn <id|selector>

/vbot language [en_US|zh_CN]
/vbot reload --check
/vbot reload
```

### Batch selectors

Use `all`, `@group:<name>`, `@tag:<name>`, `@server:<name>`, or the short form `@farm`:

```text
/vbot start all
/vbot stop @farm
/vbot server @lobby survival
/vbot reconnect @server:lobby
/vbot list all --state PLAY --page 1
```

`/vbot movehere` must be run by an in-game player. Bots are switched to the player's backend first, then moved with a server-side teleport; the player still needs teleport permission on that backend.

## AuthMe and AuthMeUI

Bots4Velo supports:

- AuthMe/AuthMeReloaded `/login` and `/register` chat flows;
- AuthMe 6 native Dialogs;
- [AuthMeUI 1.3.4](https://modrinth.com/plugin/authmeui/version/1.3.4) rules, registration, and login dialogs;
- pre-join and post-join authentication on modern protocol versions.

When an authentication plugin is installed, create the bot with a password reference:

```text
/vbot create AuthBot AFK_AuthBot secret:authbot survival
```

A secret or environment reference selects `auth.mode: AUTO`. AuthMeUI custom titles and Chinese labels are supported because the plugin reads Dialog actions and field keys such as `password` and `confirm`.

If status shows:

```text
auth=disabled
AUTHME_UI ... ignored mode=NONE
```

the bot is in no-auth mode. Remove the bot created with `-`, then recreate it with `secret:name` or `env:NAME`.

Do not enable AuthMe native Dialogs and AuthMeUI Dialogs at the same time. Version 1.16.5 does not use AuthMeUI and continues to use chat authentication.

## Backend names and protocol detection

Example Velocity configuration:

```toml
[servers]
lobby = "127.0.0.1:25591"
survival = "127.0.0.1:25592"
try = ["lobby"]
```

Use the registered name when creating the bot:

```text
/vbot create Farm01 AFK_Farm01 secret:farm01 survival
```

Both `target-server` and `protocol-detection-server` must match exactly. If the log contains:

```text
Unknown protocol detection backend: afk
```

the current Velocity instance has no backend named `afk`. Change the bot to the real name, or add `afk` to Velocity's `[servers]` and restart Velocity. `/vbot reload` does not re-register Velocity backends.

To force a protocol in a static configuration:

```yaml
proxy:
  protocol-version: "26.2"
```

A fixed protocol does not repair a missing target backend.

## Bot behavior and player state

Basic AFK behaviors start after authentication and recover after server switches, reconnects, and new PLAY sessions:

```text
/vbot behavior Farm01 start
/vbot behavior Farm01 status
/vbot behavior Farm01 pause
```

With the Paper companion enabled, server-side properties can be managed:

```text
/vbot afk Farm01 preset safe
/vbot afk Farm01 preset farm
/vbot invulnerable Farm01 on
/vbot gamemode Farm01 creative
/vbot spawnpoint Farm01 worldspawn
/vbot respawn Farm01
```

These operations are executed by Paper, not faked by client packets. Missing companions, mismatched secrets, and unreachable backends return explicit failures.

## Validation and language

Validate changes first:

```text
/vbot reload --check
```

Apply them only after validation succeeds:

```text
/vbot reload
```

A failed validation never replaces the bots that are already running.

Messages default to English. Switch to Chinese with:

```text
/vbot language zh_CN
```

Switch back with:

```text
/vbot language en_US
```

Language files live under `plugins/bots4velo/languages/`. Custom files are preserved across upgrades and missing keys are filled from the bundled messages.

## Diagnostics and troubleshooting

```text
/vbot doctor <id>
/vbot status <id>
/vbot history <id>
```

Common states:

- `CONFIGURATION`: the Configuration phase is still waiting, usually for an AuthMeUI/AuthMe Dialog or resource-pack gate;
- `auth-ui Presented=1, Submitted=0`: a UI was seen but not submitted; first check that `auth.mode` is not `NONE`, then check the credential reference;
- `target=MISSING`: the target name is not registered in Velocity's `[servers]`;
- `protocol=auto/pending`: automatic detection is not complete; check backend reachability;
- `FAILED`: authentication failure, CAPTCHA, 2FA, ban, or retry budget exhaustion; fix the account and run `start` or `reconnect` manually.

## Addons

Independent addons live in one directory per addon:

```text
plugins/bots4velo/addons/<addon-name>/
  addon.jar
  config.yml
```

The JAR must provide `dev.nulli0n.vbot.addon.api.Bots4VeloAddon` through
`META-INF/services`. Each bundle receives an isolated class loader, its own data directory,
a scoped logger, and the stable `AddonBotService` API for lifecycle, server switching,
online-player checks, messages, and bot events. An addon failure is isolated and does not
prevent Bots4Velo from starting.

Addon JARs are loaded during proxy startup and unloaded during proxy shutdown. Replacing an
addon JAR requires a Velocity restart; `/vbot reload` intentionally reloads bot
configuration without replacing addon class loaders.

## Common bot API

The recent addon merge also keeps a separate convenience API for common integrations.
`dev.nulli0n.vbot.api.common.CommonBotApi` supports selector-based batch controls such as
`all`, `@group:farm`, `@tag:lobby`, and `@server:survival`, plus commands, movement,
behavior controls, following, server switching, and normalized events. Obtain it after
proxy initialization with `CommonBotApiProvider.get()`.

The original `Bots4VeloApi` and addon SPI are unchanged. See [`docs/api.md`](docs/api.md)
and [`docs/project-structure.md`](docs/project-structure.md) for the API example and the
non-breaking package layout.

## Build

Windows:

```powershell
.\gradlew.bat clean test shadowJar
```

Linux:

```bash
chmod +x gradlew
./gradlew clean test shadowJar
```

Artifacts:

- `build/libs/bots4velo-<version>.jar`: Velocity plugin;
- `build/libs/bots4velo-paper-<version>.jar`: Paper companion.

## CI and GitHub Releases

Normal commits and pull requests use a separate CI workflow for tests and builds. Only major tags matching `vX.0.0` create a release, for example:

```bash
git tag vX.0.0
git push origin vX.0.0
```

The workflow uses Java 21, runs tests and builds, uploads an artifact, and attaches the JARs and checksums to the GitHub Release. Minor and patch tags do not trigger the major-release workflow.

## Security recommendations

- Never commit `secrets.yml`, environment variable values, or real passwords;
- use a separate account and password for each bot;
- use `-` only when the target does not require authentication;
- grant `bots4velo.create`, `control`, and `reload` only to trusted users;
- prefer environment variables or restricted file permissions in production;
- rotate any password that appeared in logs, screenshots, or chat.

## Version scope

The current maintenance line is `3.0.x`. Every release uses a new semantic version;
bug-fix releases increment the patch number (for example `3.0.1` -> `3.0.2`).
The companion project follows the same rule independently (for example `1.0.0` -> `1.0.1`).
Future work on this line is focused on reliability, compatibility, and bug fixes.
