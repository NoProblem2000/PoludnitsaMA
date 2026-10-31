import { type DragEvent } from 'react';
import { SnapshotDiffView } from '@/features/diff/SnapshotDiffView';
import { DominatorTreeView } from '@/features/heap/DominatorTreeView';
import { HeuristicsAdvisorView } from '@/features/heuristics/HeuristicsAdvisorView';
import { LiveDashboardView } from '@/features/live/LiveDashboardView';
import { OqlConsoleView } from '@/features/oql/OqlConsoleView';
import { ReportModal } from '@/features/reports/ReportModal';
import { useInspectorStore } from '@/store/useInspectorStore';
import { Sidebar } from './Sidebar';
import { SnapshotTimeline } from './SnapshotTimeline';
import { StatusBar } from './StatusBar';
import { ToastStack } from './ToastStack';
import { TopToolbar } from './TopToolbar';

function draggingFiles(event: DragEvent): boolean {
  return event.dataTransfer.types.includes('Files');
}

/** Toolbar on top, navigation on the left, the active view, the snapshot strip at the bottom. */
export function AppShell() {
  const view = useInspectorStore((s) => s.view);
  const modalOpen = useInspectorStore((s) => s.reportModalOpen);
  const setReportModalOpen = useInspectorStore((s) => s.setReportModalOpen);
  return (
    <div
      className="flex h-full flex-col bg-kraken-bg"
      // A report file dragged onto the window opens the import dialog with its drop zone.
      onDragEnter={(event) => {
        if (draggingFiles(event) && !modalOpen) {
          setReportModalOpen(true);
        }
      }}
      onDragOver={(event) => draggingFiles(event) && event.preventDefault()}
      onDrop={(event) => draggingFiles(event) && event.preventDefault()}
    >
      <TopToolbar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <main className="min-w-0 flex-1 overflow-y-auto">
          {view === 'live' && <LiveDashboardView />}
          {view === 'heuristics' && <HeuristicsAdvisorView />}
          {view === 'dominators' && <DominatorTreeView />}
          {view === 'diff' && <SnapshotDiffView />}
          {view === 'oql' && <OqlConsoleView />}
        </main>
      </div>
      <SnapshotTimeline />
      <StatusBar />
      <ReportModal />
      <ToastStack />
    </div>
  );
}
