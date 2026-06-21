import type { Submission } from "../storage";

const formatSpeed = (speed: number) =>
  speed.toLocaleString(undefined, { maximumFractionDigits: 1, minimumFractionDigits: 1 });

const telemetryText = (submission: Submission) => {
  const telemetry = submission.telemetry;
  if (!telemetry) return `${submission.device.chip_class} · ${submission.device.ram_class}`;
  const battery = telemetry.battery_percent === null ? "battery —" : `${telemetry.battery_percent}% battery`;
  return `CPU ${telemetry.cpu_activity_percent}% · ${telemetry.cpu_cores ?? "—"} cores · ${battery}`;
};

export function LeaderboardTable({ submissions }: { submissions: Submission[] }) {
  return (
    <div className="leaderboard-list">
      {submissions.map((submission, index) => (
        <article className="leaderboard-row" key={`${submission.anonymous_id}-${submission.created_at}`}>
          <div className="rank-badge">{index + 1}</div>
          <div>
            <strong>{submission.player_name}</strong>
            <div className="section-copy">
              {submission.device.device_class} · {submission.result.best_model}
            </div>
            <div className="telemetry-mini">{telemetryText(submission)}</div>
            {submission.benchmark_signature?.verified && <div className="telemetry-mini">MobileCore signature verified</div>}
          </div>
          <div className="speed-cell">
            <strong>{formatSpeed(submission.result.avg_decode_tok_s)} tok/s</strong>
            <div className="section-copy">{submission.runtime.backend}</div>
          </div>
        </article>
      ))}
    </div>
  );
}
