import { LineChart, ScatterChart } from 'echarts/charts';
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
use([LineChart, ScatterChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent,
  AxisPointerComponent, CanvasRenderer]);

interface EChartProps {
  option: EChartsCoreOption;
  className?: string;
}

/**
 * ECharts in React: one instance per mount, resized with its container, disposed on unmount. Option updates merge
 * into the existing chart (series replaced), so streaming data does not reset zoom or legend state.
 */
export function EChart({ option, className }: EChartProps) {
  const container = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType | null>(null);

  useEffect(() => {
    const element = container.current;
    if (!element) {
      return undefined;
    }
    const instance = init(element, undefined, { renderer: 'canvas' });
    chart.current = instance;
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
