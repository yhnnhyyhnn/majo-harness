import { useEffect, useState } from "react";
import { api } from "../api";
import type { SkillDetail, SkillInfo } from "../types";
import type { Feature } from "../slots";

function SkillRow({ skill }: { skill: SkillInfo }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<SkillDetail | null>(null);
  const [failed, setFailed] = useState(false);

  const load = async () => {
    if (detail || failed) return;
    try {
      setDetail(await api.skillDetail(skill.name));
    } catch {
      setFailed(true);
    }
  };

  return (
    <div className="side-item">
      <button
        type="button"
        className="skill-row"
        onClick={() => {
          setOpen(!open);
          if (!open) void load();
        }}
        title={skill.description || ""}
      >
        {skill.name} <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="skill-detail">
          {skill.description && <div className="meta">{skill.description}</div>}
          {failed && <div className="meta">cannot load instructions</div>}
          {detail && (
            <>
              {detail.instructions && (
                <pre className="code skill-instructions">{detail.instructions}</pre>
              )}
              <div className="meta">agents load this via the load_skill tool</div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function SkillsPanel() {
  const [open, setOpen] = useState(false);
  const [skills, setSkills] = useState<SkillInfo[] | null>(null);

  const load = async () => {
    const index = await api.skills();
    setSkills(index.skills || []);
  };

  // fetch on open, then keep the catalog fresh while the panel is visible
  useEffect(() => {
    if (!open) return;
    void load().catch(() => setSkills([]));
    const timer = window.setInterval(() => void load().catch(() => {}), 8000);
    return () => window.clearInterval(timer);
  }, [open]);

  return (
    <div className="side-section">
      <button type="button" className="side-section-head" onClick={() => setOpen(!open)}>
        Skills {skills ? `(${skills.length})` : ""} <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="side-section-body">
          {skills === null && <div className="meta">loading…</div>}
          {skills && skills.length === 0 && <div className="meta">no skills mounted</div>}
          {skills?.map((skill) => (
            <SkillRow skill={skill} key={skill.name} />
          ))}
          <button type="button" className="side-refresh" onClick={() => void load()}>
            refresh
          </button>
        </div>
      )}
    </div>
  );
}

export const skillsFeature: Feature = {
  id: "skills",
  register(context) {
    context.addSidebarSection("skills", () => <SkillsPanel />);
  },
};
