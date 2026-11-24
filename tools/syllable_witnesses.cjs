// Export northern witnesses with the same source- and point-specific spelling rules as tujia.biz.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const zlib = require('node:zlib');
const site = process.argv[2];
const resolve = name => path.join(site, name);
const read = name => JSON.parse(fs.readFileSync(resolve(name), 'utf8'));
const context = vm.createContext({localStorage: {getItem: () => null},
    document: {addEventListener() {}}, window: {addEventListener() {}}});
for (const name of ['ipa-normalize.js', 'notation-profiles.js', 'notation-ui.js', 'southern-notation.js',
    'yubao.js', 'grammar-notation.js', 'lexical-reviews.js', 'spelling-boundaries.js']) {
    vm.runInContext(fs.readFileSync(resolve(name), 'utf8'), context, {filename: name});
}
const words = JSON.parse(zlib.gunzipSync(fs.readFileSync(resolve('dictionary.json.gz')))).words;
context.initializeSpellingBoundaries({words});
let northernEntries = 0, northernAttestations = 0, surveyRows = 0;
const witnesses = [], unmatched = [];
for (const word of words) {
    const north = new Map(word.attestations.filter(a => a.dialect === '北部').map(a => [a.id, a]));
    if (!north.size) continue;
    northernEntries++;
    northernAttestations += north.size;
    const used = new Set();
    // Include the per-profile variants even when an identical spelling from another
    // survey point has already been deduplicated for display by the website.
    const forms = [...context.notationValues(word, 'ye'), ...context.sourceNotationValues(word).ye];
    for (const a of north.values()) {
        if (!a.alt || !a.scheme.startsWith('叶氏拼写')) continue;
        for (const alt of a.alt.replace(/[()（）]/g, '').split(/[,，;；/／~～]/u))
            if (alt.trim()) forms.push({text: alt.trim(), method: 'source-variant', attestationIds: [a.id]});
    }
    for (const form of word.notations?.brassett || []) {
        const spelling = context.normalizedBrassettText(form.text);
        if (context.standardBrassettIPA(spelling))
            forms.push({...form, text: context.displayBrassettYe(spelling), method: 'brassett-mapped'});
    }
    for (const form of forms) for (const id of form.attestationIds || []) {
        const a = north.get(id);
        if (!a) continue;
        const spelling = context.reviewedSpelling(word, form.text, 'ye');
        const key = id + '\t' + spelling;
        if (used.has(key)) continue;
        used.add(key);
        witnesses.push({word: word.id, id, source: a.source, locality: a.language || '',
            citation: a.citation || a.scheme, ipaOrTerm: a.term, spelling, sense: a.zh || word.zh || '',
            en: a.en || word.en || '', note: '', pos: a.pos || word.pos || '', method: form.method,
            uncertain: !!form.ambiguous || ['source-partial', 'source-candidate', 'ambiguous'].includes(form.method)});
    }
    if (!used.size) unmatched.push(word.id);
}
const correctedSurveyHeadwords = vm.runInContext('YUBAO_ETHNONYM_GLOSSES', context);
for (const file of read('yubao-manifest.json')) {
    const data = read(file);
    if (data.dialect !== '北部') continue;
    for (const [serial, prompt, raw, note] of data.words) {
        surveyRows++;
        const id = `yubao-${data.point}-v-${serial}`;
        const reviewed = correctedSurveyHeadwords[data.point + '-' + serial];
        const sense = reviewed ? reviewed[0] : prompt;
        const correction = context.surveyWordCorrection(raw, data.point, serial);
        const term = correction ? correction.corrected : raw;
        const full = context.northernSurveySpelling(term, data.point);
        if (full) {
            witnesses.push({word: id, id, source: `yubao-${data.point}`, locality: data.location,
                citation: '中国语保', ipaOrTerm: term, spelling: full.ye, sense, en: '', note, pos: '',
                method: 'point-mapping', uncertain: false});
            continue;
        }
        unmatched.push(id);
        // A partially mapped compound can still refute a proposed meaning of its component.
        for (const [piece] of term.matchAll(/[^\s()（）0-9⁰¹²³⁴⁵⁶⁷⁸⁹]+[0-9⁰¹²³⁴⁵⁶⁷⁸⁹]+/gu)) {
            const segment = context.northernSurveySpelling(piece, data.point);
            if (!segment) continue;
            witnesses.push({word: id, id, source: `yubao-${data.point}`, locality: data.location,
                citation: '中国语保', ipaOrTerm: term, spelling: segment.ye, sense, en: '', note, pos: '',
                method: 'partial-component', uncertain: true});
        }
    }
}
process.stdout.write(JSON.stringify({northernEntries, northernAttestations, surveyRows, witnesses, unmatched}));
