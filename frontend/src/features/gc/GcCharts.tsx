import type { EChartsCoreOption } from 'echarts/core';
import { useMemo } from 'react';
import type { GcAnalysisDto, GcPauseCategory, GcPauseDto } from '@/api/types';
import { EChart } from '@/components/charts/EChart';
import { palette, withAlpha } from '@/theme/palette';

export const CATEGORY_COLOR: Record<GcPauseCategory, string> = {
  YOUNG: palette.emerald,
  MIXED: palette.cyan,
  FULL: palette.coral,
  HUMONGOUS: palette.amber,
  REMARK: palette.violet,
  CLEANUP: '#7c8cff',
  CONCURRENT_PAUSE: '#5ad1b0',
  OTHER: palette.subtle,
};

const CATEGORY_LABEL: Record<GcPauseCategory, string> = {
  YOUNG: 'Young',
  MIXED: 'Mixed',
  FULL: 'Full',
  HUMONGOUS: 'Humongous',
  REMARK: 'Remark',
  CLEANUP: 'Cleanup',
  CONCURRENT_PAUSE: 'Concurrent (STW)',
  OTHER: 'Other',
};

const axisLine = { lineStyle: { color: palette.border } };
const splitLine = { lineStyle: { color: withAlpha(palette.border, 0.6) } };
const axisLabel = { color: palette.muted, fontSize: 11 };

function seconds(ms: number): string {
  return ms >= 60_000 ? `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s` : `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)}s`;
}

function base(yName: string): EChartsCoreOption {
  return {
    backgroundColor: 'transparent',
    animation: false,
    textStyle: { color: palette.muted, fontFamily: 'Inter, Segoe UI, system-ui, sans-serif' },
    legend: { top: 0, left: 56, itemWidth: 12, itemHeight: 8, textStyle: { color: palette.muted, fontSize: 11 }, inactiveColor: palette.subtle },
    grid: { left: 64, right: 24, top: 32, bottom: 48 },
    xAxis: { type: 'value', axisLine, splitLine, axisLabel: { ...axisLabel, formatter: seconds }, scale: true },
    yAxis: {
      type: 'value', name: yName, nameLocation: 'middle', nameGap: 44, nameRotate: 90,
      nameTextStyle: { color: palette.muted, fontSize: 10 }, axisLine, splitLine, axisLabel,
    },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', xAxisIndex: 0, height: 14, bottom: 8, borderColor: palette.border }],
  };
}

const tooltipStyle = { backgroundColor: palette.card, borderColor: palette.border, textStyle: { color: palette.text, fontSize: 11 } };

/** Pause Duration Scatter: each stop-the-world pause at its uptime, coloured by Young / Mixed / Full / Humongous. */
export function PauseScatter({ pauses }: { pauses: GcPauseDto[] }) {
  const option = useMemo<EChartsCoreOption>(() => {
    const byCategory = new Map<GcPauseCategory, GcPauseDto[]>();
    pauses.forEach((p) => byCategory.set(p.category, [...(byCategory.get(p.category) ?? []), p]));
    return {
      ...base('pause, ms'),
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        formatter: (item: { data: { pause: GcPauseDto } }) => {
          const p = item.data.pause;
          return `<b>${p.name}</b> #${p.gcId}<br/>${p.cause ?? ''}<br/>${p.pauseMs.toFixed(2)} ms @ ${seconds(p.uptimeMs)}`
            + (p.heapBeforeMb != null ? `<br/>${p.heapBeforeMb.toFixed(0)} → ${p.heapAfterMb?.toFixed(0)} MB` : '');
        },
      },
      series: [...byCategory.entries()].map(([category, list]) => ({
        type: 'scatter',
        name: CATEGORY_LABEL[category],
        large: list.length > 3000,
        symbolSize: category === 'FULL' ? 9 : 6,
        itemStyle: { color: CATEGORY_COLOR[category], opacity: 0.85 },
        data: list.map((p) => ({ value: [p.uptimeMs, p.pauseMs], pause: p })),
      })),
    };
  }, [pauses]);
  return <EChart option={option} className="h-72 w-full" />;
}

/** Heap Before vs After GC: the before → after drop of every collection; a rising "after" line is the live set growing. */
export function HeapChart({ pauses }: { pauses: GcPauseDto[] }) {
  const option = useMemo<EChartsCoreOption>(() => {
    const withHeap = pauses.filter((p) => p.heapBeforeMb != null && p.heapAfterMb != null);
    return {
      ...base('heap, MB'),
      tooltip: { ...tooltipStyle, trigger: 'axis' },
      series: [
        {
          type: 'line', name: 'Before GC', showSymbol: false, step: false, lineStyle: { width: 1, color: palette.amber },
          itemStyle: { color: palette.amber }, data: withHeap.map((p) => [p.uptimeMs, p.heapBeforeMb]),
        },
        {
          type: 'line', name: 'After GC', showSymbol: false, lineStyle: { width: 2, color: palette.cyan },
          itemStyle: { color: palette.cyan }, areaStyle: { color: withAlpha(palette.cyan, 0.08) },
          data: withHeap.map((p) => [p.uptimeMs, p.heapAfterMb]),
        },
        {
          type: 'line', name: 'Heap size', showSymbol: false, lineStyle: { width: 1, type: 'dashed', color: palette.subtle },
          itemStyle: { color: palette.subtle }, data: withHeap.filter((p) => p.heapTotalMb != null).map((p) => [p.uptimeMs, p.heapTotalMb]),
        },
      ],
    };
  }, [pauses]);
  return <EChart option={option} className="h-72 w-full" />;
}

/** Allocation vs Promotion Rate per time window, MB/s. */
export function RateChart({ analysis }: { analysis: GcAnalysisDto }) {
  const option = useMemo<EChartsCoreOption>(() => ({
    ...base('MB/s'),
    tooltip: { ...tooltipStyle, trigger: 'axis' },
    series: [
      {
        type: 'line', name: 'Allocation', showSymbol: false, smooth: true, lineStyle: { width: 2, color: palette.emerald },
        itemStyle: { color: palette.emerald }, areaStyle: { color: withAlpha(palette.emerald, 0.1) },
        data: analysis.rates.map((r) => [r.uptimeMs, Number(r.allocationMbPerSec.toFixed(2))]),
      },
      {
        type: 'line', name: 'Promotion', showSymbol: false, smooth: true, lineStyle: { width: 2, color: palette.violet },
        itemStyle: { color: palette.violet }, connectNulls: true,
        data: analysis.rates.map((r) => [r.uptimeMs, r.promotionMbPerSec == null ? null : Number(r.promotionMbPerSec.toFixed(2))]),
      },
    ],
  }), [analysis.rates]);
  return <EChart option={option} className="h-72 w-full" />;
}
