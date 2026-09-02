package io.majo.harness.subprocess;

/**
 * The subprocess provider seam (Service Definition): implementations execute
 * {@link Command argv} against an execution world and capture the result. The
 * local implementation ships in this module; sandboxed/remote providers
 * implement the same interface so policy and tool consumers never fork.
 *
 * <p>Providers must enforce the command timeout and throw
 * {@link SubprocessException} for every start or timeout failure.
 */
public interface SubprocessProvider {

    ProcessResult run(Command command);
}
