#!/usr/bin/env node
// 开发期源码热重载：串行编译、按内容发布不可变产物、仅成功后通知原生容器。
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const work = path.join(root, '.devtools', 'hot-reload');
const artifacts = path.join(work, 'artifacts');
const manifestPath = path.join(work, 'manifest.json');
const sha256 = data => crypto.createHash('sha256').update(data).digest('hex');

export function parseArgs(args) {
  const options = { platform: 'android', port: 8088, host: '0.0.0.0', once: false,
    check: false, noBuild: false, inspect: false, interval: 800 };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (['android', 'ios', 'ohos', 'all'].includes(arg)) options.platform = arg;
    else if (arg === '--once') options.once = true;
    else if (arg === '--check') options.check = true;
    else if (arg === '--no-build') options.noBuild = true;
    else if (arg === '--inspect') options.inspect = true;
    else if (['--port', '--host', '--interval'].includes(arg)) {
      const value = args[++i];
      if (!value) throw new Error(`${arg} 缺少值`);
      options[arg.slice(2)] = arg === '--host' ? value : Number(value);
    } else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) throw new Error('端口无效');
  if (!Number.isFinite(options.interval) || options.interval < 100) throw new Error('监听间隔至少 100ms');
  return options;
}

function log(message) { console.log(`[热重载 ${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${message}`); }
function readManifest() {
  try { return JSON.parse(fs.readFileSync(manifestPath, 'utf8')); }
  catch { return { androidVersion: null, iosVersion: null, ohosVersion: null }; }
}
function saveManifest(state) {
  fs.mkdirSync(work, { recursive: true });
  const temp = `${manifestPath}.tmp`;
  fs.writeFileSync(temp, JSON.stringify(state, null, 2) + '\n');
  fs.renameSync(temp, manifestPath);
}

export function publishFile(file, platformNames, state) {
  const data = fs.readFileSync(file);
  if (!data.length) throw new Error(`构建产物为空：${file}`);
  const extension = platformNames.includes('android') ? 'dex' : 'js';
  if (extension === 'dex' && !data.subarray(0, 4).equals(Buffer.from('dex\n'))) throw new Error('D8 产物不是 DEX');
  if (extension === 'js' && !data.includes(Buffer.from('callKotlinMethod'))) throw new Error('JS 缺少 Kuikly 导出入口');
  const version = sha256(data);
  fs.mkdirSync(artifacts, { recursive: true });
  const destination = path.join(artifacts, `${version}.${extension}`);
  if (!fs.existsSync(destination)) {
    fs.writeFileSync(`${destination}.tmp`, data);
    fs.renameSync(`${destination}.tmp`, destination);
  }
  for (const name of platformNames) state[`${name}Version`] = version;
  state.updatedAt = new Date().toISOString();
  saveManifest(state);
  log(`${platformNames.join('/')} 已发布 ${version.slice(0, 12)}（${(data.length / 1024 / 1024).toFixed(1)} MiB）`);
  return version;
}

export function createArtifactServer(state, status) {
  return http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const headers = { 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' };
    if (req.method !== 'GET') { res.writeHead(405, headers); res.end(); return; }
    if (url.pathname === '/version') {
      const version = state.androidVersion || state.iosVersion || state.ohosVersion || null;
      res.writeHead(200, { ...headers, 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ...state, version, ready: !!version, ...status })); return;
    }
    const routes = { '/android/classes.dex': ['androidVersion', 'dex'],
      '/ios/bundle.js': ['iosVersion', 'js'], '/ohos/bundle.js': ['ohosVersion', 'js'] };
    const route = routes[url.pathname];
    if (route) {
      const version = url.searchParams.get('version') || state[route[0]];
      if (!version || !/^[a-f0-9]{64}$/.test(version)) { res.writeHead(404, headers); res.end('暂无成功构建'); return; }
      const file = path.join(artifacts, `${version}.${route[1]}`);
      if (!fs.existsSync(file)) { res.writeHead(404, headers); res.end('指定版本不存在'); return; }
      res.writeHead(200, { ...headers, 'Content-Type': route[1] === 'dex' ? 'application/octet-stream' : 'text/javascript; charset=utf-8',
        'Content-Length': fs.statSync(file).size, 'X-Sondo-Version': version });
      fs.createReadStream(file).on('error', () => res.destroy()).pipe(res); return;
    }
    if (url.pathname === '/') {
      res.writeHead(200, { ...headers, 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Sondo Kotlin 源码热重载服务\nGET /version\n原生调试容器连接此端口，保存 shared Kotlin 后自动编译刷新。\n'); return;
    }
    res.writeHead(404, headers); res.end('not found');
  });
}

let runningChild = null;
let inspectorChild = null;
function run(command, args, logFile) {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
    const output = fs.openSync(logFile, 'a');
    fs.writeSync(output, `\n${command} ${args.join(' ')}\n`);
    const child = spawn(command, args, { cwd: root, stdio: ['ignore', output, output], env: process.env });
    runningChild = child;
    const finish = () => { fs.closeSync(output); if (runningChild === child) runningChild = null; };
    child.once('error', error => { finish(); reject(error); });
    child.once('exit', code => { finish(); code === 0 ? resolve() : reject(new Error(`${path.basename(command)} 退出 ${code}，日志：${logFile}`)); });
  });
}

export function androidSdk() {
  const localProperties = path.join(root, 'local.properties');
  const local = fs.existsSync(localProperties) ? fs.readFileSync(localProperties, 'utf8').match(/^sdk\.dir=(.+)$/m)?.[1]?.trim().replaceAll('\\:', ':').replaceAll('\\ ', ' ') : null;
  return process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || local || path.join(os.homedir(), 'Library/Android/sdk');
}

function androidTools() {
  const sdk = androidSdk();
  const directory = path.join(sdk, 'build-tools');
  const versions = fs.existsSync(directory) ? fs.readdirSync(directory).filter(v => /^\d+(\.\d+){1,2}$/.test(v)).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })) : [];
  const d8 = versions.map(v => path.join(directory, v, 'd8')).find(file => fs.existsSync(file));
  const androidJar = path.join(sdk, 'platforms', 'android-34', 'android.jar');
  if (!d8 || !fs.existsSync(androidJar)) throw new Error('Android SDK 缺少 D8 或 platforms/android-34/android.jar');
  return { d8, androidJar, adb: path.join(sdk, 'platform-tools', 'adb') };
}

async function gradle(tasks, options, logFile) {
  const args = [...tasks, '-Psondo.enableIos=false', '--console=plain'];
  if (options.inspect) {
    const npm = spawnSync('npm', ['root', '-g'], { encoding: 'utf8' });
    const home = path.join((npm.stdout || '').trim(), 'kuikly-devtools');
    const init = path.join(home, 'gradle/devtools.init.gradle');
    if (npm.status !== 0 || !fs.existsSync(init)) throw new Error('找不到全局 kuikly-devtools，请先 npm install -g kuikly-devtools@1.0.7');
    args.push('--init-script', init, '-Pkuikly.devtools=true', `-Pkuikly.devtools.home=${home}`, '-Pkuikly.devtools.port=8089');
  }
  await run(path.join(root, 'gradlew'), args, logFile);
}

async function ensureInspector() {
  const healthy = async () => {
    try {
      const ping = await fetch('http://127.0.0.1:8089/__kuikly_devtools/ping', { signal: AbortSignal.timeout(500) }).then(r => r.json());
      const panel = await fetch('http://127.0.0.1:8090/api/sessions', { signal: AbortSignal.timeout(500) }).then(r => r.json());
      return ping.service === 'kuikly-devtools-ingest' && Array.isArray(panel.sessions);
    } catch { return false; }
  };
  if (await healthy()) { log('复用 DevTools http://localhost:8090'); return; }
  const logFile = path.join(work, 'devtools.log');
  const output = fs.openSync(logFile, 'a');
  inspectorChild = spawn('kuikly-devtools', ['serve', '--project', root], { cwd: root, stdio: ['ignore', output, output] });
  fs.closeSync(output);
  let spawnError;
  inspectorChild.on('error', error => { spawnError = error; });
  for (let i = 0; i < 40; i++) {
    if (spawnError) throw spawnError;
    if (await healthy()) { log('DevTools http://localhost:8090 已启动'); return; }
    if (inspectorChild.exitCode !== null) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  inspectorChild.kill('SIGTERM');
  throw new Error(`DevTools 启动失败，检查 ${logFile}`);
}

export async function buildTargets(options, state) {
  const logFile = path.join(work, 'logs', `build-${Date.now()}.log`);
  const names = options.platform === 'all' ? ['android', 'ios', 'ohos'] : [options.platform];
  log(`开始编译 ${names.join('/')}，日志 ${path.relative(root, logFile)}`);
  if (names.includes('android')) {
    const tools = androidTools();
    await gradle([':shared:bundleLibCompileToJarDebug'], options, logFile);
    const jar = path.join(root, 'shared/build/intermediates/compile_library_classes_jar/debug/classes.jar');
    if (!fs.existsSync(jar)) throw new Error(`找不到 Android 编译产物 ${jar}`);
    const dexDirectory = fs.mkdtempSync(path.join(work, 'dex-'));
    try {
      await run(tools.d8, ['--debug', '--min-api', '27', '--lib', tools.androidJar, '--output', dexDirectory, jar], logFile);
      if (fs.existsSync(path.join(dexDirectory, 'classes2.dex'))) throw new Error('业务代码产生了多个 DEX，目前调试加载器仅支持单 DEX');
      publishFile(path.join(dexDirectory, 'classes.dex'), ['android'], state);
    } finally { fs.rmSync(dexDirectory, { recursive: true, force: true }); }
  }
  const jsNames = names.filter(name => name !== 'android');
  if (jsNames.length) {
    await gradle([':shared:jsBrowserDevelopmentWebpack', '-Psondo.hotReload=true'], options, logFile);
    // 两端执行完全相同的 Kotlin/JS；任一 JS 构建成功后同时发布，避免跨端字段滞后。
    publishFile(path.join(root, 'shared/build/kotlin-webpack/js/developmentExecutable/sondo.js'), ['ios', 'ohos'], state);
  }
}

export function sourceFingerprint(directory = path.join(root, 'shared', 'src')) {
  const hash = crypto.createHash('sha256');
  const walk = folder => {
    if (!fs.existsSync(folder)) return;
    for (const item of fs.readdirSync(folder, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const file = path.join(folder, item.name);
      if (item.isDirectory()) walk(file);
      else if (item.isFile() && item.name.endsWith('.kt')) { hash.update(path.relative(directory, file)); hash.update(fs.readFileSync(file)); }
    }
  };
  for (const name of ['commonMain/kotlin', 'jsMain/kotlin']) walk(path.join(directory, name));
  return hash.digest('hex');
}

function setupAdb(port) {
  let adb;
  try { adb = androidTools().adb; } catch { return; }
  const devices = spawnSync(adb, ['devices'], { encoding: 'utf8' });
  for (const line of (devices.stdout || '').split('\n').slice(1)) {
    const [serial, state] = line.trim().split(/\s+/);
    if (state !== 'device') continue;
    const result = spawnSync(adb, ['-s', serial, 'reverse', `tcp:${port}`, `tcp:${port}`], { encoding: 'utf8' });
    if (result.status === 0) log(`Android ${serial}：adb reverse ${port} 已建立`);
  }
}

function check(options) {
  const tools = { node: process.version, project: root, platforms: options.platform,
    gradlew: fs.existsSync(path.join(root, 'gradlew')), xcode: spawnSync('xcodebuild', ['-version'], { encoding: 'utf8' }).stdout?.trim() || null };
  try { const android = androidTools(); tools.d8 = android.d8; tools.androidDevices = spawnSync(android.adb, ['devices'], { encoding: 'utf8' }).stdout?.trim(); }
  catch (error) { tools.androidError = error.message; }
  console.log(JSON.stringify(tools, null, 2));
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log('用法：./dev.sh android|ios|ohos|all [--port 8088] [--inspect] [--check] [--once] [--no-build]\n默认启动服务并监听 shared Kotlin 保存。--once 只构建一次；--no-build 复用产物启动服务。\n原生宿主需先按 docs/hot-reload.md 启用开发加载器。页面内存状态会重置，应用进程不重启。'); return;
  }
  if (options.check) { check(options); return; }
  fs.mkdirSync(work, { recursive: true });
  const state = readManifest();
  const status = { building: false, error: null };
  let server;
  let stopped = false;
  let pending = false;
  let attempted = sourceFingerprint();
  let timer;
  const shutdown = () => {
    if (stopped) return;
    stopped = true;
    clearInterval(timer);
    runningChild?.kill('SIGTERM');
    inspectorChild?.kill('SIGTERM');
    server?.close();
    process.stdin.pause();
    log('监听已停止');
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
  if (options.inspect) await ensureInspector();
  const rebuild = async () => {
    if (status.building) { pending = true; return; }
    do {
      pending = false;
      status.building = true;
      status.error = null;
      try {
        await buildTargets(options, state);
        if (['android', 'all'].includes(options.platform)) setupAdb(options.port);
      }
      catch (error) { status.error = error.message; console.error(`[热重载] 构建失败，保留上次成功版本：${error.message}`); }
      finally { status.building = false; }
    } while (pending && !stopped);
  };
  if (!options.once) {
    server = createArtifactServer(state, status);
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(options.port, options.host, resolve); });
    log(`服务 http://localhost:${options.port}；保存 shared Kotlin 后自动刷新页面`);
    const addresses = Object.values(os.networkInterfaces()).flat().filter(item => item.family === 'IPv4' && !item.internal && !item.address.startsWith('198.18.'));
    for (const item of addresses) log(`局域网地址 http://${item.address}:${options.port}`);
    if (['android', 'all'].includes(options.platform)) setupAdb(options.port);
    timer = setInterval(() => {
      try {
        const current = sourceFingerprint();
        if (current !== attempted) { attempted = current; void rebuild(); }
      } catch (error) { console.error(`[热重载] 暂时无法读取源文件：${error.message}`); }
    }, options.interval);
    if (process.stdin.isTTY) {
      process.stdin.setEncoding('utf8');
      process.stdin.on('data', text => { if (text.trim() === 'r') void rebuild(); if (text.trim() === 'q') shutdown(); });
      log('输入 r 回车重新构建，q 回车退出');
    }
  }
  if (!options.noBuild) await rebuild();
  if (options.once) { inspectorChild?.kill('SIGTERM'); if (status.error) process.exitCode = 1; }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(`[热重载] ${error.message}`); process.exitCode = 1; });
}
