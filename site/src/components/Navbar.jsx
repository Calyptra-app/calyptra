import { useState, useEffect } from 'react';
import Logo from './Logo';
import { DOWNLOAD_URL } from '../constants';

export default function Navbar() {
    const [scrolled, setScrolled] = useState(false);

    useEffect(() => {
        const handleScroll = () => setScrolled(window.scrollY > 40);
        handleScroll();
        window.addEventListener('scroll', handleScroll, { passive: true });
        return () => window.removeEventListener('scroll', handleScroll);
    }, []);

    return (
        <nav className="fixed top-5 left-0 right-0 z-50 flex justify-center px-4 pointer-events-none">
            <div
                className={`pointer-events-auto flex items-center justify-between gap-4 px-4 sm:px-6 py-3 rounded-full transition-all duration-500 w-full max-w-5xl border ${scrolled
                    ? 'bg-cream/85 backdrop-blur-xl border-navy/10 text-navy shadow-lg shadow-navy/5'
                    : 'bg-white/5 backdrop-blur-sm border-white/10 text-white'
                    }`}
            >
                <a href="#top" className="flex items-center gap-2.5 hover-lift" aria-label="Calyptra home">
                    <Logo size={32} />
                    <span className="font-outfit font-bold tracking-tight text-xl md:text-2xl">Calyptra</span>
                </a>

                <div className="hidden md:flex items-center gap-8 font-sans font-medium text-sm">
                    <a href="#features" className="hover-lift opacity-90 hover:opacity-100">Features</a>
                    <a href="#how-it-works" className="hover-lift opacity-90 hover:opacity-100">How it works</a>
                    <a href="#privacy" className="hover-lift opacity-90 hover:opacity-100">Privacy</a>
                </div>

                <a
                    href={DOWNLOAD_URL}
                    target="_blank"
                    rel="noreferrer"
                    className="magnetic-btn px-5 py-2.5 rounded-full font-sans font-semibold text-sm bg-coral text-white shadow-md shadow-coral/20"
                >
                    <span className="relative z-10">Download</span>
                    <span className="hover-bg rounded-full"></span>
                </a>
            </div>
        </nav>
    );
}
