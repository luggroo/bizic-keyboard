// Reuse the exact front-end spelling and text conversion functions of the source snapshot.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const site = process.argv[2];
const request = JSON.parse(fs.readFileSync(0, 'utf8'));
const context = vm.createContext({ document: { addEventListener() {} } });
for (const source of ['notation-ui.js', 'longshan-texts.js']) {
    vm.runInContext(fs.readFileSync(path.join(site, source), 'utf8'), context, {filename: source});
}
const convert = (keys, fn) => Object.fromEntries(keys.map(key => [key, fn(key)]));
process.stdout.write(JSON.stringify({
    accented: convert(request.words, context.yeToAccent),
    spellings: convert(request.ipa, context.xuSiteSpelling)
}));
