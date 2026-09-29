# Minecraft AI Assistant

[下载预发布版](https://github.com/Chen-Zz1/mc-ai-assistant-mod/releases) · [自动构建状态](https://github.com/Chen-Zz1/mc-ai-assistant-mod/actions/workflows/build.yml)

这是一个服务端 Fabric 模组，提供普通聊天、多轮对话、可配置角色，以及可选的网页和 Minecraft Wiki 检索。玩家可使用原版客户端进入服务器。服主自行配置模型 API 并承担费用；项目不提供共享密钥或托管服务。

支持目标为 Minecraft Java 26.2、Java 25、Fabric Loader 0.19.3、Fabric API 0.158.0+26.2，当前游戏提示为中文。其他版本和加载器尚未验证。AI 只生成文字，不能执行服务器命令或修改世界。

## 安装

1. 备份服务器，安装对应版本的 Fabric 服务端与 Fabric API。
2. 将 `mc-ai-assistant-0.2.0-beta.1.jar` 放入 `mods/`，不要安装 `-sources.jar`。
3. 启动一次并正常关闭，在 `config/mc_ai_assistant.json` 中配置接口协议、地址和模型；参见[配置说明](docs/CONFIGURATION.md)。
4. 将密钥放在独立文件中，仅允许服务账户读取，设置 `secretFile` 后重启。不要把密钥写入聊天、Issue 或 Git。
5. 阅读[数据与隐私说明](docs/PRIVACY.md)，从 `/ai private 你好` 开始测试。

首次默认是普通聊天、全服公开。`/ai settings private` 可保存私聊默认值；`/ai settings role companion` 可选择已安装的虚构角色，`/ai settings ordinary` 取消角色。`/ai plain 内容` 只在本次跳过角色。

常用命令：`/ai help`、`/ai clear`、`/ai persona list`、`/ai private persona companion 内容`、`/ai private search 问题`、`/ai private wiki 问题`。管理员使用 `/aiadmin status|reload|enable|disable|clear`；启停命令只影响当前运行周期。

## 公开版边界

- 完整对话日志默认关闭，首次提问会显示外部服务与日志状态说明；`private` 仅限制游戏内可见范围。
- 问题、上下文和角色样本会发给服主选择的模型服务；Responses 请求还会包含玩家 UUID。搜索会额外向检索服务发出查询。
- 默认每日配额是全服外部请求次数，不是每人聊天轮数。一次检索、重试或模型降级可能消耗多次额度，也可能产生费用。
- 仅提供[虚构角色示例](docs/PERSONAS.md)，不提供真人资料、聊天导出、私人提示词、运行日志和评测网站。
- Wiki 功能仍属实验性能力；接口可用性和答案准确性不作保证。
- 初始公开源码已通过本地测试与 GitHub Linux CI；当前预发布版仍待一次真实双玩家验收。参见[发布验收清单](docs/RELEASE_CHECKLIST.md)，不要将自动测试当作完整游戏验收。

使用 Java 25 执行 `gradlew.bat --no-daemon check build` 可构建。项目准备以 MIT 公开，第三方组件保留各自许可。代码和文档开发大量使用了 AI，详见 [AI_DISCLOSURE.md](AI_DISCLOSURE.md)。
