import { useEffect, useState } from "react";

/**
 * Theme control (dsh ui-theme, proportionate): cycles dark → light → system,
 * persisted in localStorage and applied as a `data-theme` attribute the
 * stylesheet's light-override block keys on. `system` tracks
 * `prefers-color-scheme` live.
 */
const LABEL: Record<string, string> = { dark: "🌙", light: "☀️", system: "🖥" };
const NEXT: Record<string, string> = { dark: "light", light: "system", system: "dark" };
const STORAGE_KEY = "majo-theme";

function initial(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "dark" || stored === "light" || stored === "system") {
      return stored;
    }
  } catch {
    // storage unavailable: system default
  }
  return "system";
}

function apply(theme: string): void {
  const effective =
    theme === "system"
      ? window.matchMedia("(prefers-color-scheme: light)").matches
        ? "light"
        : "dark"
      : theme;
  document.documentElement.setAttribute("data-theme", effective);
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<string>(initial);

  useEffect(() => {
    apply(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // ignore: theme still works for this page session
    }
    if (theme !== "system") {
      return;
    }
    const media = window.matchMedia("(prefers-color-scheme: light)");
    const onChange = () => apply("system");
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [theme]);

  return (
    <button
      id="theme-toggle"
      type="button"
      title={"theme: " + theme + " (click to switch)"}
      onClick={() => setTheme((current) => NEXT[current] ?? "system")}
    >
      {LABEL[theme] ?? "🖥"}
    </button>
  );
}
