import { Activity, Camera, Cpu, ShieldAlert } from 'lucide-react';
import type { ReactNode } from 'react';

// Placeholder shell; real layout lands in features/ and components/layout in the next steps.
export default function App() {
  return (
    <div className="flex h-full">
      <aside className="flex w-56 flex-col gap-1 border-r bg-kraken-panel p-3">
        <div className="mb-4 font-semibold text-accent-cyan">JVM Leak Inspector</div>
        <NavItem icon={<Cpu size={16} />} label="Processes" active />
        <NavItem icon={<Activity size={16} />} label="Live" />
        <NavItem icon={<Camera size={16} />} label="Snapshots" />
        <NavItem icon={<ShieldAlert size={16} />} label="Inspections" />
      </aside>
      <main className="flex-1 p-6">
        <div className="rounded-lg border bg-kraken-card p-4 shadow-panel">
          <span className="text-mem-healthy">healthy</span> ·{' '}
          <span className="text-mem-suspect">suspect</span> ·{' '}
          <span className="text-mem-critical">critical</span> ·{' '}
          <span className="text-mem-meta">GC / Jakarta / Hazelcast</span>
        </div>
      </main>
    </div>
  );
}

function NavItem({ icon, label, active = false }: { icon: ReactNode; label: string; active?: boolean }) {
  return (
    <button
      type="button"
      className={
        'flex items-center gap-2 rounded px-2 py-1.5 text-left hover:bg-kraken-hover ' +
        (active ? 'bg-kraken-card text-accent-cyan' : 'text-kraken-muted')
      }
    >
      {icon}
      {label}
    </button>
  );
}
