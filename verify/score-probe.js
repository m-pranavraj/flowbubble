const VOWELS = "aeiouy";
const FOLDINGS = [["wh","w"],["ph","f"],["sh","s"],["ch","k"],["th","t"],["ck","k"],["x","ks"],["q","k"],["z","s"],["v","f"],["b","p"],["d","t"],["g","k"],["j","k"],["c","k"]];

const letters = t => t.toLowerCase().split("").filter(c => /[a-z0-9]/.test(c)).join("");

function fold(t) {
  let s = letters(t);
  for (const [from, to] of FOLDINGS) s = s.split(from).join(to);
  let out = "", previous = "";
  for (const ch of s) {
    const mapped = VOWELS.includes(ch) ? "a" : ch;
    if (mapped !== previous) out += mapped;
    previous = mapped;
  }
  return out;
}

function editDistance(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let previous = Array.from({length: b.length + 1}, (_, i) => i);
  let current = new Array(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    current[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost);
    }
    const swap = previous; previous = current; current = swap;
  }
  return previous[b.length];
}

const similarity = (a, b) => {
  if (!a.length || !b.length) return 0;
  if (a.length > 64 || b.length > 64) return 0;
  return Math.min(1, Math.max(0, 1 - editDistance(a, b) / Math.max(a.length, b.length)));
};

const words = t => t.toLowerCase().split(/[^a-z0-9]+/).filter(w => w.length);
const covers = (spoken, cand) => {
  const h = words(spoken), k = words(cand);
  return k.length > 0 && k.every(w => h.includes(w));
};

function score(spoken, cand) {
  const heard = letters(spoken), known = letters(cand);
  if (!heard.length || !known.length) return 0;
  if (heard === known) return 1;
  if (covers(spoken, cand)) return 0.97;
  const literal = similarity(heard, known);
  const phonetic = similarity(fold(heard), fold(known));
  const blend = (phonetic >= 0.75 && literal >= 0.55) ? (literal + phonetic) / 2 + 0.1 : 0;
  return Math.min(1, Math.max(literal, phonetic, blend));
}

const vocab = ["YouTube","Camera","Chrome","Clock","Settings","Contacts","Google","Play Store",
  "Files","Calendar","Maps","Messages","Phone","Gmail","Photos","Calculator","Sound Recorder",
  "Velvet Store","Velvet"];

// Candidate matched against a contiguous window of heard words, instead of the whole phrase.
function windowScore(spoken, cand) {
  const h = words(spoken), k = words(cand);
  if (!h.length || !k.length) return 0;
  let best = 0, at = "";
  for (let size = 1; size <= Math.min(h.length, k.length + 1); size++) {
    for (let i = 0; i + size <= h.length; i++) {
      const win = h.slice(i, i + size).join(" ");
      const s = score(win, cand);
      if (s > best) { best = s; at = win; }
    }
  }
  return { best, at };
}

const cases = [
  "opem youube", "open youtube", "kholo flipkart", "opem chrome", "open youube",
  "open the youtube app", "telegraf", "zebra",
  "I wanted to tell you that the meeting moved to four thirty tomorrow",
  "hey could you chrome the settings please",
  "call me tomorrow and open maps"
];
console.log("=== window matching vs whole-phrase, with the dictation guard ===");
for (const spoken of cases) {
  const rows = vocab.map(v => {
    const w = windowScore(spoken, v);
    return { v, whole: score(spoken, v), win: w.best, at: w.at };
  }).filter(r => r.win >= 0.55 || r.whole >= 0.55)
    .sort((a, b) => b.win - a.win || a.v.localeCompare(b.v));
  console.log("\n\"" + spoken + "\"");
  if (!rows.length) console.log("  Unknown (nothing >= 0.55)");
  for (const r of rows.slice(0, 3)) {
    console.log("  " + r.v.padEnd(14) + " whole=" + r.whole.toFixed(3) +
      "  window=" + r.win.toFixed(3) + "  via [" + r.at + "]");
  }
}
