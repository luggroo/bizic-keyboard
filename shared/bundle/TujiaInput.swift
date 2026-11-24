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
    public let consumedUTF16: Int
    /// Flattened UTF-16 start/end-exclusive pairs for two-Han syllables in text.
    public let joinedHanRanges: [Int]
    fileprivate let input: String
}

public struct SelectedWord: Equatable {
    /// The text ready for the current editor, including word-boundary spaces.
    public let text: String
    /// The unselected suffix to look up on the next frame.
    public let remainingInput: String
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
        try self.init(data: asset("words.tsv"))
    }

    public init(data: Data) throws {
        guard let loaded = data.withUnsafeBytes({ bytes in
            tji_words_open(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count)
        }) else { throw TujiaInputError.invalidDictionary }
        handle = loaded
    }

    deinit { tji_words_close(handle) }

    /// Tujia candidates carry both a compact gloss for the keyboard and a full definition.
    public func candidates(for input: String, form: InputForm) -> [WordCandidate] {
        candidates(for: input, form: form, orthography: .ye)
    }

    public func candidates(for input: String, form: InputForm, orthography: SpellingOrthography) -> [WordCandidate] {
        guard !input.utf8.contains(0),
              let result = input.withCString({ tji_lookup_with_orthography(handle, $0, form.rawValue,
                                                                           orthography.rawValue) }) else { return [] }
        defer { tji_choices_close(result) }
        return (0..<tji_choices_count(result)).map { i in
            var rangeCount = 0
            let offsets = tji_choice_joined_han_ranges(result, i, &rangeCount)
            let ranges = UnsafeBufferPointer(start: offsets, count: rangeCount).map(Int.init)
            return WordCandidate(text: String(cString: tji_choice_text(result, i)),
                          meaning: String(cString: tji_choice_meaning(result, i)),
                          shortMeaning: String(cString: tji_choice_short_meaning(result, i)),
                          kind: CandidateKind(rawValue: tji_choice_kind(result, i)) ?? .tujia,
                          consumedUTF16: Int(tji_choice_consumed_utf16(result, i)), joinedHanRanges: ranges, input: input)
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
        return SelectedWord(text: String(cString: text), remainingInput: String(cString: rest))
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
