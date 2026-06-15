import { useEffect, useState } from 'react';

// Tracks the user's "reduce motion" OS/browser preference so JS-driven
// animations (GSAP) can opt out, the same way our CSS does.
export default function usePrefersReducedMotion() {
    const query = '(prefers-reduced-motion: reduce)';
    const [reduced, setReduced] = useState(
        () => typeof window !== 'undefined' && window.matchMedia(query).matches,
    );

    useEffect(() => {
        const mql = window.matchMedia(query);
        const onChange = () => setReduced(mql.matches);
        mql.addEventListener('change', onChange);
        return () => mql.removeEventListener('change', onChange);
    }, []);

    return reduced;
}
