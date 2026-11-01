import clsx from 'clsx';
import { type ComponentType, type DragEvent, useState } from 'react';
import { DotTraceCpuAndTimelineView } from '@/features/cpu/DotTraceCpuAndTimelineView';
import { SnapshotDiffView } from '@/features/diff/SnapshotDiffView';
import { GcLogAnalyzerView } from '@/features/gc/GcLogAnalyzerView';
import { DominatorTreeView } from '@/features/heap/DominatorTreeView';
import { HeuristicsAdvisorView } from '@/features/heuristics/HeuristicsAdvisorView';
import { LiveDashboardView } from '@/features/live/LiveDashboardView';
import { OqlConsoleView } from '@/features/oql/OqlConsoleView';
import { ReportModal } from '@/features/reports/ReportModal';
import { ThreadsAndContentionView } from '@/features/threads/ThreadsAndContentionView';
import { ViewActiveContext } from '@/hooks/useViewActive';
import { useInspectorStore, type ViewId } from '@/store/useInspectorStore';
import { Sidebar } from './Sidebar';
import { SnapshotTimeline } from './SnapshotTimeline';
import { StatusBar } from './StatusBar';
import { ToastStack } from './ToastStack';
import { TopToolbar } from './TopToolbar';

const VIEWS: readonly (readonly [ViewId, ComponentType])[] = [
  ['live', LiveDashboardView],
  ['heuristics', HeuristicsAdvisorView],
  ['dominators', DominatorTreeView],
  ['diff', SnapshotDiffView],
  ['oql', OqlConsoleView],
  ['cpu', DotTraceCpuAndTimelineView],
  ['threads', ThreadsAndContentionView],
  ['gc', GcLogAnalyzerView],
];

function draggingFiles(event: DragEvent): boolean {
  return event.dataTransfer.types.includes('Files');
}

/** Toolbar on top, navigation on the left, the active view, the snapshot strip at the bottom. */
export function AppShell() {
  const view = useInspectorStore((s) => s.view);
  // a view is mounted on its first visit and kept afterwards
  const [visited, setVisited] = useState<ReadonlySet<ViewId>>(() => new Set([view]));
  if (!visited.has(view)) {
    setVisited(new Set(visited).add(view));
  }
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
        <main className="relative min-w-0 flex-1">
          {VIEWS.filter(([id]) => visited.has(id)).map(([id, View]) => (
            // visibility instead of unmounting: local state and the scroll position survive a switch
            <div
              key={id}
              className={clsx('absolute inset-0 overflow-y-auto', id !== view && 'invisible')}
              aria-hidden={id !== view}
            >
              <ViewActiveContext.Provider value={id === view}>
                <View />
              </ViewActiveContext.Provider>
            </div>
          ))}
        </main>
      </div>
      <SnapshotTimeline />
      <StatusBar />
      <ReportModal />
      <ToastStack />
    </div>
  );
}
