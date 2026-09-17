import { useEffect, useState } from "react";

/**
 * Composer font-size control (dsh ui-theme stretch, proportionate): cycles
 * small → medium → large, persisted in localStorage and applied as a
 * `data-composer-size` attribute the stylesheet keys on.
 */
const LABEL: Record<string, string> = { small: "A−", medium: "A", large: "A+" };
const NEXT: Record<string, string> = { small: "medium", medium: "large", large: "small" };
const STORAGE_KEY = "majo-composer-size";

function initial(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "small" || stored === "medium" || stored === "large") {
      return stored;
    }
  } catch {
    // storage unavailable: default size
  }
  return "medium";
}

export function FontSizeToggle() {
  const [size, setSize] = useState<string>(initial);

  useEffect(() => {
    document.documentElement.setAttribute("data-composer-size", size);
    try {
      localStorage.setItem(STORAGE_KEY, size);
    } catch {
      // ignore: size still works for this page session
    }
  }, [size]);

  return (
    <button
      id="composer-size"
      type="button"
      title={"composer font: " + size + " (click to change)"}
      onClick={() => setSize((current) => NEXT[current] ?? "medium")}
    >
      {LABEL[size] ?? "A"}
    </button>
  );
}
