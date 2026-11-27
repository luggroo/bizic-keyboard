package biz.tujia.ime;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative, local whole-word suggestions for a short northern IPA utterance. */
final class VoiceMatcher {
    static String key(String text, boolean tones) {
        StringBuilder out = new StringBuilder();
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '1' && c <= '5') c = "¹²³⁴⁵".charAt(c - '1');
            if ("¹²³⁴⁵".indexOf(c) >= 0) { if (tones) out.append(c); }
            else if (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK)
                out.append(c == 'g' ? 'ɡ' : c);
        }
        return out.toString();
    }
    private static String tonePattern(String text) {
        StringBuilder pattern = new StringBuilder();
        boolean inTone = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '1' && c <= '5') c = "¹²³⁴⁵".charAt(c - '1');
            if ("¹²³⁴⁵".indexOf(c) >= 0) {
                if (!inTone && pattern.length() > 0) pattern.append('/');
                pattern.append(c);
                inTone = true;
            } else { inTone = false; }
        }
        return pattern.toString();
    }
    private static int syllableCount(String pattern) {
        return pattern.isEmpty() ? 0 : pattern.split("/", -1).length;
    }
    private static int distance(String a, String b) {
        int[] previous = new int[b.length()+1], next = new int[b.length()+1];
        for (int j=0; j<=b.length(); j++) previous[j]=j;
        for (int i=1; i<=a.length(); i++) {
            next[0]=i;
            for (int j=1; j<=b.length(); j++) next[j]=Math.min(Math.min(next[j-1]+1, previous[j]+1), previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            int[] swap=previous; previous=next; next=swap;
        }
        return previous[b.length()];
    }
    private static final class Scored {
        final IpaKeyboard.Entry entry; final int distance; final boolean tone;
        Scored(IpaKeyboard.Entry e, int d, boolean t) { entry=e; distance=d; tone=t; }
    }
    static List<IpaKeyboard.Entry> match(String raw, List<IpaKeyboard.Entry> entries) {
        String heard=key(raw,false), toned=key(raw,true), tones=tonePattern(raw);
        if (heard.length() < 2 || heard.length() > 32 || raw.trim().split("\\s+").length > 5) return Collections.emptyList();
        int max = heard.length() < 5 ? 0 : heard.length() < 9 ? 1 : 2;
        List<Scored> scored=new ArrayList<>();
        for (IpaKeyboard.Entry entry: entries) {
            if ((entry.dialects & IpaKeyboard.NORTH)==0 || entry.ipa.startsWith("-")) continue;
            String candidate=key(entry.ipa,false);
            if (Math.abs(candidate.length()-heard.length())>max) continue;
            String candidateTones=tonePattern(entry.ipa);
            if (syllableCount(tones)>0 && syllableCount(candidateTones)>0
                    && syllableCount(tones)!=syllableCount(candidateTones)) continue;
            int d=distance(heard,candidate);
            // When a sound is also changed, require the reported tone pattern to agree.
            if (d>0 && !tones.equals(candidateTones)) continue;
            if (d<=max) scored.add(new Scored(entry,d,key(entry.ipa,true).equals(toned)));
        }
        scored.sort(Comparator.comparingInt((Scored s)->s.distance).thenComparing(s->!s.tone));
        List<IpaKeyboard.Entry> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for (Scored s:scored) if (seen.add(s.entry.ipa)) { result.add(s.entry); if (result.size()==12) break; }
        return result;
    }
    private VoiceMatcher() {}
}
