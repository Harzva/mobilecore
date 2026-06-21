import { useMemo, useState } from "react";
import { Download, Save, Upload } from "lucide-react";
import { sampleBoard } from "../data/sampleBoard";

const STORAGE_KEY = "tuima-push-custom-board-v0.1";

export function CustomGrid() {
  const [jsonText, setJsonText] = useState(() => {
    return localStorage.getItem(STORAGE_KEY) ?? JSON.stringify(sampleBoard, null, 2);
  });
  const parsed = useMemo(() => {
    try {
      return { ok: true as const, value: JSON.parse(jsonText) };
    } catch (error) {
      return { ok: false as const, error: error instanceof Error ? error.message : "Invalid JSON" };
    }
  }, [jsonText]);

  const save = () => {
    if (!parsed.ok) return;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed.value, null, 2));
  };

  const exportJson = () => {
    const blob = new Blob([jsonText], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "custom-board.json";
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="content-grid">
      <section className="surface">
        <span className="section-kicker">Custom Grid</span>
        <h2 className="section-title">Build your own benchmark puzzle</h2>
        <p className="section-copy">
          MVP editor supports JSON import/export and local save. Drag placement can come later without changing the board schema.
        </p>
        <div className="form-grid">
          <textarea value={jsonText} onChange={(event) => setJsonText(event.target.value)} spellCheck={false} />
          <div className="inline-actions">
            <button className="primary-button" disabled={!parsed.ok} onClick={save} type="button">
              <Save size={18} /> Save Local
            </button>
            <button className="secondary-button" onClick={exportJson} type="button">
              <Download size={18} /> Export JSON
            </button>
            <label className="ghost-button">
              <Upload size={18} /> Import JSON
              <input
                accept="application/json"
                hidden
                type="file"
                onChange={async (event) => {
                  const file = event.target.files?.[0];
                  if (file) setJsonText(await file.text());
                }}
              />
            </label>
          </div>
          {!parsed.ok && <p className="privacy-note">JSON error: {parsed.error}</p>}
        </div>
      </section>
      <aside className="tool-panel">
        <span className="section-kicker">Tile Palette</span>
        <h3>Palette</h3>
        <div className="palette">
          {["0.5B Model", "1.5B Model", "3B Model", "7B Model", "14B Model", "Target Phone", "Wall Block", "Bonus Score", "Speed Boost", "Upload Badge"].map((item) => (
            <span className="palette-chip" key={item}>
              {item}
            </span>
          ))}
        </div>
        <p className="section-copy">
          Saved boards stay in localStorage. Supabase custom board upload is intentionally left as a safe placeholder for the next integration step.
        </p>
      </aside>
    </div>
  );
}
