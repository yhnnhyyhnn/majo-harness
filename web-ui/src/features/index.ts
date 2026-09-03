import type { Feature } from "../slots";
import { approvalFeature } from "./approval";
import { chatFeature } from "./chat";
import { commandsFeature } from "./commands";
import { pluginsFeature } from "./plugins";
import { settingsFeature } from "./settings";
import { skillsFeature } from "./skills";
import { subagentsFeature } from "./subagents";

// The registered UI feature modules (compile-time "bundle" list). Adding a
// feature = write one module + append it here; the shell only renders slots.
export const FEATURES: Feature[] = [
  chatFeature,
  approvalFeature,
  skillsFeature,
  settingsFeature,
  subagentsFeature,
  commandsFeature,
  pluginsFeature,
];
