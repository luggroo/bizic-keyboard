import Foundation
import CTujiaCore

public enum InputForm: Int32 {
    case spelling = 0, recommendedHan = 1, handwritingToSpelling = 2
}

public enum CandidateKind: Int32 {
    case tujia = 0, chinese = 1, literal = 2, punctuation = 3
}

public enum SpellingOrthography: Int32 {
    case ye = 0, diacritic = 1
}

public struct WordCandidate: Equatable {
    public let text: String
    public let meaning: String
    public let shortMeaning: String
    public let kind: CandidateKind
    /// Space-separated source IDs in commit order. Empty ends lexical context.
    public let lexemeIds: String
    /// Shared by typed Han variants of one reading and sense at the same edit boundary.
    public let hanSenseId: String
    public let lastSyllable: String
    public let syllableOnly: Bool
    public let plainHanLoan: Bool
    public let consumedUTF16: Int
    /// Flattened UTF-16 start/end-exclusive pairs for two-Han syllables in text.
    public let joinedHanRanges: [Int]
    let input: String
}

public struct SelectedWord: Equatable {
    /// The text ready for the current editor, including word-boundary spaces.
    public let text: String
    /// The unselected suffix to look up on the next frame.
    public let remainingInput: String
}

public struct HandwrittenWord: Equatable {
    public let candidate: WordCandidate
    /// UTF-16 range in the supplied text before the caret.
    public let range: NSRange
}

public enum TujiaInputError: Error {
    case missingAsset(String), invalidDictionary, invalidStrokeModel
}

private func asset(_ name: String) throws -> Data {
    guard let url = Bundle.module.resourceURL?.appendingPathComponent("Assets/" + name) else {
        throw TujiaInputError.missingAsset(name)
    }
    return try Data(contentsOf: url)
}

public final class TujiaDictionary {
    private let handle: OpaquePointer

    public convenience init() throws {
        try self.init(data: asset("words.tsv") + asset("northern-words.tsv"), ngrams: asset("ngrams.tsv"),
                      syllableBigrams: asset("syllable_bigrams.tsv"), syllableSenses: asset("syllable_senses.tsv"),
                      regionalReadings: asset("handwriting-readings.tsv"))
    }

    public init(data: Data, ngrams: Data = Data(), syllableBigrams: Data = Data(), syllableSenses: Data = Data(),
                regionalReadings: Data = Data()) throws {
        guard let loaded = data.withUnsafeBytes({ bytes in
            ngrams.withUnsafeBytes { context in
                syllableBigrams.withUnsafeBytes { sounds in
                    syllableSenses.withUnsafeBytes { senses in
                        regionalReadings.withUnsafeBytes { readings in
                            tji_words_open_with_readings(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count,
                                                   context.bindMemory(to: UInt8.self).baseAddress, context.count,
                                                   sounds.bindMemory(to: UInt8.self).baseAddress, sounds.count,
                                                   senses.bindMemory(to: UInt8.self).baseAddress, senses.count,
                                                   readings.bindMemory(to: UInt8.self).baseAddress, readings.count)
                        }
                    }
                }
            }
        }) else { throw TujiaInputError.invalidDictionary }
        handle = loaded
    }

    deinit { tji_words_close(handle) }

    /// Tujia candidates carry both a compact gloss for the keyboard and a full definition.
    public func candidates(for input: String, form: InputForm) -> [WordCandidate] {
        candidates(for: input, form: form, orthography: .ye)
    }

    public func candidates(for input: String, form: InputForm, orthography: SpellingOrthography) -> [WordCandidate] {
        guard !input.utf8.contains(0) else { return [] }
        let choices = input.withCString { tji_lookup_with_orthography(handle, $0, form.rawValue, orthography.rawValue) }
        return readCandidates(choices, input: input)
    }

    public func handwritingSuggestions(for glyphs: String, form: InputForm,
                                       orthography: SpellingOrthography = .ye) -> [WordCandidate] {
        guard !glyphs.utf8.contains(0) else { return [] }
        let choices = glyphs.withCString { tji_handwriting_suggestions(handle, $0, form.rawValue, orthography.rawValue) }
        return readCandidates(choices, input: glyphs)
    }

    public func scoreHandwritingPrefix(_ glyphs: String) -> Int {
        guard !glyphs.utf8.contains(0) else { return 0 }
        return Int(glyphs.withCString { tji_score_handwriting_prefix(handle, $0) })
    }

    public var handwritingContextLength: Int { Int(tji_handwriting_context_length(handle)) }

    // Four Han characters and up to two pairs of display delimiters.
    private func handwritingTail(_ text: String) -> String {
        let raw = text.unicodeScalars.suffix(8)
        if let boundary = raw.lastIndex(of: "\0") { return String(raw.suffix(from: raw.index(after: boundary))) }
        return String(raw)
    }

    public func handwrittenWords(beforeCaret text: String, form: InputForm = .recommendedHan,
                                 orthography: SpellingOrthography = .diacritic) -> [HandwrittenWord] {
        let context = handwritingTail(text)
        let result = context.withCString { tji_handwritten_words(handle, $0, form.rawValue, orthography.rawValue) }
        let length = text.utf16.count
        return readCandidates(result, input: context, fromEnd: true).map {
            HandwrittenWord(candidate: $0, range: NSRange(location: length - $0.consumedUTF16, length: $0.consumedUTF16))
        }
    }

    public func nextHandwrittenGlyphs(beforeCaret text: String) -> [WordCandidate] {
        let context = handwritingTail(text)
        return readCandidates(context.withCString { tji_next_handwritten_glyphs(handle, $0) }, input: "")
    }

    /// Complete a partially typed word using the confirmed context and chosen output spelling.
    public func spellingCompletions(_ prefix: String, after previousIds: String = "", form: InputForm,
                                    orthography: SpellingOrthography = .ye) -> [WordCandidate] {
        guard !prefix.utf8.contains(0), !previousIds.utf8.contains(0) else { return [] }
        let result = prefix.withCString { typed in
            previousIds.withCString { tji_spelling_completions(handle, typed, $0, form.rawValue, orthography.rawValue) }
        }
        return readCandidates(result, input: prefix)
    }

    public func nextWords(after previousIds: String, form: InputForm,
                          orthography: SpellingOrthography = .ye) -> [WordCandidate] {
        guard !previousIds.utf8.contains(0) else { return [] }
        let choices = previousIds.withCString { tji_next_words(handle, $0, form.rawValue, orthography.rawValue) }
        return readCandidates(choices, input: "")
    }

    /// Supply "<s>" at a known start or an exact Ye/diacritic syllable from a selected word.
    public func nextSyllables(after context: String, orthography: SpellingOrthography = .ye) -> [WordCandidate] {
        nextSyllables(after: context, form: .spelling, orthography: orthography)
    }

    public func nextSyllables(after context: String, form: InputForm, orthography: SpellingOrthography = .ye) -> [WordCandidate] {
        guard !context.utf8.contains(0) else { return [] }
        let choices = context.withCString { tji_next_syllables_for_mode(handle, $0, form.rawValue, orthography.rawValue) }
        return readCandidates(choices, input: "")
    }

    private func readCandidates(_ choices: OpaquePointer?, input: String, fromEnd: Bool = false) -> [WordCandidate] {
        guard let result = choices else { return [] }
        defer { tji_choices_close(result) }
        return (0..<tji_choices_count(result)).map { i in
            var rangeCount = 0
            let offsets = tji_choice_joined_han_ranges(result, i, &rangeCount)
            let ranges = UnsafeBufferPointer(start: offsets, count: rangeCount).map(Int.init)
            let consumed = Int(tji_choice_consumed_utf16(result, i))
            let matched = fromEnd ? String(decoding: input.utf16.suffix(consumed), as: UTF16.self) : input
            return WordCandidate(text: String(cString: tji_choice_text(result, i)),
                          meaning: String(cString: tji_choice_meaning(result, i)),
                          shortMeaning: String(cString: tji_choice_short_meaning(result, i)),
                          kind: CandidateKind(rawValue: tji_choice_kind(result, i)) ?? .tujia,
                          lexemeIds: String(cString: tji_choice_lexeme_ids(result, i)),
                          hanSenseId: String(cString: tji_choice_han_sense_id(result, i)),
                          lastSyllable: String(cString: tji_choice_last_syllable(result, i)),
                          syllableOnly: tji_choice_is_syllable(result, i) != 0,
                          plainHanLoan: tji_choice_plain_han_loan(result, i) != 0,
                          consumedUTF16: consumed, joinedHanRanges: ranges, input: matched)
        }
    }

    /// Call only after the user chooses. Supplying neighboring editor text allows
    /// the shared core to put a space between adjacent Latin-script words.
    public func selection(_ choice: WordCandidate, in input: String,
                          before: String = "", after: String = "") -> SelectedWord? {
        // NFC and NFD compare equal as Swift strings, but have different editor offsets.
        guard choice.input.utf8.elementsEqual(input.utf8),
              let rest = input.withCString({ tji_suffix_after_choice($0, Int32(choice.consumedUTF16)) })
        else { return nil }
        defer { tji_text_free(rest) }
        guard let text = choice.text.withCString({
            tji_space_word($0, before.utf16.last ?? 0, after.utf16.first ?? 0)
        }) else { return nil }
        defer { tji_text_free(text) }
        let spaced = String(cString: text)
        let offset = spaced.hasPrefix(choice.text) ? 0 : 1
        let source = spaced as NSString
        var result = ""
        var previous = 0
        for index in stride(from: 0, to: choice.joinedHanRanges.count - 1, by: 2) {
            let first = choice.joinedHanRanges[index] + offset
            let last = choice.joinedHanRanges[index + 1] + offset
            guard first >= previous, last > first, last <= source.length else { continue }
            result += source.substring(with: NSRange(location: previous, length: first - previous))
            result += "˻" + source.substring(with: NSRange(location: first, length: last - first)) + "˼"
            previous = last
        }
        result += source.substring(from: previous)
        return SelectedWord(text: result, remainingInput: String(cString: rest))
    }
}

public final class TujiaHandwriting {
    private let handle: OpaquePointer
    public var glyphCount: Int { tji_glyph_count(handle) }

    public convenience init() throws {
        try self.init(data: asset("handwriting/medians.tsv"))
    }

    public init(data: Data) throws {
        guard let loaded = data.withUnsafeBytes({ bytes in
            tji_pen_open(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count)
        }) else { throw TujiaInputError.invalidStrokeModel }
        handle = loaded
    }

    deinit { tji_pen_close(handle) }

    /// Pass each stroke in writing order. Coordinates grow rightwards and downwards.
    public func glyphs(for strokes: [[CGPoint]], limit: Int = 12) -> [String] {
        guard limit > 0, !strokes.isEmpty else { return [] }
        var xy = [Float]()
        var ends = [Int]()
        for stroke in strokes {
            guard !stroke.isEmpty else { return [] }
            for point in stroke { xy.append(Float(point.x)); xy.append(Float(point.y)) }
            ends.append(xy.count / 2)
        }
        let result = xy.withUnsafeBufferPointer { points in
            ends.withUnsafeBufferPointer {
                tji_recognize(handle, points.baseAddress, points.count / 2,
                              $0.baseAddress, $0.count, Int32(clamping: limit))
            }
        }
        guard let items = result else { return [] }
        defer { tji_choices_close(items) }
        return (0..<tji_choices_count(items)).map { String(cString: tji_choice_text(items, $0)) }
    }
}
