package biz.tujia.ime;

import java.io.FileInputStream;
import java.io.SequenceInputStream;
import java.io.File;
import java.util.List;

public final class HanChoiceTest {
    private static HanChoice group(List<CandidateEngine.Candidate> raw, String form) {
        HanChoice choice = null;
        for (CandidateEngine.Candidate c : raw) if (c.text.equals(form) && c.kind == CandidateEngine.Kind.TUJIA) {
            if (choice == null) choice = new HanChoice(c);
            choice.add(c, c.consumed, !c.plainHanLoan && !c.syllableOnly && !c.shortMeaning.isEmpty());
        }
        if (choice == null) throw new AssertionError("Missing " + form);
        return choice;
    }

    private static void expect(String wanted, String actual) {
        if (!wanted.equals(actual)) throw new AssertionError("Expected [" + wanted + "], got [" + actual + "]");
    }

    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]).getParentFile();
        try (CandidateEngine engine = new CandidateEngine(new FileInputStream(args[0]),
                new FileInputStream(new File(assets, "ngrams.tsv")),
                new FileInputStream(new File(assets, "syllable_bigrams.tsv")),
                new FileInputStream(new File(assets, "syllable_senses.tsv")))) {
            HanChoice go = group(engine.lookup("go", CandidateEngine.Mode.HAN), "过");
            expect("讨", HanChoice.gloss(go.primary.shortMeanings, true));
            expect("讨", HanChoice.gloss(go.primary.shortMeanings, false));
            expect("讨、过错", go.primary.fullMeanings());
            expect("", go.primary.ids());
            expect("gof", go.primary.lastSyllable());
            HanChoice ga = group(engine.lookup("ga", CandidateEngine.Mode.HAN), "夹");
            expect("¹上²干", HanChoice.gloss(ga.primary.shortMeanings, false));
            expect("", ga.primary.ids());
            HanChoice ri = group(engine.lookup("rif", CandidateEngine.Mode.HAN), "日");
            if (ri.primary.consumed != 3 || ri.actions.size() != 2 || !ri.primary.shortMeanings.isEmpty())
                throw new AssertionError("The raw syllable must consume the full spelling without a gloss");
            HanChoice.Action partial = ri.actions.get(0) == ri.primary ? ri.actions.get(1) : ri.actions.get(0);
            expect("f", CandidateEngine.suffixAfterChoice("rif", partial.consumed));
            if (!partial.meanings.contains("做") || !partial.meanings.contains("多"))
                throw new AssertionError("Partial boundary lost distinct senses");
            // A spelling that explicitly selects a single tone and sense can retain that word ID.
            HanChoice marked = group(engine.lookup("gǒ", CandidateEngine.Mode.HAN), "郭");
            if (marked.primary.ids().isEmpty()) throw new AssertionError("Unambiguous word lost its history");
            expect("gor", marked.primary.lastSyllable());
            List<CandidateEngine.Candidate> sounds = engine.nextSyllables("kar",
                    CandidateEngine.Mode.HAN, CandidateEngine.Orthography.YE);
            long readings = sounds.stream().filter(c -> c.text.equals("萌")).map(c -> c.lastSyllable).distinct().count();
            if (readings != 2) throw new AssertionError("Expected both munr and mongr for 萌");
            HanChoice meng = group(sounds, "萌");
            expect("", meng.primary.ids());
            expect("", meng.primary.lastSyllable());
            List<CandidateEngine.Candidate> gaChoices = engine.lookup("ga", CandidateEngine.Mode.HAN);
            CandidateEngine.Candidate eating = gaChoices.stream().filter(c -> c.lexemeIds.equals("w684")).findFirst().get();
            CandidateEngine.Candidate dialect = gaChoices.stream().filter(c -> c.kind == CandidateEngine.Kind.CHINESE
                    && c.text.equals("架") && c.consumed == 2).findFirst().get();
            // A normal Han fallback is not a competing Tujia reading, regardless of which source arrived first.
            for (boolean wordFirst : new boolean[]{false, true}) {
                HanChoice written = new HanChoice(wordFirst ? eating : dialect);
                written.add(wordFirst ? eating : dialect, 2, wordFirst);
                written.add(wordFirst ? dialect : eating, 2, !wordFirst);
                expect("吃", HanChoice.gloss(written.primary.shortMeanings, false));
                expect("w684", written.primary.ids());
                expect("gaf", written.primary.lastSyllable());
                if (engine.nextWords(written.primary.ids(), CandidateEngine.Mode.HAN, CandidateEngine.Orthography.YE).isEmpty())
                    throw new AssertionError("Eating lost its word predictions");
            }
            CandidateEngine.Candidate regular = engine.lookup("jia", CandidateEngine.Mode.HAN).stream().filter(
                    c -> c.text.equals("架") && c.consumed == 3 && c.kind == CandidateEngine.Kind.CHINESE).findFirst().get();
            HanChoice jia = new HanChoice(regular);
            jia.add(regular, 3, false);
            expect("", jia.primary.ids());
            expect("", jia.primary.lastSyllable());
            expect("", jia.primary.fullMeanings());
        }
        // The installed keyboard also loads the northern extension; many
        // same-form senses are absent from the smaller base fixture above.
        try (CandidateEngine full = new CandidateEngine(
                new SequenceInputStream(new FileInputStream(args[0]), new FileInputStream(new File(assets, "northern-words.tsv"))),
                new FileInputStream(new File(assets, "ngrams.tsv")),
                new FileInputStream(new File(assets, "syllable_bigrams.tsv")),
                new FileInputStream(new File(assets, "syllable_senses.tsv")))) {
            HanChoice fullGa = group(full.lookup("ga", CandidateEngine.Mode.HAN), "夹");
            String gaGloss = HanChoice.gloss(fullGa.primary.shortMeanings, false);
            if (!gaGloss.startsWith("¹上²干") || fullGa.primary.shortMeanings.size() < 3)
                throw new AssertionError("Northern 夹 senses missing or reordered: " + gaGloss);
            HanChoice nga = group(full.lookup("nga", CandidateEngine.Mode.HAN), "牙");
            expect("¹我²压", HanChoice.gloss(nga.primary.shortMeanings, false));
        }
        System.out.println("Han form grouping, pronunciation consensus, short glosses and prefix boundaries passed");
    }
}
