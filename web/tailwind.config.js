/** GEO Tree design tokens. Colours resolve to CSS variables in src/index.css. */
const token = (name) => `rgb(var(--${name}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        forest: { DEFAULT: token("forest"), deep: token("forest-deep"), soft: token("forest-soft") },
        tamarind: token("tamarind"),
        moss: { DEFAULT: token("moss"), soft: token("moss-soft") },
        clay: { DEFAULT: token("clay"), soft: token("clay-soft") },
        amber: { DEFAULT: token("amber"), soft: token("amber-soft") },
        danger: { DEFAULT: token("danger"), soft: token("danger-soft") },
        sky: { DEFAULT: token("sky"), soft: token("sky-soft") },
        cream: token("cream"),
        sand: token("sand"),
        surface: token("surface"),
        line: { DEFAULT: token("line"), strong: token("line-strong") },
        ink: { DEFAULT: token("ink"), muted: token("ink-muted"), faint: token("ink-faint") },
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', "system-ui", "-apple-system", '"Segoe UI"', "Roboto", "sans-serif"],
        mono: ['"IBM Plex Mono"', "ui-monospace", "Consolas", "monospace"],
      },
      fontSize: { "2xs": ["0.6875rem", { lineHeight: "1rem" }] },
      borderRadius: { xl: "0.875rem", "2xl": "1.125rem" },
      boxShadow: {
        card: "0 1px 2px rgb(35 38 42 / 0.05), 0 1px 1px rgb(35 38 42 / 0.03)",
        raised: "0 8px 24px -8px rgb(31 61 43 / 0.18), 0 2px 6px rgb(35 38 42 / 0.06)",
        focus: "0 0 0 3px rgb(var(--moss) / 0.35)",
      },
      keyframes: {
        "fade-in": { from: { opacity: "0" }, to: { opacity: "1" } },
        "rise-in": { from: { opacity: "0", transform: "translateY(6px) scale(0.99)" }, to: { opacity: "1", transform: "none" } },
        "slide-in": { from: { transform: "translateX(100%)" }, to: { transform: "none" } },
        shimmer: { "100%": { transform: "translateX(100%)" } },
      },
      animation: {
        "fade-in": "fade-in 150ms ease-out",
        "rise-in": "rise-in 180ms cubic-bezier(0.2, 0.8, 0.2, 1)",
        "slide-in": "slide-in 220ms cubic-bezier(0.2, 0.8, 0.2, 1)",
      },
    },
  },
  plugins: [],
};
