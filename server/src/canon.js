// Canonicalization + token-marker handling. Single source of truth: the server
// canonicalizes both when issuing and when verifying, so clients never need to
// port this logic.
import { randomBytes, createHash } from 'node:crypto';

// Crockford base32 (no I, L, O, U) -> unambiguous when read off a screen.
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const MARKER_RE = /\s*\bvt:([0-9A-HJKMNP-TV-Z]{8})\s*$/i;

export function newTokenCode() {
  const bytes = randomBytes(8);
  let out = '';
  for (const b of bytes) out += ALPHABET[b & 31];
  return out;
}

/** Split an incoming SMS body into visible text and the trailing token (if any). */
export function splitToken(body) {
  const s = String(body ?? '');
  const m = s.match(MARKER_RE);
  if (!m) return { text: s, code: null };
  return { text: s.slice(0, m.index), code: m[1].toUpperCase() };
}

/** Text -> stable form that survives the usual SMS/carrier mangling. */
export function canonicalize(text) {
  return String(text ?? '')
    .normalize('NFKC')
    .replace(/[‘’‚‛ʼ]/g, "'")
    .replace(/[“”„‟]/g, '"')
    .replace(/[‐-―−]/g, '-')
    .replace(/\s+/g, ' ')
    .trim();
}

export function bodyHash(text) {
  return createHash('sha256').update(canonicalize(text), 'utf8').digest('hex');
}

export function appendMarker(text, code) {
  return `${String(text).trimEnd()}\n\nvt:${code}`;
}

/** Normalize a sender address for comparison. */
export function normalizeAddress(addr) {
  const a = String(addr ?? '').trim();
  if (/[a-z]/i.test(a)) return a.toLowerCase().replace(/\s+/g, '');
  const digits = a.replace(/\D/g, '');
  return digits.length >= 10 ? digits.slice(-10) : digits;
}
