/**
 * 静态闸门：检测「回调参数声明了默认值、函数体内也确实调用它，但所有调用点都没传」。
 *
 * 为什么需要它（这是真实踩过的坑）：
 *   ExpandedPanel 的 onFilterBarBounds 写成 `= {}` 默认值后，
 *   OverlayContent 的调用点漏传 —— **编译完全通过**，
 *   但回调永远是空的，导致「标签栏矩形永远上报不到拦截器」，
 *   表现就是「标签栏怎么都滑不动」。
 *
 *   已有的「必需参数」检查只覆盖**没有默认值**的参数，这类漏传它看不见。
 *   本闸门专门补这个盲区：只要回调有默认值、体内又在调用它，
 *   就要求项目里至少有一个调用点显式传入。
 */
const fs = require("fs");
const path = require("path");

const ROOT = process.argv[2];
if (!ROOT) { console.error("usage: node check_wiring.js <kotlin-src-root>"); process.exit(2); }

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, out);
    else if (e.name.endsWith(".kt")) out.push(full);
  }
  return out;
}

/** 去掉注释与字符串字面量，避免误判。 */
function stripLiterals(src) {
  let out = "", i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === "/" && src[i + 1] === "/") { while (i < src.length && src[i] !== "\n") i++; continue; }
    if (c === "/" && src[i + 1] === "*") { i += 2; while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) i++; i += 2; continue; }
    if (src.slice(i, i + 3) === "\"\"\"") { i += 3; while (i < src.length && src.slice(i, i + 3) !== "\"\"\"") i++; i += 3; continue; }
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
    if (c === '"') { i++; while (i < code.length && code[i] !== '"') { if (code[i] === "\\") i++; i++; } continue; }
    if (c === "(") depth++;
    else if (c === ")") { depth--; if (depth === 0) return i; }
  }
  return -1;
}

function splitTopLevel(args) {
  const out = []; let depth = 0, cur = "";
  for (let i = 0; i < args.length; i++) {
    const c = args[i];
    if (c === "(" || c === "{" || c === "[") depth++;
    else if (c === ")" || c === "}" || c === "]") depth--;
    if (c === "," && depth === 0) { out.push(cur.trim()); cur = ""; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

const files = walk(ROOT);
const codeByFile = new Map();
for (const f of files) codeByFile.set(f, stripLiterals(fs.readFileSync(f, "utf8")));

// 收集：函数名 -> { 默认回调参数名[], 体内被调用的参数名[] }
const defs = new Map();
for (const [file, code] of codeByFile) {
  const re = /(?:^|\n)\s*(?:@\w+\s*)?\s*(?:private |internal |public |suspend |inline )*fun\s+([A-Za-z_]\w*)\s*\(/g;
  let m;
  while ((m = re.exec(code))) {
    const name = m[1];
    const open = code.indexOf("(", m.index + m[0].length - 1);
    const close = matchParen(code, open);
    if (close < 0) continue;
    const params = splitTopLevel(code.slice(open + 1, close));
    // 只关心「函数类型 + 有默认值」的参数
    const callbacks = [];
    for (const p of params) {
      if (!p.includes("->") || !p.includes("=")) continue;
      // 只检查**非空**回调（形如 `onX: () -> Unit = {}`）：
      //   可空可选槽（形如 `trailing: (@Composable () -> Unit)? = null`）
      //   本来就是「给未来预留的钩子」，没人用是正常设计，不是 bug。
      //   而非空回调若从未接线，其默认空实现会让整条链路静默失效 ——
      //   这正是标签栏矩形上报不出去的那类问题。
      if (/\)\?\s*=/.test(p)) continue;
      const pname = p.split(":")[0].trim();
      if (!/^[A-Za-z_]\w*$/.test(pname)) continue;
      callbacks.push(pname);
    }
    if (!callbacks.length) continue;
    // 函数体（花括号配平）
    let bodyStart = code.indexOf("{", close);
    if (bodyStart < 0) continue;
    let depth = 0, bodyEnd = -1;
    for (let i = bodyStart; i < code.length; i++) {
      if (code[i] === "{") depth++;
      else if (code[i] === "}") { depth--; if (depth === 0) { bodyEnd = i; break; } }
    }
    if (bodyEnd < 0) continue;
    const body = code.slice(bodyStart, bodyEnd);
    const invoked = callbacks.filter((n) => new RegExp("(?<![\\w.])" + n + "\\s*\\(").test(body));
    if (invoked.length) defs.set(name, invoked);
  }
}

// 检查每个函数的所有调用点是否至少有一处传入了这些回调
let problems = 0;
for (const [name, callbacks] of defs) {
  let anyPassed = false;
  const passed = new Set();
  for (const [, code] of codeByFile) {
    const callRe = new RegExp("(?<![\\w.])" + name + "\\s*\\(", "g");
    let cm;
    while ((cm = callRe.exec(code))) {
      if (/fun\s+$/.test(code.slice(Math.max(0, cm.index - 20), cm.index))) continue;
      const open = cm.index + cm[0].length - 1;
      const close = matchParen(code, open);
      if (close < 0) continue;
      const args = code.slice(open + 1, close);
      for (const cb of callbacks) {
        if (new RegExp("(?<![\\w.])" + cb + "\\s*=").test(args)) passed.add(cb);
      }
    }
  }
  for (const cb of callbacks) {
    if (!passed.has(cb)) {
      console.log("  !! " + name + "(...) 的 " + cb + " 从未被任何调用点传入（默认值是空实现 → 回调永远不生效）");
      problems++;
    }
  }
}
console.log(problems === 0 ? "  OK 未发现「回调声明了但从未接线」的情况" : "  共 " + problems + " 处");
process.exit(problems === 0 ? 0 : 1);
