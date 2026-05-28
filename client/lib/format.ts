const THOUSAND_SEPARATOR_REGEX = /\B(?=(\d{3})+(?!\d))/g;

/** Format with a regular space as thousand separator. */
export function formatWithSpaces(num: number | bigint): string {
  return num.toString().replace(THOUSAND_SEPARATOR_REGEX, " ");
}

/** Format with a thin space (U+2009) — better for monospace balances. */
export function formatWithThinSpaces(num: number | bigint): string {
  return num.toString().replace(THOUSAND_SEPARATOR_REGEX, " ");
}

/** Format with a comma as thousand separator. */
export function formatWithCommas(num: number | bigint): string {
  return num.toString().replace(THOUSAND_SEPARATOR_REGEX, ",");
}

/** Relative "time ago" label matching the Glow transaction list. */
export function formatTimeAgo(timestampSecs: number): string {
  const now = Math.floor(Date.now() / 1000);
  const diff = now - timestampSecs;
  if (diff < 60) return "Just now";
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 2592000) return `${Math.floor(diff / 86400)}d ago`;
  if (diff < 31536000) return `${Math.floor(diff / 2592000)}mo ago`;
  return `${Math.floor(diff / 31536000)}y ago`;
}
