import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import type { ChatActions, ChatState } from "./useChat";
import type { ApprovalFrame, EventFrame, EventKind, QuestionFrame } from "./types";

// Slot registry (mirrors dsh ui-slots, simplified): UI features register
// message renderers, rail contributions, sidebar sections and commands against
// typed slots; the App shell only renders what was registered. Registration is
// mutable at runtime — native plugin modules (S3) register through the same
// methods and are rolled back via the returned disposer.

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
  /** Opens a mounted plugin's hosted frontend in the main pane. */
  openPlugin?: (name: string, url: string) => void;
}

/**
 * A slash command contribution (dsh {@code ui-commands}): pure definitions
 * registered by features; the shell injects live state/actions at run time.
 */
export interface Command {
  /** Invocation names without the slash, e.g. ["help", "?"] for {@code /help}. */
  names: string[];
  usage: string;
  description: string;
  run(seat: CommandSeat, args: string[]): string | void | Promise<string | void>;
}

export interface CommandSeat {
  state: ChatState;
  run(action: (actions: ChatActions) => void | Promise<void>): Promise<void>;
  flash(message: string): void;
  commands: Command[];
}

export interface FeatureContext {
  renderMessage(kind: EventKind, renderer: MessageRenderer): void;
  addRail(id: string, render: (props: RailProps) => ReactNode): void;
  addSidebarSection(id: string, render: (props: SectionProps) => ReactNode): void;
  addCommand(command: Command): void;
}

export interface Feature {
  id: string;
  register(context: FeatureContext): void;
}

/** Runtime registration helpers; every add returns a rollback disposer. */
export interface Registrar {
  renderMessage(kind: EventKind, renderer: MessageRenderer): () => void;
  addRail(id: string, render: (props: RailProps) => ReactNode): () => void;
  addSidebarSection(id: string, render: (props: SectionProps) => ReactNode): () => void;
  addCommand(command: Command): () => void;
}

/**
 * Host handed to a native plugin module's {@code register(host)} export:
 * React (single shared instance) plus the registrar and convenience seats.
 */
export interface PluginHost {
  React: typeof import("react");
  api: typeof import("./api").api;
  openPlugin(name: string, url: string): void;
  flash(message: string): void;
  registrar: Registrar;
}

interface RegistryState {
  messages: Partial<Record<EventKind, MessageRenderer>>;
  rails: Map<string, (props: RailProps) => ReactNode>;
  sections: Map<string, (props: SectionProps) => ReactNode>;
  commands: Command[];
}

interface RegistryRef {
  state: RegistryState;
  notify(): void;
}

const RegistryContext = createContext<RegistryRef | null>(null);

function buildContext(state: RegistryState): FeatureContext {
  return {
    renderMessage(kind, renderer) {
      state.messages[kind] = renderer;
    },
    addRail(id, render) {
      state.rails.set(id, render);
    },
    addSidebarSection(id, render) {
      state.sections.set(id, render);
    },
    addCommand(command) {
      state.commands.push(command);
    },
  };
}

export function SlotRoot({ features, children }: { features: Feature[]; children: ReactNode }) {
  const stateRef = useRef<RegistryState | null>(null);
  if (!stateRef.current) {
    stateRef.current = { messages: {}, rails: new Map(), sections: new Map(), commands: [] };
    for (const feature of features) feature.register(buildContext(stateRef.current));
  }
  const [tick, setTick] = useState(0);
  const notify = useCallback(() => setTick((tick) => tick + 1), []);
  const value = useMemo<RegistryRef>(() => ({ state: stateRef.current!, notify }), [tick, notify]);
  return <RegistryContext.Provider value={value}>{children}</RegistryContext.Provider>;
}

function useRegistry(): RegistryRef {
  const registry = useContext(RegistryContext);
  if (!registry) {
    throw new Error("useSlots must be used inside SlotRoot");
  }
  return registry;
}

export function useSlots(): {
  messageRenderer: (kind: EventKind) => MessageRenderer | undefined;
  rails: Array<{ id: string; render: (props: RailProps) => ReactNode }>;
  sidebarSections: Array<{ id: string; render: (props: SectionProps) => ReactNode }>;
  commands: Command[];
} {
  const { state } = useRegistry();
  return {
    messageRenderer: (kind) => state.messages[kind],
    rails: [...state.rails.entries()].map(([id, render]) => ({ id, render })),
    sidebarSections: [...state.sections.entries()].map(([id, render]) => ({ id, render })),
    commands: [...state.commands],
  };
}

/** Runtime registrar for plugin modules; every add rolls back with its disposer. */
export function useRegistrar(): Registrar {
  const { state, notify } = useRegistry();
  const mutate = <T,>(fn: () => T): T => {
    const result = fn();
    notify();
    return result;
  };
  return {
    renderMessage(kind, renderer) {
      mutate(() => {
        state.messages[kind] = renderer;
      });
      return () =>
        mutate(() => {
          if (state.messages[kind] === renderer) delete state.messages[kind];
        });
    },
    addRail(id, render) {
      mutate(() => {
        state.rails.set(id, render);
      });
      return () =>
        mutate(() => {
          if (state.rails.get(id) === render) state.rails.delete(id);
        });
    },
    addSidebarSection(id, render) {
      mutate(() => {
        state.sections.set(id, render);
      });
      return () =>
        mutate(() => {
          if (state.sections.get(id) === render) state.sections.delete(id);
        });
    },
    addCommand(command) {
      mutate(() => {
        state.commands.push(command);
      });
      return () =>
        mutate(() => {
          state.commands = state.commands.filter((candidate) => candidate !== command);
        });
    },
  };
}
