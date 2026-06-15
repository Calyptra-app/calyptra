# Calyptra — landing page

The marketing site for [Calyptra](https://github.com/Calyptra-app/calyptra), a
privacy-first, on-device ad &amp; tracker blocker for Android.

Built with **React + Vite + Tailwind CSS + GSAP**. Single-page, animated,
responsive, and accessible (respects `prefers-reduced-motion`).

**Live:** https://calyptra-app.github.io/calyptra/

## Develop

```bash
cd site
npm install
npm run dev      # http://localhost:5173/calyptra/
```

## Build

```bash
npm run build    # outputs to site/dist
npm run preview  # preview the production build locally
```

The Vite `base` defaults to `/calyptra/` so assets resolve on the project
page. To build for a different host (root domain or local), override it:

```bash
VITE_BASE=/ npm run build
```

## Deploy

Deployment is automatic. The
[`Deploy site to GitHub Pages`](../.github/workflows/deploy-site.yml) workflow
builds `site/` and publishes it whenever changes under `site/**` land on
`main`. Pages must be set to **Build and deployment → Source: GitHub Actions**
in the repository settings (one-time).

## Structure

```
site/
├── index.html              # shell, fonts, SEO/social meta
├── src/
│   ├── main.jsx
│   ├── App.jsx             # section composition
│   ├── index.css          # design tokens + utilities
│   ├── constants.js       # outbound links (download, repo, license)
│   ├── assets/            # brand emblem
│   └── components/        # Navbar, Hero, Features, Philosophy, HowItWorks, Download, Footer
└── public/                # favicons / brand icons
```

## Brand

| Token        | Value     | Use                          |
| ------------ | --------- | ---------------------------- |
| `navy`       | `#071461` | Primary brand / hero canvas  |
| `coral`      | `#E5734A` | Accent / CTAs                |
| `cream`      | `#F7F3EE` | Light section background     |
| `ink`        | `#13132B` | Body text on light surfaces  |

Derived from the app icon (white emblem on deep navy). Type: Plus Jakarta Sans
(body), Outfit (UI), Fraunces (display italics), IBM Plex Mono (labels).
