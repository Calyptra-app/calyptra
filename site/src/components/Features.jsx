import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { ShieldAlert, Lock } from 'lucide-react';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

// Card 1 — DNS blocking: a shuffling stack of blocked DNS requests.
function DnsShuffler() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();
    const domains = ['ads.doubleclick.net', 'tracker.segment.io', 'telemetry.google.com'];

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
            <div className="absolute top-0 right-0 p-6 text-navy opacity-[0.04] group-hover:opacity-[0.07] transition-opacity">
                <ShieldAlert size={120} />
            </div>
            <div className="flex-1 flex items-center justify-center relative min-h-[210px] z-10" ref={containerRef}>
                {domains.map((domain) => (
                    <div key={domain} className="shuffler-card absolute w-full max-w-[280px] bg-navy text-white p-4 rounded-xl shadow-2xl border border-white/10 flex flex-col gap-2">
                        <div className="flex justify-between items-center text-xs font-mono text-white/70">
                            <span>DNS request</span>
                            <span className="text-coral font-bold tracking-wider">BLOCKED</span>
                        </div>
                        <span className="font-mono text-sm break-all">{domain}</span>
                        <div className="w-full h-1 bg-white/10 rounded-full mt-2 overflow-hidden">
                            <div className="w-full h-full bg-coral/70"></div>
                        </div>
                    </div>
                ))}
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">Every request, inspected.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed">
                    Filtering happens at the DNS layer, so ads and trackers are stopped across
                    every app and browser &mdash; not just one.
                </p>
            </div>
        </article>
    );
}

// Card 2 — Zero telemetry: a terminal that types out the privacy promise.
function TelemetryTerminal() {
    const reduced = usePrefersReducedMotion();
    const messages = ['no_analytics_loaded', 'no_account_required', 'no_data_transmitted', 'on_device_only: true'];
    const [text, setText] = useState(reduced ? 'on_device_only: true' : '');

    useEffect(() => {
        if (reduced) return;
        let msgIdx = 0;
        let charIdx = 0;
        let deleting = false;
        let timeout;

        function type() {
            const current = messages[msgIdx];
            charIdx += deleting ? -1 : 1;
            setText(current.substring(0, charIdx));

            let speed = deleting ? 30 : 70;
            if (!deleting && charIdx === current.length) {
                speed = 1900;
                deleting = true;
            } else if (deleting && charIdx === 0) {
                deleting = false;
                msgIdx = (msgIdx + 1) % messages.length;
                speed = 450;
            }
            timeout = setTimeout(type, speed);
        }
        timeout = setTimeout(type, 900);
        return () => clearTimeout(timeout);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [reduced]);

    return (
        <article className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-52 h-52 bg-coral/5 rounded-full blur-3xl"></div>
            <div className="flex-1 flex flex-col items-start justify-center min-h-[210px] w-full bg-navy rounded-[1.4rem] p-6 border border-white/5 relative z-10 shadow-inner">
                <div className="flex items-center gap-2 mb-4 w-full border-b border-white/10 pb-3">
                    <span className="w-2.5 h-2.5 rounded-full bg-coral animate-pulse-soft shadow-[0_0_10px_#E5734A]"></span>
                    <span className="font-mono text-[10px] uppercase text-white/45 tracking-widest">calyptra terminal</span>
                </div>
                <div className="font-mono text-white/90 text-sm sm:text-base leading-relaxed h-[60px] flex items-start">
                    <span className="text-coral mr-2">root@local:~#</span>
                    <span>{text}</span>
                    <span className="inline-block w-2 h-5 bg-white/80 ml-1 animate-pulse-soft"></span>
                </div>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5 mt-6">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">What we collect: nothing.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed">
                    No analytics, no crash logs, no account &mdash; not even an app-open count.
                    Zero telemetry is the whole point.
                </p>
            </div>
        </article>
    );
}

// Card 3 — Kid-simple, parent-controlled: a protection toggle that flips on.
function KidParentToggle() {
    const containerRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return; // toggle stays in its static "off" state
        const ctx = gsap.context(() => {
            const tl = gsap.timeline({ repeat: -1, repeatDelay: 1.4 });
            // Flip on
            tl.to('.toggle-knob', { x: 32, duration: 0.5, ease: 'back.out(2)' })
                .to('.toggle-track', { backgroundColor: '#E5734A', borderColor: '#E5734A', duration: 0.4 }, '<')
                .to('.label-off', { opacity: 0, y: -6, duration: 0.2 }, '<0.1')
                .to('.label-on', { opacity: 1, y: 0, duration: 0.25 }, '<')
                .to({}, { duration: 2 })
                // Flip off
                .to('.toggle-knob', { x: 0, duration: 0.5, ease: 'back.out(2)' })
                .to('.toggle-track', { backgroundColor: 'rgba(7,20,97,0.10)', borderColor: 'rgba(7,20,97,0.15)', duration: 0.4 }, '<')
                .to('.label-on', { opacity: 0, y: 6, duration: 0.2 }, '<0.1')
                .to('.label-off', { opacity: 1, y: 0, duration: 0.25 }, '<');
        }, containerRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <article ref={containerRef} className="flex flex-col h-full bg-white rounded-[2.25rem] border border-navy/10 shadow-xl shadow-navy/5 p-8 hover-lift relative overflow-hidden">
            <div className="flex-1 flex flex-col items-center justify-center gap-6 min-h-[210px] relative w-full z-10 bg-cream/60 rounded-[1.4rem] border border-navy/5 p-6">
                {/* Big kid-friendly toggle */}
                <div aria-hidden="true" className="toggle-track relative w-[76px] h-10 rounded-full border border-navy/15 bg-navy/10">
                    <span className="toggle-knob absolute top-1/2 -translate-y-1/2 left-1 w-8 h-8 rounded-full bg-white shadow-md"></span>
                </div>
                <div className="relative h-4 w-full flex items-center justify-center" aria-hidden="true">
                    <span className="label-off absolute font-mono text-[11px] tracking-[0.2em] text-navy/60 uppercase">Tap to protect</span>
                    <span className="label-on absolute font-mono text-[11px] tracking-[0.2em] text-coral uppercase opacity-0">Protection on</span>
                </div>

                {/* Parent PIN badge */}
                <div className="flex items-center gap-2 px-3 py-1.5 rounded-full border border-navy/15 bg-white text-[10px] font-mono font-bold text-navy/70 uppercase tracking-widest">
                    <Lock size={12} className="text-coral" />
                    Parent PIN
                </div>
            </div>
            <div className="relative z-10 pt-5 border-t border-navy/5 mt-6">
                <h3 className="font-outfit font-bold text-2xl text-navy mb-2">One tap for kids. PIN for parents.</h3>
                <p className="font-sans text-ink/65 text-sm leading-relaxed">
                    A single, friendly switch turns protection on. Settings &mdash; and turning it
                    off &mdash; live safely behind a parent PIN.
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
                <div className="mb-16 md:mb-24 flex flex-col md:flex-row md:items-end justify-between gap-8 border-b border-navy/10 pb-12">
                    <div className="max-w-2xl">
                        <span className="font-mono text-coral text-sm tracking-widest uppercase mb-4 flex items-center gap-3">
                            <span className="w-1.5 h-1.5 bg-coral rounded-full"></span> Core capabilities
                        </span>
                        <h2 className="font-sans font-bold text-4xl md:text-6xl text-navy tracking-tight leading-[1.08]">
                            Built to protect,<br />
                            <span className="font-drama italic font-medium text-coral">never to surveil.</span>
                        </h2>
                    </div>
                    <p className="font-outfit text-ink/65 max-w-sm text-sm md:text-base leading-relaxed">
                        No generic web filter. Calyptra uses Android&rsquo;s own VPN to cut trackers
                        off at the source &mdash; all on the device, all under your control.
                    </p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-6 lg:gap-8">
                    <div className="feature-card-wrapper min-h-[460px]"><DnsShuffler /></div>
                    <div className="feature-card-wrapper min-h-[460px]"><TelemetryTerminal /></div>
                    <div className="feature-card-wrapper min-h-[460px]"><KidParentToggle /></div>
                </div>
            </div>
        </section>
    );
}
