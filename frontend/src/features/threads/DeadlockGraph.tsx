import { Skull } from 'lucide-react';
import type { DeadlockDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { palette } from '@/theme/palette';
import { frameText, shortLock } from './threadFormat';

const WIDTH = 560;
const NODE_W = 170;
const NODE_H = 44;

/**
 * A deadlock as a ring: every thread is a node, an arrow goes from a thread to the thread that holds the lock it
 * waits for, labelled with that lock. Below it, who holds what and where each thread waits.
 */
export function DeadlockGraph({ deadlock }: { deadlock: DeadlockDto }) {
  const nodes = deadlock.cycle;
  const n = nodes.length;
  const radius = n <= 2 ? 150 : Math.min(220, 90 + n * 30);
  const height = n <= 2 ? 200 : radius * 2 + NODE_H + 40;
  const cx = WIDTH / 2;
  const cy = height / 2;
  const points = nodes.map((_, i) => {
    const angle = n <= 2 ? Math.PI * i : -Math.PI / 2 + (2 * Math.PI * i) / n;
    return { x: cx + radius * Math.cos(angle), y: cy + (n <= 2 ? 0 : radius * Math.sin(angle)) };
  });

  return (
    <div className="rounded-md border border-accent-coral/60 bg-accent-coral/5 p-3">
      <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-accent-coral">
        <Skull size={16} />
        DEADLOCK: {n} потока ждут друг друга
        <Badge color={palette.coral}>{deadlock.kind}</Badge>
      </div>
      <svg viewBox={`0 0 ${WIDTH} ${height}`} className="mx-auto w-full max-w-2xl" role="img" aria-label="Граф взаимной блокировки">
        <defs>
          <marker id="deadlock-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill={palette.coral} />
          </marker>
        </defs>
        {nodes.map((node, i) => {
          const j = nodes.findIndex((m) => m.threadId === node.heldByThreadId && node.heldByThreadId >= 0);
          const to = points[j >= 0 ? j : (i + 1) % n];
          const from = points[i];
          if (!from || !to) {
            return null;
          }
          // bend every edge to the same side so the two directions of a 2-cycle do not overlap
          const mx = (from.x + to.x) / 2;
          const my = (from.y + to.y) / 2;
          const dx = to.x - from.x;
          const dy = to.y - from.y;
          const length = Math.hypot(dx, dy) || 1;
          const bend = n <= 2 ? 70 : 40;
          const qx = mx + (-dy / length) * bend;
          const qy = my + (dx / length) * bend;
          const shrink = (px: number, py: number, tx: number, ty: number) => {
            const l = Math.hypot(tx - px, ty - py) || 1;
            return { x: px + ((tx - px) / l) * (NODE_W / 2.4), y: py + ((ty - py) / l) * (NODE_H / 1.2) };
          };
          const start = shrink(from.x, from.y, qx, qy);
          const end = shrink(to.x, to.y, qx, qy);
          return (
            <g key={`edge-${node.threadId}-${node.threadName}`}>
              <path
                d={`M${start.x},${start.y} Q${qx},${qy} ${end.x},${end.y}`}
                fill="none"
                stroke={palette.coral}
                strokeWidth={1.8}
                markerEnd="url(#deadlock-arrow)"
              />
              {/* the middle of a quadratic curve: (P0 + 2·Q + P2) / 4 */}
              <text
                x={(start.x + 2 * qx + end.x) / 4}
                y={(start.y + 2 * qy + end.y) / 4 + (qy < my ? -6 : 14)}
                textAnchor="middle"
                fontSize={11}
                fill={palette.amber}
              >
                ждёт {shortLock(node.waitingForLock)}
              </text>
            </g>
          );
        })}
        {nodes.map((node, i) => {
          const p = points[i];
          if (!p) {
            return null;
          }
          const top = node.stack[0];
          return (
            <g key={`node-${node.threadId}-${node.threadName}`} transform={`translate(${p.x - NODE_W / 2},${p.y - NODE_H / 2})`}>
              <rect width={NODE_W} height={NODE_H} rx={6} fill={palette.card} stroke={palette.coral} strokeWidth={1.5} />
              <text x={10} y={18} fontSize={12} fontWeight={600} fill={palette.text}>
                {node.threadName.length > 22 ? `${node.threadName.slice(0, 21)}…` : node.threadName}
              </text>
              <text x={10} y={34} fontSize={10} fill={palette.muted} fontFamily="JetBrains Mono, Consolas, monospace">
                {top ? `${top.methodName}:${top.lineNumber}` : node.state}
              </text>
            </g>
          );
        })}
      </svg>
      <ul className="mt-2 space-y-1.5 text-xs">
        {nodes.map((node) => (
          <li key={`text-${node.threadId}-${node.threadName}`} className="rounded border border-kraken-border bg-kraken-panel px-2.5 py-1.5">
            <span className="font-semibold text-kraken-text">{node.threadName}</span>
            <span className="text-kraken-muted"> ждёт </span>
            <code className="text-accent-amber">{node.waitingForLock ?? '?'}</code>
            <span className="text-kraken-muted">, который держит </span>
            <span className="font-semibold text-accent-coral">{node.heldByThreadName ?? '?'}</span>
            {node.heldLocks.length > 0 && (
              <span className="text-kraken-muted"> · сам держит <code className="text-kraken-text">{node.heldLocks.join(', ')}</code></span>
            )}
            {node.stack[0] && <div className="mt-0.5 font-mono text-2xs text-kraken-subtle">at {frameText(node.stack[0])}</div>}
          </li>
        ))}
      </ul>
    </div>
  );
}
