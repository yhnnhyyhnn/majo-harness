# 验证清单（0.1.0）

发版前的手动/脚本化检查项。在仓库根目录（Windows Git Bash 或 POSIX）执行。

## 1. 后端构建与测试

```bash
mvn clean verify            # 编译所有模块 + 全部 seam 测试 + 把 UI 重新打进 jar
```

预期：`BUILD SUCCESS`。40+ 个测试类覆盖 store 与各接缝（fs/shell/subprocess/
sandbox/interaction/skill/subagent/title/web-access）、agent loop（并行委派、
scoped 运行、maxSteps 上限）、settings 与类型生成器。

## 2. 插件示例 jar 与 web 伺服

```bash
bash scripts/build-plugin-demo.sh                 # 构建 examples/web-plugin-demo/web-demo.jar
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar \
  --port 8899 --profile web-mock \
  --plugin web-demo=./examples/web-plugin-demo/web-demo.jar
```

检查（curl 或浏览器）：

- `GET /api/plugins` 列出 web-demo（含 `module` URL）。
- `GET /plugins/web-demo/index.html` 与 `/plugin.mjs` 返回 200；mjs 是
  `text/javascript`。
- 浏览器：Plugins 区显示 web-demo；Native demo 侧栏区出现；**unload** 移除它，
  **mount/reload** 无需整页刷新即可恢复。
- 穿越（`/plugins/web-demo/../…`）与未知插件返回 404。

## 3. Web UI 功能走查（浏览器）

`--profile web-mock --port 8899` 起服务，打开 http://localhost:8899：

- 聊天：输入 `1+2` → 审批条 → Allow → `calculated: 3`，实时流式。
- 可选 API 鉴权：`--token <secret>` 启动；UI 读取 `?token=…`（持久化），curl 用
  `Authorization: Bearer <secret>`，SSE 用 `?token=`。
- 离线工具卡：输入 `file examples/offline-demo/hello.txt` → Allow → 渲染带 path
  chip 与行数的文件卡（结构化 `data`）。
- 工具卡广度（web-mock，全离线）：`shell echo hi-d2` → Allow → shell 输出卡
  （`shell output: hi-d2`）；`fetch file:hello-page.txt` → Allow → 本地语料
  抓取卡（零网络：local-file 后端只读 `examples/demo-corpus` 且拒绝穿越）；
  `search majo` → Allow → 静态结果卡（`Majo FAQ` 等）。
- 模型下拉（全局 + 按会话）、👍/👎 反馈刷新后仍在。
- 斜杠命令：输入 `/` 出现分组补全；`/model mock`、`/help`、`/delegate 2+2`
  （返回 child id + 答案）。
- 会话搜索：搜 `calculated` → 命中高亮 → Enter/点击跳转并闪烁定位。
- Manage 模式：多选 → Archive (n)/Delete (n)；Active/Archived 视图；搜索中
  归档结果可内联恢复。
- 导出 ⬇ → 经 **Import JSONL…** 导入可还原转写。
- 插件 + 移动端（DevTools 窄视口）：抽屉侧栏、触控目标、无 iOS 聚焦放大。
- dev 全栈替代：
  `java -jar … --port 8899 --profile web-mock` +
  `MAJO_API_TARGET=http://127.0.0.1:8899 npx vite --port 5173`。

## 4. 真模型（可选，需网络）

```bash
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar --profile web --port 8900
curl -X POST -H 'Content-Type: application/json' \
  -d '{"task":"What is 33 times 4?","model":"kilo-free"}' \
  http://127.0.0.1:8900/api/subagents/delegate
# 预期 {"childSessionId":…,"answer":"132"}；面板显示 model kilo-free
```

Fan-out SSE 检查：提示要求同一轮发起两个 `delegate_task` → 每个子代理审批
带 `subagent-<child8>` 标签；逐一 Allow 后得到正确汇总答案。

## 5. Git 卫生

- `git status` 干净；`git push origin main` 与远端一致。
- HEAD 上带注解的 release tag（`v0.1.0`）。
