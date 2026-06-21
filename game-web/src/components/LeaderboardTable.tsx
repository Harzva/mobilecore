import type { Submission } from "../storage";

export function LeaderboardTable({ submissions }: { submissions: Submission[] }) {
  return (
    <div className="leaderboard-list">
      {submissions.map((submission, index) => (
        <article className="leaderboard-row" key={`${submission.anonymous_id}-${submission.created_at}`}>
          <div className="rank-badge">{index + 1}</div>
          <div>
            <strong>{submission.player_name}</strong>
            <div className="section-copy">
              {submission.device.device_class} · best {submission.result.best_model}
            </div>
          </div>
          <div>
            <strong>{submission.result.total_score.toLocaleString()}</strong>
            <div className="section-copy">{submission.result.avg_decode_tok_s} tok/s</div>
          </div>
        </article>
      ))}
    </div>
  );
}
