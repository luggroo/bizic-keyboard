import Foundation
import XCTest
import TujiaInput

final class TujiaInputTests: XCTestCase {
    func testHandwritingCompletionAndContext() throws {
        let dictionary = try TujiaDictionary()
        let child = try XCTUnwrap(dictionary.handwritingSuggestions(for: "玻", form: .recommendedHan).first { $0.lexemeIds == "w309" })
        XCTAssertEqual(child.text, "波俐")
        XCTAssertEqual(dictionary.selection(child, in: "玻")?.remainingInput, "")
        XCTAssertEqual(dictionary.scoreHandwritingPrefix("波俐"), 2)
        let meal = try XCTUnwrap(dictionary.nextWords(after: "w2548", form: .spelling, orthography: .diacritic).first)
        XCTAssertEqual(meal.lexemeIds, "w684")
        XCTAssertEqual(meal.consumedUTF16, 0)
        XCTAssertEqual(dictionary.selection(meal, in: "", before: "yě")?.text, " gá")
        XCTAssertTrue(dictionary.nextWords(after: "w2548 。", form: .spelling).isEmpty)
    }

    func testRankedSyllableContext() throws {
        let dictionary = try TujiaDictionary()
        let start = dictionary.nextSyllables(after: "<s>", orthography: .diacritic)
        XCTAssertEqual(start.count, 175)
        let hint = try XCTUnwrap(start.first)
        XCTAssertTrue(hint.syllableOnly)
        XCTAssertEqual(hint.consumedUTF16, 0)
        XCTAssertFalse(hint.lastSyllable.isEmpty)
        XCTAssertEqual(dictionary.selection(hint, in: "", before: "yě")?.text, " " + hint.text)
        XCTAssertEqual(dictionary.selection(hint, in: "")?.remainingInput, "")
        XCTAssertNil(dictionary.selection(hint, in: "stale draft"))
        let han = try XCTUnwrap(dictionary.nextSyllables(after: "<s>", form: .recommendedHan, orthography: .diacritic).first)
        XCTAssertTrue(han.text.unicodeScalars.allSatisfy { $0.properties.isIdeographic })
        XCTAssertEqual(dictionary.selection(han, in: "", before: "叶")?.text, han.text)
        XCTAssertEqual(han.lastSyllable, hint.lastSyllable)
        let tight = try XCTUnwrap(start.first { $0.lastSyllable == "jinv" })
        XCTAssertEqual(tight.shortMeaning, "紧")
        XCTAssertEqual(dictionary.selection(tight, in: "")?.text, "jìn")
        let hanAfterKa = dictionary.nextSyllables(after: "kar", form: .recommendedHan)
        for sound in ["mǒng", "mǔn"] {
            let part = try XCTUnwrap(dictionary.nextSyllables(after: "kǎ", orthography: .diacritic).first { $0.text == sound })
            XCTAssertTrue(part.meaning.isEmpty && part.shortMeaning.isEmpty && part.lexemeIds.isEmpty)
            XCTAssertEqual(dictionary.selection(part, in: "", before: "kǎ")?.text, " " + sound)
            let glyph = try XCTUnwrap(hanAfterKa.first { $0.lastSyllable == part.lastSyllable })
            XCTAssertTrue(glyph.shortMeaning.isEmpty)
            XCTAssertEqual(dictionary.selection(glyph, in: "")?.text, "萌")
        }
        let earthBranch = try XCTUnwrap(dictionary.candidates(for: "mǒng", form: .recommendedHan)
            .first { $0.lexemeIds == "yubao-60757-v-2498" })
        XCTAssertEqual(earthBranch.shortMeaning, "地支午")
        XCTAssertEqual(dictionary.selection(earthBranch, in: "mǒng")?.text, "萌")
        let learned = try XCTUnwrap(dictionary.nextWords(after: "w309 \(earthBranch.lexemeIds) w309", form: .spelling,
                                                        orthography: .diacritic).first { $0.lexemeIds == earthBranch.lexemeIds })
        XCTAssertEqual(learned.shortMeaning, "地支午")
        XCTAssertEqual(dictionary.selection(learned, in: "")?.text, "mǒng")
        XCTAssertTrue(dictionary.nextSyllables(after: "").isEmpty)
        XCTAssertEqual(dictionary.candidates(for: "boxlir", form: .recommendedHan).first?.lastSyllable, "lir")
    }

    func testLoanCharactersAndHandwritingAlias() throws {
        let dictionary = try TujiaDictionary()
        let loan = try XCTUnwrap(dictionary.candidates(for: "伤哥", form: .recommendedHan).first { $0.lexemeIds == "xu213-2-1" })
        XCTAssertTrue(loan.plainHanLoan)
        XCTAssertEqual(loan.kind, .tujia)
        XCTAssertEqual(loan.text, "山歌")
        XCTAssertEqual(loan.meaning, "山歌")
        XCTAssertEqual(loan.shortMeaning, "")
        let spelled = try XCTUnwrap(dictionary.candidates(for: "山歌", form: .handwritingToSpelling).first { $0.lexemeIds == loan.lexemeIds })
        XCTAssertFalse(spelled.plainHanLoan)
        XCTAssertEqual(spelled.shortMeaning, "山歌")
        XCTAssertEqual(spelled.text, "sangxgox")
    }

    func testOneHanCardShowsBothSensesAndPreservesEditBoundaries() throws {
        let dictionary = try TujiaDictionary()
        let groups = dictionary.hanCandidates(for: "go")
        XCTAssertEqual(Set(groups.map { $0.primary.text }).count, groups.count)
        let go = try XCTUnwrap(groups.first { $0.primary.text == "过" })
        XCTAssertEqual(go.caption(), "¹他 ²讨")
        XCTAssertEqual(go.caption(compact: true), "¹他²讨")
        XCTAssertTrue(go.primary.meaning.contains("过错（借词本字）"))
        XCTAssertEqual(go.primary.lexemeIds, "")
        XCTAssertEqual(dictionary.selection(go.primary, in: "go")?.text, "过")
        let ri = try XCTUnwrap(dictionary.hanCandidates(for: "rif").first { $0.primary.text == "日" })
        XCTAssertEqual(dictionary.selection(ri.primary, in: "rif")?.remainingInput, "")
        XCTAssertTrue(ri.alternativeEdits.contains { dictionary.selection($0, in: "rif")?.remainingInput == "f" })
        let next = dictionary.nextSyllables(after: "kar", form: .recommendedHan)
        let merged = try XCTUnwrap(dictionary.groupHanCandidates(next, in: "").first { $0.primary.text == "萌" })
        XCTAssertEqual(merged.primary.lastSyllable, "")
        let elder = try XCTUnwrap(dictionary.hanCandidates(for: "gogo").first { $0.primary.text == "哥哥" })
        XCTAssertEqual(elder.caption(), "")
        XCTAssertEqual(elder.primary.meaning, "")
        let ga = try XCTUnwrap(dictionary.hanCandidates(for: "ga").first { $0.primary.text == "架" })
        XCTAssertEqual(ga.caption(), "吃")
        XCTAssertEqual(ga.primary.lexemeIds, "w684")
        let jia = try XCTUnwrap(dictionary.hanCandidates(for: "jia").first { $0.primary.text == "架" })
        XCTAssertEqual(jia.caption(), "")
        XCTAssertEqual(jia.primary.lexemeIds, "")
        let noon = try XCTUnwrap(dictionary.hanCandidates(for: "banricai").first { $0.primary.text == "半日猜" })
        XCTAssertEqual(dictionary.selection(noon.primary, in: "banricai")?.remainingInput, "")
    }

    func testContinuousSelectionAndDifferentOutputs() throws {
        let dic = try TujiaDictionary()
        let input = "marmardiexhxif"
        let first = try XCTUnwrap(dic.candidates(for: input, form: .recommendedHan).first)
        XCTAssertEqual(first.text, "麻麻爹")
        XCTAssertEqual(first.meaning, "女人")
        let picked = try XCTUnwrap(dic.selection(first, in: input))
        XCTAssertEqual(picked.text, "麻麻爹")
        XCTAssertEqual(picked.remainingInput, "hxif")
        XCTAssertNil(dic.selection(first, in: "jir"))
        let following = dic.candidates(for: picked.remainingInput, form: .recommendedHan)
        XCTAssertTrue(following.contains { $0.text == "诶" && $0.meaning == "走" })
        let senses = dic.candidates(for: "jir", form: .recommendedHan)
        XCTAssertTrue(senses.contains { $0.meaning == "脚" && $0.text == "及" })
        let sentence = try XCTUnwrap(dic.candidates(for: "ngafyergaf。", form: .spelling).first)
        XCTAssertEqual(sentence.text, "ngaf yer gaf。")
        let marked = try XCTUnwrap(dic.candidates(for: "ngafyergaf。", form: .spelling, orthography: .diacritic).first)
        XCTAssertEqual(marked.text, "ngá yě gá。")
        let word = try XCTUnwrap(dic.candidates(for: "hxif", form: .spelling).first)
        let spaced = dic.selection(word, in: "hxif", before: "ngaf", after: "yer")
        XCTAssertEqual(spaced?.text, " hxif ")
    }

    func testEveryBundledEntryThroughSwift() throws {
        let project = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let rows = try String(contentsOf: project.appendingPathComponent("shared/bundle/Assets/words.tsv"),
                              encoding: .utf8).split(separator: "\n").dropFirst()
        let dic = try TujiaDictionary()
        var words = 0
        var compactGlosses = 0
        for row in rows {
            let fields = row.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            if fields.count >= 9 && ["zh", "syllable"].contains(fields[8]) { continue }
            let spelling = fields[1], gloss = fields[3], han = fields[4]
            let candidate = try XCTUnwrap(dic.candidates(for: spelling, form: .recommendedHan)
                .first { $0.text == han && $0.meaning == gloss }, fields[0])
            let compact = fields.count >= 10 && !fields[9].isEmpty ? fields[9] : gloss
            let loan = fields.count >= 15 && fields[14] == "plainHanLoan"
            XCTAssertEqual(candidate.shortMeaning, loan ? "" : compact, fields[0])
            XCTAssertEqual(candidate.plainHanLoan, loan, fields[0])
            XCTAssertEqual(candidate.kind, .tujia, fields[0])
            if fields.count >= 13 {
                var expected: [Int] = []
                var offset = 0
                for unit in fields[12].split(separator: "|") {
                    if unit.unicodeScalars.count == 2 && unit.unicodeScalars.allSatisfy({ $0.properties.isIdeographic }) {
                        expected.append(contentsOf: [offset, offset + unit.utf16.count])
                    }
                    offset += unit.utf16.count
                }
                XCTAssertEqual(candidate.joinedHanRanges, expected, fields[0])
            }
            XCTAssertTrue(dic.candidates(for: han, form: .handwritingToSpelling)
                .contains { $0.text == spelling && $0.meaning == gloss }, fields[0])
            if fields.count >= 12 && !fields[10].isEmpty && !fields[11].isEmpty {
                let plain = fields[10], marked = fields[11]
                for (form, key) in [(InputForm.spelling, plain), (.handwritingToSpelling, han)] {
                    let result = dic.candidates(for: key, form: form, orthography: .diacritic)
                    XCTAssertTrue(result.contains { $0.text == marked && $0.meaning == gloss }, fields[0])
                }
                XCTAssertTrue(dic.candidates(for: plain, form: .spelling, orthography: .ye)
                    .contains { $0.text == spelling && $0.meaning == gloss }, fields[0])
            }
            words += 1
            if compact != gloss { compactGlosses += 1 }
        }
        XCTAssertGreaterThanOrEqual(words, 345)
        XCTAssertGreaterThan(compactGlosses, 0)
    }

    func testDecomposedMarkedSelection() throws {
        let dic = try TujiaDictionary()
        let input = "GA\u{301}hxif"
        let choice = try XCTUnwrap(dic.candidates(for: input, form: .recommendedHan)
            .first { $0.meaning == "吃、咬" })
        XCTAssertEqual(choice.consumedUTF16, 3)
        XCTAssertEqual(dic.selection(choice, in: input)?.remainingInput, "hxif")
        XCTAssertNil(dic.selection(choice, in: input.precomposedStringWithCanonicalMapping))
        XCTAssertFalse(dic.candidates(for: "gá", form: .spelling).contains { $0.meaning == "干、渴" })
    }

    func testChinesePinyinAndSelection() throws {
        let dic = try TujiaDictionary()
        for form in [InputForm.spelling, .recommendedHan] {
            let nihao = try XCTUnwrap(dic.candidates(for: "nihao", form: form)
                .first { $0.text == "你好" && $0.kind == .chinese })
            XCTAssertEqual(nihao.meaning, "")
            XCTAssertEqual(nihao.shortMeaning, "")
            XCTAssertEqual(nihao.consumedUTF16, 5)
            XCTAssertEqual(dic.selection(nihao, in: "nihao")?.text, "你好")
            XCTAssertTrue(dic.candidates(for: "nihao", form: form, orthography: .diacritic)
                .contains { $0.text == "你好" && $0.kind == .chinese })
        }
        XCTAssertFalse(dic.candidates(for: "nihao", form: .handwritingToSpelling)
            .contains { $0.kind == .chinese })
    }

    func testHandwritingAndVariantLookup() throws {
        let pen = try TujiaHandwriting()
        XCTAssertEqual(pen.glyphCount, 3565)
        let strokes = [[CGPoint(x: 20,y: 20), CGPoint(x: 70,y: 20)],
                       [CGPoint(x: 25,y: 49), CGPoint(x: 75,y: 49)],
                       [CGPoint(x: 10,y: 80), CGPoint(x: 90,y: 80)]]
        XCTAssertEqual(pen.glyphs(for: strokes).first, "三")
        XCTAssertTrue(pen.glyphs(for: []).isEmpty)
        let dic = try TujiaDictionary()
        for glyph in ["嘎", "尬"] {
            let words = dic.candidates(for: glyph, form: .handwritingToSpelling)
            XCTAssertTrue(words.contains { $0.text == "gaf" && $0.meaning == "吃、咬" })
        }
    }
}
