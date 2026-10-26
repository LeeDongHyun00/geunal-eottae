//! The Hangul part of Unicode normalization (Unicode §3.12, "Conjoining Jamo Behavior").
//!
//! The recovery words are Hangul syllables only, and between NFC and NFD (which is also NFKD for Hangul) a syllable
//! and its conjoining jamo map to each other by arithmetic. That covers every way a word arrives, whether typed (NFC
//! from the phone keyboards, NFD from some desktop ones) or pasted, without the general normalization tables, which
//! would cost the Android library more than the word list itself.

const S_BASE: u32 = 0xAC00;
const L_BASE: u32 = 0x1100;
const V_BASE: u32 = 0x1161;
const T_BASE: u32 = 0x11A7;
const L_COUNT: u32 = 19;
const V_COUNT: u32 = 21;
const T_COUNT: u32 = 28;
const N_COUNT: u32 = V_COUNT * T_COUNT;
const S_COUNT: u32 = L_COUNT * N_COUNT;

fn hangul(code: u32) -> char {
    char::from_u32(code).expect("Hangul syllables and jamo are scalar values")
}

/// Composes leading consonant, vowel and optional trailing consonant jamo into syllables, as NFC does. Everything
/// else, syllables included, stays as it is.
pub fn compose(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut last: Option<char> = None;
    for ch in text.chars() {
        if let Some(prev) = last {
            let (p, c) = (u32::from(prev), u32::from(ch));
            if (L_BASE..L_BASE + L_COUNT).contains(&p) && (V_BASE..V_BASE + V_COUNT).contains(&c) {
                last = Some(hangul(S_BASE + ((p - L_BASE) * V_COUNT + (c - V_BASE)) * T_COUNT));
                continue;
            }
            let open_syllable = (S_BASE..S_BASE + S_COUNT).contains(&p) && (p - S_BASE).is_multiple_of(T_COUNT);
            if open_syllable && (T_BASE + 1..T_BASE + T_COUNT).contains(&c) {
                last = Some(hangul(p + (c - T_BASE)));
                continue;
            }
            out.push(prev);
        }
        last = Some(ch);
    }
    out.extend(last);
    out
}

/// Splits syllables into their conjoining jamo, as NFD and NFKD do. Everything else stays as it is.
pub fn decompose(text: &str) -> String {
    let mut out = String::with_capacity(text.len() * 3);
    for ch in text.chars() {
        let s = u32::from(ch);
        if !(S_BASE..S_BASE + S_COUNT).contains(&s) {
            out.push(ch);
            continue;
        }
        let index = s - S_BASE;
        out.push(hangul(L_BASE + index / N_COUNT));
        out.push(hangul(V_BASE + index % N_COUNT / T_COUNT));
        if !index.is_multiple_of(T_COUNT) {
            out.push(hangul(T_BASE + index % T_COUNT));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn composes_and_decomposes_open_and_closed_syllables() {
        // 가 = ᄀ + ᅡ, 각 = ᄀ + ᅡ + ᆨ, 힣 is the last syllable.
        assert_eq!(decompose("가각힣"), "\u{1100}\u{1161}\u{1100}\u{1161}\u{11A8}\u{1112}\u{1175}\u{11C2}");
        assert_eq!(compose("\u{1100}\u{1161}\u{1100}\u{1161}\u{11A8}\u{1112}\u{1175}\u{11C2}"), "가각힣");
        // Every syllable survives the round trip.
        let all: String = (S_BASE..S_BASE + S_COUNT).map(hangul).collect();
        assert_eq!(compose(&decompose(&all)), all);
    }

    #[test]
    fn leaves_everything_else_alone() {
        assert_eq!(compose("1. 가격, abc"), "1. 가격, abc");
        assert_eq!(decompose("abc 123"), "abc 123");
        // A trailing consonant after a closed syllable or a lone vowel does not combine.
        assert_eq!(compose("각\u{11A8}"), "각\u{11A8}");
        assert_eq!(compose("\u{1161}\u{11A8}"), "\u{1161}\u{11A8}");
        // Already composed text and a mix of both forms come out composed.
        assert_eq!(compose("가\u{1100}\u{1161}\u{11A8}"), "가각");
        assert_eq!(compose(""), "");
    }
}
