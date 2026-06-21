import { useEffect, useMemo, useState } from "react";

export interface DeviceTelemetry {
  readonly cpuActivityPercent: number;
  readonly cpuCores: number | null;
  readonly memoryGb: number | null;
  readonly batteryPercent: number | null;
  readonly charging: boolean | null;
  readonly networkType: string;
  readonly online: boolean;
  readonly viewport: string;
  readonly screen: string;
  readonly platform: string;
  readonly source: "browser";
  readonly updatedAt: string;
}

type NavigatorWithDeviceSignals = Navigator & {
  readonly connection?: { readonly effectiveType?: string; readonly downlink?: number };
  readonly deviceMemory?: number;
  readonly userAgentData?: { readonly platform?: string; readonly mobile?: boolean };
  readonly getBattery?: () => Promise<{
    readonly level: number;
    readonly charging: boolean;
  }>;
};

interface DeviceSignals {
  readonly batteryPercent: number | null;
  readonly charging: boolean | null;
  readonly networkType: string;
  readonly online: boolean;
  readonly viewport: string;
  readonly screen: string;
}

const nav = () => navigator as NavigatorWithDeviceSignals;

const readNetworkType = () => {
  const connection = nav().connection;
  if (!connection) return navigator.onLine ? "online" : "offline";
  const downlink = connection.downlink ? ` · ${connection.downlink} Mbps` : "";
  return `${connection.effectiveType ?? "network"}${downlink}`;
};

const readSignals = (batteryPercent: number | null, charging: boolean | null): DeviceSignals => ({
  batteryPercent,
  charging,
  networkType: readNetworkType(),
  online: navigator.onLine,
  viewport: `${window.innerWidth} x ${window.innerHeight}`,
  screen: `${window.screen.width} x ${window.screen.height}`,
});

const readPlatform = () => {
  const info = nav().userAgentData;
  if (info?.platform) return info.mobile ? `${info.platform} mobile` : info.platform;
  return navigator.platform || "Browser device";
};

export const useDeviceTelemetry = (): DeviceTelemetry => {
  const [cpuActivityPercent, setCpuActivityPercent] = useState(0);
  const [batteryPercent, setBatteryPercent] = useState<number | null>(null);
  const [charging, setCharging] = useState<boolean | null>(null);
  const [signals, setSignals] = useState<DeviceSignals>(() => readSignals(null, null));

  useEffect(() => {
    const intervalMs = 250;
    let expected = performance.now() + intervalMs;
    const timer = window.setInterval(() => {
      const now = performance.now();
      const lag = Math.max(0, now - expected);
      expected = now + intervalMs;
      const sample = Math.min(100, Math.round((lag / intervalMs) * 100));
      setCpuActivityPercent((previous) => Math.round(previous * 0.76 + sample * 0.24));
    }, intervalMs);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const getBattery = nav().getBattery;
    if (!getBattery) return;
    getBattery().then((battery) => {
      if (cancelled) return;
      setBatteryPercent(Math.round(battery.level * 100));
      setCharging(battery.charging);
    }).catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const refresh = () => setSignals(readSignals(batteryPercent, charging));
    refresh();
    window.addEventListener("resize", refresh);
    window.addEventListener("online", refresh);
    window.addEventListener("offline", refresh);
    const timer = window.setInterval(refresh, 5000);
    return () => {
      window.removeEventListener("resize", refresh);
      window.removeEventListener("online", refresh);
      window.removeEventListener("offline", refresh);
      window.clearInterval(timer);
    };
  }, [batteryPercent, charging]);

  return useMemo(() => ({
    cpuActivityPercent,
    cpuCores: navigator.hardwareConcurrency || null,
    memoryGb: nav().deviceMemory ?? null,
    batteryPercent: signals.batteryPercent,
    charging: signals.charging,
    networkType: signals.networkType,
    online: signals.online,
    viewport: signals.viewport,
    screen: signals.screen,
    platform: readPlatform(),
    source: "browser",
    updatedAt: new Date().toLocaleTimeString(),
  }), [cpuActivityPercent, signals]);
};
