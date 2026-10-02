# Bots4Velo

[English documentation](README.md)

Bots4Velo 是一个运行在 Velocity 内的多协议无界面 Minecraft 客户端。它可以让机器人保持在线、自动认证、切换后端、执行挂机行为，并可选地通过 Paper 伴侣插件修改无敌、游戏模式和重生点。

默认配置是安全的：**首次安装不会启动任何机器人**。管理员或获授权的玩家通过 `/vbot create` 创建机器人；创建记录保存在 `managed-bots.yml`，密码只保存在独立密钥文件或环境变量中。

## 效果展示

以下截图来自实际挂机场景，展示机器人在保护结构中保持在线，以及玻璃防护挂机点的使用方式：

<p align="center">
  <img src="docs/images/afk-enclosure.png" alt="机器人在下界砖挂机结构中保持在线" width="48%" />
  <img src="docs/images/afk-glass-shelter.png" alt="机器人在玻璃防护挂机点保持在线" width="48%" />
</p>

截图只用于说明使用场景；无敌、游戏模式和重生点等服务端属性需要安装可选的 Paper 伴侣插件。

## 支持的协议

| 配置值 | 协议号 | 适配器 |
| --- | ---: | --- |
| `1.16.5` | 754 | Legacy Login/Play |
| `1.21.11` | 774 | Modern Login/Configuration/Play |
| `26.1.2` | 775 | Modern Login/Configuration/Play |
| `26.2` | 776 | Modern Login/Configuration/Play |
| `26.3` | 777 | Modern Login/Configuration/Play |

`protocol-version: "AUTO"` 会优先 ping 机器人目标后端，再选择协议。目标后端名称必须与 Velocity 的 `[servers]` 配置完全一致并区分大小写。

## 安装

### 只使用机器人连接功能

1. 将最新的 `bots4velo.jar` 发布包放入 Velocity 的 `plugins` 目录。
2. 启动一次 Velocity，让它生成：

   - `plugins/bots4velo/config.yml`
   - `plugins/bots4velo/secrets.yml.example`
   - `plugins/bots4velo/managed-bots.yml`（创建第一个机器人后出现）

3. 确认 Velocity 的 `[servers]` 已配置目标后端。
4. 重启或重新加载 Bots4Velo 后再创建机器人。

### 使用 Paper 后端控制

如果需要无敌、游戏模式、挂机属性或重生点：

1. 将 `bots4velo-paper.jar` 安装到每一个目标 Paper 后端的 `plugins` 目录。
2. Velocity 与所有 Paper 后端使用同一个至少 32 字节的共享密钥。
3. 推荐通过环境变量设置密钥：

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

环境变量优先于 YAML 中的回退值。修改密钥后重启 Velocity 和所有 Paper 后端。

## 默认配置与第一次启动

默认 `config.yml` 中明确写有：

```yaml
templates: {}
bots: {}
```

这表示没有静态机器人，也不会因为安装插件而自动登录任何账号。不要把真实密码直接写入 `config.yml` 或 `managed-bots.yml`。

这项默认值只影响首次生成的配置；升级已有实例时，Bots4Velo 不会自动删除你已经配置的机器人。

### 1. 准备密码

将示例复制为：

```text
plugins/bots4velo/secrets.yml
```

内容示例：

```yaml
passwords:
  farm01: "replace-with-a-strong-password"
```

`secrets.yml` 已被 Git 忽略，不能提交到仓库。生产环境也可以使用 Velocity 进程环境变量代替密钥文件。

### 2. 创建机器人

在 Velocity 控制台或拥有权限的玩家聊天框执行：

```text
/vbot create Farm01 AFK_Farm01 secret:farm01 survival
```

参数含义：

- `Farm01`：机器人 ID，只能使用字母、数字、`_`、`-`；
- `AFK_Farm01`：离线模式玩家名，长度 3–16；
- `secret:farm01`：读取 `secrets.yml` 中的 `passwords.farm01`；
- `survival`：Velocity 注册的后端名称。

创建后机器人会写入 `managed-bots.yml` 并加入启动队列。全局 `spawn-interval-ms` 会限制批量登录速度。

不需要认证的机器人可以使用：

```text
/vbot create Observer AFK_Observer - lobby
```

第三个参数为 `-` 时认证模式是 `NONE`。如果服务器安装了 AuthMe/AuthMeUI，不要使用 `-`，否则机器人会忽略认证界面。

### 3. 使用环境变量

```text
/vbot create Farm02 AFK_Farm02 env:BOTS4VELO_FARM02_PASSWORD survival
```

`BOTS4VELO_FARM02_PASSWORD` 必须存在于 Velocity 进程的环境中。Windows 服务或启动脚本应在启动 Velocity 之前设置它。

### 4. 删除机器人

```text
/vbot remove Farm01
```

`remove` 只删除通过 `/vbot create` 创建的运行时机器人；静态配置机器人必须从 `config.yml` 中删除。

## 权限

Bots4Velo 不默认允许所有玩家控制机器人。使用 LuckPerms 时，可按需要授予：

```text
/lp user <玩家> permission set bots4velo.view true
/lp user <玩家> permission set bots4velo.control true
/lp user <玩家> permission set bots4velo.create true
/lp user <玩家> permission set bots4velo.reload true
```

权限说明：

| 权限 | 用途 |
| --- | --- |
| `bots4velo.view` | 查看列表、状态、队列、诊断和历史 |
| `bots4velo.control` | 启动、停止、重连、切服、移动和行为控制 |
| `bots4velo.create` | 创建和删除运行时机器人 |
| `bots4velo.reload` | 执行配置检查和重载 |
| `bots4velo.admin` | 全部权限 |

如果希望玩家只能创建机器人，授予 `bots4velo.create`；若还要让他们查看或控制自己的机器人，再分别授予 `view` 和 `control`。

## 常用命令

```text
/vbot help [页码]
/vbot list [id|selector] [--page <页码>]
/vbot queue [id|selector] [--page <页码>]
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

### 批量选择器

机器人可以使用 `all`、`@group:<name>`、`@tag:<name>`、`@server:<name>` 或简写 `@farm`：

```text
/vbot start all
/vbot stop @farm
/vbot server @lobby survival
/vbot reconnect @server:lobby
/vbot list all --state PLAY --page 1
```

`/vbot movehere` 必须由游戏内玩家执行。插件会先把机器人切到执行者所在后端，再执行服务端传送；执行者仍需要目标后端的传送权限。

## AuthMe 与 AuthMeUI

Bots4Velo 支持：

- AuthMe/AuthMeReloaded 的 `/login`、`/register` 聊天流程；
- AuthMe 6 原生 Dialog；
- [AuthMeUI 1.3.4](https://modrinth.com/plugin/authmeui/version/1.3.4) 的规则、注册和登录界面；
- 现代版本的 pre-join 和 post-join 认证流程。

使用认证插件时，创建机器人必须引用密码：

```text
/vbot create AuthBot AFK_AuthBot secret:authbot survival
```

有密码引用时，运行时机器人自动使用 `auth.mode: AUTO`。AuthMeUI 的自定义标题、中文标签不会影响识别，插件按 Dialog 的 action 和字段 key 处理 `password`、`confirm` 等字段。

如果状态显示：

```text
auth=disabled
AUTHME_UI ... ignored mode=NONE
```

说明机器人是无认证模式；请删除 `-` 创建的机器人后，使用 `secret:name` 或 `env:NAME` 重新创建。

建议不要同时开启 AuthMe 原生 Dialog 与 AuthMeUI Dialog。1.16.5 不使用 AuthMeUI，继续使用聊天命令认证。

## 后端名称与协议探测

Velocity 的配置示例：

```toml
[servers]
lobby = "127.0.0.1:25591"
survival = "127.0.0.1:25592"
try = ["lobby"]
```

创建时必须使用注册名：

```text
/vbot create Farm01 AFK_Farm01 secret:farm01 survival
```

`target-server` 和 `protocol-detection-server` 都要求精确匹配。如果日志出现：

```text
Unknown protocol detection backend: afk
```

说明当前 Velocity 没有名为 `afk` 的后端；请改成实际名称，或在 Velocity 的 `[servers]` 中添加它，然后重启 Velocity。只执行 `/vbot reload` 不会重新注册 Velocity 后端。

需要固定协议时，可在静态配置中使用：

```yaml
proxy:
  protocol-version: "26.2"
```

固定协议不会修复不存在的目标后端。

## 机器人行为与玩家状态

基础挂机行为会在认证完成后运行，并在切服、重连和重新进入 PLAY 后恢复：

```text
/vbot behavior Farm01 start
/vbot behavior Farm01 status
/vbot behavior Farm01 pause
```

Paper 伴侣启用后，可以控制真实服务端属性：

```text
/vbot afk Farm01 preset safe
/vbot afk Farm01 preset farm
/vbot invulnerable Farm01 on
/vbot gamemode Farm01 creative
/vbot spawnpoint Farm01 worldspawn
/vbot respawn Farm01
```

这些命令由 Paper 服务端执行，不是客户端伪造。伴侣未安装、共享密钥不一致或后端不可达时，命令会返回明确失败。

## 配置检查与语言

修改配置后先检查：

```text
/vbot reload --check
```

确认没有错误后再执行：

```text
/vbot reload
```

检查失败时不会替换正在运行的机器人。

插件默认消息为英文。切换中文：

```text
/vbot language zh_CN
```

切回英文：

```text
/vbot language en_US
```

语言文件位于 `plugins/bots4velo/languages/`；自定义文件会在升级时保留，缺失的键会从内置文件补齐。

## 诊断与常见问题

```text
/vbot doctor <id>
/vbot status <id>
/vbot history <id>
```

常见状态：

- `CONFIGURATION`：仍在处理 Configuration 阶段，通常是 AuthMeUI/AuthMe Dialog 或资源包门禁未完成；
- `auth-ui Presented=1, Submitted=0`：看到了 UI，但没有提交，先确认不是 `auth.mode: NONE`，再检查密码引用；
- `target=MISSING`：目标名称不在 Velocity 的 `[servers]` 中；
- `protocol=auto/pending`：自动探测尚未完成，先检查目标后端可达性；
- `FAILED`：认证失败、验证码、2FA、封禁或超出重试上限，需要修复账号后手动 `start` 或 `reconnect`。

## 常用机器人 API

项目同时提供独立的 `dev.nulli0n.vbot.api.common.CommonBotApi`，用于常见的批量操作。
它支持 `all`、`@group:farm`、`@tag:lobby`、`@server:survival` 等选择器，以及启动、停止、
重连、执行命令、移动、转头、跳跃、挥手、潜行、行为控制、跟随和跨服，并提供统一事件。
Velocity 初始化完成后可通过 `CommonBotApiProvider.get()` 获取。

原有 `Bots4VeloApi` 与 addon SPI 没有修改，旧插件不需要重新编译。详见
[`docs/api.md`](docs/api.md) 和 [`docs/project-structure.md`](docs/project-structure.md)。

## 构建

Windows：

```powershell
.\gradlew.bat clean test shadowJar
```

Linux：

```bash
chmod +x gradlew
./gradlew clean test shadowJar
```

产物：

- `build/libs/bots4velo-<version>.jar`：Velocity 插件；
- `build/libs/bots4velo-paper-<version>.jar`：Paper 伴侣插件。

## CI 与 GitHub Release

普通 commit/PR 使用独立 CI 执行测试与构建。只有大版本标签 `vX.0.0` 会创建 Release，例如：

```bash
git tag vX.0.0
git push origin vX.0.0
```

工作流会使用 Java 21，执行测试、构建、上传 artifact，并通过 GitHub Release 附件发布 JAR 和校验文件。次版本和修订版本标签不会触发大版本发布。

## 安全建议

- 不要把 `secrets.yml`、环境变量值或真实密码提交到 Git；
- 每个机器人使用独立账号和独立密码；
- 不需要认证时才使用 `-`；
- 将 `bots4velo.create`、`control` 和 `reload` 分配给可信用户；
- 生产环境优先使用环境变量或受限文件权限；
- 账号密码曾出现在日志、截图或聊天记录后应立即更换。

## 版本范围

当前维护版本线为 `3.0.x`。每次发布都必须使用新的语义化版本号；
错误修复版本只递增修订号，例如 `3.0.1` -> `3.0.2`。
Paper/Rental 伴侣项目独立遵循同样规则，例如 `1.0.0` -> `1.0.1`。
后续主要进行稳定性、兼容性和错误修复。
