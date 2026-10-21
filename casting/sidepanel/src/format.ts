/** 0:42, 12:05 or 1:02:09. */
export function time(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600), minutes = Math.floor(total / 60) % 60, seconds = String(total % 60).padStart(2, "0");
  return hours ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}

export function ago(at: number, now = Date.now()): string {
  const seconds = Math.round((now - at) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86400) return new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return new Date(at).toLocaleDateString([], { day: "numeric", month: "short" });
}

/** Sites pad titles with "watch online free in HD"; keep the name. */
export function cleanTitle(title: string): string {
  return title.split(/\s+(?:смотреть|дивитися|watch)\s/i)[0].split(/\s[|–—]\s/)[0].trim() || title;
}

/** A series name without the player's "(2 сезон) - 1 серия". */
export function seriesTitle(title: string): string {
  return title.replace(/\s*\([\d\s,–-]+(?:сезон|season)[^)]*\)\s*/i, " ").replace(/\s*[-–—]\s*\d+\s*(?:серия|episode).*$/i, "").trim();
}

/** Films are often cropped (1920 × 800), so the width counts too: 1920 wide is 1080p. */
export function lines(width = 0, height = 0): number {
  const value = Math.max(height, Math.round(width * 9 / 16));
  if (!value) return 0;
  if (value >= 2000) return 2160;
  if (value >= 1300) return 1440;
  if (value >= 1000) return 1080;
  if (value >= 650) return 720;
  if (value >= 440) return 480;
  if (value >= 330) return 360;
  return 240;
}

export const qualityLabel = (value: number) => (value === 2160 ? "4K" : value ? `${value}p` : "");

export function fileName(url: string): string {
  try {
    const parsed = new URL(url);
    return decodeURIComponent(parsed.pathname.split("/").pop() || parsed.hostname);
  } catch {
    return url;
  }
}

export function host(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

export const LANGUAGE = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;
// Language codes and names found in subtitle names, mapped to ISO 639-1.
const LANGUAGE_WORDS: Record<string, string> = Object.fromEntries(Object.entries({
  en: "en eng english англ английский", ru: "ru rus russian рус русский русские", uk: "uk ukr ukrainian укр украинский",
  de: "de ger deu german", fr: "fr fre fra french", es: "es spa spanish", it: "it ita italian", pt: "pt por portuguese",
  pl: "pl pol polish", tr: "tr tur turkish", ja: "ja jpn japanese", ko: "ko kor korean", zh: "zh chi zho chinese",
  ar: "ar ara arabic", he: "he heb hebrew", nl: "nl dut nld dutch", sv: "sv swe swedish", fi: "fi fin finnish",
  cs: "cs cze ces czech", hu: "hu hun hungarian", el: "el gre ell greek", ro: "ro rum ron romanian", bg: "bg bul bulgarian"
}).flatMap(([code, words]) => words.split(" ").map(word => [word, code])));

const languageNames = new Intl.DisplayNames(["en"], { type: "language" });

export function guessLanguage(text: string): string {
  for (const word of text.toLowerCase().split(/[^a-zа-яёіїє]+/)) if (LANGUAGE_WORDS[word]) return LANGUAGE_WORDS[word];
  return "";
}

export function languageName(code: string): string {
  try {
    return languageNames.of(code) || "";
  } catch {
    return "";
  }
}

/** The language the viewer reads, from Chrome's settings. */
export function preferredLanguages(): string[] {
  return navigator.languages.map(language => language.split("-")[0].toLowerCase());
}
