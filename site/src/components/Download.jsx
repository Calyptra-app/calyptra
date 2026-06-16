import { ArrowDownToLine, Github } from 'lucide-react';
import { DOWNLOAD_URL, REPO_URL } from '../constants';

export default function Download() {
    return (
        <section id="download" className="w-full py-28 md:py-44 px-6 flex flex-col items-center justify-center bg-navy-900 text-center relative z-10 overflow-hidden border-t border-coral/30">
            <div className="absolute inset-0 bg-grid-dark opacity-25 pointer-events-none"></div>
            <div className="absolute inset-x-0 top-0 h-40 bg-linear-to-b from-navy/40 to-transparent pointer-events-none z-0"></div>

            <div className="max-w-4xl flex flex-col items-center relative z-10">
                <div className="mb-8 flex items-center justify-center w-16 h-16 rounded-full bg-coral/10 border border-coral/25 text-coral">
                    <ArrowDownToLine size={28} />
                </div>

                <h2 className="font-sans font-bold text-4xl md:text-6xl lg:text-7xl text-white tracking-tight mb-8 leading-[1.08]">
                    Privacy is a right.<br />
                    <span className="font-drama italic text-coral font-semibold mt-2 inline-block leading-[1.1] pb-1">Not a premium feature.</span>
                </h2>

                <p className="font-outfit font-light text-lg md:text-xl text-white/65 max-w-xl mb-10 leading-relaxed">
                    Calyptra is free and open source. Grab the latest version and protect your
                    child&rsquo;s phone in a couple of taps.
                </p>

                <div className="flex flex-col sm:flex-row items-center gap-4 mb-10">
                    <a
                        href={DOWNLOAD_URL}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="magnetic-btn bg-coral text-white px-10 py-5 rounded-full font-sans font-bold text-lg shadow-[0_8px_40px_rgba(229,115,74,0.3)] leading-none"
                    >
                        <span className="relative z-10 flex items-center gap-2">
                            <ArrowDownToLine size={20} /> Download for Android
                        </span>
                        <span className="hover-bg rounded-full"></span>
                    </a>
                    <a
                        href={REPO_URL}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="magnetic-btn border border-white/20 text-white px-8 py-5 rounded-full font-sans font-semibold text-lg leading-none hover:bg-white/5"
                    >
                        <span className="relative z-10 flex items-center gap-2">
                            <Github size={20} /> View source
                        </span>
                    </a>
                </div>

                <p className="-mt-4 mb-10 text-white/40 font-sans text-xs leading-relaxed">
                    Want to be sure your download is the real thing? You can check it against the{' '}
                    <a
                        href={DOWNLOAD_URL}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="underline decoration-white/30 underline-offset-2 hover:text-white/60 transition-colors"
                    >
                        official release page
                    </a>
                    .
                </p>

                <div className="flex flex-wrap items-center justify-center gap-x-8 gap-y-3 text-white/60 font-mono text-xs tracking-tight border border-white/10 bg-white/5 px-8 py-4 rounded-2xl">
                    <span>Open source</span>
                    <span>No ads</span>
                    <span>No in-app purchases</span>
                    <span>Works on Android 8 and up</span>
                </div>

                <p className="mt-6 text-white/40 font-sans text-xs max-w-md leading-relaxed">
                    Calyptra isn&rsquo;t on the Play Store, which bans ad-blocking apps, so you
                    install it directly. Android will ask you to confirm the first time.
                </p>
            </div>
        </section>
    );
}
