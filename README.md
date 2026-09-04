<h1 align="center">世一Harness</h1>
<h6 align="center"><sub><small>的前世，努力升级中</small></sub></h6>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25+-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Gradle-9.7-02303A?style=flat-square&logo=gradle&logoColor=white" alt="Gradle">
  <img src="https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0.0-blue?style=flat-square" alt="PolyForm Noncommercial License 1.0.0">
</p>

> 现代化Java25实现的 claude code harness 代理

> **🌐 语言:** [English](README.en.md) | [中文](README.md)

## 项目概述

提供近似于 2.1.197 官方版本的**基线能力**——对话、工具调用、权限、会话管理与
终端体验，更多新的特性**持续追加中**。

## What can go beyond？

> claude-code-java 在下面几点易用性上希望超越官方的地方.

### 1. 自带模型路由

自带模型路由配置，对于不喜欢太重的cc-switch方案的用户，直接内置`chat` / `response` / `message` 三种协议兼容。

<!-- 媒体位 1：模型路由演示截图 / 配置界面截图 -->
![模型路由演示](docs/acceptance-assets/add-custom-model.png)

### 2. 自带飞书 connect 能力

提供 **TUI 级别的 connect**——直接在已有 claude code java 进程里关联飞书
Thread，会话管理清晰、所见即所得。不像传统 cc-connect 方案那样必须依赖
agent sdk、以非交互式方式启动。

> 🎯 展望：目前仅支持TUI、IM 接入，我们相信好的Harness应该做到接入层无关，未来计划支持常见的api、webui、desktop<br>
> 而且我们相信好的Harness应该是把能力接口提供出来白盒使用，而不像claude code sdk把逻辑都藏在二进制包里

![飞书 connect 架构展望](docs/screenshots/feishu-connect-architecture.png)
<p align="center"><sub>▲ 概念展望图（via. gpt-image-2）</sub></p>

![飞书 connect 失焦状态](docs/acceptance-assets/collaboration-off-focused.png)

![飞书 connect 选择器](docs/acceptance-assets/collaboration-picker.png)

![飞书 connect 选择器（飞书 Thread）](docs/acceptance-assets/collaboration-picker-feishu.png)

![飞书 Thread 关联](docs/acceptance-assets/collaboration-feishu-thread.png)

### 3. 自带 HUD

实时感知模型使用情况与各类监控数据，给你把context焦虑安排上。

<!-- 媒体位 3：HUD 监控面板截图 -->
![Claude HUD 监控](docs/acceptance-assets/hud.png)

### 4. Pokemon 系统

升级buddy为pokemon系统，**38 种宝可梦等你来抽**。

<!-- 媒体位 4：宝可梦孵化 / 进化演示 -->
<video src="docs/acceptance-assets/pokemon-hatch-evolve.mp4" controls style="max-width: 100%;"></video>

### 5. 项目菜单与跨项目 resume

官方的 resume 选择器是**单项目内的平铺会话列表**，且**拒绝跨目录 resume**——选到别的项目
只会打印一条 `cd … && claude --resume …` 提示让你自己重开一个进程。

我们把它做成了左侧常驻的**项目抽屉**（Codex desktop 风格的两级 项目 → 会话 树）：
footer 最左侧按钮、点击或 `/project` 打开，`↑/↓` 走行、`→/←` 展开收起、`Enter` resume、
`x` 两段式删除、`Space` 直接开一个宽幅可滚动的会话预览（和 resume 选择器同一条渲染管线，
预览出来长什么样，resume 之后就长什么样）。项目索引带**指纹校验的持久化缓存**（文件数 +
最新 mtime），没变过的目录直接命中缓存，改动过的目录只重扫那一个目录，几百个会话也不卡。

更关键的是**跨项目 resume 是进程内真实切换**：`user.dir`、QuerySession 的工作目录、
项目身份（决定 transcript 目录 / settings 层级 / 项目级 `CLAUDE.md` 作用域 / 权限根）一起
重新指向新项目，transcript recorder、权限根、git status 快照、settings 监听器全部重建。
切换分两阶段——准备阶段在虚拟线程上校验目标目录、只做暂存，提交阶段在 UI 线程上原子生效，
所以**失败的 resume 不会破坏当前项目**。

> 已知边界（有意后置，不做假装）：项目级 MCP 连接仍保持切换前项目的连接、LSP root 不重启、
> plugin/skill 不重新扫描。

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                claude-code-cli (Picocli)                    │
│                 应用入口 / CLI 解析 / 组合根                    │
│  claude-code-sdk：独立 JVM query transport + 进程内 SDK MCP  │
├─────────────────────────────────────────────────────────────┤
│                  claude-code-ui (Lanterna)                  │
│    终端 UI：REPL、消息渲染、输入、Vim 模式、Markdown            │
├──────────────────┬──────────────────┬───────────────────────┤
│  claude-code-    │  claude-code-    │  claude-code-         │
│  commands        │  tools           │  services             │
│  斜杠命令系统     │  工具系统         │  compact/hooks/memory  │
├──────────────────┴──────────────────┴───────────────────────┤
│                  claude-code-runtime                        │
│     QuerySession │ turn/session orchestration │ ports       │
├─────────────────────────────────────────────────────────────┤
│                   claude-code-core                          │
│  Protocol │ Message │ Value Object │ Pure Policy │ Config   │
├──────────────────┬──────────────────────┬───────────────────┤
│  claude-code-api │  claude-code-mcp     │  claude-code-lsp   │
│  模型路由 + 三协议 │  MCP 客户端          │  语言服务器协议      │
│  自定义模型路由     │                      │                    │
│  Anthropic/OpenAI │                      │                    │
├──────────────────┴──────────────────────┴───────────────────┤
│                   claude-code-http                          │
│           共享 OkHttp 传输（api / mcp / lsp 共用）            │
├─────────────────────────────────────────────────────────────┤
│  claude-code-session │ claude-code-permissions              │
│        会话持久化       │          权限引擎                    │
└─────────────────────────────────────────────────────────────┘
```

| 模块 | 描述 |
|------|------|
| `claude-code-core` | 稳定模型协议、消息系统、纯策略和值对象 |
| `claude-code-http` | 共享 OkHttp 传输层（api / mcp / lsp 共用） |
| `claude-code-api` | 模型路由 + 三协议（Anthropic / OpenAI Chat / OpenAI Responses）+ Vertex、Bedrock 适配 |
| `claude-code-permissions` | 权限系统（allow/deny/ask） |
| `claude-code-runtime` | 查询/会话交易编排与端口（中枢） |
| `claude-code-tools` | 工具系统（工具实现 + 配套支持类） |
| `claude-code-commands` | 斜杠命令系统 + 命令适配器 |
| `claude-code-mcp` | Model Context Protocol 集成 |
| `claude-code-session` | 会话管理（JSONL 持久化） |
| `claude-code-services` | 服务层：compact、hooks、memory 等 |
| `claude-code-ui` | 终端 UI：渲染器、对话框、菜单、Vim 模式 |
| `claude-code-lsp` | Language Server Protocol 集成 |
| `claude-code-cli` | CLI 入口与组合根 |
| `claude-code-sdk` | 进程外 Agent SDK、控制协议与进程内 SDK MCP server |
| `claude-code-app` | 应用打包与分发 |

### JVM 与原生二进制双启动

整个项目基于 **Java 25** 开发，支持两种运行形态：既可通过 JVM 直接运行
fat JAR，也可编译为 **GraalVM native 二进制**直接执行。

| 对比项 | JVM fat JAR | GraalVM 原生二进制 |
|--------|-------------|--------------------|
| 启动方式 | `java -jar claude-code-app.jar` | 直接执行编译出的可执行文件 |
| 启动时间 | < 2s | < 500ms |
| 适用场景 | 日常开发、快速迭代、调试与观察日志 | 秒级启动、脚本化调用、CI 集成 |
| 产物 | 包含全部依赖的 fat JAR | 平台绑定的独立可执行文件 |

原生二进制提供三种编译档位，按目标取舍资源与体积：

- `nativeQuickCompile`（`-Ob`）：功能完整、构建资源消耗低，适合日常验证
- `nativeCompile`（GraalVM 默认 `-O2`）：用于性能基准与吞吐回归
- `nativeReleaseCompile`（`-Os`）：优先压缩最终体积，用于正式发布

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 25+ (Records, Sealed Classes, Pattern Matching, Virtual Threads) |
| 构建 | Gradle 9.7 + Kotlin DSL |
| JSON | Jackson 2.18.9 |
| HTTP | OkHttp 5.4.0（共享传输层） |
| 终端 UI | **[Lanterna fork](https://github.com/aconeshana/lanterna) 3.2.0-cc4** —— 深度魔改的终端框架 |
| CLI | Picocli 4.7.6 |
| Markdown | commonmark-java 0.22.0 |
| 日志 | SLF4J 2.0.13 + Logback 1.5.34 |
| 工具库 | Commons Lang3 3.18.0, Commons IO 2.16.1, Caffeine 3.1.8 |
| LSP | Eclipse LSP4J 0.24.0 |
| 平台 | JNA 5.19.1（Windows 终端后端） |
| 即时通信 | **[cc-connect fork](https://github.com/aconeshana/cc-connect)** —— 发行包随带的 Session Host sidecar |
| 测试 | JUnit 5.10.3, jqwik 1.9.0 (属性测试) |

> **`scripts/` 下的开发辅助脚本（`pty_ui_benchmark.py`、`pty_high_frequency_e2e.py`
> 等）仅供开发/性能复现，不参与 native 二进制或 fat JAR 的构建与运行时依赖；
> 可选 Python 依赖见 [`scripts/requirements.txt`](scripts/requirements.txt)。

## 快速开始

> 打 `v*` tag 后由 CI 自动构建并上传各平台二进制（见
> [`.github/workflows/release.yml`](.github/workflows/release.yml)）。
> 每个 tag 对应一个 release 版本；资产名保持稳定（不带版本号），
> `releases/latest` 始终指向最新发布版。

### 下载

从 [Releases](../../releases) 下载对应当前平台的二进制：

| 平台 | 资产 |
|------|------|
| macOS（本机架构） | `claude-code-app-darwin-arm64`（Apple Silicon）或 `claude-code-app-darwin-amd64`（Intel） |
| Linux | `claude-code-app-linux-arm64` 或 `claude-code-app-linux-amd64` |
| Windows | `claude-code-app-windows-amd64.exe` |
| 任意平台（JVM 备选） | `claude-code-app.jar`（跨平台，需 Java 25） |

> **二进制跨平台限制**：native 二进制与构建平台绑定，不能跨 OS/架构复用；
> Apple Silicon / Windows ARM 需在对应机器上构建。

### macOS 启动

下载后，先给二进制加执行权限再运行（macOS/浏览器下载裸文件
默认不保留可执行位）：

**推荐 —— 原生二进制**（零依赖，启动最快）：

```bash
chmod +x claude-code-app && ./claude-code-app
```

**备选 —— JVM 版**（任意平台，需本机装有 Java 25）：

```bash
java -jar claude-code-app.jar
```

## 许可证

除另有明确标注的第三方组件和资源外，本项目由 `acone` 按
[PolyForm Noncommercial License 1.0.0](LICENSE) 授权。

- 允许个人学习、研究、实验、业余项目及其他非商业用途。
- 未经项目权利人另行书面授权，不允许商业使用。
- 商业集成、商业分发、付费服务或其他商业用途，请联系项目维护者取得商业授权。
- 具体授权边界以 [LICENSE](LICENSE) 正文为准。

由于许可证限制商业用途，本项目属于**源码可用软件**，并非 OSI 定义的开源软件。
项目包含或分发的第三方代码、二进制文件和资源继续适用各自的许可证、版权声明及
商标规则，不因本项目许可证而被重新授权。

## 致谢

- [pokemon-colorscripts](https://gitlab.com/phoneybadger/pokemon-colorscripts)
  （MIT）— 提供欢迎界面使用的 Pokémon ANSI 图稿；仓库内保留了原始
  [许可文本](claude-code-ui/src/main/resources/welcome/pokemon-colorscripts-LICENSE.txt)。
- [Lanterna](https://github.com/mabe02/lanterna)（LGPL-3.0）— 终端 TUI
  框架。**由衷感谢这个项目**：我们fork了官方
  [Lanterna fork](https://github.com/aconeshana/lanterna)，在里面修了
  IXON 流控、补了主题继承链回退。
- [cc-connect](https://github.com/chenhg5/cc-connect) — Session Host 与即时通信
  sidecar：我们基于它fork并维护了
  [cc-connect fork](https://github.com/aconeshana/cc-connect)。
- [claude-hud](https://github.com/jarrodwatts/claude-hud)（MIT）— 为内置 HUD
  的信息布局与指标呈现提供参考。
- [ripgrep](https://github.com/BurntSushi/ripgrep)（MIT / Unlicense）— 发行包
  内置的高速文本搜索工具。
- [Picocli](https://picocli.info/)（Apache-2.0）— CLI 框架。
- [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j)
  （EPL-2.0 / EDL-1.0）— LSP 客户端与协议类型。

Pokémon 名称、角色和相关标识的权利归其各自权利人所有。

---
