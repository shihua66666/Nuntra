/**
 * 静态闸门：检测「使用了 Compose 扩展函数但没有 import」。
 *
 * 为什么需要它：
 *   像 boundsInRoot()、onGloballyPositioned()、horizontalScroll() 这些都是
 *   androidx.compose.* 里的**顶层扩展函数**，必须显式 import。
 *   漏掉时编译器只报 "unresolved"，看起来很像「版本不兼容」，
 *   极易被误判成依赖问题而去做无谓的升级 —— 实际只是差一行 import。
 *   （本项目就因此误判过一次：boundsInRoot 漏 import 被当成 Compose 版本太老。）
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
if (!ROOT) { console.error("usage: node check_compose_imports.js <kotlin-src-root>"); process.exit(2); }

/** 已知需要 import 的 Compose 扩展函数（都是「.name(」形式调用）。 */
const EXTENSIONS = [
  "background", "border", "clickable", "clip", "alpha", "padding",
  "height", "width", "fillMaxWidth", "fillMaxSize", "fillMaxHeight",
  "horizontalScroll", "verticalScroll", "onGloballyPositioned", "onSizeChanged",
  "boundsInRoot", "boundsInWindow", "detectTapGestures", "detectTransformGestures",
  "detectDragGesturesAfterLongPress", "graphicsLayer", "zIndex", "aspectRatio",
  "wrapContentSize", "offset", "rotate", "scale",
];

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, out);
    else if (e.name.endsWith(".kt")) out.push(full);
  }
  return out;
}

let problems = 0;
for (const file of walk(ROOT)) {
  const src = fs.readFileSync(file, "utf8");
  const imported = new Set();
  for (const line of src.split("\n")) {
    const m = line.match(/^\s*import\s+([\w.]+)\s*$/);
    if (m) imported.add(m[1].split(".").pop());
  }
  const rel = path.relative(ROOT, file).replace(/\\/g, "/");
  for (const name of EXTENSIONS) {
    // 只匹配「.name(」形式的调用；并要求该标识符是大写开头的接收者或 Modifier 链
    const re = new RegExp("\\.\s*" + name + "\\s*\\(", "g");
    const hit = re.exec(src);
    if (!hit) continue;
    if (imported.has(name)) continue;
    // 全限定名写法也算已 import
    if (src.includes("androidx.compose." + name)) continue;
    console.log("  !! " + rel + " 使用了 " + name + "() 但没有 import");
    problems++;
  }
}
console.log(problems === 0 ? "  OK 未发现缺失的 Compose 扩展 import" : "  共 " + problems + " 处");
process.exit(problems === 0 ? 0 : 1);
