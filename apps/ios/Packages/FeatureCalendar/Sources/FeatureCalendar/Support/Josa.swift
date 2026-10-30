import Foundation

/// The object particle after a title the person typed: "카페 마감을", "회의를" (docs/design/tone.md "확인 대신
/// 되돌리기"). Korean picks 을 after a final consonant and 를 after a vowel, so it depends on how the last letter
/// reads aloud.
public enum Josa {
    public static func object(after word: String) -> String {
        switch endsInConsonant(word) {
        case true: String(localized: "을", bundle: .module, comment: "Object particle after a final consonant")
        case false: String(localized: "를", bundle: .module, comment: "Object particle after a vowel")
        }
    }

    /// Whether the last letter or digit reads with a final consonant. Brackets, punctuation and emoji at the end are
    /// skipped ("회의(온라인)" reads up to "인"); a title without any letter counts as ending in a vowel.
    public static func endsInConsonant(_ word: String) -> Bool {
        for scalar in word.unicodeScalars.reversed() {
            switch scalar.value {
            case 0xAC00...0xD7A3:
                // Hangul syllables are laid out as 19 × 21 × 28: the last index is the final consonant, 0 for none.
                return (scalar.value - 0xAC00) % 28 != 0
            case 0x30...0x39:
                // 영, 일, 삼, 육, 칠, 팔 end in a consonant; 이, 사, 오, 구 do not.
                return "013678".unicodeScalars.contains(scalar)
            case 0x41...0x5A, 0x61...0x7A:
                // English letters as Koreans read them: 엘, 엠, 엔, 알 end in a consonant.
                return "lmnrLMNR".unicodeScalars.contains(scalar)
            default:
                continue
            }
        }
        return false
    }
}
