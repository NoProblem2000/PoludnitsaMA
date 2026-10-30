import type { GcGeneration, MemoryGroup, PackageCategory, ProcessType, Severity } from '@/api/types';
import { palette } from '@/theme/palette';

/** Mirror of backend {@code MemoryGroup.of(PackageCategory)}. */
export function memoryGroupOf(category: PackageCategory): MemoryGroup {
  switch (category) {
    case 'APPLICATION':
      return 'USER_BUSINESS_CODE';
    case 'JAKARTA_EE':
    case 'WILDFLY':
      return 'JAKARTA_WILDFLY_INTERNAL';
    case 'HAZELCAST':
      return 'HAZELCAST_CLIENT';
    case 'THIRD_PARTY':
      return 'THIRD_PARTY_LIBRARY';
    default:
      return 'JDK_CORE';
  }
}

export const GROUP_LABEL: Record<MemoryGroup, string> = {
  USER_BUSINESS_CODE: 'My Code',
  JAKARTA_WILDFLY_INTERNAL: 'WildFly',
  HAZELCAST_CLIENT: 'Hazelcast',
  JDK_CORE: 'JDK',
  THIRD_PARTY_LIBRARY: 'Library',
};

export const GROUP_COLOR: Record<MemoryGroup, string> = {
  USER_BUSINESS_CODE: palette.emerald,
  JAKARTA_WILDFLY_INTERNAL: palette.violet,
  HAZELCAST_CLIENT: palette.amber,
  JDK_CORE: palette.cyan,
  THIRD_PARTY_LIBRARY: palette.muted,
};

export const PROCESS_TYPE_COLOR: Record<ProcessType, string> = {
  WILDFLY: palette.violet,
  HAZELCAST: palette.amber,
  SPRING_BOOT: palette.emerald,
  STANDARD_JVM: palette.muted,
};

export const GENERATION_COLOR: Record<GcGeneration, string> = {
  YOUNG: palette.cyan,
  OLD: palette.coral,
  CONCURRENT: palette.violet,
  UNKNOWN: palette.muted,
};

export const SEVERITY_COLOR: Record<Severity, string> = {
  CRITICAL: palette.coral,
  WARNING: palette.amber,
  INFO: palette.cyan,
};
