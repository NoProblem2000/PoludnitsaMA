import { DiffView } from '@/features/diff/DiffView';
import { DominatorsView } from '@/features/heap/DominatorsView';
import { HeuristicsView } from '@/features/heuristics/HeuristicsView';
import { LiveDashboardView } from '@/features/live/LiveDashboardView';
import { useInspectorStore } from '@/store/useInspectorStore';
import { Sidebar } from './Sidebar';
import { SnapshotTimeline } from './SnapshotTimeline';
import { ToastStack } from './ToastStack';
import { TopToolbar } from './TopToolbar';

/** Toolbar on top, navigation on the left, the active view, the snapshot strip at the bottom. */
export function AppShell() {
  const view = useInspectorStore((s) => s.view);
  return (
    <div className="flex h-full flex-col bg-kraken-bg">
      <TopToolbar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <main className="min-w-0 flex-1 overflow-y-auto">
          {view === 'live' && <LiveDashboardView />}
          {view === 'heuristics' && <HeuristicsView />}
          {view === 'dominators' && <DominatorsView />}
          {view === 'diff' && <DiffView />}
        </main>
      </div>
      <SnapshotTimeline />
      <ToastStack />
    </div>
  );
}
