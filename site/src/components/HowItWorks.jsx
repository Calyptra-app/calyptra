import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

export default function HowItWorks() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        const ctx = gsap.context(() => {
            // EKG draw on the "active" step. A bounded, on-brand loop.
            gsap.fromTo('.ekg-path', { strokeDashoffset: 1000 }, { strokeDashoffset: 0, duration: 3, repeat: -1, ease: 'linear' });

            if (reduced) return;
            // Each step reveals as it enters, so scrolling always moves something.
            // No pin, no scroll-jacking: the steps are ordinary stacked blocks.
            gsap.utils.toArray('.hit-card').forEach((card) => {
                gsap.from(card.querySelectorAll('.hit-anim'), {
                    scrollTrigger: { trigger: card, start: 'top 78%' },
                    y: 44,
                    opacity: 0,
                    duration: 0.85,
                    stagger: 0.12,
                    ease: 'power3.out',
                });
            });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    const cardBase = 'hit-card relative w-full overflow-hidden flex flex-col md:flex-row items-center justify-center gap-12 md:gap-20 px-6 md:px-24 py-24 md:py-32';

    return (
        <section id="how-it-works" ref={containerRef} className="relative w-full bg-cream border-t border-navy/5">
            <div className="absolute inset-0 bg-grid opacity-60 pointer-events-none z-0"></div>

            <div className="relative z-10 pt-24 md:pt-28 text-center">
                <h2 className="font-mono text-[10px] tracking-[0.3em] uppercase text-navy/40 flex items-center justify-center gap-4">
                    <span className="w-12 h-px bg-navy/20"></span>
                    How it works
                    <span className="w-12 h-px bg-navy/20"></span>
                </h2>
            </div>

            {/* Step 1 — Install */}
            <div className={`${cardBase} bg-cream z-10`}>
                <div className="absolute -left-16 top-0 text-[320px] md:text-[500px] font-drama italic text-navy/[0.04] leading-none pointer-events-none select-none z-0">01</div>

                <div className="hit-anim flex-1 flex justify-center items-center relative z-10 w-full">
                    <div className="relative w-64 h-64 md:w-80 md:h-80 bg-white rounded-full flex items-center justify-center shadow-[0_30px_60px_-30px_rgba(7,20,97,0.4)] border border-navy/10">
                        <svg width="240" height="240" viewBox="0 0 240 240" fill="none" className="z-10">
                            <circle cx="120" cy="120" r="100" stroke="#E5734A" strokeWidth="1" opacity="0.25" />
                            <circle cx="120" cy="120" r="80" stroke="#E5734A" strokeWidth="1" opacity="0.35" />
                            <path d="M120 40L180 60V110C180 155 155 190 120 200C85 190 60 155 60 110V60L120 40Z" stroke="#071461" strokeWidth="3" strokeLinejoin="round" fill="rgba(7,20,97,0.05)" />
                        </svg>
                    </div>
                </div>
                <div className="hit-anim flex-1 max-w-lg relative z-10">
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight">Install the app.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light">
                        Download Calyptra and open it. There is no sign-up and no Play Store
                        account. The only permission it ever asks for is the one it needs to
                        block ads.
                    </p>
                </div>
            </div>

            {/* Step 2 — Approve (text left, illustration right: alternates with steps 1 and 3) */}
            <div className={`${cardBase} bg-cream-200 z-20 border-t border-white`}>
                <div className="absolute -right-16 bottom-0 text-[320px] md:text-[500px] font-drama italic text-navy/[0.04] leading-none pointer-events-none select-none z-0">02</div>

                <div className="hit-anim flex-1 max-w-lg relative z-10">
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight">Turn on protection.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light">
                        Android asks you to allow Calyptra once. It sets up a private filter on the
                        phone itself, so your child&rsquo;s activity{' '}
                        <span className="text-navy font-medium border-b border-navy/30 pb-0.5">never</span>{' '}
                        leaves the device, and no other app can see it.
                    </p>
                </div>
                <div className="hit-anim flex-1 flex justify-center items-center relative w-full z-10">
                    <div className="relative overflow-hidden w-full max-w-[280px] h-[320px] bg-navy rounded-[2.5rem] shadow-[0_30px_60px_-30px_rgba(7,20,97,0.5)] border border-white/10 flex flex-col items-center justify-center">
                        <div className="absolute inset-0 p-8 flex flex-wrap gap-4 justify-center items-center">
                            {[...Array(20)].map((_, i) => (
                                <span key={i} className={`w-3.5 h-3.5 rounded-sm ${i % 4 === 0 ? 'bg-coral' : 'bg-white'} opacity-60`}></span>
                            ))}
                        </div>
                    </div>
                </div>
            </div>

            {/* Step 3 — Active */}
            <div className={`${cardBase} bg-cream z-30 border-t border-white`}>
                <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 text-[360px] md:text-[600px] font-drama italic text-navy/[0.04] leading-none pointer-events-none select-none z-0">03</div>

                <div className="hit-anim flex-1 flex justify-center items-center z-10 w-full">
                    <div className="relative bg-white border border-navy/10 p-6 md:p-8 rounded-[2.5rem] shadow-[0_30px_60px_-30px_rgba(7,20,97,0.4)] overflow-hidden w-full max-w-sm">
                        <svg width="100%" height="150" viewBox="0 0 300 150" fill="none" className="bg-navy/[0.04] rounded-2xl relative z-10">
                            <path className="ekg-path" d="M0 75 L80 75 L95 20 L115 130 L135 75 L160 75 L170 85 L180 65 L190 75 L300 75"
                                stroke="#E5734A" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="1000" strokeDashoffset="1000" />
                            <path d="M0 75 L80 75 L95 20 L115 130 L135 75 L160 75 L170 85 L180 65 L190 75 L300 75"
                                stroke="#071461" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" opacity="0.1" />
                        </svg>
                    </div>
                </div>
                <div className="hit-anim flex-1 max-w-lg z-10">
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight">It just keeps running.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light">
                        Calyptra restarts itself when the phone reboots, barely touches the
                        battery, and needs nothing from you. Set it up once and the protection
                        stays on, quietly, in the background.
                    </p>
                </div>
            </div>
        </section>
    );
}
