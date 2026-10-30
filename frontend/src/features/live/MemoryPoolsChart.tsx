import type { EChartsCoreOption } from 'echarts/core';
import { useMemo } from 'react';
import { EChart } from '@/components/charts/EChart';
import { type ChartPoint, type GcMarker, LIVE_WINDOW_MS } from '@/store/liveSeries';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette, withAlpha } from '@/theme/palette';
import { GENERATION_COLOR } from '@/utils/categories';
import { formatBytes, formatDuration, formatTime } from '@/utils/format';

type Value = [number, number | null];

function line(points: readonly ChartPoint[], pick: (point: ChartPoint) => number | null): Value[] {
  return points.map((point) => [point.t, pick(point)]);
}

interface TooltipParam {
  seriesName: string;
  seriesType: string;
  marker: string;
  value: [number, number, string?, number?];
}

function tooltip(params: TooltipParam[] | TooltipParam): string {
  const list = Array.isArray(params) ? params : [params];
  const first = list[0];
  if (!first) {
    return '';
  }
  const rows = list.map((item) => {
    if (item.seriesType === 'scatter') {
      const [, pause, collector, count] = item.value;
      return `${item.marker}${collector ?? item.seriesName}: <b>${formatDuration(pause)}</b>${count && count > 1 ? ` (${count}×)` : ''}`;
    }
    return `${item.marker}${item.seriesName}: <b>${formatBytes(item.value[1])}</b>`;
  });
  return `<div style="font-size:12px"><div style="color:${palette.muted};margin-bottom:4px">${formatTime(first.value[0])}</div>${rows.join('<br/>')}</div>`;
}

function buildOption(points: readonly ChartPoint[], markers: readonly GcMarker[], windowed: boolean): EChartsCoreOption {
  const end = points[points.length - 1]?.t ?? Date.now();
  const start = windowed ? end - LIVE_WINDOW_MS : points[0]?.t ?? end - LIVE_WINDOW_MS;
  const hasYoung = points.some((point) => point.eden != null);
  const area = (name: string, color: string, data: Value[]) => ({
    name,
    type: 'line',
    stack: 'heap',
    data,
    showSymbol: false,
    connectNulls: false,
    lineStyle: { width: 1.5, color },
    itemStyle: { color },
    areaStyle: { color: withAlpha(color, 0.22) },
    emphasis: { focus: 'series' },
  });
  const plain = (name: string, color: string, data: Value[], extra: Record<string, unknown> = {}) => ({
    name,
    type: 'line',
    data,
    showSymbol: false,
    lineStyle: { width: 1.5, color },
    itemStyle: { color },
    ...extra,
  });
  const gcSeries = (['YOUNG', 'OLD', 'CONCURRENT', 'UNKNOWN'] as const)
    .map((generation) => ({
      generation,
      data: markers
        .filter((marker) => marker.generation === generation)
        .map((marker) => [marker.t, marker.pauseMs, marker.collector, marker.collections]),
    }))
    .filter((group) => group.data.length > 0)
    .map((group) => ({
      name: `GC ${group.generation.toLowerCase()}`,
      type: 'scatter',
      xAxisIndex: 1,
      yAxisIndex: 2,
      data: group.data,
      symbolSize: (value: [number, number]) => Math.max(6, Math.min(26, 5 + Math.sqrt(value[1]) * 2)),
      itemStyle: { color: GENERATION_COLOR[group.generation], opacity: 0.85 },
    }));

  const axisLine = { lineStyle: { color: palette.border } };
  const splitLine = { lineStyle: { color: withAlpha(palette.border, 0.6) } };
  const bytesLabel = { color: palette.muted, fontSize: 11, formatter: (value: number) => formatBytes(value, 0) };

  return {
    backgroundColor: 'transparent',
    animation: false,
    textStyle: { color: palette.muted, fontFamily: 'Inter, Segoe UI, system-ui, sans-serif' },
    legend: {
      top: 0,
      left: 56,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: palette.muted, fontSize: 11 },
      inactiveColor: palette.subtle,
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.card,
      borderColor: palette.border,
      textStyle: { color: palette.text },
      axisPointer: { type: 'line', lineStyle: { color: palette.cyan, opacity: 0.5 } },
      formatter: tooltip,
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 72, right: 72, top: 36, bottom: '34%' },
      { left: 72, right: 72, top: '74%', bottom: 26 },
    ],
    xAxis: [
      { type: 'time', gridIndex: 0, min: start, max: end, axisLine, axisLabel: { show: false }, splitLine },
      {
        type: 'time',
        gridIndex: 1,
        min: start,
        max: end,
        axisLine,
        splitLine,
        axisLabel: { color: palette.muted, fontSize: 11, formatter: '{HH}:{mm}:{ss}' },
      },
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, axisLabel: bytesLabel, splitLine, axisLine },
      {
        type: 'value',
        gridIndex: 0,
        position: 'right',
        name: 'Metaspace',
        nameTextStyle: { color: palette.violet, fontSize: 11 },
        axisLabel: { ...bytesLabel, color: palette.violet },
        splitLine: { show: false },
      },
      {
        type: 'value',
        gridIndex: 1,
        name: 'GC, мс',
        nameLocation: 'middle',
        nameGap: 44,
        nameRotate: 90,
        nameTextStyle: { color: palette.muted, fontSize: 11 },
        axisLabel: { color: palette.muted, fontSize: 10 },
        splitNumber: 2,
        splitLine: { lineStyle: { color: withAlpha(palette.border, 0.4), type: 'dashed' } },
      },
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], filterMode: 'none' }],
    series: [
      ...(hasYoung
        ? [
            area('Old Gen', palette.amber, line(points, (p) => p.old)),
            area('Survivor', palette.emerald, line(points, (p) => p.survivor)),
            area('Eden', palette.cyan, line(points, (p) => p.eden)),
          ]
        : [
            area('Old Gen', palette.amber, line(points, (p) => p.old)),
            plain('Heap used', palette.cyan, line(points, (p) => p.heapUsed)),
          ]),
      plain('Old Gen после GC', palette.coral, line(points, (p) => p.oldAfterGc), {
        lineStyle: { width: 2, color: palette.coral, type: 'dashed' },
        step: 'end',
      }),
      plain('Heap committed', palette.subtle, line(points, (p) => p.heapCommitted), {
        lineStyle: { width: 1, color: palette.subtle, type: 'dotted' },
      }),
      plain('Metaspace', palette.violet, line(points, (p) => p.metaspace), { yAxisIndex: 1 }),
      ...gcSeries,
    ],
  };
}

/** Stacked heap pools (Eden / Survivor / Old Gen), Metaspace, the old-gen live set and GC pause markers. */
export function MemoryPoolsChart() {
  const points = useInspectorStore((s) => s.points);
  const markers = useInspectorStore((s) => s.gcMarkers);
  const mode = useInspectorStore((s) => s.mode);
  const option = useMemo(() => buildOption(points, markers, mode === 'live'), [points, markers, mode]);

  if (points.length === 0) {
    return (
      <div className="flex h-80 items-center justify-center text-xs text-kraken-subtle">
        Ожидаем первые сэмплы из MBeanServerConnection целевой JVM…
      </div>
    );
  }
  return <EChart option={option} className="h-80 w-full" />;
}
