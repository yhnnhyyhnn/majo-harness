import { createContext, useContext, useRef, type ReactNode } from "react";
import type { ApprovalFrame, EventFrame, EventKind, QuestionFrame } from "./types";

// Pure slot registry (mirrors dsh ui-slots, simplified): UI features register
// message renderers (per event kind) and rail contributions (above the
// conversation) against typed slots; the App shell only renders what was
// registered. Adding a new kind means adding its EventKind and registering a
// renderer — no switch growth in the shell.

export interface ChatFrameProps {
  event: EventFrame;
  streaming?: boolean;
  /** Opens another session in the transcript (child links, …). */
  openSession?: (id: string) => void;
  /** User rating for this event's message (durable seq only). */
  rate?: "up" | "down" | null;
  onRate?: (seq: number, value: "up" | "down" | null) => void;
}

export type MessageRenderer = (props: ChatFrameProps) => ReactNode;

export interface RailProps {
  approvals: ApprovalFrame[];
  question: QuestionFrame | null;
  qInput: string;
  onQInput(value: string): void;
  onDecide(id: string, granted: boolean): void;
  onAnswerAsk(): void;
}

export interface SectionProps {
  // shared sidebar seat for feature sections; extend when a feature needs it
}

export interface FeatureContext {
  renderMessage(kind: EventKind, renderer: MessageRenderer): void;
  addRail(id: string, render: (props: RailProps) => ReactNode): void;
  addSidebarSection(id: string, render: (props: SectionProps) => ReactNode): void;
}

export interface Feature {
  id: string;
  register(context: FeatureContext): void;
}

interface Registry {
  messages: Partial<Record<EventKind, MessageRenderer>>;
  rails: Map<string, (props: RailProps) => ReactNode>;
  sections: Map<string, (props: SectionProps) => ReactNode>;
}

const RegistryContext = createContext<Registry | null>(null);

export function SlotRoot({ features, children }: { features: Feature[]; children: ReactNode }) {
  const registry = useRef<Registry | null>(null);
  if (!registry.current) {
    const value: Registry = { messages: {}, rails: new Map(), sections: new Map() };
    const context: FeatureContext = {
      renderMessage(kind, renderer) {
        value.messages[kind] = renderer;
      },
      addRail(id, render) {
        value.rails.set(id, render);
      },
      addSidebarSection(id, render) {
        value.sections.set(id, render);
      },
    };
    for (const feature of features) feature.register(context);
    registry.current = value;
  }
  return <RegistryContext.Provider value={registry.current}>{children}</RegistryContext.Provider>;
}

export function useSlots(): {
  messageRenderer: (kind: EventKind) => MessageRenderer | undefined;
  rails: Array<{ id: string; render: (props: RailProps) => ReactNode }>;
  sidebarSections: Array<{ id: string; render: (props: SectionProps) => ReactNode }>;
} {
  const registry = useContext(RegistryContext);
  if (!registry) {
    throw new Error("useSlots must be used inside SlotRoot");
  }
  return {
    messageRenderer: (kind) => registry.messages[kind],
    rails: [...registry.rails.entries()].map(([id, render]) => ({ id, render })),
    sidebarSections: [...registry.sections.entries()].map(([id, render]) => ({ id, render })),
  };
}
