import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

export default function HowItWorks() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            const cards = gsap.utils.toArray('.hit-card');

            ScrollTrigger.create({
                trigger: containerRef.current,
                start: 'top top',
                end: `+=${cards.length * 100}%`,
                pin: true,
                pinSpacing: true,
            });

            cards.forEach((card, index) => {
                if (index === cards.length - 1) return;
                gsap.to(card, {
                    scale: 0.9,
                    opacity: 0.25,
                    filter: 'blur(12px)',
                    scrollTrigger: {
                        trigger: containerRef.current,
                        start: () => `top -${index * 100}%`,
                        end: () => `top -${(index + 1) * 100}%`,
                        scrub: true,
                    },
                });
            });

            gsap.to('.shield-ring', { rotation: 360, transformOrigin: 'center center', repeat: -1, duration: 16, ease: 'none' });
            gsap.fromTo('.laser-line', { y: -20 }, { y: 220, duration: 2, repeat: -1, ease: 'linear', yoyo: true });
            gsap.fromTo('.ekg-path', { strokeDashoffset: 1000 }, { strokeDashoffset: 0, duration: 2.5, repeat: -1, ease: 'linear' });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    // When motion is reduced we drop the pinned scroll-jack and let the three
    // steps stack as ordinary full-height sections.
    const sectionPos = reduced ? 'relative w-full' : 'relative w-full h-[100dvh]';
    const cardPos = reduced
        ? 'relative w-full min-h-[100dvh]'
        : 'absolute inset-0 w-full h-full';

    return (
        <section id="how-it-works" ref={containerRef} className={`${sectionPos} bg-cream border-t border-navy/5`}>
            <div className="absolute inset-0 bg-grid opacity-60 pointer-events-none z-0"></div>

            <div className="absolute top-12 left-0 w-full text-center z-50 pointer-events-none">
                <h2 className="font-mono text-[10px] tracking-[0.3em] uppercase text-navy/40 flex items-center justify-center gap-4">
                    <span className="w-12 h-px bg-navy/20"></span>
                    How it works
                    <span className="w-12 h-px bg-navy/20"></span>
                </h2>
            </div>

            {/* Step 1 — Install */}
            <div className={`hit-card ${cardPos} flex flex-col md:flex-row items-center justify-center p-8 md:p-24 bg-cream z-10 border-b border-white shadow-[0_-20px_40px_-20px_rgba(7,20,97,0.08)]`}>
                <div className="absolute -left-16 top-0 text-[320px] md:text-[500px] font-drama italic text-navy/[0.03] leading-none pointer-events-none select-none z-0">01</div>
                <div className="absolute bottom-12 right-12 text-navy/25 font-mono text-xs hidden md:block">STEP_01 // INSTALL</div>

                <div className="flex-1 flex justify-center items-center mb-12 md:mb-0 relative z-10 w-full">
                    <div className="relative w-64 h-64 md:w-80 md:h-80 bg-white/70 backdrop-blur-xl rounded-full flex items-center justify-center shadow-2xl border border-white">
                        <svg width="240" height="240" viewBox="0 0 240 240" fill="none" className="z-10">
                            <circle className="shield-ring" cx="120" cy="120" r="100" stroke="#E5734A" strokeWidth="1" strokeDasharray="10 10" opacity="0.45" />
                            <circle className="shield-ring" cx="120" cy="120" r="80" stroke="#E5734A" strokeWidth="1" strokeDasharray="4 8" opacity="0.6" />
                            <path d="M120 40L180 60V110C180 155 155 190 120 200C85 190 60 155 60 110V60L120 40Z" stroke="#071461" strokeWidth="3" strokeLinejoin="round" fill="rgba(7,20,97,0.05)" />
                        </svg>
                        <div className="absolute inset-0 bg-coral/5 rounded-full blur-2xl"></div>
                    </div>
                </div>
                <div className="flex-1 max-w-lg relative z-10">
                    <div className="font-mono text-coral text-xs tracking-widest uppercase mb-6 flex items-center gap-3">
                        <span className="w-8 h-px bg-coral"></span> Install
                    </div>
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight">Install Calyptra.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light">
                        Download the APK and open it. No account sign-up, no Play Store, and no
                        permissions beyond the local VPN it needs to filter DNS.
                    </p>
                </div>
            </div>

            {/* Step 2 — Approve */}
            <div className={`hit-card ${cardPos} flex flex-col md:flex-row items-center justify-center p-8 md:p-24 bg-cream-200 z-20 shadow-[0_-20px_50px_-20px_rgba(7,20,97,0.12)] border-t border-white`}>
                <div className="absolute -right-16 bottom-0 text-[320px] md:text-[500px] font-drama italic text-navy/[0.04] leading-none pointer-events-none select-none z-0">02</div>
                <div className="absolute bottom-12 left-12 text-navy/25 font-mono text-xs hidden md:block">STEP_02 // LOCAL_TUNNEL</div>

                <div className="flex-1 max-w-lg order-2 md:order-1 mt-12 md:mt-0 relative z-10 md:pr-12">
                    <div className="font-mono text-coral text-xs tracking-widest uppercase mb-6 flex items-center gap-3 w-full justify-end md:justify-start">
                        <span className="w-8 h-px bg-coral hidden md:block"></span> Approve <span className="w-8 h-px bg-coral md:hidden"></span>
                    </div>
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight text-right md:text-left">Enable the tunnel.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light text-right md:text-left">
                        Android routes DNS through a local VPN that never leaves the phone.
                        Calyptra is the{' '}
                        <span className="text-navy font-medium border-b border-navy/30 pb-0.5">only</span>{' '}
                        app that reads it.
                    </p>
                </div>
                <div className="flex-1 flex justify-center items-center order-1 md:order-2 relative w-full z-10">
                    <div className="relative overflow-hidden w-full max-w-[280px] h-[320px] bg-navy rounded-[2.5rem] shadow-2xl border border-white/10 flex flex-col items-center justify-center">
                        <div className="absolute top-4 left-6 font-mono text-[10px] text-white/50">PACKET SCAN // ACTIVE</div>
                        <div className="absolute inset-0 p-8 pt-12 flex flex-wrap gap-4 justify-center items-center">
                            {[...Array(20)].map((_, i) => (
                                <span key={i} className={`w-3.5 h-3.5 rounded-sm ${i % 4 === 0 ? 'bg-coral' : 'bg-white'} opacity-60 shadow-sm`}></span>
                            ))}
                        </div>
                        <div className="laser-line absolute top-0 left-0 w-full h-[3px] bg-coral shadow-[0_0_20px_#E5734A] z-20"></div>
                    </div>
                </div>
            </div>

            {/* Step 3 — Active */}
            <div className={`hit-card ${cardPos} flex flex-col md:flex-row items-center justify-center p-8 md:p-24 bg-cream z-30 shadow-[0_-20px_50px_-20px_rgba(7,20,97,0.10)] border-t border-white`}>
                <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 text-[360px] md:text-[600px] font-drama italic text-navy/[0.03] leading-none pointer-events-none select-none z-0">03</div>
                <div className="absolute bottom-12 right-12 text-navy/25 font-mono text-xs hidden md:block">STEP_03 // PERSISTENT</div>

                <div className="flex-1 flex justify-center items-center mb-12 md:mb-0 z-10 w-full">
                    <div className="relative bg-white/70 backdrop-blur-xl border border-white p-6 md:p-8 rounded-[2.5rem] shadow-2xl overflow-hidden w-full max-w-sm">
                        <div className="absolute inset-0 bg-coral/5 blur-3xl rounded-full"></div>
                        <svg width="100%" height="150" viewBox="0 0 300 150" fill="none" className="bg-navy/[0.04] rounded-2xl relative z-10">
                            <path className="ekg-path" d="M0 75 L80 75 L95 20 L115 130 L135 75 L160 75 L170 85 L180 65 L190 75 L300 75"
                                stroke="#E5734A" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="1000" strokeDashoffset="1000" />
                            <path d="M0 75 L80 75 L95 20 L115 130 L135 75 L160 75 L170 85 L180 65 L190 75 L300 75"
                                stroke="#071461" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" opacity="0.1" />
                        </svg>
                    </div>
                </div>
                <div className="flex-1 max-w-lg z-10">
                    <div className="font-mono text-coral text-xs tracking-widest uppercase mb-6 flex items-center gap-3">
                        <span className="w-8 h-px bg-coral"></span> Active
                    </div>
                    <h3 className="font-outfit font-bold text-4xl md:text-6xl lg:text-7xl text-navy mb-8 tracking-tight">It just keeps running.</h3>
                    <p className="font-sans text-lg md:text-xl text-ink/65 leading-relaxed font-light">
                        Auto-starts on boot, sips battery, and needs zero maintenance. Set it once
                        and the protection stays on &mdash; quietly, in the background.
                    </p>
                </div>
            </div>
        </section>
    );
}
