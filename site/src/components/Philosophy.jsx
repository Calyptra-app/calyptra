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
            {/* Quiet organic background */}
            <div className="absolute inset-0 bg-grid-dark opacity-40 pointer-events-none"></div>
            <div className="parallax-pod absolute -right-40 top-1/2 -translate-y-1/2 w-[34rem] h-[40rem] bg-navy-700/20 blur-[100px] pod-shape pointer-events-none"></div>

            <div className="max-w-7xl mx-auto z-10 w-full">
                <div className="max-w-3xl">
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
            </div>
        </section>
    );
}
