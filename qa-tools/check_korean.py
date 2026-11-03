# Check the claims protocol §10 (PR #26) makes about the BIP39 Korean wordlist.
import sys, unicodedata, collections
raw = open(sys.argv[1], encoding="utf-8").read().split("\n")
words = [w for w in raw if w != ""]
print("words:", len(words), "trailing newline:", raw[-1] == "")
print("all NFKD in file:", all(unicodedata.normalize("NFKD", w) == w for w in words))
print("any NFC already:", sum(unicodedata.normalize("NFC", w) == w for w in words))
nfc = [unicodedata.normalize("NFC", w) for w in words]
print("NFC unique:", len(set(nfc)) == len(nfc))
print("all Hangul syllables after NFC:", all(all("가" <= ch <= "힣" for ch in w) for w in nfc))
lengths = collections.Counter(len(w) for w in nfc)
print("syllable counts:", sorted(lengths.items()))
# Prefix: a word that is the start of another word.
s = sorted(nfc)
prefixes = [(a, b) for a in nfc for b in nfc if a != b and b.startswith(a)]
print("prefix pairs:", len(prefixes), prefixes[:10])
# Two-syllable uniqueness.
by2 = collections.defaultdict(list)
for w in nfc:
    by2[w[:2]].append(w)
dup2 = {k: v for k, v in by2.items() if len(v) > 1}
print("first-two-syllable collisions:", len(dup2), list(dup2.items())[:10])
short = [w for w in nfc if len(w) < 2]
print("words shorter than two syllables:", short)
# First syllable alone: how many candidates at most after one syllable.
by1 = collections.Counter(w[:1] for w in nfc)
print("max words sharing the first syllable:", max(by1.values()))
# The checksum math: 128-bit E + 4-bit checksum = 132 bits = 12 x 11.
print("12 words x 11 bits =", 12 * 11)
