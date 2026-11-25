// Read the same source corrections and point-specific spellings as tujia.biz.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const site = process.argv[2];
const context = vm.createContext({
  localStorage: {getItem: () => null},
  document: {addEventListener() {}},
  window: {addEventListener() {}},
  SiteRequest: {json: async file => JSON.parse(fs.readFileSync(path.join(site, file), 'utf8'))}
});
for (const file of ['ipa-normalize.js','notation-profiles.js','notation-ui.js','southern-notation.js',
                    'yubao.js','grammar-notation.js','lexical-reviews.js','spelling-boundaries.js','longshan-texts.js'])
  vm.runInContext(fs.readFileSync(path.join(site, file), 'utf8'), context, {filename: file});
const plainTones = vm.runInContext("plainTones", context);
(async () => {
  const data = {sources: [], words: [], wordAliases: {}, corpus: []};
  await context.loadYubao(data);
  const selected = new Set(['yubao-60756', 'yubao-60757', 'yubao-60823']);
  const records = data.words.filter(w => selected.has(w.source)).map(w => ({
    id: w.id, source: w.source, zh: w.zh, term: w.term,
    forms: w.notations.ye.map((v, i) => {
      const point = w.source.slice(-5);
      const ipa = (v.ipaForms || [w.notations.ipa[i].text])[0];
      const text = plainTones(ipa).normalize('NFC');
      const parts = Array.from(text.matchAll(/([^0-9\s（）()]+)([0-9]+)/g), m => {
        const spelling = context.northernSurveySpelling(m[0], point);
        return spelling && spelling.ye;
      });
      return {ye: v.text, accent: v.accent, parts, ipa};
    })
  }));
  process.stdout.write(JSON.stringify(records));
})().catch(err => {console.error(err); process.exitCode = 1});
