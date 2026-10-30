import { LineChart, ScatterChart, SunburstChart } from 'echarts/charts';
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components';
import { type EChartsCoreOption, type EChartsType, init, use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useRef } from 'react';

// Only the modules the charts use: keeps the bundle a fraction of full ECharts.
use([LineChart, ScatterChart, SunburstChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent,
  AxisPointerComponent, CanvasRenderer]);

/** The part of an ECharts mouse event payload the views read. */
export interface EChartEvent {
  data?: unknown;
  name?: string;
  seriesType?: string;
}

const FORWARDED_EVENTS = ['click', 'dblclick'] as const;

interface EChartProps {
  option: EChartsCoreOption;
  className?: string;
  /** e.g. {@code { click: handler }}; handlers may change between renders */
  onEvents?: Partial<Record<(typeof FORWARDED_EVENTS)[number], (event: EChartEvent) => void>>;
}

/**
 * ECharts in React: one instance per mount, resized with its container, disposed on unmount. Option updates merge
 * into the existing chart (series replaced), so streaming data does not reset zoom or legend state.
 */
export function EChart({ option, className, onEvents }: EChartProps) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType | null>(null);
  const handlers = useRef(onEvents);
  handlers.current = onEvents;

  useEffect(() => {
    const element = container.current;
    if (!element) {
      return undefined;
    }
    const instance = init(element, undefined, { renderer: 'canvas' });
    chart.current = instance;
    // Dispatch through the ref: handlers may be new closures on every render.
    for (const name of FORWARDED_EVENTS) {
      instance.on(name, (event: unknown) => handlers.current?.[name]?.(event as EChartEvent));
    }
    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(element);
    return () => {
      observer.disconnect();
      instance.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    chart.current?.setOption(option, { replaceMerge: ['series'], lazyUpdate: true });
  }, [option]);

  return <div ref={container} className={className} />;
}
