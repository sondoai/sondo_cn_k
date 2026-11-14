#!/usr/bin/env node
// Cursor 运行和调试的统一入口。launch.json 与 tasks.json 只传预设名，命令集中在这里。
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const home = process.env.HOME || os.homedir();
const javaHome = process.env.JAVA_HOME || `${home}/dev-tools/jdk/jdk-17.0.21+9/Contents/Home`;
const androidHome = process.env.ANDROID_HOME || `${home}/dev-tools/android-sdk`;
const env = {
  ...process.env,
  JAVA_HOME: javaHome,
  ANDROID_HOME: androidHome,
  PATH: [
    `${javaHome}/bin`,
    `${androidHome}/platform-tools`,
    `${androidHome}/emulator`,
    process.env.PATH ?? '',
  ].join(':'),
};

const startApp = 'adb shell am start -n com.sondo.music.video.ai/.kuikly.KuiklyLaunchActivity';

const presets = {
  compile: './gradlew :shared:compileDebugKotlinAndroid --console=plain',
  install: `./gradlew :androidApp:installDebug --console=plain && ${startApp}`,
  'install-test': `./gradlew :androidApp:installDebug --console=plain -PAPI_ENV=test -PSHOW_TOKEN_EDITOR=true && ${startApp}`,
  'install-hot': `./gradlew :androidApp:installDebug --console=plain -Psondo.hotReloadUrl=http://127.0.0.1:8088 && adb reverse tcp:8088 tcp:8088 && ${startApp}`,
  hot: './dev.sh android',
  emulator: 'emulator -avd sondo_test',
};

const name = process.argv[2];
const command = presets[name];
if (!command) {
  console.error(`未知预设：${name ?? '(无)'}\n可用：${Object.keys(presets).join('、')}`);
  process.exit(1);
}

// 独立进程组：停止调试时把信号转给整组，Gradle / ./dev.sh 会一起退出。
const child = spawn(command, {
  cwd: root,
  env,
  shell: '/bin/bash',
  detached: true,
  stdio: 'inherit',
});

let stopping = false;

function forward(signal) {
  if (stopping || child.pid == null) return;
  stopping = true;
  try {
    process.kill(-child.pid, signal);
  } catch {
    try { child.kill(signal); } catch { /* 子进程已经退出 */ }
  }
}

process.on('SIGINT', () => forward('SIGINT'));
process.on('SIGTERM', () => forward('SIGTERM'));
process.on('SIGHUP', () => forward('SIGHUP'));
process.on('exit', () => forward('SIGTERM'));

child.on('error', error => {
  console.error(error.message);
  process.exit(1);
});

child.on('exit', (code, signal) => {
  stopping = true;
  if (signal) process.exit(1);
  process.exit(code ?? 1);
});
