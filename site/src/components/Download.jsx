import { ArrowDownToLine, Github } from 'lucide-react';
import { DOWNLOAD_URL, REPO_URL } from '../constants';

export default function Download() {
    return (
        <section id="download" className="w-full py-28 md:py-44 px-6 flex flex-col items-center justify-center bg-navy-900 text-center relative z-10 overflow-hidden border-t border-coral/30">
            <div className="absolute inset-0 bg-grid-dark opacity-25 pointer-events-none"></div>
            <div className="absolute inset-x-0 top-0 h-40 bg-gradient-to-b from-navy/40 to-transparent pointer-events-none z-0"></div>
            <div className="absolute -bottom-40 left-1/2 -translate-x-1/2 w-[40rem] h-[40rem] bg-coral/10 blur-[130px] rounded-full pointer-events-none"></div>

            <div className="absolute top-12 left-12 text-white/25 font-mono text-[10px] hidden md:block tracking-widest">SYS_DL_NODE // ONLINE</div>

            <div className="max-w-4xl flex flex-col items-center relative z-10">
                <div className="mb-8 flex items-center justify-center w-16 h-16 rounded-full bg-coral/10 border border-coral/25 text-coral">
                    <ArrowDownToLine size={28} />
                </div>

                <h2 className="font-sans font-bold text-4xl md:text-6xl lg:text-7xl text-white tracking-tight mb-8 leading-[1.08]">
                    Privacy is a right.<br />
                    <span className="font-drama italic text-coral font-medium mt-2 block">Not a premium feature.</span>
                </h2>

                <p className="font-outfit font-light text-lg md:text-xl text-white/65 max-w-xl mb-10 leading-relaxed">
                    Calyptra is free and open source. Grab the latest APK and protect your
                    child&rsquo;s device in a couple of taps.
                </p>

                <div className="flex flex-col sm:flex-row items-center gap-4 mb-10">
                    <a
                        href={DOWNLOAD_URL}
                        target="_blank"
                        rel="noreferrer"
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
                        rel="noreferrer"
                        className="magnetic-btn border border-white/20 text-white px-8 py-5 rounded-full font-sans font-semibold text-lg leading-none hover:bg-white/5"
                    >
                        <span className="relative z-10 flex items-center gap-2">
                            <Github size={20} /> View source
                        </span>
                    </a>
                </div>

                <div className="flex flex-col md:flex-row items-center justify-center gap-3 md:gap-4 text-white/60 font-mono text-xs tracking-tight border border-white/10 bg-white/5 px-7 py-3 rounded-full backdrop-blur-md">
                    <span>Open source</span>
                    <span className="hidden md:block w-1 h-1 rounded-full bg-white/30"></span>
                    <span>No ads</span>
                    <span className="hidden md:block w-1 h-1 rounded-full bg-white/30"></span>
                    <span>No in-app purchases</span>
                    <span className="hidden md:block w-1 h-1 rounded-full bg-white/30"></span>
                    <span>Min Android 8.0</span>
                </div>

                <p className="mt-6 text-white/40 font-sans text-xs max-w-md leading-relaxed">
                    Not on the Play Store, whose policy prohibits ad-blocking VPN apps. You install
                    the APK directly &mdash; Android will ask you to allow it from this source.
                </p>
            </div>
        </section>
    );
}
