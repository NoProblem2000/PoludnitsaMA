import type { GcRootPathStepDto } from '@/api/types';
import { palette } from '@/theme/palette';

/** What finally keeps a path alive, in the words a WildFly / Hazelcast developer uses. */
export type RootType = 'THREAD' | 'STATIC_FIELD' | 'CDI_SINGLETON' | 'MODULE_CLASSLOADER' | 'JNI' | 'OTHER' | 'NONE';

export const ROOT_TYPE_LABEL: Record<RootType, string> = {
  THREAD: 'Thread',
  STATIC_FIELD: 'Static Field',
  CDI_SINGLETON: 'CDI Singleton',
  MODULE_CLASSLOADER: 'ModuleClassLoader',
  JNI: 'JNI Global',
  OTHER: 'GC Root',
  NONE: 'Нет GC Root',
};

export const ROOT_TYPE_COLOR: Record<RootType, string> = {
  THREAD: palette.cyan,
  STATIC_FIELD: palette.amber,
  CDI_SINGLETON: palette.violet,
  MODULE_CLASSLOADER: palette.violet,
  JNI: palette.coral,
  OTHER: palette.muted,
  NONE: palette.subtle,
};

/** Framework objects on a path that explain who owns the chain (shown as tags on the hop). */
const ANCHORS: { test: RegExp; label: string; type?: RootType }[] = [
  { test: /^org\.jboss\.modules\.ModuleClassLoader/, label: 'ModuleClassLoader', type: 'MODULE_CLASSLOADER' },
  { test: /ClassLoader(\$|$)/, label: 'ClassLoader' },
  {
    test: /^org\.jboss\.weld\..*(ApplicationContext|SingletonContext|BeanManagerImpl|ContextualInstance|Container)/,
    label: 'CDI Singleton',
    type: 'CDI_SINGLETON',
  },
  { test: /^org\.jboss\.weld\./, label: 'CDI / Weld' },
  { test: /ThreadLocal/, label: 'ThreadLocal' },
  { test: /^com\.hazelcast\./, label: 'Hazelcast' },
  { test: /^io\.undertow\..*[Ss]ession/, label: 'HTTP Session' },
  { test: /^org\.hibernate\./, label: 'Hibernate' },
];

const THREAD_KINDS = /thread|java frame|native stack|jni local|monitor/i;

function bareClass(className: string): string {
  return className.startsWith('class ') ? className.slice('class '.length) : className;
}

export function anchorOf(step: GcRootPathStepDto): { label: string; type?: RootType } | null {
  const name = bareClass(step.className);
  return ANCHORS.find((anchor) => anchor.test.test(name)) ?? null;
}

/**
 * Classifies a path (GC root first) by the owner a developer has to look at, most specific first: a CDI context,
 * a static field, a WildFly module class loader (redeploy leaks), then the JVM root itself. Every class is reached
 * through some class loader, so a class loader only counts when no static field explains the path.
 */
export function classifyRoot(path: readonly GcRootPathStepDto[]): RootType {
  const root = path[0];
  if (!root || !root.gcRoot) {
    return 'NONE';
  }
  const anchors = path.map((step) => anchorOf(step)?.type);
  if (anchors.includes('CDI_SINGLETON')) {
    return 'CDI_SINGLETON';
  }
  const kind = root.gcRootKind ?? '';
  // referenceName is the field through which a step holds the next one; a class object (root or the inspected one)
  // retains what its static fields hold.
  const target = path[path.length - 1];
  if (path.some((step) => step.referenceName?.startsWith('static ')) || root.className.startsWith('class ')
      || target?.className.startsWith('class ') || /sticky class/i.test(kind)) {
    return 'STATIC_FIELD';
  }
  if (anchors.includes('MODULE_CLASSLOADER')) {
    return 'MODULE_CLASSLOADER';
  }
  if (/^java\.lang\.(Virtual)?Thread$/.test(root.className) || THREAD_KINDS.test(kind)) {
    return 'THREAD';
  }
  if (/jni global/i.test(kind)) {
    return 'JNI';
  }
  return 'OTHER';
}

/** "static CACHE" → "static-поле CACHE", "[142]" → "элемент [142]", "table" → "поле table". */
export function describeReference(reference: string | null): string {
  if (!reference) {
    return 'ссылка';
  }
  if (reference.startsWith('[')) {
    return `элемент ${reference}`;
  }
  if (reference.startsWith('static ')) {
    return `static-поле ${reference.slice('static '.length)}`;
  }
  return `поле ${reference}`;
}

export function formatObjectId(objectId: number): string {
  return `@0x${objectId.toString(16)}`;
}
