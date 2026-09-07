// Scaffolded native module for __name__: register(host) may add sidebar
// sections, commands, rails or message renderers. See docs/plugin-development
// for the PluginHost/registrar contract.
export function register(host) {
  const { React } = host;
  const dispose = host.registrar.addSidebarSection("__name__-section", () =>
    React.createElement("div", { className: "side-section-body" },
      React.createElement("div", { className: "side-item" },
        "Hello from the __name__ native module."),
      React.createElement("button", {
        type: "button",
        className: "side-refresh",
        onClick: () => host.flash("__name__ native module is live"),
      }, "flash host")
    )
  );
  return dispose;
}
