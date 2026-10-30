import { fileURLToPath } from 'node:url';

// Explicit config path: Tailwind otherwise looks for its config in the process working directory, which differs
// when Vite is started from outside frontend/ (Maven bundle-ui profile, IDE run configurations).
export default {
  plugins: {
    tailwindcss: { config: fileURLToPath(new URL('./tailwind.config.js', import.meta.url)) },
    autoprefixer: {},
  },
};
