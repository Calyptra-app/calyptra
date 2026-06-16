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
            <div className="absolute -bottom-52 right-0 w-[44rem] h-[44rem] bg-navy-700/40 blur-[120px] rounded-full pointer-events-none"></div>

            <div className="relative z-10 w-full max-w-7xl mx-auto grid lg:grid-cols-[1.15fr_0.85fr] gap-12 lg:gap-8 items-center">
                {/* Copy */}
                <div className="max-w-2xl">
                    <div className="hero-anim mb-7 inline-flex items-center text-coral-soft font-mono text-[11px] tracking-[0.22em] uppercase">
                        On-device protection for Android
                    </div>

                    <h1 className="mb-7">
                        <span className="hero-anim block font-sans font-extrabold text-[2.9rem] leading-[1.04] sm:text-6xl lg:text-[4.6rem] tracking-tight">
                            The shield that
                        </span>
                        <span className="hero-anim block font-drama italic font-semibold text-[3.2rem] leading-[1.1] pb-1 sm:text-6xl lg:text-[5rem] tracking-tight text-coral mt-1">
                            lets them grow.
                        </span>
                    </h1>

                    <p className="hero-anim font-outfit font-light text-lg md:text-xl lg:text-2xl text-white/75 leading-relaxed max-w-xl mb-10">
                        Calyptra blocks ads and trackers right on your child&rsquo;s phone.
                        Nothing leaves the device.{' '}
                        <span className="text-white/95 font-normal">No one sees what they do.</span>
                    </p>

                    <div className="hero-anim flex flex-col sm:flex-row items-stretch sm:items-center gap-4">
                        <a
                            href={DOWNLOAD_URL}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="magnetic-btn bg-coral text-white px-8 py-4 rounded-full font-sans font-bold text-lg shadow-[0_10px_30px_-12px_rgba(7,20,97,0.7)]"
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
                </div>

                {/* Emblem artwork */}
                <div className="hero-art relative hidden lg:flex items-center justify-center">
                    <div className="relative w-[26rem] h-[26rem] flex items-center justify-center">
                        {/* Static concentric rings echoing the icon */}
                        <svg className="absolute inset-0 w-full h-full" viewBox="0 0 400 400" fill="none" aria-hidden="true">
                            <circle cx="200" cy="200" r="190" stroke="rgba(255,255,255,0.10)" strokeWidth="1" />
                            <circle cx="200" cy="200" r="150" stroke="rgba(255,255,255,0.08)" strokeWidth="1" />
                        </svg>
                        <div className="relative w-60 h-60 rounded-[30%] overflow-hidden border border-white/10 glow-coral bg-navy">
                            <img src={logo} alt="The Calyptra app icon: a white emblem on a deep navy field" className="w-full h-full object-cover" />
                        </div>
                    </div>
                </div>
            </div>
        </section>
    );
}
