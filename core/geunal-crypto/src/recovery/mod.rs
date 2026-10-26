//! The recovery code and the values derived from it (protocol §10, §13).
//!
//! The device draws 16 random bytes `E`. The code is `E` written as twelve words of the BIP-39 Korean list: `E`, then
//! the first 4 bits of SHA-256(`E`), cut into 11-bit indexes. The words are only a way to write `E` down; what goes
//! into the cryptography is `E` itself, so two spellings of the same word can never give two keys, and none of BIP-39's
//! wallet seed derivation is used. `E` and the AMK stay on the device; the server keeps the wrapped AMK, the recovery
//! id and the recovery public key, all derived from one Argon2id over `E` ([`crate::kdf`]).

mod hangul;

use std::sync::LazyLock;

use ed25519_dalek::{Signer, SigningKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

use crate::aead::{Key, NONCE_LEN};
use crate::kdf::{self, KdfError};

pub use hangul::{compose, decompose};

/// Bytes of entropy behind a recovery code.
pub const ENTROPY_LEN: usize = 16;
/// Words in a recovery code.
pub const WORD_COUNT: usize = 12;
/// Words in the list.
pub const LIST_LEN: usize = 2048;
/// Bytes of the recovery id the server finds a guest's account by.
pub const RECOVERY_ID_LEN: usize = 16;
/// SHA-256 of the BIP-39 file the list comes from. The file is NFKD; the copy here is NFC (see NOTICE.md).
pub const LIST_SHA256: &str = "9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60";

/// What the 16 bytes of a recovery code are.
pub type Entropy = [u8; ENTROPY_LEN];

const LIST_NFC: &str = include_str!("korean.txt");
static LIST: LazyLock<Vec<&'static str>> = LazyLock::new(|| LIST_NFC.lines().collect());

const WRAP_INFO: &[u8] = b"geunal amk wrap v1";
const ID_INFO: &[u8] = b"geunal recovery id v1";
const SIGN_INFO: &[u8] = b"geunal recovery sign v1";
/// What the recovery signing key signs, each followed by the server's nonce: overwriting the recovery material,
/// a guest coming back, and a guest deleting the account. The API server checks the same bytes.
pub const ROTATE_LABEL: &[u8] = b"geunal recovery rotate v1";
pub const RECOVER_LABEL: &[u8] = b"geunal recover v1";
pub const DELETE_LABEL: &[u8] = b"geunal delete v1";

const BITS_PER_WORD: usize = 11;

/// Why typed words are not a recovery code.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum CodeError {
    #[error("a recovery code has 12 words, not {0}")]
    WordCount(usize),
    /// The position (from 0) of the first word that is not in the list.
    #[error("word {0} is not in the list")]
    UnknownWord(usize),
    /// Every word is in the list but they do not end in the checksum of the rest. One wrong word in sixteen still
    /// passes, which is why a restore decides by whether the AMK unwraps.
    #[error("the words do not form a recovery code")]
    Checksum,
}

/// A fresh `E` from the operating system's CSPRNG.
pub fn new_entropy() -> Entropy {
    let mut entropy = [0u8; ENTROPY_LEN];
    OsRng.fill_bytes(&mut entropy);
    entropy
}

/// The 2048 words in list order, NFC. The order is also code point order, which [`word`] relies on.
pub fn list() -> &'static [&'static str] {
    &LIST
}

/// The listed word `text` spells, whatever its normalization and surrounding whitespace; `None` when it is not one of
/// the 2048.
pub fn word(text: &str) -> Option<&'static str> {
    index_of(text).map(|i| LIST[i])
}

/// The listed words that start with `text`, for the input cell's completion. Nothing for an empty prefix; two
/// syllables single out one word, since no two words share their first two syllables.
pub fn completions(text: &str) -> Vec<&'static str> {
    let prefix = compose(text.trim());
    if prefix.is_empty() {
        return Vec::new();
    }
    let start = LIST.partition_point(|w| *w < prefix.as_str());
    LIST[start..].iter().take_while(|w| w.starts_with(prefix.as_str())).copied().collect()
}

/// The words of a pasted code in order: each run of Hangul, composed. Numbering, punctuation, Latin letters and line
/// breaks fall away; whether each word is in the list is [`word`]'s question.
pub fn split(text: &str) -> Vec<String> {
    text.split(|c: char| !is_hangul(c)).filter(|run| !run.is_empty()).map(compose).collect()
}

/// `E` written as twelve words.
pub fn words(entropy: &Entropy) -> [&'static str; WORD_COUNT] {
    let mut bits = [0u8; ENTROPY_LEN + 1];
    bits[..ENTROPY_LEN].copy_from_slice(entropy);
    bits[ENTROPY_LEN] = checksum(entropy);
    std::array::from_fn(|i| LIST[usize::from(read_index(&bits, i * BITS_PER_WORD))])
}

/// The `E` twelve words spell, after checking that each is in the list and that the last 4 bits match.
pub fn entropy<S: AsRef<str>>(words: &[S]) -> Result<Entropy, CodeError> {
    if words.len() != WORD_COUNT {
        return Err(CodeError::WordCount(words.len()));
    }
    let mut bits = [0u8; ENTROPY_LEN + 1];
    for (i, w) in words.iter().enumerate() {
        let index = index_of(w.as_ref()).ok_or(CodeError::UnknownWord(i))?;
        write_index(&mut bits, i * BITS_PER_WORD, index);
    }
    let mut entropy = [0u8; ENTROPY_LEN];
    entropy.copy_from_slice(&bits[..ENTROPY_LEN]);
    if bits[ENTROPY_LEN] != checksum(&entropy) {
        return Err(CodeError::Checksum);
    }
    Ok(entropy)
}

/// What `K` gives (protocol §10): the key that wraps the AMK, the recovery id and the recovery signing key. Neither
/// `Debug` nor `Clone`, so the keys cannot reach a log line or outlive the restore that needed them by accident.
pub struct RecoveryKeys {
    wrap_key: Key,
    id: [u8; RECOVERY_ID_LEN],
    signing: SigningKey,
}

impl RecoveryKeys {
    /// Runs the Argon2id (64 MiB) and the expansions. Seconds on a phone; never in the iOS extensions.
    pub fn derive(entropy: &Entropy) -> Result<Self, KdfError> {
        Ok(Self::from_root(&kdf::recovery_root(entropy)?))
    }

    /// The expansions alone, from a `K` already derived.
    pub fn from_root(root: &Key) -> Self {
        Self {
            wrap_key: kdf::expand(root, WRAP_INFO),
            id: kdf::expand(root, ID_INFO),
            signing: SigningKey::from_bytes(&kdf::expand(root, SIGN_INFO)),
        }
    }

    /// The wrap key itself, only for test vectors.
    pub fn wrap_key(&self) -> &Key {
        &self.wrap_key
    }

    /// The recovery id: what `POST /auth/recover` finds a guest's account by.
    pub fn id(&self) -> [u8; RECOVERY_ID_LEN] {
        self.id
    }

    /// The Ed25519 public key the server checks the recovery signatures with.
    pub fn public_key(&self) -> [u8; 32] {
        self.signing.verifying_key().to_bytes()
    }

    pub fn wrap_amk(&self, amk: &Key) -> Vec<u8> {
        kdf::wrap_amk(&self.wrap_key, amk)
    }

    /// [`Self::wrap_amk`] with a caller-chosen nonce, only for test vectors.
    pub fn wrap_amk_with_nonce(&self, nonce: &[u8; NONCE_LEN], amk: &Key) -> Vec<u8> {
        kdf::wrap_amk_with_nonce(&self.wrap_key, nonce, amk)
    }

    /// `None` when this is not the code the AMK was wrapped under, which is the final word on a typed code.
    pub fn unwrap_amk(&self, wrapped: &[u8]) -> Option<Key> {
        kdf::unwrap_amk(&self.wrap_key, wrapped)
    }

    /// The proof that overwrites the recovery material on the server (`PUT /me/recovery`): only the current code
    /// can replace itself, so a stolen session cannot lock the owner out.
    pub fn sign_rotation(&self, nonce: &str) -> [u8; 64] {
        self.sign(ROTATE_LABEL, nonce)
    }

    /// A guest coming back on another device (`POST /auth/recover`).
    pub fn sign_recovery(&self, nonce: &str) -> [u8; 64] {
        self.sign(RECOVER_LABEL, nonce)
    }

    /// A guest deleting the account (`DELETE /me`). Made from the `E` on the device, without asking the user.
    pub fn sign_deletion(&self, nonce: &str) -> [u8; 64] {
        self.sign(DELETE_LABEL, nonce)
    }

    fn sign(&self, label: &[u8], nonce: &str) -> [u8; 64] {
        self.signing.sign(&[label, nonce.as_bytes()].concat()).to_bytes()
    }
}

fn is_hangul(c: char) -> bool {
    matches!(c, '\u{AC00}'..='\u{D7A3}' | '\u{1100}'..='\u{11FF}')
}

fn index_of(text: &str) -> Option<usize> {
    LIST.binary_search(&compose(text.trim()).as_str()).ok()
}

/// The first 4 bits of SHA-256(`E`), in the high half of the byte that follows `E`.
fn checksum(entropy: &Entropy) -> u8 {
    Sha256::digest(entropy)[0] & 0xF0
}

fn read_index(bits: &[u8], start: usize) -> u16 {
    (start..start + BITS_PER_WORD).fold(0, |acc, pos| acc << 1 | u16::from(bits[pos / 8] >> (7 - pos % 8) & 1))
}

fn write_index(bits: &mut [u8], start: usize, index: usize) {
    for k in 0..BITS_PER_WORD {
        if index >> (BITS_PER_WORD - 1 - k) & 1 == 1 {
            let pos = start + k;
            bits[pos / 8] |= 1 << (7 - pos % 8);
        }
    }
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signature, VerifyingKey};

    use super::*;

    #[test]
    fn the_list_is_the_bip39_korean_file() {
        assert_eq!(list().len(), LIST_LEN);
        let nfkd = decompose(LIST_NFC);
        assert_eq!(hex::encode(Sha256::digest(nfkd.as_bytes())), LIST_SHA256);
        assert!(list().windows(2).all(|pair| pair[0] < pair[1]), "sorted and without repeats");
        // The completion promise: two syllables single out a word, and no word starts another.
        let mut firsts: Vec<String> = list().iter().map(|w| w.chars().take(2).collect()).collect();
        firsts.dedup();
        assert_eq!(firsts.len(), LIST_LEN);
        assert!(list().iter().all(|w| completions(w) == vec![*w]));
    }

    #[test]
    fn words_and_entropy_round_trip() {
        for _ in 0..200 {
            let e = new_entropy();
            assert_eq!(entropy(&words(&e)), Ok(e));
        }
        // Cross-checked with a Python BIP-39 encoder over the same list.
        assert_eq!(words(&[0u8; ENTROPY_LEN]).join(" "), "가격 가격 가격 가격 가격 가격 가격 가격 가격 가격 가격 가능");
        assert_eq!(
            words(&[0xFFu8; ENTROPY_LEN]).join(" "),
            "힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 힘껏 흑백"
        );
    }

    #[test]
    fn rejects_codes_that_are_not_twelve_listed_words_with_the_checksum() {
        let e = [0x5Au8; ENTROPY_LEN];
        let good: Vec<String> = words(&e).iter().map(|w| w.to_string()).collect();
        assert_eq!(entropy(&good[..11]), Err(CodeError::WordCount(11)));
        let mut unknown = good.clone();
        unknown[3] = "가나다라".into();
        assert_eq!(entropy(&unknown), Err(CodeError::UnknownWord(3)));
        // The last word carries the checksum; any other listed word there fails it unless its low 4 bits agree.
        let last = index_of(&good[11]).unwrap();
        let mut wrong = good.clone();
        wrong[11] = LIST[last ^ 0b1].into();
        assert_eq!(entropy(&wrong), Err(CodeError::Checksum));
    }

    #[test]
    fn reads_words_in_any_normalization_and_with_spaces() {
        assert_eq!(word("가격"), Some("가격"));
        assert_eq!(word(" \u{1100}\u{1161}\u{1100}\u{1167}\u{11A8} \n"), Some("가격"));
        assert_eq!(word("가격이"), None);
        assert_eq!(word(""), None);
        assert_eq!(completions("가"), list()[..completions("가").len()].to_vec());
        assert!(completions("가").len() > 1);
        assert_eq!(completions("가격"), vec!["가격"]);
        assert!(completions(" ").is_empty());
        assert!(completions("abc").is_empty());
    }

    #[test]
    fn splits_a_pasted_code() {
        assert_eq!(split("1. 가격 2) 가끔\n3.가난, 가능 · 가득abc"), vec!["가격", "가끔", "가난", "가능", "가득"]);
        assert_eq!(split("\u{1100}\u{1161}\u{1100}\u{1167}\u{11A8}"), vec!["가격"]);
        assert!(split("1 2 3").is_empty());
    }

    #[test]
    fn signatures_bind_their_label_and_nonce() {
        let keys = RecoveryKeys::from_root(&[9u8; 32]);
        let public = VerifyingKey::from_bytes(&keys.public_key()).unwrap();
        let nonce = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        let rotation = Signature::from_bytes(&keys.sign_rotation(nonce));
        assert!(public.verify_strict(&[ROTATE_LABEL, nonce.as_bytes()].concat(), &rotation).is_ok());
        assert!(public.verify_strict(&[RECOVER_LABEL, nonce.as_bytes()].concat(), &rotation).is_err());
        let recovery = Signature::from_bytes(&keys.sign_recovery(nonce));
        assert!(public.verify_strict(&[RECOVER_LABEL, nonce.as_bytes()].concat(), &recovery).is_ok());
        let deletion = Signature::from_bytes(&keys.sign_deletion(nonce));
        assert!(public.verify_strict(&[DELETE_LABEL, nonce.as_bytes()].concat(), &deletion).is_ok());
        assert!(public.verify_strict(&[DELETE_LABEL, b"other".as_slice()].concat(), &deletion).is_err());
    }

    #[test]
    fn the_wrapped_amk_opens_only_with_its_own_code() {
        let keys = RecoveryKeys::from_root(&[1u8; 32]);
        let amk = crate::random_key();
        let wrapped = keys.wrap_amk(&amk);
        assert_eq!(keys.unwrap_amk(&wrapped), Some(amk));
        assert_eq!(RecoveryKeys::from_root(&[2u8; 32]).unwrap_amk(&wrapped), None);
        // Every value is its own expansion of K.
        assert_ne!(keys.wrap_key()[..RECOVERY_ID_LEN], keys.id());
    }
}
