# today-proxy-android

把 [today-proxy](https://github.com/Main8986/today-proxy) 的上游能力打包成 **安卓本地反代 APP**：
手机自己当服务器，OpenAI / Anthropic 双协议接口跑在 `127.0.0.1:8770`，别的 App（Minis /
LobeChat / 任意 SDK）直连本机即可用上 Today 的 DeepSeek / GLM 额度。

```
┌──────────── 你的手机 ────────────┐              ┌──────────────┐
│  Minis / 其它客户端               │              │              │
│      │ http://127.0.0.1:8770     │   轮询取回复  │ Today 后端    │
│      ▼                            │ ───────────► │ api.todayai.cn│
│  today-proxy-android (前台服务)   │              │              │
└───────────────────────────────────┘              └──────────────┘
```

## 特性

- **本机反代**：不用 VPS，手机自己就是服务器；同一 Wi-Fi 下别的设备也能连
- **双协议**：`/v1/chat/completions`（OpenAI）+ `/v1/messages`（Anthropic），都支持 `stream: true`
- **号池**：多账号加权轮询、额度感知、401/5xx 分级冷却、全忙排队
- **短信登录**：APP 内直接发码登录，不用抓包抠 token
- **前台服务**：常驻通知栏，避免被系统杀
- **体积小**：纯 Java + 系统 API，无第三方依赖，APK 约 100 KB

## 下载

去 [Releases](../../releases) 拿最新 `today-proxy-android.apk`，或从 Actions 的 artifact 下载。

## 用法

1. 安装 APK（需要允许「安装未知来源应用」）
2. 打开 APP → 点 **＋ 加号**
   - 粘贴已有 session token，或
   - 点「短信登录」用手机号收码登录（推荐）
3. 回到主页，状态显示 **运行中 · 端口 8770 · 可用 1/1**
4. 在客户端里配：

   | 字段 | 值 |
   |---|---|
   | Base URL（OpenAI） | `http://127.0.0.1:8770/v1` |
   | Base URL（Anthropic） | `http://127.0.0.1:8770` |
   | API Key | APP 首页显示的 `sk-today-…` |
   | Model | `deepseek` / `glm-5.3` |

同一局域网的其他设备把 `127.0.0.1` 换成手机 IP（首页会显示）。

## 号池行为

| 情况 | 处理 |
|---|---|
| 某号额度 ≥ 98% | 停止派单该号 |
| session token 失效（401/403） | 冷却 600 秒，自动跳过 |
| 5xx / 网络错误 | 从 30 秒起指数退避，最长 900 秒 |
| 该号该模型正在跑 | 换下一个号（同号不同模型可并行） |
| 所有号都忙 | 排队等待最多 120 秒，而不是直接报错 |

权重：加号时可填，权重越高分到的请求越多（1:3 实测约 22%:78%）。

## 自己构建

不需要 Gradle。GitHub Actions 里已经配好（`.github/workflows/build.yml`），推 tag 就出 APK：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

本地构建（需要 JDK 17 + Android SDK build-tools 34 + platform 34）：

```bash
export ANDROID_HOME=/path/to/sdk
bash build.sh
# 产物：today-proxy-android.apk
```

## 源码结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/main8986/todayproxy/
│   ├── MainActivity.java   界面（纯代码构建，无 XML 布局）
│   ├── ProxyService.java   前台服务，通知栏常驻
│   ├── ProxyServer.java    HTTP 服务：OpenAI + Anthropic + SSE
│   ├── Pool.java           号池调度（smooth WRR / 冷却 / 排队）
│   ├── Account.java        单账号客户端（JWT / 发送 / 轮询）
│   ├── TodayApi.java       上游常量与模型映射
│   ├── JwtUtil.java        JWT 只读解析
│   ├── Store.java          配置持久化
│   └── ApiException.java   带状态码异常
└── res/mipmap-*/ic_launcher.png
```

## 已知限制

- **不是真流式**：上游只回 202，正文靠轮询，拿到全文后一次性 flush。
  客户端不会报错，但首字延迟 2–6 秒。
- **单会话**：Today 主对话 ID 固定为 userId，同号并发会挤在同一上下文，多号才有真并发。
- **吃自己额度**：GLM 5.3 是 4× 消耗，首页能看到剩余百分比。
- **需要前台服务**：Android 会限后台，通知栏那条别手动划掉。

## 免责声明

仅用于把你自己账号已有的额度接到自己的客户端上使用。不提供账号、不绕过付费。
使用可能违反 Today 的用户协议，风险自负。请勿用于转售或对外提供服务。

## License

MIT
