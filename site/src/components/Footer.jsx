import { Lock } from 'lucide-react';
import Logo from './Logo';
import { REPO_URL, LICENSE_URL, PRIVACY_URL, ATTRIBUTIONS_URL, SUPPORT_URL } from '../constants';

export default function Footer() {
    const year = new Date().getFullYear();

    return (
        <footer className="w-full bg-[#040A33] text-white px-6 md:px-16 py-16 relative z-20 border-t border-white/5">
            <div className="max-w-7xl mx-auto flex flex-col md:flex-row gap-12 md:gap-0 justify-between items-start md:items-end">
                <div className="flex flex-col gap-6 max-w-sm">
                    <div className="flex items-center gap-3">
                        <Logo size={36} />
                        <h3 className="font-outfit font-bold text-3xl tracking-tight text-white">Calyptra</h3>
                    </div>
                    <p className="font-sans text-white/55 text-sm leading-relaxed">
                        The shield that lets them grow. On-device ad and tracker blocking for
                        kids&rsquo; Android phones, with no cloud and nothing to track.
                    </p>
                    <div className="flex items-center gap-2.5 bg-white/5 border border-white/10 rounded-full px-5 py-2 w-fit mt-2">
                        <Lock size={13} className="text-coral" />
                        <span className="font-mono text-[10px] tracking-[0.2em] text-white/75 uppercase">Runs entirely on the phone</span>
                    </div>
                </div>

                <div className="flex flex-col sm:flex-row gap-12 sm:gap-20 font-sans text-sm">
                    <div className="flex flex-col gap-4">
                        <span className="font-mono text-[10px] text-white/35 tracking-widest uppercase mb-1">Resources</span>
                        <a href={REPO_URL} target="_blank" rel="noopener noreferrer" className="font-outfit text-white/70 hover:text-coral transition-colors">GitHub repository</a>
                        <a href={PRIVACY_URL} target="_blank" rel="noopener noreferrer" className="font-outfit text-white/70 hover:text-coral transition-colors">Privacy</a>
                        <a href={ATTRIBUTIONS_URL} target="_blank" rel="noopener noreferrer" className="font-outfit text-white/70 hover:text-coral transition-colors">Attributions</a>
                        <a href={LICENSE_URL} target="_blank" rel="noopener noreferrer" className="font-outfit text-white/70 hover:text-coral transition-colors">License (AGPL-3.0)</a>
                        <a href={SUPPORT_URL} target="_blank" rel="noopener noreferrer" className="font-outfit text-white/70 hover:text-coral transition-colors">Buy us a coffee</a>
                    </div>
                    <div className="flex flex-col gap-4 sm:text-right">
                        <span className="font-mono text-[10px] text-white/35 tracking-widest uppercase mb-1">System info</span>
                        <span className="font-outfit text-white/50">&copy; {year} Calyptra</span>
                        <span className="font-outfit text-white/50">Beta release, installed as an APK</span>
                        <span className="font-outfit text-white/50">Free and open source</span>
                    </div>
                </div>
            </div>
        </footer>
    );
}
