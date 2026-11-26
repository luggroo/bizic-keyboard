// node qa/learning-device.cjs SERIAL APK PATH_TO_DICTIONARY_MOBILE_SUITE EVIDENCE_DIRECTORY
// Use a fresh emulator and a debuggable APK. The dictionary suite supplies the shared UI assertions.
'use strict';
const assert = require('node:assert/strict');
const {execFile} = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const {promisify} = require('node:util');
const {setTimeout: delay} = require('node:timers/promises');
const {_android} = require('playwright');

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

async function keyboardRoundTrip(info) {
  await waitFor(async () => /(?:mResumedActivity|topResumedActivity|mCurrentFocus).*biz.tujia.ime\/\.SetupActivity/.test(
    await shell('dumpsys', 'activity', 'activities')), 'native keyboard management');
  const hierarchy = '/data/local/tmp/bizic-reader-setup.xml';
  try {
    await waitFor(async () => {
      await shell('uiautomator', 'dump', '--compressed', hierarchy);
      return (await shell('cat', hierarchy)).includes('土家语输入法');
    }, 'drawn keyboard management controls');
  } finally {
    await shell('rm', '-f', hierarchy);
  }
  await capture('keyboard-management');
  await shell('input', 'keyevent', 'KEYCODE_BACK');
  await waitFor(async () => /(?:mResumedActivity|topResumedActivity|mCurrentFocus).*biz.tujia.ime\/\.LearningActivity/.test(
    await shell('dumpsys', 'activity', 'activities')), 'return to the dictionary');
  info.checks.push('Native keyboard management and hardware Back with IME disabled');
}

async function main() {
  if (!evidence) throw Error('Expected SERIAL APK DICTIONARY_MOBILE_SUITE EVIDENCE_DIRECTORY');
  const {exerciseLearningUI} = require(path.resolve(suite));
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
    info.launchStartedAt = Date.now();
    info.androidLaunch = await shell('am', 'start', '-W', '-n', pkg + '/.LearningActivity');
    const socket = await waitFor(async () => {
      const rows = (await shell('ps', ...(Number(info.api) >= 26 ? ['-A'] : []))).split('\n');
      const reader = rows.find(line => line.trimEnd().endsWith(pkg + ':learning'));
      if (!reader) return;
      const pid = reader.trim().split(/\s+/)[1];
      const name = 'webview_devtools_remote_' + pid;
      return (await shell('cat', '/proc/net/unix')).includes('@' + name) && name;
    }, 'the learning process WebView');
    if (Number(info.api) <= 25 || process.env.TUJIA_QA_RAW_CDP === '1') {
      await require('./learning-legacy.cjs').exerciseLegacyUI({adb, shell, socket, info, capture, keyboardRoundTrip, apk});
    } else {
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
      const keyboard = page.locator('aside a[href^="/_app/keyboard"]').first();
      await keyboard.click();
      await keyboardRoundTrip(info);
    }
    assert.deepEqual(info.pageErrors, []);
    assert.deepEqual(info.externalRequests, []);
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
