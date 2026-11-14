import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createArtifactServer, parseArgs, sourceFingerprint, root } from './hot-reload.mjs';

test('保存监听识别 Kotlin 的新增、改动和删除，忽略无关文件', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sondo-source-watch-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const source = path.join(dir, 'commonMain/kotlin');
  fs.mkdirSync(source, { recursive: true });
  const empty = sourceFingerprint(dir);
  fs.writeFileSync(path.join(source, 'Page.kt'), 'class Page { val title = "before" }');
  const before = sourceFingerprint(dir);
  assert.notEqual(before, empty);
  fs.writeFileSync(path.join(source, 'unrelated.log'), 'noise');
  assert.equal(sourceFingerprint(dir), before);
  fs.writeFileSync(path.join(source, 'Page.kt'), 'class Page { val title = "after" }');
  assert.notEqual(sourceFingerprint(dir), before);
  fs.rmSync(path.join(source, 'Page.kt'));
  assert.equal(sourceFingerprint(dir), empty);
});

test('客户端获取固定版本的产物，构建中和失败时继续提供上次成功版本', async t => {
  const directory = path.join(root, '.devtools/hot-reload/artifacts');
  fs.mkdirSync(directory, { recursive: true });
  const previous = Buffer.from(`dex\nprevious-${crypto.randomUUID()}`);
  const next = Buffer.from(`dex\nnext-${crypto.randomUUID()}`);
  const hash = data => crypto.createHash('sha256').update(data).digest('hex');
  const oldVersion = hash(previous);
  const newVersion = hash(next);
  const files = [[oldVersion, previous], [newVersion, next]].map(([version, data]) => {
    const file = path.join(directory, `${version}.dex`);
    fs.writeFileSync(file, data); return file;
  });
  const state = { androidVersion: oldVersion, iosVersion: null, ohosVersion: null };
  const status = { building: true, error: null };
  const server = createArtifactServer(state, status);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    files.forEach(file => fs.rmSync(file, { force: true }));
  });
  const address = `http://127.0.0.1:${server.address().port}`;
  const initial = await fetch(`${address}/version`).then(r => r.json());
  assert.equal(initial.androidVersion, oldVersion);
  assert.equal(initial.building, true);
  status.building = false;
  status.error = '编译失败';
  const failure = await fetch(`${address}/version`).then(r => r.json());
  assert.equal(failure.androidVersion, oldVersion);
  assert.equal(failure.ready, true);
  state.androidVersion = newVersion;
  status.error = null;
  const pinned = await fetch(`${address}/android/classes.dex?version=${oldVersion}`);
  assert.equal(pinned.headers.get('x-sondo-version'), oldVersion);
  assert.deepEqual(Buffer.from(await pinned.arrayBuffer()), previous);
  const latest = await fetch(`${address}/android/classes.dex`);
  assert.deepEqual(Buffer.from(await latest.arrayBuffer()), next);
  assert.equal((await fetch(`${address}/ios/bundle.js`)).status, 404);
  assert.equal((await fetch(`${address}/android/classes.dex?version=../../manifest`)).status, 404);
  assert.equal((await fetch(`${address}/android/classes.dex?version=${'0'.repeat(64)}`)).status, 404);
});

test('首次构建之前不通知原生容器刷新', async t => {
  const server = createArtifactServer({ androidVersion: null }, { building: true, error: null });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const state = await fetch(`http://127.0.0.1:${server.address().port}/version`).then(r => r.json());
  assert.equal(state.ready, false);
  assert.equal(state.version, null);
});

test('命令行拒绝无效端口和监听间隔', () => {
  assert.equal(parseArgs(['all', '--inspect']).platform, 'all');
  assert.throws(() => parseArgs(['--port', '70000']));
  assert.throws(() => parseArgs(['--interval', '0']));
  assert.throws(() => parseArgs(['--unknown']));
});

test('业务邮箱正则在 Kotlin/JS 使用的 Unicode 模式下可编译并正常匹配', () => {
  const source = fs.readFileSync(path.join(root, 'shared/src/commonMain/kotlin/com/sondo/app/core/utils/Ext.kt'), 'utf8');
  const literal = source.match(/private val emailRegex = Regex\(("(?:\\.|[^"\\])*")\)/)?.[1];
  assert.ok(literal, '应读取业务实际使用的正则');
  const regex = new RegExp(JSON.parse(literal), 'u');
  for (const email of ['user@example.com', 'first.last@test.cn', 'first-last@test.cn']) assert.ok(regex.test(email), email);
  for (const email of ['no-at-sign', 'a@', '@example.com']) assert.equal(regex.test(email), false, email);
});
