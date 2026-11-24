// Run the same northern display functions used by tujia.biz's prepared word index.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const site = process.argv[2];
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
const context = vm.createContext({
    localStorage: {getItem: () => null},
    document: {addEventListener() {}},
    window: {addEventListener() {}}
});
for (const source of ['ipa-normalize.js','notation-profiles.js','notation-ui.js','southern-notation.js',
                      'yubao.js','grammar-notation.js','lexical-reviews.js','spelling-boundaries.js','longshan-texts.js']) {
    vm.runInContext(fs.readFileSync(path.join(site, source), 'utf8'), context, {filename: source});
}
const convert = (keys, fn) => Object.fromEntries((keys || []).map(key => [key, fn(key)]));
const records = request.entries || [];
context.initializeSpellingBoundaries({words: records});
const display = {};
for (const w of records) {
    display[w.id] = {};
    for (const mode of ['ye','accent']) {
        context.mode = mode;
        vm.runInContext('spellingDisplay=mode', context);
        display[w.id][mode] = context.completeSpellings(w)
          .filter(v => (request.includeAlternatives || !v.ambiguous) && !/[□⟦⟧?]/u.test(v.text))
          .map(v => v.text);
    }
}
process.stdout.write(JSON.stringify({
    accented: convert(request.words, context.yeToAccent),
    spellings: convert(request.ipa, context.xuSiteSpelling),
    display
}));
