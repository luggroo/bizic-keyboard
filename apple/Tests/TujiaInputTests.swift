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
        XCTAssertNil(dictionary.selection(hint, in: ""))
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
        XCTAssertEqual(pen.glyphCount, 3561)
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
