package io.majo.harness.llm;

/** Message roles of the model protocol, mirroring OpenAI-style roles. */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** A tool execution result, referencing the assistant's tool call id. */
    TOOL
}
