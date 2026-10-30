import { fileURLToPath } from 'node:url';

/** @type {import('tailwindcss').Config} */

// GitKraken-style palette. src/theme/palette.ts (ECharts, inline styles) must use the same values.
const kraken = {
  bg: '#1b1d23',
  panel: '#232730',
  card: '#2c313c',
  border: '#363c4a',
};

const accent = {
  cyan: '#00e5ff',
  emerald: '#00c853',
  amber: '#ffab00',
  coral: '#ff3d71',
  violet: '#b388ff',
};

// Content globs relative to this file, not to the process working directory (globs need forward slashes).
const here = fileURLToPath(new URL('.', import.meta.url)).replace(/\\/g, '/');

export default {
  content: [`${here}index.html`, `${here}src/**/*.{ts,tsx}`],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        kraken: {
          ...kraken,
          hover: kraken.card,
          text: '#e6e8ee',
          muted: '#9aa3b2',
          subtle: '#6b7385',
        },
        accent,
        // Semantic aliases: use these in components, not raw accent colors.
        info: accent.cyan,
        mem: {
          healthy: accent.emerald,
          suspect: accent.amber,
          critical: accent.coral,
          meta: accent.violet,
        },
        severity: {
          info: accent.cyan,
          warning: accent.amber,
          critical: accent.coral,
        },
        category: {
          jakarta: accent.violet,
          hazelcast: accent.violet,
          jvm: accent.cyan,
        },
      },
      borderColor: {
        DEFAULT: kraken.border,
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Cascadia Code', 'Consolas', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },
      boxShadow: {
        panel: '0 1px 0 0 rgba(255,255,255,0.03) inset, 0 4px 16px rgba(0,0,0,0.35)',
        'glow-cyan': `0 0 0 1px ${accent.cyan}55, 0 0 12px ${accent.cyan}40`,
        'glow-amber': `0 0 0 1px ${accent.amber}55, 0 0 12px ${accent.amber}40`,
        'glow-coral': `0 0 0 1px ${accent.coral}55, 0 0 12px ${accent.coral}40`,
      },
      keyframes: {
        'leak-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.45' },
        },
      },
      animation: {
        'leak-pulse': 'leak-pulse 1.6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};
