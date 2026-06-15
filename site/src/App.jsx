import Navbar from './components/Navbar';
import Hero from './components/Hero';
import Features from './components/Features';
import Philosophy from './components/Philosophy';
import HowItWorks from './components/HowItWorks';
import Download from './components/Download';
import Footer from './components/Footer';

function App() {
  return (
    <main className="relative w-full bg-cream min-h-screen text-ink font-sans selection:bg-coral selection:text-white overflow-x-hidden">
      {/* Global film-grain overlay */}
      <div className="noise-overlay" aria-hidden="true"></div>

      <Navbar />
      <Hero />
      <Features />
      <Philosophy />
      <HowItWorks />
      <Download />
      <Footer />
    </main>
  );
}

export default App;
