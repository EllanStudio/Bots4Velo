# Issue #3：AuthMe 登录后的切服循环

关联：https://github.com/EllanStudio/Bots4Velo/issues/3

## 原因与修复

截图显示认证成功后，机器人已经连接到 `spawn`，却每隔数秒继续发送
`server spawn`。旧代码只通过客户端 PLAY/CONFIGURATION 状态转换确认切服；
向当前所在的后端发起切服不会产生新的状态转换，因而一直等待。
`server-switch-maximum-attempts: 0` 又允许无限重试。

这说明问题发生在认证成功后的切服确认阶段。AuthMe 更新可能改变了加入/转服时序，
但现有证据不足以认定 AuthMe 自身存在回归，也不能据此声称所有新版 AuthMe 场景已验证。

本次修复：

- Velocity 插件在初始化和配置重载时，将按用户名查询实际后端的函数注入 BotManager/BotSession。
- 每次切服请求前先核对实际后端；已到达目标则结束等待、取消重试，并安排登录后命令。
- 代理端查询可用时，以后端名称确认结果；其他后端的协议状态变化不再被误判为目标切服成功。
- 代理端暂时查不到玩家时继续等待，不回退成仅凭协议变化确认成功。
- 第一次切服请求使用 INFO，后续请求使用 DEBUG，每 20 次仍未完成的请求发出 WARN。
  当前每 3 秒重试的配置下，持续故障约每分钟告警一次（每个机器人分别计数）。
- 保留原有构造器及未接入代理查询的独立客户端行为，不更改认证成功条件或重试上限配置。
- 修复现有 GitHub 工作流测试在 Windows CRLF 检出下的两处失败；仅规范化测试读取的文本。

## 2026-09-06 只读线上核对

仅查询进程、读取插件文件/配置及有限日志，没有执行服务器控制命令，也没有写入远程文件。

| 项目 | 观察结果 |
| --- | --- |
| Velocity 工作目录 | `/mnt/data/minecraft/velocity` |
| 后端目录 | `/mnt/data/minecraft/spawn`、`survival`、`redstone` |
| Velocity 启动日志 | `4.1.1-SNAPSHOT (git-133f0e36-b23)` |
| Bots4Velo | 启动日志及 JAR 元数据均为 `3.1.0` |
| Bots4Velo JAR SHA-256 | `d41402d79d3d570bebd5b24b15c7509002f9de80846d102e180d2595b4be953b` |
| AuthMe 相关插件文件 | `AuthMe-6.0.1-Paper.jar`、`AuthMeUI-1.3.4.jar`、AuthMeVelocity `4.3.0` |
| 受影响机器人 | 8 个 `Jail_*`，目标 `spawn`，重试间隔 3000 ms，上限 0 |
| 日志 | 切服请求仍持续，单个机器人的 attempt 超过 9500 |

**来源差异：**本次仓库基线是 `57d8b73`，默认版本及公开最新发布均为 `3.0.2`。
线上 `3.1.0` 不在当前远程分支/标签清单中。不能将本分支构建的包直接视为线上
3.1.0 的等价升级；上线前需找到线上 3.1.0 的源码或变更清单，将本修复移植并验证，
或明确接受回到当前仓库功能基线。

本文件不包含远程登录凭证、机器人密码或完整生产配置。

## 本地验证与构建

Windows PowerShell，使用 Java 21：

```powershell
./gradlew.bat check shadowJar writeArtifactChecksum '-PpluginVersion=3.0.2-issue3' --console=plain
```

`BotSessionServerSwitchTest` 覆盖：已在目标服、重试之间到达目标、错误后端的
CONFIGURATION/PLAY、认证前转入错误后端、代理暂时查不到玩家、达到重试上限时
刚好到达目标、重试耗尽、不把所在后端当成认证结果、停止后的延迟回调、日志级别与
定期告警，以及旧协议的后端确认。

这些是本地状态机与构建验证。GitHub 网络集成矩阵和生产环境验收分别记录，
不得将本地通过表述为线上刷屏已消失。

## 更新方法与影响（由操作员执行）

当前服务器保持运行，暂不更换生产 JAR。完成上述 3.1.0 来源核对后：

1. 在维护窗口前，将新包及 SHA-256 上传到 **plugins 目录之外** 的暂存位置。
   备份现有 `bots4velo-3.1.0.jar` 和整个 `plugins/bots4velo` 数据目录；
   备份包含凭证，应保持原来的严格访问权限，不上传到公开仓库。
2. 在 MCSManager 选择 **Velocity Proxy**，安排一次正常停止。
   **这会断开经过该代理的所有在线玩家及机器人，不只是机器人。**
3. 确认代理已退出后，将旧 Bots4Velo JAR 移到 plugins 之外的备份目录，
   放入已核验的新包，保证 plugins 中只有一个 Bots4Velo 主插件 JAR。
   保留配置、managed-bots.yml、secrets.yml 和 addons。
4. 启动 Velocity Proxy，核对启动日志中的版本，并检查机器人认证后到达 `spawn`。
   已在目标服时预期出现 `confirmed server switch to spawn after 0 attempt(s)`；
   正常转服则预期确认一次，后续不再反复请求同一后端。
5. 观察至少 5 分钟：8 个机器人保持预期后端、没有同服切换循环、登录后命令按配置执行，
   并检查认证失败或重连日志。真实转服失败仍会保留定期 WARN，不应只靠日志安静判断成功。

本修复只修改 Velocity 侧代码。**无需重启 Linux 主机，也无需为本修复重启
spawn/survival/redstone 或更换 Paper companion。** 本次构建包含 companion 产物，
是现有构建流程的结果，不表示需要部署它。

`/vbot reload` 重载配置和机器人管理器，不会替换 JVM 中已加载的插件类，
因此不能用它加载这个 JAR 修复。

回滚：再次正常停止 Velocity，移出新 JAR，恢复原 JAR；如果期间更改了配置，
恢复匹配的配置备份后再启动。回滚同样会断开所有经过代理的玩家。
本次未执行上述任何更新、停止、启动或回滚步骤。
