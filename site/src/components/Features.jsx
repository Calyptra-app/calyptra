import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { Lock } from 'lucide-react';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

// Card 1 — Blocks everywhere: a shuffling stack of things Calyptra stops.
function BlocksEverywhere() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();
    const blocked = [
        { tag: 'Ad', host: 'ads.doubleclick.net' },
        { tag: 'Tracker', host: 'graph.facebook.com' },
        { tag: 'Analytics', host: 'app-measurement.com' },
    ];

    useEffect(() => {
        const ctx = gsap.context(() => {
            const cards = gsap.utils.toArray('.shuffler-card');
            if (!cards.length) return;

            gsap.set(cards, {
                y: (i) => i * 16,
                scale: (i) => 1 - i * 0.05,
                opacity: (i) => 1 - i * 0.22,
                zIndex: (i) => 3 - i,
            });

            if (reduced) return; // keep the static stacked layout, no shuffle

            const tl = gsap.timeline({ repeat: -1 });
            cards.forEach((_, i) => {
                tl.to(cards, {
                    y: (j) => ((j - 1 + cards.length) % cards.length) * 16,
                    scale: (j) => 1 - ((j - 1 + cards.length) % cards.length) * 0.05,
                    opacity: (j) => 1 - ((j - 1 + cards.length) % cards.length) * 0.22,
                    duration: 0.8,
                    ease: 'back.out(1.5)',
                    onStart: () => {
                        gsap.set(cards, {
                            zIndex: (j) => 3 - ((j - (i + 1) + cards.length) % cards.length),
                        });
                    },
                }, '+=2');
            });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <article className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift group relative overflow-hidden">
            <div className="flex-1 flex items-center justify-center relative min-h-[230px] z-10" ref={containerRef}>
                {blocked.map((item) => (
                    <div key={item.host} className="shuffler-card absolute w-full max-w-[300px] bg-navy text-white p-4 rounded-xl shadow-2xl border border-white/10 flex flex-col gap-2">
                        <div className="flex justify-between items-center">
                            <span className="text-xs font-sans font-semibold text-white/85">{item.tag}</span>
                            <span className="text-coral font-mono text-[11px] font-bold tracking-wider">BLOCKED</span>
                        </div>
                        <span className="font-mono text-[13px] text-white/55 break-all">{item.host}</span>
                        <div className="w-full h-1 bg-white/10 rounded-full mt-1 overflow-hidden">
                            <div className="w-full h-full bg-coral/70"></div>
                        </div>
                    </div>
                ))}
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">Blocks ads in every app, not just the browser.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed max-w-md">
                    Games, free apps, videos: Calyptra filters them all from one place.
                    No setup for each app, and far fewer ads getting through.
                </p>
            </div>
        </article>
    );
}

// Card 2 — We see nothing: a ledger of data Calyptra never collects.
function PrivacyLedger() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();
    const rows = ['Location', 'Browsing history', 'Screen-time reports', 'Contacts & photos', 'Account or sign-in'];

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.fromTo('.ledger-mark',
                { opacity: 0.35 },
                { opacity: 1, duration: 0.5, stagger: 0.45, repeat: -1, yoyo: true, repeatDelay: 1.2, ease: 'sine.inOut' });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <article ref={containerRef} className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="flex-1 flex flex-col justify-center min-h-[230px] w-full">
                <ul className="flex flex-col gap-3 w-full">
                    {rows.map((row) => (
                        <li key={row} className="flex items-center justify-between gap-3 border-b border-navy/5 pb-3 last:border-0">
                            <span className="font-sans text-[15px] text-ink/70">{row}</span>
                            <span className="ledger-mark font-mono text-[11px] font-bold tracking-wider text-coral uppercase whitespace-nowrap">Never</span>
                        </li>
                    ))}
                </ul>
                <div className="mt-5 flex items-center justify-between bg-navy text-white rounded-2xl px-5 py-4">
                    <span className="font-sans font-semibold text-sm">Collected from your child</span>
                    <span className="font-drama italic text-coral text-2xl leading-none">nothing</span>
                </div>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5 mt-2">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">We never watch your child.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed">
                    No accounts, no analytics, no logs. Calyptra keeps no record of what your
                    child does, and sends us nothing.
                </p>
            </div>
        </article>
    );
}

// Card 3 — Social control: parent chooses which platforms work.
function SocialControl() {
    const platforms = [
        { name: 'TikTok', blocked: true },
        { name: 'Instagram', blocked: true },
        { name: 'Snapchat', blocked: true },
        { name: 'Reddit', blocked: false },
        { name: 'Discord', blocked: false },
        { name: 'Twitch', blocked: false },
    ];

    return (
        <article className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="flex-1 flex flex-col justify-center min-h-[230px]">
                <div className="flex flex-wrap gap-2.5">
                    {platforms.map((p) => (
                        <span
                            key={p.name}
                            className={
                                p.blocked
                                    ? 'inline-flex items-center gap-1.5 px-3.5 py-2 rounded-full bg-navy text-white text-sm font-medium'
                                    : 'inline-flex items-center gap-1.5 px-3.5 py-2 rounded-full border border-navy/15 bg-white text-ink/45 text-sm'
                            }
                        >
                            {p.blocked && <Lock size={12} className="text-coral" />}
                            {p.name}
                        </span>
                    ))}
                </div>
                <p className="mt-5 font-mono text-[11px] tracking-wide text-ink/45">
                    YouTube uses Restricted Mode, not a full block.
                </p>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">Block the apps you choose.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed">
                    Switch off TikTok, Instagram, Snapchat and more, one platform at a time.
                    Changes take effect right away.
                </p>
            </div>
        </article>
    );
}

// Card 4 — Safe search: forced SafeSearch + YouTube Restricted Mode.
function SafeSearchControl() {
    const engines = ['Google', 'Bing', 'DuckDuckGo'];

    return (
        <article className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="flex-1 flex flex-col justify-center min-h-[230px]">
                <ul className="flex flex-col gap-3">
                    {engines.map((name) => (
                        <li key={name} className="flex items-center justify-between gap-3 border-b border-navy/5 pb-3">
                            <span className="font-sans text-[15px] text-ink/70">{name}</span>
                            <span className="font-mono text-[11px] font-bold tracking-wider text-coral uppercase whitespace-nowrap">SafeSearch on</span>
                        </li>
                    ))}
                </ul>
                <div className="mt-4 flex items-center justify-between gap-3 bg-navy text-white rounded-2xl px-5 py-4">
                    <span className="font-sans font-semibold text-sm">YouTube</span>
                    <span className="font-mono text-[11px] tracking-wider text-coral uppercase whitespace-nowrap">Restricted: Strict</span>
                </div>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">Safe results, by default.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed max-w-lg">
                    Calyptra forces SafeSearch on Google, Bing and DuckDuckGo, so explicit results
                    are filtered where the search engine supports it. YouTube opens in Restricted Mode, set to strict.
                </p>
            </div>
        </article>
    );
}

// Card 5 — Dangerous sites: malware/phishing always-on + parent adult toggle.
function DangerousSites() {
    const rows = [
        { label: 'Malware sites', state: 'Always on', locked: true },
        { label: 'Phishing & scams', state: 'Always on', locked: true },
        { label: 'Adult content', state: 'Parent switch', locked: false },
    ];

    return (
        <article className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="flex-1 flex flex-col justify-center min-h-[230px]">
                <ul className="flex flex-col gap-3">
                    {rows.map((r) => (
                        <li key={r.label} className="flex items-center justify-between gap-3 border-b border-navy/5 pb-3 last:border-0">
                            <span className="font-sans text-[15px] text-ink/70">{r.label}</span>
                            <span className={
                                r.locked
                                    ? 'inline-flex items-center gap-1.5 font-mono text-[11px] font-bold tracking-wider text-coral uppercase whitespace-nowrap'
                                    : 'font-mono text-[11px] font-bold tracking-wider text-navy/45 uppercase whitespace-nowrap'
                            }>
                                {r.locked && <Lock size={12} className="text-coral" />}
                                {r.state}
                            </span>
                        </li>
                    ))}
                </ul>
                <p className="mt-5 font-mono text-[11px] tracking-wide text-ink/45">
                    Blocked a safe site by mistake? Add it to your allowed sites.
                </p>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">Helps block scams, malware, and adult sites.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed max-w-lg">
                    Calyptra checks every site against trusted malware, phishing, and adult-content
                    lists. Malware and scams are blocked automatically. Adult content is one switch
                    you control, and you can allow any site caught by mistake.
                </p>
            </div>
        </article>
    );
}

// Card 6 — Kid-simple, parent-controlled: a banner with a friendly toggle.
function KidParentBanner() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return; // toggle stays in its static "off" state
        const ctx = gsap.context(() => {
            const tl = gsap.timeline({ repeat: -1, repeatDelay: 1.4 });
            tl.to('.toggle-knob', { x: 32, duration: 0.5, ease: 'back.out(2)' })
                .to('.toggle-track', { backgroundColor: '#E5734A', borderColor: '#E5734A', duration: 0.4 }, '<')
                .to('.label-off', { opacity: 0, y: -6, duration: 0.2 }, '<0.1')
                .to('.label-on', { opacity: 1, y: 0, duration: 0.25 }, '<')
                .to({}, { duration: 2 })
                .to('.toggle-knob', { x: 0, duration: 0.5, ease: 'back.out(2)' })
                .to('.toggle-track', { backgroundColor: 'rgba(7,20,97,0.10)', borderColor: 'rgba(7,20,97,0.15)', duration: 0.4 }, '<')
                .to('.label-on', { opacity: 0, y: 6, duration: 0.2 }, '<0.1')
                .to('.label-off', { opacity: 1, y: 0, duration: 0.25 }, '<');
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <article ref={containerRef} className="flex flex-col md:flex-row md:items-center gap-10 md:gap-16 h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 md:p-12 hover-lift relative overflow-hidden">
            <div className="absolute -right-20 -top-20 w-64 h-64 bg-coral/5 rounded-full blur-3xl pointer-events-none"></div>

            <div className="flex flex-col items-center justify-center gap-6 bg-cream/60 rounded-[1.6rem] border border-navy/5 p-8 md:px-14 shrink-0">
                <div aria-hidden="true" className="toggle-track relative w-[76px] h-10 rounded-full border border-navy/15 bg-navy/10">
                    <span className="toggle-knob absolute top-1/2 -translate-y-1/2 left-1 w-8 h-8 rounded-full bg-white shadow-md"></span>
                </div>
                <div className="relative h-4 w-full flex items-center justify-center" aria-hidden="true">
                    <span className="label-off absolute font-mono text-[11px] tracking-[0.2em] text-navy/60 uppercase whitespace-nowrap">Tap to protect</span>
                    <span className="label-on absolute font-mono text-[11px] tracking-[0.2em] text-coral uppercase opacity-0 whitespace-nowrap">Protection on</span>
                </div>
                <div className="flex items-center gap-2 px-3 py-1.5 rounded-full border border-navy/15 bg-white text-[10px] font-mono font-bold text-navy/70 uppercase tracking-widest">
                    <Lock size={12} className="text-coral" />
                    Parent PIN
                </div>
            </div>

            <div className="relative z-10 max-w-xl">
                <h3 className="font-outfit font-bold text-3xl md:text-4xl text-navy mb-3 tracking-tight">One tap for kids. A PIN for you.</h3>
                <p className="font-sans text-ink/65 text-base leading-relaxed">
                    Your child taps once to turn protection on. No menus, no settings to
                    get lost in. Changing anything, or switching it off, stays behind a PIN only
                    you know.
                </p>
            </div>
        </article>
    );
}

export default function Features() {
    const sectionRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.from('.feature-card-wrapper', {
                scrollTrigger: { trigger: sectionRef.current, start: 'top 75%' },
                y: 60,
                opacity: 0,
                duration: 1,
                stagger: 0.15,
                ease: 'power3.out',
            });
        }, sectionRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <section id="features" ref={sectionRef} className="py-28 md:py-44 px-6 md:px-12 lg:px-24 bg-cream relative z-10 border-t border-navy/5">
            <div className="absolute inset-0 bg-grid opacity-50 pointer-events-none"></div>

            <div className="max-w-7xl mx-auto relative z-10">
                <div className="mb-16 md:mb-24 max-w-3xl border-b border-navy/10 pb-12">
                    <h2 className="font-sans font-bold text-4xl md:text-6xl text-navy tracking-tight leading-[1.08]">
                        Built to protect,<br />
                        <span className="font-drama italic font-semibold text-coral inline-block leading-[1.1] pb-1">never to spy.</span>
                    </h2>
                    <p className="font-outfit text-ink/65 max-w-xl text-base md:text-lg leading-relaxed mt-6">
                        Calyptra works quietly in the background, keeping ads, trackers, and junk
                        out of every app your child opens, without ever sending their data
                        anywhere.
                    </p>
                </div>

                {/* Asymmetric bento, six cells: wide+narrow rhythm with a banner to close. */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6 lg:gap-8">
                    <div className="feature-card-wrapper md:col-span-2 min-h-[460px]"><BlocksEverywhere /></div>
                    <div className="feature-card-wrapper md:col-span-1 min-h-[460px]"><PrivacyLedger /></div>
                    <div className="feature-card-wrapper md:col-span-2 min-h-[460px]"><DangerousSites /></div>
                    <div className="feature-card-wrapper md:col-span-1 min-h-[460px]"><SocialControl /></div>
                    <div className="feature-card-wrapper md:col-span-1 min-h-[460px]"><SafeSearchControl /></div>
                    <div className="feature-card-wrapper md:col-span-2"><KidParentBanner /></div>
                </div>

                <p className="font-sans text-xs text-ink/45 mt-12 max-w-2xl mx-auto text-center leading-relaxed">
                    Calyptra uses domain blocklists, which reduce but cannot eliminate exposure to
                    ads, trackers, or harmful sites. It is not a substitute for parental supervision.
                </p>
            </div>
        </section>
    );
}
