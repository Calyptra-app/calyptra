/** @type {import('tailwindcss').Config} */
export default {
    content: [
        "./index.html",
        "./src/**/*.{js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            colors: {
                // Brand identity — derived from the app icon (white emblem on deep navy)
                navy: '#071461',       // primary brand / hero canvas
                'navy-700': '#0C1B72', // lighter navy for layered surfaces
                'navy-900': '#050E47', // deepest navy for footer / contrast
                coral: '#E5734A',      // accent — CTAs, highlights
                'coral-soft': '#F0936F',
                cream: '#F7F3EE',      // soft off-white sections
                'cream-200': '#EFEAE1',
                ink: '#13132B',        // body text on light surfaces
            },
            fontFamily: {
                sans: ['"Plus Jakarta Sans"', 'sans-serif'],
                outfit: ['Outfit', 'sans-serif'],
                drama: ['"Fraunces"', 'serif'],
                mono: ['"IBM Plex Mono"', 'monospace'],
            },
            keyframes: {
                'pulse-soft': {
                    '0%, 100%': { opacity: '1' },
                    '50%': { opacity: '0.4' },
                },
                float: {
                    '0%, 100%': { transform: 'translateY(0)' },
                    '50%': { transform: 'translateY(-12px)' },
                },
            },
            animation: {
                'pulse-soft': 'pulse-soft 2.4s ease-in-out infinite',
                float: 'float 7s ease-in-out infinite',
            },
        },
    },
    plugins: [],
}
