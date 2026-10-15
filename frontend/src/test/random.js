// Seeded randomness for tests: the loop sets PGWEB_TEST_SEED; each test derives its own stream from its name.
export function seededRandom(name) {
  let h = Number(process.env.PGWEB_TEST_SEED || 1) >>> 0;
  for (const ch of name) h = (Math.imul(h ^ ch.charCodeAt(0), 2654435761) >>> 0) || 1;
  return () => {
    // xorshift32
    h ^= h << 13;
    h >>>= 0;
    h ^= h >>> 17;
    h ^= h << 5;
    h >>>= 0;
    return h / 4294967296;
  };
}

export function randomText(rnd, length) {
  const chars = 'abcXYZ019 <>&"\'/\\é漢🙂\n\t';
  let s = '';
  for (let i = 0; i < length; i++) s += chars[Math.floor(rnd() * chars.length)];
  return s;
}
