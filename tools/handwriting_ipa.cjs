// Build IPA renderings for the keyboard's Northern lexicon using the site's
// reviewed Ye–Brassett correspondences. Unresolved forms keep their handwritten Han.
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const [site, sourceCommit] = process.argv.slice(2);
if (!site || !sourceCommit) throw new Error('Pass the dictionary site directory and its commit SHA');

const runtime = vm.createContext({ document: { addEventListener() {} } });
for (const name of ['ipa-normalize.js', 'notation-profiles.js', 'notation-ui.js']) {
  vm.runInContext(fs.readFileSync(path.join(site, name), 'utf8'), runtime, { filename: name });
}
const convert = vm.runInContext(`text => {
  const sections = text.split(/([ -])/);
  const output = [];
  for (const token of sections) {
    if (!token || token === ' ' || token === '-') { output.push(token); continue; }
    const candidates = [...new Set(reverseYe(token).map(standardBrassettIPA).filter(Boolean))];
    if (candidates.length !== 1) return null;
    output.push(candidates[0]);
  }
  return output.join('');
}`, runtime);

const words = new Set(['bifzixkar']);
for (const file of ['words.tsv', 'northern-words.tsv']) {
  for (const line of fs.readFileSync('shared/bundle/Assets/' + file, 'utf8').trimEnd().split('\n')) {
    const [, word, , , , , , , kind] = line.split('\t');
    if (kind !== 'zh' && kind !== 'kind') words.add(word.toLowerCase());
  }
}
for (const line of fs.readFileSync('shared/bundle/Assets/syllable_bigrams.tsv', 'utf8').trimEnd().split('\n').slice(1)) {
  const [context, next] = line.split('\t');
  if (context !== '<s>') words.add(context);
  words.add(next);
}

const output = ['# Northern Ye spelling and IPA from the Bizic Dictionary notation table.',
  '# https://github.com/luggroo/bizic-dictionary/tree/' + sourceCommit + '/site'];
for (const word of [...words].sort()) {
  const ipa = convert(word);
  if (ipa !== null) output.push(word + '\t' + ipa);
}
fs.writeFileSync('app/src/main/assets/handwriting-ipa.tsv', output.join('\n') + '\n');
console.log(output.length - 2 + ' resolved forms out of ' + words.size);
