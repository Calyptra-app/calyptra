import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

export default function Philosophy() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.to('.parallax-pod', {
                yPercent: 22,
                ease: 'none',
                scrollTrigger: {
                    trigger: containerRef.current,
                    start: 'top bottom',
                    end: 'bottom top',
                    scrub: true,
                },
            });
            gsap.from('.philo-text', {
                scrollTrigger: { trigger: containerRef.current, start: 'top 62%' },
                y: 40,
                opacity: 0,
                duration: 1.1,
                stagger: 0.14,
                ease: 'power3.out',
            });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <section
            id="privacy"
            ref={containerRef}
            className="relative w-full min-h-[88vh] bg-navy text-white flex items-center overflow-hidden border-t border-coral/20 py-28 md:py-36 px-6 md:px-12 lg:px-24"
        >
            {/* Organic decorative background */}
            <div className="absolute inset-0 bg-grid-dark opacity-40 pointer-events-none"></div>
            <div className="parallax-pod absolute -right-32 top-1/2 -translate-y-1/2 w-[34rem] h-[40rem] bg-gradient-to-br from-coral/15 to-navy-700/10 blur-[90px] pod-shape pointer-events-none"></div>
            <div className="absolute inset-0 bg-[radial-gradient(circle_at_25%_50%,rgba(229,115,74,0.08),transparent_55%)] pointer-events-none"></div>

            <div className="max-w-7xl mx-auto z-10 w-full flex flex-col md:flex-row gap-12 md:gap-24 items-center justify-between">
                <div className="flex-1">
                    <span className="philo-text font-mono text-coral text-sm tracking-widest uppercase mb-8 flex items-center gap-4">
                        <span className="w-12 h-px bg-coral"></span>
                        The promise
                    </span>
                    <p className="philo-text font-sans text-2xl md:text-3xl text-white/55 mb-6 font-light leading-snug">
                        Most parental controls{' '}
                        <i className="font-drama italic text-white/90 pr-1 text-3xl md:text-4xl">watch</i>{' '}
                        your child<br className="hidden md:block" /> to protect them.
                    </p>
                    <div className="philo-text w-full h-px bg-white/10 my-8"></div>
                    <h2 className="philo-text font-sans font-bold text-4xl md:text-6xl lg:text-7xl tracking-tight leading-[1.04]">
                        Calyptra protects them<br />
                        <span className="font-drama italic text-coral font-medium block mt-3">without watching them.</span>
                    </h2>
                    <p className="philo-text font-outfit text-white/60 text-lg max-w-xl mt-8 leading-relaxed font-light">
                        No screen-time reports. No location history. No browsing logs sent to a
                        dashboard. The filtering happens on the phone, and the data stays there.
                    </p>
                </div>

                <div className="philo-text hidden lg:flex flex-col gap-10 w-24 items-center justify-center py-12 relative">
                    <div className="absolute top-0 right-1/2 w-px h-full bg-gradient-to-b from-transparent via-white/20 to-transparent"></div>
                    <span className="font-mono text-[10px] text-white/40 -rotate-90 whitespace-nowrap tracking-[0.3em] uppercase">Surveillance-free</span>
                    <div className="w-px h-28 bg-coral/50"></div>
                    <span className="font-mono text-[10px] text-white/40 -rotate-90 whitespace-nowrap tracking-[0.3em] uppercase">Data stays local</span>
                </div>
            </div>
        </section>
    );
}
