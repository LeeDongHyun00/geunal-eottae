//! Invite codes and links (protocol §15). Both apps and the server must agree on what a code is, so its normalisation
//! lives here once instead of in each app's text field.

/// Crockford base32: no I, L, O or U, so a code read aloud or copied by hand survives.
pub const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// Ten symbols of five bits: 50 bits, the floor the server's `group_invites.code` check enforces.
pub const CODE_LEN: usize = 10;

/// The longest group name a link carries, in characters; the same cap as the group name field (design m2 §13).
pub const MAX_LINK_NAME_CHARS: usize = 20;

/// The twelve group colours (`color.group.*` in docs/design/tokens.json), as `group_meta.color` stores them.
pub const GROUP_COLORS: [&str; 12] =
    ["tomato", "tangerine", "mustard", "olive", "forest", "mint", "sky", "sea", "grape", "plum", "blossom", "cocoa"];

/// What a pasted code or opened link says. `name` and `color` come from the link's fragment, which the inviting
/// device wrote and the server never saw: they are shown before joining only, and `group_meta` replaces them after.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invite {
    pub code: String,
    pub name: Option<String>,
    pub color: Option<String>,
}

/// The canonical code for what a person typed: case, hyphens and spaces are ignored, and the look-alikes Crockford
/// folds (O → 0, I and L → 1) are folded. `None` unless exactly ten valid symbols remain.
pub fn normalize_code(input: &str) -> Option<String> {
    let mut code = String::with_capacity(CODE_LEN);
    for c in input.chars() {
        if c == '-' || c.is_whitespace() {
            continue;
        }
        let c = match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        };
        if !c.is_ascii() || !ALPHABET.contains(&(c as u8)) {
            return None;
        }
        code.push(c);
        if code.len() > CODE_LEN {
            return None;
        }
    }
    (code.len() == CODE_LEN).then_some(code)
}

/// The code as screens show it: two groups of five joined by a hyphen ("GN7KQ-4XZM2").
pub fn display_code(code: &str) -> String {
    match code.char_indices().nth(CODE_LEN / 2) {
        Some((at, _)) if code.len() == CODE_LEN => format!("{}-{}", &code[..at], &code[at..]),
        _ => code.to_owned(),
    }
}

/// The link the inviting device shares: `https://{host}/i/{code}`, with the group name and colour in the fragment so
/// that they never reach the server. A colour outside [`GROUP_COLORS`] is left out, and the name is cut to
/// [`MAX_LINK_NAME_CHARS`].
pub fn invite_link(host: &str, code: &str, name: Option<&str>, color: Option<&str>) -> String {
    let mut link = format!("https://{host}/i/{code}");
    let mut fields = Vec::new();
    if let Some(name) = name.map(clean_name).filter(|n| !n.is_empty()) {
        fields.push(format!("n={}", percent_encode(&name)));
    }
    if let Some(color) = color.filter(|c| GROUP_COLORS.contains(c)) {
        fields.push(format!("c={color}"));
    }
    if !fields.is_empty() {
        link.push('#');
        link.push_str(&fields.join("&"));
    }
    link
}

/// Reads a pasted code, an invite link, or a message that contains one (a KakaoTalk card copied as text). The host is
/// not checked: a link only ever yields a code, and the server decides whether that code is any good.
pub fn parse_invite(text: &str) -> Option<Invite> {
    let text = text.trim();
    let Some(at) = text.rfind("/i/") else {
        return normalize_code(text).map(|code| Invite { code, name: None, color: None });
    };
    let rest = &text[at + 3..];
    let rest = rest.split(char::is_whitespace).next().unwrap_or_default();
    let (path, fragment) = match rest.split_once('#') {
        Some((path, fragment)) => (path, Some(fragment)),
        None => (rest, None),
    };
    let code = normalize_code(path.split(['?', '/']).next().unwrap_or_default())?;
    let mut invite = Invite { code, name: None, color: None };
    for field in fragment.unwrap_or_default().split('&') {
        match field.split_once('=') {
            Some(("n", value)) => {
                invite.name = percent_decode(value).map(|n| clean_name(&n)).filter(|n| !n.is_empty());
            }
            Some(("c", value)) if GROUP_COLORS.contains(&value) => invite.color = Some(value.to_owned()),
            _ => {}
        }
    }
    Some(invite)
}

/// Control and bidirectional-formatting characters are dropped, since a fragment is anyone's text and those could
/// make the name read differently on screen; then the name is cut to the field's length.
fn clean_name(name: &str) -> String {
    let shown = |c: &char| {
        !c.is_control() && !matches!(*c, '\u{200e}' | '\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')
    };
    name.chars().filter(shown).take(MAX_LINK_NAME_CHARS).collect::<String>().trim().to_owned()
}

fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len() * 3);
    for &b in text.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn percent_decode(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                let hex = text.get(i + 1..i + 3)?;
                out.push(u8::from_str_radix(hex, 16).ok()?);
                i += 3;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_code_typed_loosely_is_the_same_code() {
        assert_eq!(normalize_code("gn7kq-4xzm2").as_deref(), Some("GN7KQ4XZM2"));
        assert_eq!(normalize_code(" GN7KQ 4XZM2 ").as_deref(), Some("GN7KQ4XZM2"));
        assert_eq!(normalize_code("GN7KQ-4XZMO").as_deref(), Some("GN7KQ4XZM0"));
        assert_eq!(normalize_code("il000-00000").as_deref(), Some("1100000000"));
    }

    #[test]
    fn a_code_of_the_wrong_length_or_alphabet_is_no_code() {
        assert_eq!(normalize_code("GN7KQ-4XZM"), None);
        assert_eq!(normalize_code("GN7KQ-4XZM22"), None);
        assert_eq!(normalize_code("GN7KQ-4XZMU"), None);
        assert_eq!(normalize_code("GN7KQ-4XZM가"), None);
        assert_eq!(normalize_code(""), None);
    }

    #[test]
    fn a_link_round_trips_its_name_and_colour() {
        let link = invite_link("geunal.app", "GN7KQ4XZM2", Some("주말 모임 & 보드게임"), Some("mint"));
        let invite = parse_invite(&link).unwrap();
        assert_eq!(invite.code, "GN7KQ4XZM2");
        assert_eq!(invite.name.as_deref(), Some("주말 모임 & 보드게임"));
        assert_eq!(invite.color.as_deref(), Some("mint"));
    }

    #[test]
    fn a_link_inside_a_message_is_found() {
        let text = "하린님이 초대했어요\nhttps://geunal.app/i/GN7KQ4XZM2#c=sky 참여하기";
        let invite = parse_invite(text).unwrap();
        assert_eq!(invite.code, "GN7KQ4XZM2");
        assert_eq!(invite.color.as_deref(), Some("sky"));
    }

    #[test]
    fn a_broken_fragment_still_gives_the_code() {
        let invite = parse_invite("https://geunal.app/i/GN7KQ4XZM2#n=%FF%&c=neon").unwrap();
        assert_eq!(invite, Invite { code: "GN7KQ4XZM2".into(), name: None, color: None });
    }

    #[test]
    fn a_long_name_is_cut_and_control_characters_dropped() {
        let link = invite_link(
            "geunal.app",
            "GN7KQ4XZM2",
            Some("가\u{202e}나다라마바사아자차카타파하가나다라마바사아자"),
            None,
        );
        let name = parse_invite(&link).unwrap().name.unwrap();
        assert_eq!(name.chars().count(), MAX_LINK_NAME_CHARS);
        assert!(!name.contains('\u{202e}'));
    }

    #[test]
    fn display_splits_into_two_groups() {
        assert_eq!(display_code("GN7KQ4XZM2"), "GN7KQ-4XZM2");
    }
}
