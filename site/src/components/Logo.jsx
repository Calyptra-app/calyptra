import logo from '../assets/calyptra-logo.png';

// The brand emblem (white calyptra pod on deep navy). Rounded so the navy
// field reads as an intentional badge rather than a square image.
export default function Logo({ size = 36, className = '' }) {
    return (
        <img
            src={logo}
            width={size}
            height={size}
            alt="Calyptra"
            className={`rounded-[28%] shadow-xs ${className}`}
            style={{ width: size, height: size }}
        />
    );
}
