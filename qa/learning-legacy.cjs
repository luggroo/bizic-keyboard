// Android 7's stock Chromium 51/52 predates the Playwright protocol. Exercise its
// real WebView using the DevTools commands it implements. Requires Node 22+.
// Set BIZIC_PROFILE_GZIP=1 to time the original decoder on the same APK index.
'use strict';
const assert = require('node:assert/strict');
const {setTimeout: delay} = require('node:timers/promises');
const {execFileSync} = require('node:child_process');

const origin = 'https://appassets.androidplatform.net';
const start = origin + '/index.html';

async function exerciseLegacyUI({adb, shell, socket, info, capture, keyboardRoundTrip, returnFromPrivacy, cancelExternal, apk}) {
  const port = (await adb('forward', 'tcp:0', 'localabstract:' + socket)).toString().trim();
  let ws;
  try {
    const targets = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
    const target = targets.find(item => item.type === 'page' && item.url.startsWith(origin));
    assert(target, 'The local WebView page is missing');
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, {once: true});
      ws.addEventListener('error', reject, {once: true});
    });
    const pending = new Map();
    let sequence = 0;
    ws.addEventListener('close', () => {
      for (const done of pending.values()) done({error: {message: 'WebView debugger disconnected'}});
    });
    info.consoleErrors = [];
    ws.addEventListener('message', ({data}) => {
      const message = JSON.parse(data);
      if (pending.has(message.id)) {
        pending.get(message.id)(message);
        return;
      }
      if (message.method === 'Network.requestWillBeSent') {
        const url = message.params.request.url;
        if (/^https?:/.test(url) && new URL(url).origin !== origin) info.externalRequests.push(url);
      }
      if (message.method === 'Runtime.exceptionThrown') {
        const details = message.params.exceptionDetails || message.params;
        info.pageErrors.push(details.exception?.description || details.text || JSON.stringify(details));
      }
      if (message.method === 'Console.messageAdded') {
        const item = message.params.message;
        if (item.level === 'error') info.consoleErrors.push({text: item.text, url: item.url});
      }
    });
    function call(method, params = {}) {
      const id = ++sequence;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(Error('Timed out: ' + method + ' ' + String(params.expression || '').slice(0, 120)));
        }, 240000);
        pending.set(id, message => {
          clearTimeout(timer);
          pending.delete(id);
          if (message.error) reject(Error(method + ': ' + message.error.message));
          else resolve(message.result);
        });
        ws.send(JSON.stringify({id, method, params}));
      });
    }
    async function value(expression) {
      const answer = await call('Runtime.evaluate', {expression, returnByValue: true});
      if (answer.wasThrown || answer.exceptionDetails) throw Error(answer.result?.description || expression);
      return answer.result.value;
    }
    async function wait(expression, description) {
      const until = Date.now() + 180000;
      let actual;
      do {
        actual = await value(expression);
        if (actual) return actual;
        await delay(400);
      } while (Date.now() < until);
      throw Error('Timed out waiting for ' + description + '; page errors: ' + JSON.stringify(info.pageErrors));
    }
    async function ready(view) {
      await wait('window.fastReady && typeof navigationReady !== "undefined" && navigationReady && ' +
        'typeof state !== "undefined" && state.view === ' + JSON.stringify(view) +
        ' && !SitePages.pending && SitePages.isReady(state, currentDetail) && !!document.querySelector("#main h1")', view);
    }
    async function visit(route, view) {
      await call('Page.navigate', {url: start + route});
      await ready(view);
    }
    async function click(selector, trusted = false) {
      const query = 'Array.prototype.find.call(document.querySelectorAll(' + JSON.stringify(selector)
        + '), function(node){return node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0})';
      await wait('!!' + query, selector);
      if (!trusted) { await value(query + '.click()'); return; }
      const point = await value('(function(el){el.scrollIntoView(true);var r=el.getBoundingClientRect();' +
          'return {x:r.left+r.width/2,y:r.top+r.height/2,viewport:window.innerWidth};})(' + query + ')');
      // Android can advertise the debugging socket before laying out its first frame.
      const frame = await (async () => {
        const end = Date.now() + 20000;
        do {
          const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
          const current = pages.find(item => item.id === target.id);
          const box = current && JSON.parse(current.description);
          if (box && box.width > 0 && box.height > 0) return box;
          await delay(250);
        } while (Date.now() < end);
        throw Error('The foreground Android WebView did not report its laid-out bounds');
      })();
      const scale = frame.width / point.viewport;
      assert(scale > 0, 'The Android WebView has no visible viewport');
      await shell('input', 'tap', String(Math.round(frame.screenX + scale * point.x)),
        String(Math.round(frame.screenY + scale * point.y)));
    }
    function passed(message) { info.checks.push(message); console.log(message); }
    await call('Network.enable');
    await call('Runtime.enable');
    await call('Console.enable');
    await call('Page.enable');
    info.agent = await value('navigator.userAgent');
    info.navigatorOnline = await value('navigator.onLine');
    await wait('document.documentElement && document.documentElement.getAttribute("data-edition") === "offline"', 'packaged document');
    await wait('window.fastReady && typeof navigationReady !== "undefined" && navigationReady', 'offline first launch');
    info.domContentLoadedMs = await value('performance.timing.domContentLoadedEventEnd - performance.timing.navigationStart');
    info.launchToReadyUpperBoundMs = Date.now() - info.launchStartedAt;
    console.log('Cold reader DOMContentLoaded', info.domContentLoadedMs, 'ms; ADB launch to observed reader readiness', info.launchToReadyUpperBoundMs, 'ms');
    await value('Promise.all(["/last-crash.txt","/words.tsv","/_app/keyboard"].map(function(path){' +
        'return fetch(path).then(function(r){return r.status})})).then(function(s){window.readerProbe=s});true');
    await wait('!!window.readerProbe', 'private and native route probes');
    assert.deepEqual(await value('window.readerProbe'), [404, 404, 404]);
    passed('WebView cannot read keyboard files or invoke native navigation through fetch');
    await value(`Promise.all(['GET','HEAD'].map(function (method) {
      return fetch('/prepared/home.json.gz', {method:method}).then(function (response) {
        var type = response.headers.get('content-type') || '';
        var result = method === 'GET' ? response.json().then(function (row) { return row._counts.words > 0; })
          : response.arrayBuffer().then(function (body) { return body.byteLength === 0; });
        return result.then(function (valid) { return valid && response.status === 200 && /^application\\/json/i.test(type); });
      });
    })).then(function (values) { window.readerTransport=values; },
      function (error) { window.readerTransport=String(error); }); true`);
    await wait('!!window.readerTransport', 'decoded snapshot GET and HEAD');
    assert.deepEqual(await value('window.readerTransport'), [true, true]);
    passed('Prepared JSON GET is decoded UTF-8 JSON; HEAD carries the same media type and an empty body');
    await capture('launch');
    await value(`Promise.all(['GET','HEAD'].map(function(method) {
      return fetch('/_app/ime-status.json', {method:method}).then(function(r) {
        if (r.status !== 200) throw new Error('status ' + r.status);
        return method === 'GET' ? r.json() : r.text();
      });
    })).then(function(result){window.drawerStatus=result});true`);
    await wait('!!window.drawerStatus', 'read-only native IME status');
    assert.deepEqual(await value('window.drawerStatus'), [{enabled:false, selected:false}, '']);
    await click('.academy-menu-toggle', true);
    await wait('document.body.classList.contains("academy-drawer-open")', 'open drawer');
    await capture('drawer');
    await click('#academy-drawer [data-view="settings"]', true);
    await wait(`!!document.querySelector('#academy-settings a[href="/_app/privacy"]')`, 'settings');
    const initialLanguage = await value('document.documentElement.lang');
    await click('#academy-settings a[href="/_app/keyboard"]', true);
    await keyboardRoundTrip(info, initialLanguage);
    await click('#academy-settings a[href="/_app/privacy"]', true);
    await returnFromPrivacy(info, initialLanguage);
    await visit('#grammar/north/101/1', 'grammar');
    assert(await value('/声母|韵母/.test(document.querySelector(".grammar-article").innerText)'));
    await capture('grammar-north');
    await click('[data-grammar-dialect="south"]');
    await wait('state.grammarDialect === "south" && document.querySelector(".grammar-article").innerText.length > 100',
      'southern grammar');
    await capture('grammar-south');
    passed('Northern and Southern reference grammars');

    await visit('#corpus', 'corpus');
    await value('document.querySelector("#query").value="双数"');
    await click('#search-form button[type="submit"]');
    await click('.corpus-card-content[data-open="tlp-longshan-euphemisms-01"]');
    await wait('!!document.querySelector("#detail[open] .external-text-block")', 'corpus reader');
    assert(await value('document.querySelector("#detail-body").innerText.indexOf("双数") >= 0'));
    await capture('corpus');
    passed('Corpus full-text search and local reader');

    await visit('?id=brassett-2006#sources', 'sources');
    await wait('!!document.querySelector("#detail[open] .grammar-summary")', 'bibliography');
    assert(await value('/2006/.test(document.querySelector("#detail-body").innerText)'));
    assert(await value('!!document.querySelector("#detail-body a[href^=\'https://\']")'));
    await capture('bibliography');
    const publication = '#detail-body a[href^="https://"]';
    const externalUrl = await value('document.querySelector(' + JSON.stringify(publication) + ').href');
    await click(publication, true);
    await cancelExternal(info, externalUrl);

    await click('#close');
    await wait('!document.querySelector("#detail[open]")', 'close source');
    // Time the existing reader functions on the minimum-API engine. This is
    // test-only instrumentation of a public, bundled index.
    await value(`(function () {
      var timings = window.readerIndexTimings = {};
      var decode = preparedJSON, apply = applyPreparedPart;
      preparedJSON = function (response) {
        if (response.url.indexOf('/word-search.json.gz') < 0) return decode(response);
        var began = performance.now();
        return decode(response).then(function (payload) {
          timings.decodeMs = performance.now() - began;
          return payload;
        });
      };
      applyPreparedPart = function (name, payload) {
        if (name !== 'word-search') return apply(name, payload);
        var classify = WordClasses.initialize;
        WordClasses.initialize = function (words) {
          var began = performance.now();
          try { return classify(words); } finally { timings.classesMs = performance.now() - began; }
        };
        var began = performance.now();
        try { return apply(name, payload); } finally { timings.applyMs = performance.now() - began; }
      };
      return true;
    }())`);
    const wordStart = Date.now();
    await click('.academy-menu-toggle', true);
    await click('#academy-drawer [data-view="words"]', true);
    await ready('words');
    info.wordIndex = await value('window.readerIndexTimings');
    info.wordIndex.observedRouteMs = Date.now() - wordStart;
    console.log('First dictionary route', JSON.stringify(info.wordIndex));
    await capture('dictionary');
    passed('Airplane-mode first launch and native WebView dictionary navigation');

    await value('document.querySelector("#query").value="here"');
    await click('[data-search-language="en"]');
    await wait('/here/i.test((document.querySelector(".word-card-english") || {}).innerText)', 'English sense search');
    const id = await value('document.querySelector(".word-card-content").getAttribute("data-open")');
    await click('.word-card-content');
    await wait('!!document.querySelector("#detail[open] .word-entry-definition h2")', 'word definition');
    assert.equal(await value('new URL(location.href).searchParams.get("id")'), id);
    assert(await value('!!document.querySelector("#detail-body .word-ipa")'));
    await capture('entry');
    await shell('input', 'keyevent', 'KEYCODE_BACK');
    await wait('!document.querySelector("#detail[open]")', 'hardware Back from entry');
    passed('Dictionary search, stable lexical identifier, IPA detail and Android Back');

    await visit('#notation', 'notation');
    await value('document.querySelector("#notation-source").value="zhou-fx";' +
      'document.querySelector("#notation-source").dispatchEvent(new Event("change",{bubbles:true}));' +
      'document.querySelector("#notation-input").value="kei21"');
    await click('#notation-converter button');
    await wait('!!notationState.result', 'notation conversion');
    passed('Bibliographic metadata, external publication link and local notation converter');

    await click('.academy-menu-toggle', true);
    await click('#academy-drawer [data-view="settings"]', true);
    await value('document.querySelector("#ui-language").value="en";' +
      'document.querySelector("#ui-language").dispatchEvent(new Event("change",{bubbles:true}));' +
      'document.querySelector("#academy-theme").value="dark";' +
      'document.querySelector("#academy-theme").dispatchEvent(new Event("change",{bubbles:true}))');
    await wait('document.documentElement.lang === "en" && document.documentElement.dataset.theme === "dark"', 'English/dark');
    await capture('english-dark');
    passed('English interface and dark theme');
    for (const language of ['en', 'zh']) {
      await value('document.querySelector("#ui-language").value=' + JSON.stringify(language) +
        ';document.querySelector("#ui-language").dispatchEvent(new Event("change",{bubbles:true}))');
      await wait('document.documentElement.lang.indexOf(' + JSON.stringify(language) + ')===0', 'reader language');
      await click('#academy-settings a[href="/_app/privacy"]', true);
      await returnFromPrivacy(info, language);
      await click('#academy-settings a[href="/_app/keyboard"]', true);
      await keyboardRoundTrip(info, language);
    }
    passed('Reader language carries through to the native privacy and keyboard settings screens');
    if (process.env.BIZIC_PROFILE_GZIP === '1') {
      // Compare native streaming above with the original fallback on the same
      // index. Decode base64 before starting the timer; only public APK bytes
      // cross the test debugger. The WebView's file-access policy stays active.
      const gzip = execFileSync('python3', ['-c',
        'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("assets/learning/prepared/word-search.json.gz"))',
        apk], {maxBuffer: 16 * 1024 * 1024}).toString('base64');
      // Old DevTools sockets impose a frame-size limit below the complete
      // index. Transfer the fixture outside the measured interval.
      await value('window.readerGzipChunks = []; true');
      for (let offset = 0; offset < gzip.length; offset += 32768) {
        await value('window.readerGzipChunks.push(' + JSON.stringify(gzip.slice(offset, offset + 32768)) + ');true');
      }
      await value(`(function (encoded) {
        var text=atob(encoded), bytes=new Uint8Array(text.length);
        for (var n=0; n<text.length; n++) bytes[n]=text.charCodeAt(n);
        var response=new Response(bytes), began=performance.now();
        preparedJSON(response).then(function () { window.readerRawDecode=performance.now()-began; },
          function (error) { window.readerRawDecode=String(error); });
        return true;
      }(window.readerGzipChunks.join(''))); delete window.readerGzipChunks; true`);
      await wait('typeof window.readerRawDecode !== "undefined"', 'raw gzip comparison');
      info.wordIndex.rawGzipDecodeMs = await value('window.readerRawDecode');
      assert.equal(typeof info.wordIndex.rawGzipDecodeMs, 'number');
      console.log('Original gzip fallback on the same APK index', info.wordIndex.rawGzipDecodeMs, 'ms');
    }
  } finally {
    if (ws) ws.close();
    await adb('forward', '--remove', 'tcp:' + port);
  }
}
module.exports = {exerciseLegacyUI};
