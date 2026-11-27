package biz.tujia.ime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A typed Han form occupies one slot. Different consumed prefixes remain selectable. */
final class HanChoice {
    static final class Action {
        final int consumed;
        CandidateEngine.Candidate candidate;
        final Set<String> shortMeanings = new LinkedHashSet<>();
        final Set<String> meanings = new LinkedHashSet<>();
        final Set<String> loanMeanings = new LinkedHashSet<>();
        private String agreedIds, agreedSyllable;
        private CandidateEngine.Candidate firstArticle;
        private List<CandidateEngine.Candidate> otherArticles;

        Action(int consumed, CandidateEngine.Candidate candidate) {
            this.consumed = consumed;
            this.candidate = candidate;
        }

        void add(CandidateEngine.Candidate c, boolean hasMeaning) {
            if (c.kind != CandidateEngine.Kind.TUJIA) return;
            if (candidate.kind != CandidateEngine.Kind.TUJIA || candidate.syllableOnly && !c.syllableOnly)
                candidate = c;
            if (hasMeaning) {
                shortMeanings.add(c.shortMeaning);
                meanings.add(c.meaning);
            } else if (c.plainHanLoan && !c.meaning.isEmpty()) {
                loanMeanings.add(c.meaning);
            }
            // A plain syllable supplies a pronunciation, but does not claim a word sense.
            if (!c.syllableOnly || hasMeaning) {
                agreedIds = common(agreedIds, c.lexemeIds);
                if (!c.lexemeIds.isEmpty() && c.lexemeIds.indexOf(' ') < 0) {
                    if (firstArticle == null) firstArticle = c;
                    else if (!firstArticle.lexemeIds.equals(c.lexemeIds)) {
                        if (otherArticles == null) otherArticles = new ArrayList<>();
                        otherArticles.add(c);
                    }
                }
            }
            agreedSyllable = common(agreedSyllable, c.lastSyllable);
        }

        String ids() { return agreedIds == null ? "" : agreedIds; }
        void articles(Map<String, String> result) {
            if (firstArticle != null) result.putIfAbsent(firstArticle.lexemeIds, firstArticle.meaning);
            if (otherArticles != null) for (CandidateEngine.Candidate c : otherArticles)
                result.putIfAbsent(c.lexemeIds, c.meaning);
        }
        String lastSyllable() { return agreedSyllable == null ? "" : agreedSyllable; }

        String fullMeanings() {
            Set<String> all = new LinkedHashSet<>(meanings);
            all.addAll(loanMeanings);
            return join(all, "、");
        }
    }

    CandidateEngine.Candidate first;
    final List<Action> actions = new ArrayList<>();
    Action primary;

    HanChoice(CandidateEngine.Candidate first) { this.first = first; }

    void add(CandidateEngine.Candidate c, int consumed, boolean hasMeaning) {
        if (first.kind != CandidateEngine.Kind.TUJIA && c.kind == CandidateEngine.Kind.TUJIA) first = c;
        for (Action action : actions) {
            if (action.consumed == consumed) {
                action.add(c, hasMeaning);
                return;
            }
        }
        Action action = new Action(consumed, c);
        action.add(c, hasMeaning);
        actions.add(action);
        if (primary == null || consumed > primary.consumed) primary = action;
    }

    static boolean identitiesPresent(String required, Set<String> present) {
        if (required.isEmpty()) return true;
        for (String id : required.split(" ")) if (!present.contains(id)) return false;
        return true;
    }

    private static String common(String previous, String next) {
        if (previous == null) return next;
        return previous.equals(next) ? previous : "";
    }

    static String join(Set<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() != 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    static String gloss(Set<String> meanings, boolean spaced) {
        return gloss(meanings, spaced, Integer.MAX_VALUE);
    }

    static String gloss(Set<String> meanings, boolean spaced, int limit) {
        if (meanings.size() < 2) return join(meanings, "");
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (String meaning : meanings) {
            if (index >= limit) break;
            if (spaced && index != 0) out.append(' ');
            String number = Integer.toString(++index);
            for (int i = 0; i < number.length(); i++) out.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(number.charAt(i) - '0'));
            out.append(meaning);
        }
        return out.toString();
    }
}
