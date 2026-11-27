// node qa/learning-device.cjs SERIAL APK PATH_TO_DICTIONARY_MOBILE_SUITE EVIDENCE_DIRECTORY
// Use a fresh emulator and a debuggable APK. The dictionary suite supplies the shared UI assertions.
'use strict';
const assert = require('node:assert/strict');
const {execFile} = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const {promisify} = require('node:util');
const {setTimeout: delay} = require('node:timers/promises');

const command = promisify(execFile);
const pkg = 'biz.tujia.ime';
const [serial, apk, suite, evidence] = process.argv.slice(2);

async function adb(...args) {
  return (await command(process.env.ADB || 'adb', ['-s', serial, ...args], {
    encoding: 'buffer', maxBuffer: 16 * 1024 * 1024, timeout: 180000
  })).stdout;
}

async function shell(...args) {
  return (await adb('shell', ...args)).toString().trim();
}

async function capture(name) {
  const png = await adb('exec-out', 'screencap', '-p');
  assert.equal(png.readUInt32BE(0), 0x89504e47);
  await fs.writeFile(path.join(evidence, name + '.png'), png);
  return {width: png.readUInt32BE(16), height: png.readUInt32BE(20)};
}

async function waitFor(check, description) {
  const deadline = Date.now() + 120000;
  do {
    const result = await check();
    if (result) return result;
    await delay(500);
  } while (Date.now() < deadline);
  throw Error('Timed out waiting for ' + description);
}

async function airplaneMode(enabled) {
  if (Number(await shell('getprop', 'ro.build.version.sdk')) >= 30) {
    await shell('cmd', 'connectivity', 'airplane-mode', enabled ? 'enable' : 'disable');
    return;
  }
  await shell('settings', 'put', 'global', 'airplane_mode_on', enabled ? '1' : '0');
  // Older AOSP userdebug emulator images reserve this broadcast for the system.
  await shell('su', '0', 'am', 'broadcast', '-a', 'android.intent.action.AIRPLANE_MODE', '--ez', 'state', enabled ? 'true' : 'false');
}

async function activeActivity(name) {
  const dump = await shell('dumpsys', 'activity', 'activities');
  return dump.split('\n').some(line => /mResumedActivity|topResumedActivity|mFocusedActivity/.test(line)
    && line.includes(pkg + '/.' + name));
}

async function hierarchy(file, compressed = false) {
  const result = await shell('uiautomator', 'dump', ...(compressed ? ['--compressed'] : []), file);
  // Android 7 can exit successfully with no file while its accessibility window is changing.
  return result.includes('dumped to: ' + file) ? await shell('cat', file) : '';
}

async function privacyButton(id) {
  const file = '/data/local/tmp/bizic-reader-privacy.xml';
  return await waitFor(async () => {
    const tree = await hierarchy(file);
    const node = tree.match(/<node\b[^>]*>/g)?.find(row => row.includes('resource-id="' + pkg + ':id/' + id + '"'));
    if (!node) return undefined;
    const position = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!position) return undefined;
    const [left, top, right, bottom] = position.slice(1).map(Number);
    return {left, top, right, bottom, x: (left + right) >> 1, y: (top + bottom) >> 1,
      checked: node.includes('checked="true"'), enabled: node.includes('enabled="true"'),
      text: (node.match(/text="([^"]*)"/) || [])[1]};
  }, 'the native disclosure control ' + id);
}

async function acknowledgePrivacy(info) {
  await waitFor(() => activeActivity('PrivacyActivity'), 'first launch privacy disclosure');
  await privacyButton('privacy_accept');
  const summary = await privacyButton('privacy_summary');
  const initial = await privacyButton('privacy_diagnostics');
  const screen = await capture('first-launch-privacy');
  const english = summary.text.startsWith('This app does not connect to the internet.');
  assert.match(summary.text, english ? /off by default/i : /默认关闭/);
  assert(summary.top < screen.height / 2, 'The offline statement should appear near the top');
  for (const element of [summary, initial]) {
    assert(element.left >= 0 && element.right <= screen.width && element.right > element.left);
    assert(element.top >= 0 && element.bottom <= screen.height && element.bottom > element.top);
  }
  assert(initial.enabled && !initial.checked);
  info.firstLaunch = {language: english ? 'en' : 'zh', summary, diagnostics: initial, screen};
  await shell('input', 'keyevent', 'KEYCODE_BACK');
  await shell('am', 'force-stop', pkg);
  await shell('am', 'start', '-W', '-n', pkg + '/.LearningActivity');
  await waitFor(() => activeActivity('PrivacyActivity'), 'disclosure again after leaving before acceptance');
  const optional = await privacyButton('privacy_diagnostics');
  assert(optional.enabled && !optional.checked, 'Diagnostic recording must be off on a fresh install');
  const button = await privacyButton('privacy_accept');
  info.launchStartedAt = Date.now();
  await shell('input', 'tap', String(button.x), String(button.y));
  await waitFor(() => activeActivity('LearningActivity'), 'first acknowledged learning launch');
  info.checks.push('The initial viewport states the offline policy and shows the default-off diagnostics checkbox; exit leaves the disclosure pending');
}

async function returnFromPrivacy(info, language) {
  await waitFor(() => activeActivity('PrivacyActivity'), 'native privacy screen reopened from the reader');
  const english = language.startsWith('en');
  const button = await privacyButton('privacy_accept');
  assert.equal(button.text, english ? 'Save and return' : '保存并返回');
  assert.equal((await privacyButton('privacy_diagnostics')).checked, false, 'First-launch opt-out must persist');
  await capture('reopened-privacy-' + (english ? 'en' : 'zh'));
  await shell('input', 'keyevent', 'KEYCODE_BACK');
  await waitFor(() => activeActivity('LearningActivity'), 'Back from privacy');
  info.checks.push('Privacy settings reopen from the WebView');
}

async function cancelExternal(info, url) {
  const file = '/data/local/tmp/bizic-reader-external.xml';
  await waitFor(async () => {
    const xml = (await hierarchy(file)).replaceAll('&amp;', '&');
    return xml.includes('android:id/button1') && xml.includes('android:id/button2') && xml.includes(url);
  }, 'confirmed external destination URL and cancel action');
  await capture('external-link-confirmation');
  await shell('input', 'keyevent', 'KEYCODE_BACK');
  info.checks.push('A user-tapped external HTTPS publication link shows the full URL and can be canceled');
}

async function readerSocket(api) {
  const rows = (await shell('ps', ...(Number(api) >= 26 ? ['-A'] : []))).split('\n');
  const reader = rows.find(line => line.trimEnd().endsWith(pkg + ':learning'));
  if (!reader) return undefined;
  const pid = reader.trim().split(/\s+/)[1];
  const name = 'webview_devtools_remote_' + pid;
  return (await shell('cat', '/proc/net/unix')).includes('@' + name) && name;
}

async function keyboardRoundTrip(info, language = 'zh') {
  await waitFor(async () => /(?:mResumedActivity|topResumedActivity|mCurrentFocus).*biz.tujia.ime\/\.SetupActivity/.test(
    await shell('dumpsys', 'activity', 'activities')), 'native keyboard management');
  const file = '/data/local/tmp/bizic-reader-setup.xml';
  try {
    await waitFor(async () => (await hierarchy(file, true))
      .includes(language.startsWith('en') ? 'Tujia keyboard' : '土家语输入法'), 'drawn keyboard management controls');
  } finally {
    await shell('rm', '-f', file);
  }
  await capture('keyboard-management-' + (language.startsWith('en') ? 'en' : 'zh'));
  await shell('input', 'keyevent', 'KEYCODE_BACK');
  await waitFor(async () => /(?:mResumedActivity|topResumedActivity|mCurrentFocus).*biz.tujia.ime\/\.LearningActivity/.test(
    await shell('dumpsys', 'activity', 'activities')), 'return to the dictionary');
  info.checks.push('Native keyboard management and hardware Back with IME disabled');
}

async function main() {
  if (!evidence) throw Error('Expected SERIAL APK DICTIONARY_MOBILE_SUITE EVIDENCE_DIRECTORY');
  assert(await shell('getprop', 'ro.kernel.qemu') === '1' ||
         await shell('getprop', 'ro.boot.qemu') === '1', 'Select a disposable emulator');
  assert(!(await shell('pm', 'list', 'packages', pkg)).split('\n').includes('package:' + pkg),
         'Use a fresh emulator with the application absent');
  await fs.mkdir(evidence, {recursive: true});
  const state = {};
  for (const key of ['airplane_mode_on', 'wifi_on'])
    state[key] = await shell('settings', 'get', 'global', key);
  const info = {serial, fingerprint: await shell('getprop', 'ro.build.fingerprint'),
    api: await shell('getprop', 'ro.build.version.sdk'), abi: await shell('getprop', 'ro.product.cpu.abi'),
    size: await shell('wm', 'size'), density: await shell('wm', 'density'),
    webView: await shell('dumpsys', 'webviewupdate'), checks: [], pageErrors: [], externalRequests: []};
  let installed = false;
  let disabledWifi = false;
  let devices = [];
  try {
    await airplaneMode(true);
    // Android can remember Wi-Fi in airplane mode. Check ConnectivityService:
    // a WebView without ACCESS_NETWORK_STATE can report online with all radios off.
    if (await shell('settings', 'get', 'global', 'wifi_on') === '1') {
      await shell('svc', 'wifi', 'disable');
      disabledWifi = true;
    }
    assert.equal(await shell('settings', 'get', 'global', 'airplane_mode_on'), '1');
    info.airplaneMode = true;
    info.defaultNetwork = await waitFor(async () => {
      const line = (await shell('dumpsys', 'connectivity')).split('\n').find(line => /^\s*Active default network:/i.test(line));
      return line && /:\s*(?:none|null)\s*$/i.test(line) && line.trim();
    }, 'Android to disconnect the default network in airplane mode');
    assert.match((await adb('install', apk)).toString(), /Success/);
    installed = true;
    assert(!(await shell('settings', 'get', 'secure', 'enabled_input_methods')).includes(pkg));
    await shell('input', 'keyevent', 'KEYCODE_WAKEUP');
    if (Number(info.api) >= 26) await shell('wm', 'dismiss-keyguard');
    else await shell('input', 'keyevent', 'KEYCODE_MENU');
    info.androidLaunch = await shell('am', 'start', '-W', '-n', pkg + '/.LearningActivity');
    await acknowledgePrivacy(info);
    const socket = await waitFor(() => readerSocket(info.api), 'the learning process WebView');
    if (Number(info.api) <= 25 || process.env.TUJIA_QA_RAW_CDP === '1') {
      await require('./learning-legacy.cjs').exerciseLegacyUI({adb, shell, socket, info, capture, keyboardRoundTrip, returnFromPrivacy, cancelExternal, apk});
    } else {
      const {_android} = require('playwright');
      const {exerciseLearningUI} = require(path.resolve(suite));
      devices = await _android.devices({omitDriverInstall: true});
      const device = devices.find(item => item.serial() === serial);
      assert(device, 'Playwright could not attach to the selected emulator');
      const view = await device.webView({socketName: socket}, {timeout: 60000});
      // A dead Android renderer may leave its DevTools socket advertised without a page.
      let attachTimer;
      let page;
      try {
        page = await Promise.race([view.page(), new Promise((_, reject) => {
          attachTimer = setTimeout(() => reject(Error('WebView renderer did not attach')), 120000);
        })]);
      } finally {
        clearTimeout(attachTimer);
      }
      page.on('pageerror', error => info.pageErrors.push(error.message));
      page.on('request', request => {
        if (/^https?:/.test(request.url()) && new URL(request.url()).origin !== 'https://appassets.androidplatform.net')
          info.externalRequests.push(request.url());
      });
      info.agent = await page.evaluate(() => navigator.userAgent);
      info.navigatorOnline = await page.evaluate(() => navigator.onLine);
      info.domContentLoadedMs = await page.evaluate(() => performance.timing.domContentLoadedEventEnd - performance.timing.navigationStart);
      await exerciseLearningUI(page, {capture, report: message => {info.checks.push(message); console.log(message);}});
      await page.goto('https://appassets.androidplatform.net/index.html');
      const initialLanguage = await page.evaluate(() => document.documentElement.lang);
      await page.locator('#academy-home .academy-ime-manage').click();
      await keyboardRoundTrip(info, initialLanguage);
      await page.locator('.academy-menu-toggle:visible').first().click();
      await page.locator('#academy-drawer [data-view="settings"]').click();
      for (const language of ['en', 'zh']) {
        await page.locator('#ui-language').selectOption(language);
        await page.waitForFunction(expected => document.documentElement.lang.startsWith(expected), language);
        await page.locator('#academy-settings a[href="/_app/privacy"]').click();
        await returnFromPrivacy(info, language);
        await page.locator('#academy-settings a[href="/_app/keyboard"]').click();
        await keyboardRoundTrip(info, language);
      }
    }
    assert.deepEqual(info.pageErrors, []);
    assert.deepEqual(info.externalRequests, []);
    await shell('am', 'force-stop', pkg);
    await shell('am', 'start', '-W', '-n', pkg + '/.LearningActivity');
    await waitFor(() => readerSocket(info.api), 'the acknowledged reader without another first-run prompt');
    assert(await activeActivity('LearningActivity'));
    info.checks.push('Acknowledgement survives a cold process restart');
  } catch (error) {
    info.failure = error.stack;
    await capture('failure').catch(() => {});
    throw error;
  } finally {
    await fs.writeFile(path.join(evidence, 'results.json'), JSON.stringify(info, null, 2) + '\n');
    await Promise.all(devices.map(device => device.close()));
    try {
      if (installed) await adb('uninstall', pkg);
    } finally {
      await airplaneMode(state.airplane_mode_on === '1');
      if (disabledWifi && state.wifi_on === '1') await shell('svc', 'wifi', 'enable');
    }
  }
}

main().catch(error => {console.error(error); process.exitCode = 1;});
