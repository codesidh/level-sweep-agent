/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  darkMode: 'class',
  theme: {
    extend: {
      // Surface every CSS custom property as a Tailwind colour so utility
      // classes like `bg-bg-elevated` / `text-fg-muted` / `border-subtle`
      // resolve via CSS vars (set in styles.css). One source of truth for
      // the design tokens.
      fontFamily: {
        sans: [
          'Inter',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'sans-serif',
        ],
        mono: [
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'Monaco',
          'Consolas',
          'monospace',
        ],
      },
      colors: {
        bg: {
          base: 'var(--bg-base)',
          elevated: 'var(--bg-elevated)',
          glass: 'var(--bg-glass)',
          'glass-hover': 'var(--bg-glass-hover)',
        },
        fg: {
          primary: 'var(--fg-primary)',
          secondary: 'var(--fg-secondary)',
          muted: 'var(--fg-muted)',
        },
        accent: {
          blue: 'var(--accent-blue)',
          cyan: 'var(--accent-cyan)',
          pink: 'var(--accent-pink)',
          amber: 'var(--accent-amber)',
          emerald: 'var(--accent-emerald)',
          red: 'var(--accent-red)',
        },
      },
      borderColor: {
        subtle: 'var(--border-subtle)',
        bright: 'var(--border-bright)',
      },
      borderRadius: {
        pill: 'var(--r-pill)',
        card: 'var(--r-card)',
        tile: 'var(--r-tile)',
      },
      boxShadow: {
        'glow-blue': 'var(--glow-blue)',
        'glow-cyan': 'var(--glow-cyan)',
      },
      maxWidth: {
        screen: '80rem',
      },
      animation: {
        'fade-in': 'fade-in 200ms ease-out',
        lift: 'lift 200ms ease-out',
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        lift: {
          '0%': { transform: 'translateY(2px)' },
          '100%': { transform: 'translateY(0)' },
        },
      },
    },
  },
  plugins: [],
};
