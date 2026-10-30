import { useEffect } from 'react';
import { AppShell } from '@/components/layout/AppShell';
import { useInspectorStore } from '@/store/useInspectorStore';

export default function App() {
  const init = useInspectorStore((s) => s.init);
  const dispose = useInspectorStore((s) => s.dispose);

  useEffect(() => {
    init();
    return dispose;
  }, [init, dispose]);

  return <AppShell />;
}
