import { useEffect, useRef } from 'react';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import usePrefersReducedMotion from '../usePrefersReducedMotion';

gsap.registerPlugin(ScrollTrigger);

const QUESTIONS = [
    {
        q: 'Can my child turn it off?',
        a: 'No. Switching protection off, or changing any setting, sits behind a PIN that only you set.',
    },
    {
        q: 'Will it slow down the phone?',
        a: 'No. Calyptra filters on the device and barely touches the battery. Most families forget it is even running.',
    },
    {
        q: 'Can you see what my child does online?',
        a: 'No. There are no accounts and no logs. Nothing about your child’s activity ever leaves their phone.',
    },
    {
        q: 'Is it really free?',
        a: 'Yes. Calyptra is free and open source, with no ads and no in-app purchases. There is no paid tier.',
    },
    {
        q: 'Why isn’t it on the Play Store?',
        a: 'Google’s store bans ad-blocking apps, so you install Calyptra directly. Android asks you to confirm the first time.',
    },
    {
        q: 'What age is it for?',
        a: 'Any age. The one-tap switch is simple enough for young children, and the PIN keeps you in control.',
    },
    {
        q: 'Can I block apps like TikTok or Instagram?',
        a: 'Yes. You can switch off TikTok, Instagram, Snapchat, Facebook, X, Reddit, Discord and Twitch individually, and change your mind any time.',
    },
    {
        q: 'What about YouTube?',
        a: 'Rather than block it outright, Calyptra turns on YouTube’s Restricted Mode, which hides mature content while keeping the app usable.',
    },
    {
        q: 'Does it block dangerous or adult sites?',
        a: 'Yes. Known malware and phishing sites are blocked automatically, and you can switch on adult-content blocking with one tap. It matches sites against trusted lists, so it catches known bad domains, not every possible one.',
    },
    {
        q: 'What if a safe site gets blocked?',
        a: 'Open “Allowed sites” in the parent settings and add it. Calyptra will always let that site through, even if a list flagged it by mistake.',
    },
];

export default function Faq() {
    const sectionRef = useRef(null);
    const reduced = usePrefersReducedMotion();

    useEffect(() => {
        if (reduced) return;
        const ctx = gsap.context(() => {
            gsap.from('.faq-item', {
                scrollTrigger: { trigger: sectionRef.current, start: 'top 75%' },
                y: 32,
                opacity: 0,
                duration: 0.8,
                stagger: 0.08,
                ease: 'power3.out',
            });
        }, sectionRef);
        return () => ctx.revert();
    }, [reduced]);

    return (
        <section id="faq" ref={sectionRef} className="py-28 md:py-40 px-6 md:px-12 lg:px-24 bg-cream relative z-10 border-t border-navy/5">
            <div className="max-w-7xl mx-auto">
                <div className="max-w-2xl mb-16 md:mb-20">
                    <h2 className="font-sans font-bold text-4xl md:text-6xl text-navy tracking-tight leading-[1.08]">
                        Questions parents ask.
                    </h2>
                    <p className="font-outfit text-ink/65 text-base md:text-lg leading-relaxed mt-5">
                        The short answers to what parents usually want to know before installing.
                    </p>
                </div>

                <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-12 lg:gap-x-20 gap-y-10">
                    {QUESTIONS.map(({ q, a }) => (
                        <div key={q} className="faq-item border-t border-navy/10 pt-6">
                            <dt className="font-outfit font-semibold text-xl text-navy mb-3">{q}</dt>
                            <dd className="font-sans text-ink/65 text-base leading-relaxed max-w-md">{a}</dd>
                        </div>
                    ))}
                </dl>
            </div>
        </section>
    );
}
