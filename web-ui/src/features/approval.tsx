import type { Feature, RailProps } from "../slots";

// approval/ask-user feature: contributes the rail above the conversation
// (dsh ui-approval / ui-user-questions equivalents as slot fillers).

export const approvalFeature: Feature = {
  id: "approval",
  register(context) {
    context.addRail("approval", (props: RailProps) => {
      const { approvals, question } = props;
      if (approvals.length === 0 && !question) {
        return null;
      }
      return (
        <div id="approval-rail">
          {approvals.map((approval) => (
            <div className="approval-card" key={approval.id}>
              <div className="approval-head">
                <span className="dot pending" /> waiting for approval
              </div>
              <div className="approval-body">{approval.summary}</div>
              <div className="approval-actions">
                <button type="button" onClick={() => props.onDecide(approval.id, false)}>
                  Reject
                </button>
                <button type="button" className="primary" onClick={() => props.onDecide(approval.id, true)}>
                  Allow once
                </button>
              </div>
            </div>
          ))}
          {question && (
            <div className="approval-card question">
              <div className="approval-head">
                <span className="dot pending" /> the agent asks
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
    });
  },
};
