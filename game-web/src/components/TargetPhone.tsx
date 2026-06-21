import { Check, Smartphone } from "lucide-react";

export function TargetPhone({ cleared }: { cleared: boolean }) {
  return (
    <div className="target-phone" title={cleared ? "Cleared phone target" : "Phone target"}>
      {cleared ? <Check size={22} /> : <Smartphone size={22} />}
    </div>
  );
}
