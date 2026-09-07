import { useState } from "react";
import type { Feature, RailProps } from "../slots";

// approval/ask-user feature: contributes the rail above the conversation
// (dsh ui-approval / ui-user-questions equivalents as slot fillers).
//
// A3: when several delegations wait at once, the rail collapses into a
// summary card (count + originating agents) and expands on demand.

function ApprovalRail(props: RailProps) {
  const { approvals, question } = props;
  const many = approvals.length > 2;
  const [expanded, setExpanded] = useState(false);
  if (approvals.length === 0 && !question) {
    return null;
  }
  const agents = [...new Set(approvals.map((approval) => approval.agent).filter(Boolean))] as string[];
  return (
    <div id="approval-rail">
      {many && !expanded && (
        <div className="approval-card summary">
          <div className="approval-head">
            <span className="dot pending" /> {approvals.length} requests waiting
            {agents.length > 0 && (
              <span className="agent-tag">
                {agents.length} agent{agents.length === 1 ? "" : "s"}: {agents.join(", ")}
              </span>
            )}
          </div>
          <div className="approval-actions">
            <button type="button" onClick={() => setExpanded(true)}>
              Review all ({approvals.length})
            </button>
            <button type="button" className="primary" onClick={() => props.onDecideAll(true)}>
              Allow all
            </button>
            <button type="button" onClick={() => props.onDecideAll(false)}>
              Reject all
            </button>
          </div>
        </div>
      )}
      {(expanded || !many) &&
        approvals.map((approval) => (
          <div className="approval-card" key={approval.id}>
            <div className="approval-head">
              <span className="dot pending" /> waiting for approval
              {approval.agent && <span className="agent-tag">{approval.agent}</span>}
            </div>
            <div className="approval-body">{approval.summary}</div>
            <div className="approval-actions">
              <button type="button" onClick={() => props.onDecide(approval.id, false)}>
                Reject
              </button>
              <button
                type="button"
                className="primary"
                onClick={() => props.onDecide(approval.id, true)}
              >
                Allow once
              </button>
            </div>
          </div>
        ))}
      {question && (
        <div className="approval-card question">
          <div className="approval-head">
            <span className="dot pending" /> the agent asks
            {question.agent && <span className="agent-tag">{question.agent}</span>}
          </div>
          <div className="approval-body">{question.text}</div>
          <form
            className="question-form"
            onSubmit={(e) => {
              e.preventDefault();
              props.onAnswerAsk();
            }}
          >
            <input
              value={props.qInput}
              onChange={(e) => props.onQInput(e.target.value)}
              placeholder="your answer…"
              autoFocus
            />
            <button type="submit">Send</button>
          </form>
        </div>
      )}
    </div>
  );
}

export const approvalFeature: Feature = {
  id: "approval",
  register(context) {
    context.addRail("approval", (railProps: RailProps) => {
      return <ApprovalRail {...railProps} />;
    });
  },
};
