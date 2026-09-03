// Native web-demo module (S3): loaded at runtime by the host (dynamic import
// of /plugins/web-demo/plugin.mjs) and handed a PluginHost:
//   host.React        — the shared React instance (createElement/hooks)
//   host.api          — typed API client
//   host.openPlugin / host.flash
//   host.registrar    — addSidebarSection/addCommand/addRail/renderMessage,
//                       each returning a rollback disposer
// Contract: the module exports register(host) and may return a disposer.
//
// Authoring without JSX is verbose; compile your own plugin with esbuild
// (react/react-dom external) and the same register(host) signature.

export function register(host) {
  const { React } = host;

  function NativeDemoSection() {
    const [open, setOpen] = React.useState(false);
    const [count, setCount] = React.useState(0);
    const head = React.createElement(
      "button",
      {
        type: "button",
        className: "side-section-head",
        onClick: () => setOpen(!open),
      },
      "Native demo",
      React.createElement("span", { className: "caret" }, open ? " ▾" : " ▸")
    );
    if (!open) return React.createElement("div", { className: "side-section" }, head);
    const body = React.createElement(
      "div",
      { className: "side-section-body" },
      React.createElement(
        "div",
        { className: "side-item" },
        "This section was registered at runtime from a plugin jar — no web-ui rebuild."
      ),
      React.createElement(
        "div",
        { className: "side-item" },
        React.createElement("strong", null, "count: ", String(count)),
        React.createElement("button", { className: "side-refresh", onClick: () => setCount(count + 1) }, "+1")
      ),
      React.createElement(
        "button",
        { className: "side-refresh", onClick: () => host.flash("hello from the native demo module") },
        "flash host"
      ),
      React.createElement(
        "button",
        { className: "side-refresh", onClick: () => host.openPlugin("web-demo", "/plugins/web-demo/index.html") },
        "open hosted page"
      )
    );
    return React.createElement("div", { className: "side-section" }, head, body);
  }

  const dispose = host.registrar.addSidebarSection("web-demo-native", () =>
    React.createElement(NativeDemoSection)
  );
  host.flash("native module web-demo mounted");
  return dispose;
}
