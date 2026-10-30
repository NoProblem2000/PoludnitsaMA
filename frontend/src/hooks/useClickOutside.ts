import { type RefObject, useEffect } from 'react';

/** Calls {@code onOutside} for clicks outside {@code ref} while {@code active}. */
export function useClickOutside(ref: RefObject<HTMLElement>, active: boolean, onOutside: () => void): void {
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const onDown = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        onOutside();
      }
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [ref, active, onOutside]);
}
