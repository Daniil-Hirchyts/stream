import type { ReactElement } from "react";

// 24-pixel line icons in one style: 1.8 strokes, round caps.
const stroke = (children: ReactElement) => (
  <g fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{children}</g>
);

const ICONS = {
  cast: <>{stroke(<><rect x="3" y="4" width="18" height="13" rx="2.5" /><path d="M8.5 20.5h7" /></>)}<path fill="currentColor" d="M10.2 8.2v4.6a.6.6 0 0 0 .9.5l3.8-2.3a.6.6 0 0 0 0-1l-3.8-2.3a.6.6 0 0 0-.9.5Z" /></>,
  remote: stroke(<><rect x="7" y="2.5" width="10" height="19" rx="3" /><circle cx="12" cy="8" r="2" /><path d="M10 13.5h.01M14 13.5h.01M10 17h.01M14 17h.01" /></>),
  tv: stroke(<><rect x="3" y="4.5" width="18" height="12.5" rx="2.5" /><path d="M8 20.5h8M12 17v3.5" /></>),
  activity: stroke(<path d="M3 12h4l2.5-6 5 12 2.5-6h4" />),
  refresh: stroke(<><path d="M20 12a8 8 0 1 1-2.6-5.9" /><path d="M20 4.5v4h-4" /></>),
  link: stroke(<><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></>),
  check: <path fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" d="m5 12.5 4.5 4.5L19 7.5" />,
  alert: stroke(<><circle cx="12" cy="12" r="9" /><path d="M12 7.5V13M12 16.5v.01" /></>),
  info: stroke(<><circle cx="12" cy="12" r="9" /><path d="M12 11v5.5M12 7.5v.01" /></>),
  success: stroke(<><circle cx="12" cy="12" r="9" /><path d="m8 12.3 2.8 2.8L16.2 9.5" /></>),
  close: <path fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" d="M7 7l10 10M17 7 7 17" />,
  play: <path fill="currentColor" d="M8 5.6v12.8a1 1 0 0 0 1.5.86l10.3-6.4a1 1 0 0 0 0-1.72L9.5 4.74A1 1 0 0 0 8 5.6Z" />,
  pause: <g fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1.2" /><rect x="14" y="5" width="4" height="14" rx="1.2" /></g>,
  stop: <rect fill="currentColor" x="6.5" y="6.5" width="11" height="11" rx="2.2" />,
  back10: <>{stroke(<><path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" /><path d="M4.5 3.8v3.7h3.7" /></>)}<text x="12.4" y="15" fill="currentColor" fontFamily="system-ui" fontSize="7" fontWeight="700" textAnchor="middle">10</text></>,
  forward10: <>{stroke(<><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3" /><path d="M19.5 3.8v3.7h-3.7" /></>)}<text x="11.6" y="15" fill="currentColor" fontFamily="system-ui" fontSize="7" fontWeight="700" textAnchor="middle">10</text></>,
  next: <><path fill="currentColor" d="M5.5 6.6v10.8a.8.8 0 0 0 1.2.68l8.4-5.4a.8.8 0 0 0 0-1.36L6.7 5.92a.8.8 0 0 0-1.2.68Z" />{stroke(<path d="M18.5 6v12" />)}</>,
  previous: <><path fill="currentColor" d="M18.5 6.6v10.8a.8.8 0 0 1-1.2.68l-8.4-5.4a.8.8 0 0 1 0-1.36l8.4-5.4a.8.8 0 0 1 1.2.68Z" />{stroke(<path d="M5.5 6v12" />)}</>,
  voice: stroke(<><path d="M4 9.5v5h3.5l4.5 4v-13l-4.5 4Z" /><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" /></>),
  cc: stroke(<><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M7 15h3.5M13 15h4M7 11.5h10" /></>),
  episodes: stroke(<><path d="M4 6.5h16M4 11.5h9M4 16.5h9" /><path d="m16 13.5 4.5 3-4.5 3Z" /></>),
  quality: stroke(<><rect x="3" y="5.5" width="18" height="13" rx="2.5" /><path d="M7 9.5v5M7 12h3.5M10.5 9.5v5M14 9.5v5h1.6a2.5 2.5 0 0 0 0-5Z" /></>),
  speed: stroke(<><path d="M4.5 17.5a8.5 8.5 0 1 1 15 0" /><path d="m12 13.5 4-4" /></>),
  version: stroke(<><path d="M12 3.5 3.5 8 12 12.5 20.5 8Z" /><path d="m3.5 12 8.5 4.5 8.5-4.5M3.5 16l8.5 4.5 8.5-4.5" /></>),
  resume: stroke(<><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>),
  convert: stroke(<path d="M4 8.5h14l-3.5-3.5M20 15.5H6l3.5 3.5" />),
  chevron: <path fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" d="m7 10 5 5 5-5" />,
  plus: <path fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" d="M12 5v14M5 12h14" />,
  trash: stroke(<><path d="M4.5 7h15M10 7V4.5h4V7" /><path d="m6.5 7 1 12.5h9l1-12.5" /></>),
  copy: stroke(<><rect x="8.5" y="8.5" width="11" height="11" rx="2" /><path d="M15.5 5.5V5a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 5v8A1.5 1.5 0 0 0 6 14.5h.5" /></>),
  wifi: stroke(<><path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0" /><path d="M12 19.5h.01" /></>),
  terminal: stroke(<><rect x="3" y="4.5" width="18" height="15" rx="2.5" /><path d="m7 10 3 2.5L7 15M12.5 15H17" /></>),
  style: stroke(<><path d="M5 19 12 5l7 14M7.8 13.5h8.4" /></>)
};

export type IconName = keyof typeof ICONS;

export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg className={`icon${className ? ` ${className}` : ""}`} viewBox="0 0 24 24" aria-hidden="true">
      {ICONS[name]}
    </svg>
  );
}
