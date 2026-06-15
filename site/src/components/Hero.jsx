import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import logo from '../assets/calyptra-logo.png';
import { DOWNLOAD_URL } from '../constants';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

export default function Hero() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.from('.hero-anim', {
                y: 36,
                opacity: 0,
                duration: 1.1,
                stagger: 0.09,
                ease: 'power3.out',
                delay: 0.15,
            });
            gsap.from('.hero-art', {
                opacity: 0,
                scale: 0.92,
                duration: 1.6,
                delay: 0.5,
                ease: 'power2.out',
            });
            gsap.to('.ring-slow', { rotation: 360, transformOrigin: 'center', repeat: -1, duration: 26, ease: 'none' });
            gsap.to('.ring-fast', { rotation: -360, transformOrigin: 'center', repeat: -1, duration: 18, ease: 'none' });
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <section
            id="top"
            ref={containerRef}
            className="relative w-full min-h-[100dvh] flex items-center overflow-hidden bg-navy text-white pt-32 pb-20 md:pt-28 md:pb-24 px-6 md:px-12 lg:px-24"
        >
            {/* Background layers */}
            <div className="absolute inset-0 bg-grid-dark opacity-60"></div>
            <div className="absolute -top-40 -left-40 w-[40rem] h-[40rem] bg-coral/15 blur-[140px] rounded-full pointer-events-none"></div>
            <div className="absolute -bottom-52 right-0 w-[44rem] h-[44rem] bg-navy-700/70 blur-[120px] rounded-full pointer-events-none"></div>
            <div className="absolute inset-0 bg-[radial-gradient(circle_at_75%_40%,rgba(229,115,74,0.10),transparent_55%)] pointer-events-none"></div>

            {/* Lab framing marks */}
            <div className="absolute top-28 right-12 ui-mark text-white/25 font-mono text-[10px] hidden lg:block tracking-[0.3em]">
                SYS.SHIELD // ACTIVE
            </div>
            <div className="absolute bottom-10 left-12 text-white/25 font-mono text-[10px] hidden lg:block tracking-[0.3em]">
                LOCAL_ONLY · 0_TELEMETRY
            </div>

            <div className="relative z-10 w-full max-w-7xl mx-auto grid lg:grid-cols-[1.15fr_0.85fr] gap-12 lg:gap-8 items-center">
                {/* Copy */}
                <div className="max-w-2xl">
                    <div className="hero-anim mb-7 inline-flex items-center gap-3 text-coral-soft font-mono text-[11px] tracking-[0.22em] uppercase">
                        <span className="w-2 h-2 rounded-full bg-coral animate-pulse-soft"></span>
                        On-device · Android
                    </div>

                    <h1 className="mb-7">
                        <span className="hero-anim block font-sans font-extrabold text-[2.9rem] leading-[1.04] sm:text-6xl lg:text-[4.6rem] tracking-tight">
                            The shield that
                        </span>
                        <span className="hero-anim block font-drama italic font-medium text-[3.2rem] leading-[1] sm:text-6xl lg:text-[5rem] tracking-tight text-coral mt-1">
                            lets them grow.
                        </span>
                    </h1>

                    <p className="hero-anim font-outfit font-light text-lg md:text-xl lg:text-2xl text-white/75 leading-relaxed max-w-xl mb-10">
                        Calyptra blocks ads and trackers right on your child&rsquo;s phone.
                        No root. No cloud. No telemetry.{' '}
                        <span className="text-white/95 font-normal">Nothing ever leaves the device.</span>
                    </p>

                    <div className="hero-anim flex flex-col sm:flex-row items-stretch sm:items-center gap-4 mb-10">
                        <a
                            href={DOWNLOAD_URL}
                            target="_blank"
                            rel="noreferrer"
                            className="magnetic-btn bg-coral text-white px-8 py-4 rounded-full font-sans font-bold text-lg shadow-[0_8px_40px_rgba(229,115,74,0.3)]"
                        >
                            <span className="relative z-10">Download for Android</span>
                            <span className="hover-bg rounded-full"></span>
                        </a>
                        <a
                            href="#how-it-works"
                            className="group inline-flex items-center justify-center gap-2 font-sans font-semibold text-white/80 hover:text-white transition-colors px-4 py-4"
                        >
                            See how it works
                            <span className="text-coral group-hover:translate-y-0.5 transition-transform">↓</span>
                        </a>
                    </div>

                    <div className="hero-anim flex flex-wrap items-center gap-x-6 gap-y-2 font-mono text-[11px] uppercase tracking-[0.18em] text-white/45">
                        <span>No root</span>
                        <span className="w-1 h-1 rounded-full bg-white/30"></span>
                        <span>No account</span>
                        <span className="w-1 h-1 rounded-full bg-white/30"></span>
                        <span>Open source</span>
                        <span className="w-1 h-1 rounded-full bg-white/30"></span>
                        <span>Free, no ads</span>
                    </div>
                </div>

                {/* Emblem artwork */}
                <div className="hero-art relative hidden lg:flex items-center justify-center">
                    <div className="relative w-[26rem] h-[26rem] flex items-center justify-center">
                        {/* Concentric rings echoing the icon */}
                        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 400 400" fill="none" aria-hidden="true">
                            <circle className="ring-slow" cx="200" cy="200" r="190" stroke="rgba(229,115,74,0.35)" strokeWidth="1" strokeDasharray="2 14" />
                            <circle className="ring-fast" cx="200" cy="200" r="150" stroke="rgba(255,255,255,0.12)" strokeWidth="1" strokeDasharray="10 12" />
                        </svg>
                        <div className="absolute w-72 h-72 bg-coral/20 blur-[80px] rounded-full"></div>
                        <div className="relative w-60 h-60 rounded-[30%] overflow-hidden border border-white/10 shadow-2xl glow-coral animate-float bg-navy">
                            <img src={logo} alt="Calyptra emblem" className="w-full h-full object-cover" />
                        </div>
                    </div>
                </div>
            </div>
        </section>
    );
}
