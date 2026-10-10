/**
 * 静态闸门：检测「使用了顶层扩展函数但没有 import」。
 *
 * 为什么需要它 —— 本项目已经因此失败过两次，两次都被误判成「库版本不对」：
 *
 *   ① coords.boundsInRoot()
 *      它是 androidx.compose.ui.layout 里的顶层扩展，缺 import →
 *      "unresolved reference: boundsInRoot"，被当成「Compose 版本太老」。
 *
 *   ② json.encodeToString(value)  /  json.decodeFromString<T>(text)
 *      1 参 reified 形式是 kotlinx.serialization 里的顶层扩展，缺 import →
 *      "unresolved reference: encodeToString"，CI BUILD FAILED。
 *
 * 两者的共同点：**都是顶层扩展函数，都必须显式 import**，
 * 而报错信息只显示 unresolved reference，看不出是「缺 import」还是「没有这个 API」。
 *
 * 本闸门用「有对应 import」这一条硬判据把它们挡在 CI 之前。
 *
 * 用法：node tools_check_compose_imports.js <kotlin-src-root>
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
if (!ROOT) {
  console.error("usage: node tools_check_compose_imports.js <kotlin-src-root>");
  process.exit(2);
}

/** 函数名 -> 提供它的包。同名只归一个包，避免歧义。 */
const EXTENSIONS_BY_PACKAGE = {
  "androidx.compose.foundation": [
    "background", "border", "clickable", "horizontalScroll", "verticalScroll",
    "detectTapGestures", "detectTransformGestures", "detectDragGesturesAfterLongPress",
    "awaitFirstDown", "waitForUpOrCancellation",
  ],
  "androidx.compose.ui": [
    "clip", "alpha", "onGloballyPositioned", "onSizeChanged",
    "boundsInRoot", "boundsInWindow", "graphicsLayer", "zIndex", "offset",
  ],
  "androidx.compose.foundation.layout": [
    "padding", "height", "width", "fillMaxWidth", "fillMaxSize", "fillMaxHeight",
    "wrapContentSize", "aspectRatio",
  ],
  // ★ 与上面两组同一类坑：1 参 reified 是顶层扩展，2 参（显式传 serializer）才是成员。
  "kotlinx.serialization": [
    "encodeToString", "decodeFromString", "encodeToJsonElement", "decodeFromJsonElement",
  ],
  "kotlinx.coroutines": [
    "launch", "async", "withContext", "runBlocking", "coroutineScope", "supervisorScope",
    "delay", "withTimeout", "withTimeoutOrNull", "awaitAll", "awaitCancellation",
  ],
  "kotlinx.coroutines.flow": [
    "collectLatest", "flatMapLatest", "stateIn", "shareIn", "debounce",
    "distinctUntilChanged", "catch",
  ],
};

const EXTENSION_PACKAGE = new Map();
for (const [pkg, names] of Object.entries(EXTENSIONS_BY_PACKAGE)) {
  for (const n of names) if (!EXTENSION_PACKAGE.has(n)) EXTENSION_PACKAGE.set(n, pkg);
}

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, out);
    else if (e.name.endsWith(".kt")) out.push(full);
  }
  return out;
}

/** 去掉注释与字符串字面量：避免把注释里提到的 API 也算成「使用了」。 */
function stripLiterals(src) {
  let out = "", i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === "/" && src[i + 1] === "/") { while (i < src.length && src[i] !== "\n") i++; continue; }
    if (c === "/" && src[i + 1] === "*") { i += 2; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) i++; i += 2; continue; }
    if (src.slice(i, i + 3) === '"""') { i += 3; while (i < src.length && src.slice(i, i + 3) !== '"""') i++; i += 3; continue; }
    if (c === '"') { i++; while (i < src.length && src[i] !== '"') { if (src[i] === "\\") i++; i++; } i++; continue; }
    if (c === "'") { i++; while (i < src.length && src[i] !== "'") { if (src[i] === "\\") i++; i++; } i++; continue; }
    out += c; i++;
  }
  return out;
}

function matchParen(code, open) {
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    const c = code[i];
    if (c === "(") depth++;
    else if (c === ")") { depth--; if (depth === 0) return i; }
  }
  return -1;
}

/** 顶层逗号个数：用于区分 1 参扩展与 2 参成员。 */
function topLevelCommaCount(args) {
  let depth = 0, count = 0;
  for (let i = 0; i < args.length; i++) {
    const c = args[i];
    if (c === "(" || c === "{" || c === "[") depth++;
    else if (c === ")" || c === "}" || c === "]") depth--;
    else if (c === "," && depth === 0) count++;
  }
  return count;
}

let problems = 0;
for (const file of walk(ROOT)) {
  const raw = fs.readFileSync(file, "utf8");
  const src = stripLiterals(raw);
  const rel = path.relative(ROOT, file).replace(/\\/g, "/");
  const imported = new Set();
  for (const line of raw.split("\n")) {
    const m = line.match(/^\s*import\s+([\w.]+)\s*$/);
    if (m) imported.add(m[1].split(".").pop());
  }
  for (const name of EXTENSION_PACKAGE.keys()) {
    const re = new RegExp("\\.\\s*" + name + "\\s*\\(", "g");
    let needsImport = false;
    let m;
    while ((m = re.exec(src))) {
      // ★ 区分「1 参扩展」与「2 参成员」：
      //   json.encodeToString(value)             ← 顶层扩展，需要 import
      //   json.encodeToString(serializer, value) ← StringFormat 成员，不需要
      //   判据：参数里有顶层逗号 → 至少 2 参 → 成员形式 → 放行。
      const open = m.index + m[0].length - 1;
      const close = matchParen(src, open);
      if (close < 0) { needsImport = true; break; }
      const args = src.slice(open + 1, close);
      if (topLevelCommaCount(args) > 0) continue;
      needsImport = true;
      break;
    }
    if (!needsImport) continue;
    if (imported.has(name)) continue;
    const pkg = EXTENSION_PACKAGE.get(name);
    // 写成全限定名的形式也算已解决
    if (src.includes(pkg + "." + name)) continue;
    console.log("  !! " + rel + " 使用了 " + name + "() 但没有 import");
    console.log("     建议补：import " + pkg + "." + name);
    problems++;
  }
}
console.log(
  problems === 0
    ? "  OK 未发现缺失的顶层扩展 import"
    : "  共 " + problems + " 处缺失",
);

// ══════════════════════════════════════════════════════════════
// 附加检查：Gradle Kotlin DSL 里被遮蔽的根包名
// ══════════════════════════════════════════════════════════════
//
// 本项目为此失败过一次 CI：build.gradle.kts 里写 `java.util.Properties()`，
// 结果报 "Unresolved reference: util"，还级联出 "Unresolved reference: it"。
//
// 原因：在 Kotlin DSL 脚本里 `java` 会被 Gradle 生成的 java 扩展访问器
// （JavaPluginExtension）遮蔽 —— `java.util.Properties()` 会去那个扩展上找 `util`。
// 正确做法是显式 `import java.util.Properties` 后用 `Properties()`。
//
// 判据：非 import 行里出现「前面不是 . 或字母数字」的 java.<小写名> / kotlin.<小写名>。
// 这样 libs.plugins.kotlin.android（前面有 .）与 kotlin("jvm")（后面无 .）都不会误报。
function scanGradleKts(projectRoot) {
  if (!projectRoot || !fs.existsSync(projectRoot)) return 0;
  let found = 0;
  for (const file of walkGradle(projectRoot)) {
    const raw = fs.readFileSync(file, "utf8");
    const rel = path.relative(projectRoot, file).replace(/\\/g, "/");
    const code = stripLiterals(raw);
    code.split("\n").forEach((line, idx) => {
      if (/^\s*import\s/.test(line)) return;
      // 抓取完整限定名（至少三段，如 java.util.Properties），
      // 否则建议会变成 "import java.util" —— 那在 Kotlin 里根本不合法。
      const m = line.match(/(?<![\w.])(java|kotlin)\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+/);
      if (!m) return;
      const fq = m[0];
      const parts = fq.split(".");
      const simple = parts[parts.length - 1];
      console.log("  !! " + rel + ":" + (idx + 1) + " 使用了 " + fq + "（根包名会被 Gradle 扩展遮蔽）");
      console.log("     建议补：import " + fq);
      console.log("     然后把代码里的 " + fq + " 改成 " + simple);
      found++;
    });
  }
  return found;
}

function walkGradle(root, out) {
  out = out || [];
  for (const e of fs.readdirSync(root, { withFileTypes: true })) {
    if (e.name === "build" || e.name === ".gradle" || e.name === ".git" || e.name === "node_modules") continue;
    const full = path.join(root, e.name);
    if (e.isDirectory()) walkGradle(full, out);
    else if (e.name.endsWith(".gradle.kts")) out.push(full);
  }
  return out;
}

const gradleRoot = process.argv[3];
if (gradleRoot) {
  const extra = scanGradleKts(gradleRoot);
  if (extra > 0) problems += extra;
}
process.exit(problems === 0 ? 0 : 1);
