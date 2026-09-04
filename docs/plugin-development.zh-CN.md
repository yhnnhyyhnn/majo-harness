# 插件开发指南

插件是一个**自包含 jar**，运行时即可接入 harness——无需重建 web-ui、无需改宿主代码。按对 UI 的需求从三个集成档中选（一个 jar 内可自由组合）：

| 档 | 携带内容 | 集成方式 | 需要重建 web-ui？ |
|---|---|---|---|
| 1 · 后端 | jcordis `Plugin`（SPI） | ctx 服务 / 工具 / provider / 监听 | 否 |
| 2 · 托管页面 | `static-web/<name>/` 下的静态前端 | 独立页面，`/plugins/<name>/` 托管、同源 API + postMessage 桥 | 否 |
| 3 · 原生模块 | `plugin.mjs`（ES module） | 运行时把组件注册进宿主槽 | 否 |

仓库自带示例（`examples/web-plugin-demo`，`bash scripts/build-plugin-demo.sh` 构建）在**一个 jar 里演示全部三档**。`majo-boot` 的 `PluginJarTest` 是用 Maven 构建第一档 jar 的现成配方。

---

## 挂载

```bash
# web 应用：jar 插件（可重复）+ profile
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar \
  --profile web-mock \
  --plugin web-demo=./examples/web-plugin-demo/web-demo.jar

# CLI 一次性任务
majo --plugin ext=./ext-plugin.jar "task"
```

profile 行随后可按**挂载名**引用该插件：

```yaml
- id: my-capability
  name: my-plugin            # 即 --plugin 的 name
  config:
    key: value
```

---

## jar 结构（一个 jar，最多三层）

```
my-plugin.jar
├── io/…/MyPlugin.class …              # 档 1 后端类
├── META-INF/services/
│   └── io.jcordis.core.registry.Plugin   # SPI 行：你的 Plugin 全限定类名
└── static-web/my-plugin/               # 档 2+3 前端
    ├── index.html                      # 托管页面入口（档 2）
    ├── app.js / assets/…               # 页面资源（同源）
    ├── plugin.json                     # 可选：{ "title": "…" }
    └── plugin.mjs                      # 原生模块（档 3）
```

`static-web/<name>/` 必须与**挂载名一致**（`--plugin name=jar`）。宿主把该目录下所有文件托管在 `GET /plugins/<name>/…`（已防路径穿越；自带 html/css/js/svg/png/jpg/ico/woff2/json 内容类型），并通过 `GET /api/plugins` 列出带 `index.html` 的插件。

---

## 档 1 — 后端插件

实现 `io.jcordis.core.registry.Plugin`（实例契约，非构造函数）：

```java
public final class MyPlugin implements Plugin {
    @Override public String name() { return "my-plugin"; }
    @Override public Map<String, Object> inject() { return Map.of("llm", null); }
    @Override public Object apply(Context ctx, Object config) {
        Disposable registration = ctx.get("tools") /* 注册你的工具/服务 */;
        return registration;   // 返回 Disposable：卸载时由 loader 收集回滚
    }
}
```

- `apply` 在声明的注入解析后执行；配置非法请 loud 报错；**必须返回 `Disposable`**（jcordis 收集它并在卸载时回滚）。
- SPI 文件：`META-INF/services/io.jcordis.core.registry.Plugin` 里写你的全限定类名。
- 通过 ctx 注册的一切对 agent 可用（**工具是连到 UI 的自然桥**：结构化 `data` 会渲染成卡片）。

---

## 档 2 — 托管页面插件

把静态 web 应用（任意技术栈）放进 `static-web/<name>/`。宿主托管它；主 UI 在侧栏 **Plugins** 区列出，点击后以沙箱化 iframe 在主区打开。

- 同源：可直接 `fetch("/api/sessions")`、`…/api/info`、SSE 等。
- 要驱动宿主（关面板、开会话、发任务、通知条）用 postMessage——宿主校验 `event.source` 且 `source === "majo-plugin"`：

```js
window.parent?.postMessage(
  { source: "majo-plugin", type: "sendTask", task: "2+2" },
  "*"
);
```

| `type` | 载荷 | 宿主行为 |
|---|---|---|
| `flash` | `message: string` | 瞬态通知条 |
| `newChat` | — | 新建对话 |
| `close` | — | 返回聊天视图 |
| `sendTask` | `task: string` | 关面板并把任务作为一轮 turn 运行 |
| `openSession` | `sessionId: string` | 关面板并打开该会话 |

可选 `plugin.json` 提供菜单标题：

```json
{ "title": "my-plugin" }
```

---

## 档 3 — 原生模块（`plugin.mjs`）

最强集成：宿主在**运行时动态 import** 该模块（从不打进 web-ui 包），并传入 host 对象；模块可把组件/区块/命令注册进与编译期 feature 完全相同的槽。

契约：

```js
// plugin.mjs —— 必须导出 register(host)；可返回 disposer
export function register(host) {
  const { React } = host;            // 宿主唯一共享的 React 实例
  const dispose = host.registrar.addSidebarSection("my-section", () =>
    React.createElement(MySection)   // ……或编译后的 JSX
  );
  return dispose;                    // 卸载回滚
}
```

`host`（PluginHost）：

| 字段 | 含义 |
|---|---|
| `React` | 宿主的 React（createElement/hooks）——**切勿自带 React** |
| `api` | typed API 客户端（与聊天 UI 相同端点） |
| `openPlugin(name, url)` / `flash(message)` | 打开托管页 / 显示通知的座位 |
| `registrar` | `addSidebarSection` / `addCommand` / `addRail` / `renderMessage`，每个**返回回滚 disposer** |

想用 JSX 就编译后再发布——保持 `react` 为 external，渲染走 `host.React`：

```bash
# esbuild 示例（源码 src/，产物 static-web/my-plugin/plugin.mjs）
esbuild src/index.jsx --bundle --format=esm --external:react \
  --outfile=src/main/resources/static-web/my-plugin/plugin.mjs
```

注意：hooks 之所以可用，是因为模块使用的是宿主同一 React 实例。已加载模块在本页会话内生效；刷新会重新 `register`。

---

## 验证插件

1. `bash scripts/build-plugin-demo.sh` → `examples/web-plugin-demo/web-demo.jar`。
2. 带 `--plugin web-demo=…` 启动 `majo-web`（见「挂载」）。
3. `curl http://localhost:8787/api/plugins` 列出（含 `title`/`module`）。
4. `curl http://localhost:8787/plugins/web-demo/index.html` 可拿到页面。
5. 打开 UI：侧栏 **Plugins** 区可见；点开页面体验档 2；**Native demo** 侧栏区来自档 3（需浏览器确认）。

排障：`--plugin` 需要 `name=jar`；`static-web/<name>/` 目录名必须等于挂载名；启动日志会打 `mounted plugin "name" from …`；未知插件 / 穿越路径返回 404。
