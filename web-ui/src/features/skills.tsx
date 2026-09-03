import { useEffect, useState } from "react";
import { api } from "../api";
import type { SkillInfo } from "../types";
import type { Feature } from "../slots";

function SkillsPanel() {
  const [open, setOpen] = useState(false);
  const [skills, setSkills] = useState<SkillInfo[] | null>(null);

  const load = async () => {
    const index = await api.skills();
    setSkills(index.skills || []);
  };

  useEffect(() => {
    if (open && skills === null) {
      void load().catch(() => setSkills([]));
    }
  }, [open, skills]);

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
            <div className="side-item" key={skill.name} title={skill.description || ""}>
              <div className="side-item-title">{skill.name}</div>
              {skill.description && <div className="meta">{skill.description}</div>}
            </div>
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
