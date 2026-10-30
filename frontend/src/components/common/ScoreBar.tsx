import { scoreColor, withAlpha } from '@/theme/palette';

/** Leak probability 0..100 as a bar from #00c853 (healthy) through #ffab00 to #ff3d71 (critical). */
export function ScoreBar({ score }: { score: number }) {
  const color = scoreColor(score);
  return (
    <div className="flex w-full items-center gap-2" title={`Вероятность утечки: ${score} из 100`}>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full" style={{ backgroundColor: withAlpha(color, 0.15) }}>
        <div className="h-full rounded-full transition-[width] duration-500" style={{ width: `${score}%`, backgroundColor: color }} />
      </div>
      <span className="w-8 text-right font-mono text-xs tabular-nums" style={{ color }}>
        {score}
      </span>
    </div>
  );
}
