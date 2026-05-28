import React from "react";

// Designer-spec dot positions in the logo's design coordinate space.
const DOTS = [
  { x: 250.194, y: -95.687 },
  { x: 278.194, y: 102.829 },
  { x: 200.497, y: -206.573 },
  { x: 200.497, y: 282.279 },
  { x: -83.02, y: 296.279 },
  { x: -138.329, y: -178.573 },
  { x: 31.323, y: -296.5 },
  { x: -278.5, y: -67.687 },
  { x: -278.5, y: 169.605 },
];
const LOGO_DESIGN_WIDTH = 510;
const DOT_DESIGN_SIZE = 28;
const DOT_COLOR = "#d4af83";

export interface GlowLogoProps {
  sizePx: number;
  starsAnimating?: boolean;
  onClick?: () => void;
  imgClassName?: string;
  alt?: string;
}

const GlowLogo: React.FC<GlowLogoProps> = ({
  sizePx,
  starsAnimating = false,
  onClick,
  imgClassName = "",
  alt = "Glow",
}) => {
  const scale = sizePx / LOGO_DESIGN_WIDTH;
  const dotSize = DOT_DESIGN_SIZE * scale;
  const halfDot = dotSize / 2;

  return (
    <div className="relative flex items-center justify-center" style={{ width: sizePx, height: sizePx }}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/assets/Glow_Logo.svg"
        alt={alt}
        className={`w-full h-full object-contain ${imgClassName}`}
        onClick={onClick}
      />
      {DOTS.map((dot, i) => (
        <span
          key={i}
          className={`sidebar-star ${starsAnimating ? "animate" : ""}`}
          style={{
            width: dotSize,
            height: dotSize,
            left: `calc(50% + ${dot.x * scale - halfDot}px)`,
            top: `calc(50% + ${dot.y * scale - halfDot}px)`,
            animationDelay: `${i * 0.05}s`,
            boxShadow: starsAnimating ? `0 0 ${Math.max(dotSize * 1.5, 4)}px ${DOT_COLOR}` : "none",
          }}
        />
      ))}
    </div>
  );
};

export default GlowLogo;
