import Foundation

/// One visible Han form. A tap uses the longest edit. Shorter edits can be
/// offered in the detail view when they leave a different spelling in the editor.
public struct HanCandidateGroup {
    public let primary: WordCandidate
    public let alternativeEdits: [WordCandidate]

    public func caption(compact: Bool = false) -> String {
        compact ? primary.shortMeaning.replacingOccurrences(of: " ²", with: "²") : primary.shortMeaning
    }
}

private func distinct(_ values: [String]) -> [String] {
    var seen = Set<String>()
    return values.filter { !$0.isEmpty && seen.insert($0).inserted }
}

private func sharedValue(_ values: [String]) -> String {
    guard let first = values.first, values.allSatisfy({ $0 == first }) else { return "" }
    return first
}

private func mergeEdit(_ members: [WordCandidate]) -> WordCandidate {
    let representative = members[0]
    let native = members.filter { $0.kind == .tujia }
    let lexical = native.filter {
        !$0.syllableOnly || (!$0.meaning.isEmpty && $0.meaning != "音节")
    }
    let glosses = distinct(lexical.filter { !$0.plainHanLoan }.map(\.shortMeaning))
    let caption: String
    if glosses.count >= 2 {
        caption = "¹" + glosses[0] + " ²" + glosses[1]
    } else if let only = glosses.first, only != representative.text {
        caption = only
    } else {
        caption = ""
    }
    let meanings = distinct(lexical.map {
        if !$0.plainHanLoan { return $0.meaning }
        return $0.meaning == representative.text ? "" : $0.meaning + "（借词本字）"
    })
    return WordCandidate(text: representative.text, meaning: meanings.joined(separator: "；"),
                         shortMeaning: caption, kind: representative.kind,
                         lexemeIds: sharedValue(lexical.map(\.lexemeIds)),
                         hanSenseId: sharedValue(lexical.map(\.hanSenseId)),
                         lastSyllable: sharedValue(native.map(\.lastSyllable)),
                         syllableOnly: representative.syllableOnly && lexical.isEmpty,
                         plainHanLoan: !lexical.isEmpty && lexical.allSatisfy(\.plainHanLoan),
                         consumedUTF16: representative.consumedUTF16,
                         joinedHanRanges: representative.joinedHanRanges, input: representative.input)
}

public extension TujiaDictionary {
    /// Group an ordered list of recommended-Han candidates for the same draft.
    /// The resulting edits can be passed directly to selection or commit.
    func groupHanCandidates(_ candidates: [WordCandidate], in draft: String) -> [HanCandidateGroup] {
        var forms: [String] = []
        var byForm: [String: [WordCandidate]] = [:]
        var senseForms: [Int: [String: String]] = [:]
        for word in candidates where word.input.utf8.elementsEqual(draft.utf8) {
            if !word.hanSenseId.isEmpty {
                if let preferred = senseForms[word.consumedUTF16]?[word.hanSenseId], preferred != word.text {
                    continue
                }
                senseForms[word.consumedUTF16, default: [:]][word.hanSenseId] = word.text
            }
            if byForm[word.text] == nil { forms.append(word.text) }
            byForm[word.text, default: []].append(word)
        }
        return forms.compactMap { form in
            guard let members = byForm[form] else { return nil }
            let byLength = Dictionary(grouping: members, by: \.consumedUTF16)
            let edits = byLength.keys.sorted(by: >).compactMap { byLength[$0].map(mergeEdit) }
            guard let primary = edits.first else { return nil }
            return HanCandidateGroup(primary: primary, alternativeEdits: Array(edits.dropFirst()))
        }
    }

    /// Ready-to-display typed Han matches, including dictionary completions.
    func hanCandidates(for draft: String, after previousIds: String = "") -> [HanCandidateGroup] {
        let matches = candidates(for: draft, form: .recommendedHan)
        let words = matches.filter {
            $0.kind == .tujia && !$0.syllableOnly && $0.consumedUTF16 == draft.utf16.count
        }
        let completions = spellingCompletions(draft, after: previousIds, form: .recommendedHan)
        let remaining = matches.filter {
            $0.kind != .tujia || $0.syllableOnly || $0.consumedUTF16 != draft.utf16.count
        }
        return groupHanCandidates(words + completions + remaining, in: draft)
    }
}
