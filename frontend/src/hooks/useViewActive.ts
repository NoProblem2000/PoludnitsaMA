import { createContext, useContext } from 'react';

/**
 * Whether the view this component belongs to is the one on screen. Views stay mounted after the first visit (so a
 * query, a selection or a loaded analysis survive switching), and pause what costs the target while hidden.
 */
export const ViewActiveContext = createContext(true);

export function useViewActive(): boolean {
  return useContext(ViewActiveContext);
}
