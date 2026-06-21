import { Activity, BatteryCharging, Cpu, Gauge, MemoryStick, Smartphone, Wifi } from "lucide-react";
import type { DeviceTelemetry } from "../deviceTelemetry";

const valueOrDash = (value: string | number | null) =>
  value === null || value === "" ? "—" : value;

const batteryText = (telemetry: DeviceTelemetry) => {
  if (telemetry.batteryPercent === null) return "—";
  const charging = telemetry.charging ? " charging" : "";
  return `${telemetry.batteryPercent}%${charging}`;
};

export function DeviceTelemetryCard({ telemetry }: { telemetry: DeviceTelemetry }) {
  return (
    <section className="telemetry-card">
      <div>
        <span className="section-kicker">Phone Snapshot</span>
        <h3>Live device data</h3>
      </div>
      <div className="telemetry-grid">
        <div className="telemetry-item">
          <Gauge size={17} />
          <span>CPU activity</span>
          <strong>{telemetry.cpuActivityPercent}%</strong>
        </div>
        <div className="telemetry-item">
          <Cpu size={17} />
          <span>CPU cores</span>
          <strong>{valueOrDash(telemetry.cpuCores)}</strong>
        </div>
        <div className="telemetry-item">
          <MemoryStick size={17} />
          <span>Memory</span>
          <strong>{telemetry.memoryGb ? `${telemetry.memoryGb} GB` : "—"}</strong>
        </div>
        <div className="telemetry-item">
          <BatteryCharging size={17} />
          <span>Battery</span>
          <strong>{batteryText(telemetry)}</strong>
        </div>
        <div className="telemetry-item">
          <Wifi size={17} />
          <span>Network</span>
          <strong>{telemetry.online ? telemetry.networkType : "offline"}</strong>
        </div>
        <div className="telemetry-item">
          <Smartphone size={17} />
          <span>Viewport</span>
          <strong>{telemetry.viewport}</strong>
        </div>
      </div>
      <p className="telemetry-note">
        <Activity size={16} /> Browser telemetry · {telemetry.updatedAt}
      </p>
    </section>
  );
}
