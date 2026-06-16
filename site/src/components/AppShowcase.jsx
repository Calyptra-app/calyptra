import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import usePrefersReducedMotion from '../usePrefersReducedMotion';
import appHome from '../assets/screenshots/app-home.webp';
import appParentControls from '../assets/screenshots/app-parent-controls.webp';
import appControlsList from '../assets/screenshots/app-controls-list.webp';

gsap.registerPlugin(ScrollTrigger);

const SHOTS = [
    {
        src: appParentControls,
        alt: 'Calyptra parent settings: SafeSearch, adult-content blocking, YouTube Restricted Mode and per-app switches.',
        caption: 'Block adult sites, lock SafeSearch, switch off apps. All behind your PIN.',
        offset: 'md:mt-16',
    },
    {
        src: appHome,
        alt: 'Calyptra home screen showing protection is on and the number of ads blocked.',
        caption: 'One switch tells you it is on, and how much it has stopped.',
        offset: 'md:-mt-4',
    },
    {
        src: appControlsList,
        alt: 'Calyptra controls: allowed sites, per-app whitelist and protection history.',
        caption: 'Allow a site that got blocked by mistake, any time.',
        offset: 'md:mt-10',
    },
];

export default function AppShowcase() {
    const sectionRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.from('.shot-frame', {
                scrollTrigger: { trigger: sectionRef.current, start: 'top 70%' },
                y: 48,
                opacity: 0,
                duration: 0.9,
                stagger: 0.12,
                ease: 'power3.out',
            });
        }, sectionRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <section
            id="inside-the-app"
            ref={sectionRef}
            className="relative z-10 py-28 md:py-40 px-6 md:px-12 lg:px-24 bg-cream-200 border-t border-navy/5 overflow-hidden"
        >
            {/* soft ambient wash so the panel is not a flat fill */}
            <div
                className="absolute inset-0 pointer-events-none opacity-70"
                style={{ background: 'radial-gradient(60% 50% at 50% 0%, rgba(7,20,97,0.06), transparent 70%)' }}
                aria-hidden="true"
            ></div>

            <div className="relative max-w-7xl mx-auto">
                <div className="max-w-2xl mb-16 md:mb-24">
                    <h2 className="font-sans font-bold text-4xl md:text-6xl text-navy tracking-tight leading-[1.08]">
                        This is the whole app.
                    </h2>
                    <p className="font-outfit text-ink/65 text-base md:text-lg leading-relaxed mt-5">
                        No dashboards, no accounts, nothing to learn. A switch on the home screen,
                        and the handful of controls a parent actually wants. Here are the real screens.
                    </p>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-3 gap-8 md:gap-10 max-w-4xl mx-auto">
                    {SHOTS.map((shot) => (
                        <figure key={shot.src} className={`shot-frame ${shot.offset}`}>
                            <div className="rounded-4xl bg-ink p-2 shadow-[0_40px_80px_-40px_rgba(7,20,97,0.5)] border border-navy/10">
                                <img
                                    src={shot.src}
                                    alt={shot.alt}
                                    loading="lazy"
                                    className="rounded-3xl w-full block"
                                />
                            </div>
                            <figcaption className="mt-5 font-sans text-sm text-ink/60 leading-relaxed text-center max-w-[34ch] mx-auto">
                                {shot.caption}
                            </figcaption>
                        </figure>
                    ))}
                </div>
            </div>
        </section>
    );
}
