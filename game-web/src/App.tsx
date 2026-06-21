import { useMemo, useState } from "react";
import {
  Box,
  ChartNoAxesColumnIncreasing,
  Grid3X3,
  Home,
  Send,
} from "lucide-react";
import "./styles.css";
import { Challenge } from "./pages/Challenge";
import { CustomGrid } from "./pages/CustomGrid";
import { HomePage } from "./pages/Home";
import { Leaderboard } from "./pages/Leaderboard";
import { ResultUpload } from "./pages/ResultUpload";
import { createInitialGameState, type GameState } from "./game/board";
import { calculateScore } from "./game/scoring";
import { getBenchmarkMap, loadSubmissions, saveSubmission, type Submission } from "./storage";

type Page = "home" | "challenge" | "custom" | "leaderboard" | "upload";

const navItems: Array<{ page: Page; label: string; icon: typeof Home }> = [
  { page: "home", label: "Home", icon: Home },
  { page: "challenge", label: "Challenge", icon: Box },
  { page: "custom", label: "Custom Grid", icon: Grid3X3 },
  { page: "leaderboard", label: "Leaderboard", icon: ChartNoAxesColumnIncreasing },
  { page: "upload", label: "Upload", icon: Send },
];

function App() {
  const [page, setPage] = useState<Page>("home");
  const [gameState, setGameState] = useState<GameState>(() => createInitialGameState());
  const [submissions, setSubmissions] = useState<Submission[]>(() => loadSubmissions());

  const score = useMemo(
    () => calculateScore(gameState, getBenchmarkMap(gameState)),
    [gameState],
  );

  const handleSaveSubmission = (submission: Submission) => {
    const next = saveSubmission(submission);
    setSubmissions(next);
    setPage("leaderboard");
  };

  return (
    <div className="app-shell">
      <aside className="rail" aria-label="Primary">
        <div className="rail-mark">
          <span>T</span>
        </div>
        <nav className="rail-nav">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                className={page === item.page ? "rail-button active" : "rail-button"}
                key={item.page}
                onClick={() => setPage(item.page)}
                type="button"
                title={item.label}
                aria-label={item.label}
              >
                <Icon size={20} />
              </button>
            );
          })}
        </nav>
      </aside>

      <main className="app-main">
        {page === "home" && (
          <HomePage
            score={score}
            submissions={submissions}
            onStart={() => setPage("challenge")}
            onCustom={() => setPage("custom")}
            onLeaderboard={() => setPage("leaderboard")}
          />
        )}
        {page === "challenge" && (
          <Challenge
            gameState={gameState}
            score={score}
            onGameStateChange={setGameState}
            onUpload={() => setPage("upload")}
          />
        )}
        {page === "custom" && <CustomGrid />}
        {page === "leaderboard" && <Leaderboard submissions={submissions} />}
        {page === "upload" && (
          <ResultUpload
            gameState={gameState}
            score={score}
            onSave={handleSaveSubmission}
            onBack={() => setPage("challenge")}
          />
        )}
      </main>
    </div>
  );
}

export default App;
