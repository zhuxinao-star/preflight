/* PreFlight 前端 SPA（无构建依赖，原生 JS） */
"use strict";

/* ============ 基础 ============ */
const $ = sel => document.querySelector(sel);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const PAD = n => String(n).padStart(2, "0");
const MON3 = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };

/** 全站时间显示的唯一出口：认不出的原样返回，绝不编造一个时间 */
function fmtTime(v) {
  if (v == null || v === "") return "";
  if (v instanceof Date) return isNaN(v.getTime()) ? String(v) : fmtLocal(v);
  if (typeof v === "object") return "";
  const s = String(v).trim();
  if (/^\d{10}$/.test(s) || /^\d{13}$/.test(s)) {
    const d = new Date(Number(s) * (s.length === 10 ? 1000 : 1));
    return isNaN(d.getTime()) ? s : fmtLocal(d);
  }
  const iso = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/);
  if (iso) {
    const [, Y, Mo, D, h, mi, se, zone] = iso;
    if (!h) return `${Y}-${Mo}-${D}`;
    if (!zone) return `${Y}-${Mo}-${D} ${h}:${mi}:${se || "00"}`;
    const d = new Date(s);
    return isNaN(d.getTime()) ? s : fmtLocal(d);
  }
  const jvm = s.match(/^[A-Za-z]{3} ([A-Za-z]{3}) (\d{2}) (\d{2}):(\d{2}):(\d{2}) \S+ (\d{4})/);
  if (jvm && MON3[jvm[1]] !== undefined) {
    // 历史留痕是采集机 JVM 本地时区打出来的，缩写时区（CST）含义不唯一，只取墙钟字段
    return `${jvm[6]}-${PAD(MON3[jvm[1]] + 1)}-${jvm[2]} ${jvm[3]}:${jvm[4]}:${jvm[5]}`;
  }
  return s;
}
function fmtLocal(d) {
  return `${d.getFullYear()}-${PAD(d.getMonth() + 1)}-${PAD(d.getDate())} ${PAD(d.getHours())}:${PAD(d.getMinutes())}:${PAD(d.getSeconds())}`;
}

/**
 * opts = { progress, sticky }：progress → 这次调用要真实进度（后端按 pid 登记），
 * sticky → 请求早返回、活儿还在后台跑（学习），进度由调用方 endBusy() 收尾。
 */
async function api(url, body, method, opts) {
  busyStart();
  let ok = false;
  try {
    if (opts && opts.progress) {
      const pid = newPid();
      url += (url.includes("?") ? "&" : "?") + "pid=" + pid;
      if (busy) { busy.pid = pid; if (opts.sticky) busy.sticky = true; }
    }
    const opt = { method: method || (body ? "POST" : "GET"), headers: {} };
    if (body instanceof FormData) { opt.body = body; }
    else if (body !== undefined) { opt.headers["Content-Type"] = "application/json"; opt.body = JSON.stringify(body); }
    const r = await fetch(url, opt);
    if (r.status === 401 && !url.startsWith("/api/auth/")) { showLogin(); throw new Error("未登录"); }
    const j = await r.json().catch(() => ({ ok: false, error: "HTTP " + r.status }));
    if (!r.ok && !j.error) j.error = "HTTP " + r.status;
    ok = r.ok && j.ok !== false;   // 后端 ok:false 也算失败：按钮要红闪，不能绿骗人
    return j;
  } finally {
    busyEnd(ok);                   // finally 收口：抛错分支也只减一次，计数不会被带歪
  }
}

/**
 * toast(msg, err, action) —— action 可选 {label, onClick}：带一个可点的跳转按钮（第 15 轮，
 * 用来回答「生成完在哪儿看」）。有 action 时停留更久，不然来不及点。
 */
function toast(msg, err, action) {
  const t = document.createElement("div");
  t.className = "toast" + (err ? " err" : "");
  t.textContent = msg;
  if (action) {
    const a = document.createElement("a");
    a.href = "#";
    a.textContent = action.label;
    a.style.cssText = "margin-left:12px;color:#7ee787;font-weight:600;text-decoration:underline";
    a.onclick = e => { e.preventDefault(); t.remove(); action.onClick(); };
    t.appendChild(a);
  }
  $("#toastRoot").appendChild(t);
  setTimeout(() => t.remove(), action ? 9000 : 3200);
}

/** 站内跳转：hash 没变化时不会触发 hashchange，所以补一次 route() */
function navTo(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

/** 走 <a target=_blank> 的导出：后端回 attachment，文件只落到浏览器下载条，本页一个字都不变，
    不提示就会被当成"点了没反应"（第 20 轮实测）。 */
function exportHint() {
  toast("结论单已开始导出：文件在浏览器的下载条 / 下载列表里，本页面不会跳转。没看到就检查地址栏右侧是否拦了弹窗。");
}

function modal(html, cls) {
  const mask = document.createElement("div");
  mask.className = "modal-mask";
  mask.innerHTML = `<div class="modal${cls ? " " + cls : ""}">${html}</div>`;
  mask.addEventListener("click", e => { if (e.target === mask) mask.remove(); });
  $("#modalRoot").appendChild(mask);
  fitControls(mask);
  return mask;
}

/* ---- 表单控件尺寸统一（第十五轮）：
   textarea 起始高度只看 CSS min-height（普通 200px / .code 360px），随内容长高到 900px 封顶，
   右下角仍可双向拖动；用户拖过高度后 JS 不再抢回去。
   select 高度/字号由 CSS 统一加大，宽度按最长选项自适应（选项异步加载时按签名重测）。
   量高会强制回流，所以按「行数没变就不量」省掉按键开销。 ---- */
const TA_CEIL = 900, SEL_MIN = 320, SEL_MAX = 900;
function fitTextarea(t) {
  if (!t) return;
  /* 用户拖过之后浏览器会改写 style.height，和上次程序设的值对不上 → 高度从此交还给用户。
     （不用 pointerup 判定：拖动把手是浏览器原生行为，页面收不到 pointerup，上一版就是这么把拖动吃掉的） */
  if (t.dataset.taSet && t.style.height !== t.dataset.taSet + "px") {
    t.dataset.taManualH = "1";
    t.style.overflowY = "auto";
    return;
  }
  if (t.dataset.taManualH === "1") return;
  const lines = (t.value.match(/\n/g) || []).length + 1;
  if (t.dataset.taLines === String(lines) && t.style.height) return;   // 行内编辑：不重新量
  t.dataset.taLines = String(lines);
  const floor = t.dataset.taFloor || (t.dataset.taFloor = String(parseFloat(getComputedStyle(t).minHeight) || 200));
  const ceil = +(t.dataset.taCeil || TA_CEIL);          // 只读预览这类框可用 data-ta-ceil 单独收短
  t.style.height = "auto";
  const need = t.scrollHeight + 2;
  const h = Math.max(floor, Math.min(need, ceil));
  t.style.height = h + "px";
  t.dataset.taSet = String(h);
  t.style.overflowY = need > ceil ? "auto" : "hidden";
}
function fitControls(root) {
  const r = root || document;
  r.querySelectorAll("textarea").forEach(fitTextarea);
  r.querySelectorAll("select").forEach(fitSelect);
  applyPlaceholders(r);   // 第 18 轮：渲染后的统一装饰——要填的地方给示例值（悬停说明走事件委托，不逐处挂）
}
let _probe;
function fitSelect(s) {
  if (s.closest(".pager") || s.closest(".pbbar") || s.classList.contains("jump")) return;   // 小控件不参与"按最长选项加宽"
  const opts = [...s.options];
  if (!opts.length) return;
  const texts = opts.map(o => o.textContent.trim());
  const sig = texts.reduce((a, t) => a + t.length, 0) + ":" + texts.length;
  if (s.dataset.selSig === sig) return;               // 选项没变就不重测；变了（含异步灌入）自动重测
  s.dataset.selSig = sig;
  _probe ||= Object.assign(document.createElement("span"),
    { style: "position:absolute;left:-9999px;top:0;white-space:pre;visibility:hidden;display:inline-block" });
  if (!_probe.isConnected) document.body.appendChild(_probe);
  _probe.style.font = getComputedStyle(s).font;
  _probe.textContent = texts.join("\n");              // 一次写入一次读取：整块量出行宽最大者
  const avail = s.parentElement ? s.parentElement.clientWidth : 0;
  const want = Math.min(SEL_MAX, Math.max(SEL_MIN, _probe.offsetWidth + 40));
  s.style.minWidth = Math.max(SEL_MIN, avail ? Math.min(want, avail) : want) + "px";
}
document.addEventListener("input", e => { if (e.target.tagName === "TEXTAREA") fitTextarea(e.target); });

/* ==================== 第 18 轮：界面反馈基座 ====================
   在飞反馈 / diff 高亮 / 悬停说明 / 示例值 / 统一翻页条只在这里实现一遍，
   之后新增按钮、列表、输入框自动继承，不再逐处补。 */

/* ---- B：点击必有反馈。一次点击 → 若干个 api() 请求；在飞时给那一下点到的按钮挂转圈，
   全部回来后按成败闪绿/红一次。route() 会重建 DOM（按钮随之消失），故另有 #pfTop 兜底。

   第 20 轮补上「按钮上的进度」：光有转圈，等 30 秒的人分不清是在跑还是卡死了。
   现在那一下点到的按钮文字会变成「同步 · 已 7 秒」；后端 ProgressRegistry 登记过 pid 的
   （同步 / 学习 / 生成用例 / 一键回归）再换成真实阶段「生成用例 · 3/12 · 正在处理 xxx.xml」。
   没登记的接口一行代码都不用改就有秒表，登记了才有分子分母——这是通用基座，不是逐按钮特判。 ---- */
let inflight = 0, lastClickBtn = null, topTimer = null;
let busy = null;            // 当前这一下点击绑定的按钮 + 它的进度状态
let pidSeq = 0;
const newPid = () => "p" + Date.now().toString(36) + "-" + (++pidSeq);
document.addEventListener("click", e => {
  const b = e.target.closest ? e.target.closest("button") : null;
  if (b && !b.disabled) lastClickBtn = b;
}, true);
function topBar() {
  let el = document.getElementById("pfTop");
  if (!el) { el = document.createElement("div"); el.id = "pfTop"; document.body.appendChild(el); }
  return el;
}
function setTop(on) {                 // 出现慢、消失也慢一点：连续的快请求不该让进度条闪成一片
  clearTimeout(topTimer);
  topTimer = setTimeout(() => {
    const el = topBar();
    el.classList.toggle("on", on);
    if (!on) el.textContent = "";
  }, on ? 260 : 180);
}
function busyText() {
  if (!busy) return "";
  const p = busy.prog, sec = Math.floor((Date.now() - busy.t0) / 1000);
  if (p && Number(p.total) > 0) return `${Number(p.done)}/${Number(p.total)}${p.note ? " · " + p.note : ""}`;
  if (p && p.note) return `${p.note} · 已 ${sec} 秒`;
  if (p && p.label) return `${p.label} · 已 ${sec} 秒`;
  return `已 ${sec} 秒`;
}
function drawBusy() {
  if (!busy) return;
  const t = busyText();
  if (busy.btn.isConnected) {
    busy.btn.classList.add("pf-prog-on");
    busy.btn.innerHTML = esc(busy.text0) + ` <span class="pf-prog">· ${esc(t)}</span>`;
  }
  const top = document.getElementById("pfTop");
  if (top && top.classList.contains("on")) top.textContent = (busy.text0 ? busy.text0 + " · " : "") + t;
}
async function pollBusy() {                       // 只读轮询：绝不走 api()，否则在飞计数会被自己带歪
  if (!busy || !busy.pid) return;
  try {
    const r = await fetch("/api/progress?id=" + busy.pid).then(x => x.json()).catch(() => null);
    if (r && r.found) { busy.prog = r; drawBusy(); }
  } catch (e) { /* 服务重启中：下一轮再试，按钮至少还有秒表 */ }
}
function captureBusy() {
  if (busy || !lastClickBtn || !lastClickBtn.isConnected) return;
  busy = { btn: lastClickBtn, html0: lastClickBtn.innerHTML, text0: (lastClickBtn.textContent || "").trim(),
    t0: Date.now(), pid: null, prog: null, sticky: false, timer: null };
  busy.timer = setInterval(() => { drawBusy(); pollBusy(); }, 1000);
  drawBusy();
}
function releaseBusy() {
  if (!busy) return;
  clearInterval(busy.timer);
  // 恢复原文案（连点两次不该出现"同步 · 3/12 · 5/12"），并摘掉进度类，否则跑完还留着进度态
  if (busy.btn.isConnected) { busy.btn.innerHTML = busy.html0; busy.btn.classList.remove("pf-prog-on"); }
  busy = null;
}
function endBusy(ok) {                            // sticky 进度由调用方收尾（学习：请求早返回，活儿还在后台）
  const b = busy && busy.btn;
  releaseBusy();
  setTop(false);
  if (b && b.isConnected) flashBtns([b], ok);
}
function flashBtns(btns, ok) {
  btns.forEach(b => {                  // 先摘再挂：同一个按钮连点两次要能各闪一次
    b.classList.remove("pf-ok", "pf-bad");
    void b.offsetWidth;
    b.classList.add(ok ? "pf-ok" : "pf-bad");
    setTimeout(() => b.classList.remove("pf-ok", "pf-bad"), 900);
  });
}
function busyStart() {
  inflight++;
  setTop(true);
  captureBusy();
  if (lastClickBtn && lastClickBtn.isConnected) lastClickBtn.classList.add("pf-busy");
}
function busyEnd(ok) {
  inflight = Math.max(0, inflight - 1);
  if (inflight > 0) return;
  setTop(false);
  const btns = [...document.querySelectorAll("button.pf-busy")];
  btns.forEach(b => b.classList.remove("pf-busy"));
  lastClickBtn = null;
  if (busy && busy.sticky) return;     // 后台任务还没跑完，按钮继续显示进度
  const b = busy && busy.btn;
  releaseBusy();
  flashBtns(btns.length ? btns : (b ? [b] : []), ok);
}

/* ---- 界面偏好（第 18 轮）：两个悬停说明开关 + 默认每页条数，存实例级 settings 表 ---- */
const UI_PREF_DEFAULTS = { ui_tip_menu: "1", ui_tip_buttons: "1", ui_page_size: "2" };
function pref(key) {
  const v = (state.uiPrefs || {})[key];
  return v == null || v === "" ? UI_PREF_DEFAULTS[key] : String(v);
}
const tipMenuOn = () => pref("ui_tip_menu") !== "0";
const tipButtonsOn = () => pref("ui_tip_buttons") !== "0";
async function loadUiPrefs() {
  const map = Object.assign({}, UI_PREF_DEFAULTS);
  try {
    const r = await api("/api/settings");
    (r.settings || []).forEach(s => { if (s.key in UI_PREF_DEFAULTS && s.value != null) map[s.key] = String(s.value); });
  } catch (e) { /* 未登录/服务重启中：先用默认值，下次路由再刷 */ }
  state.uiPrefs = map;
}
async function saveUiPref(key, value) {
  const r = await api("/api/settings", { key, value: String(value) });
  if (r.ok) { state.uiPrefs = Object.assign({}, state.uiPrefs, { [key]: String(value) }); toast("已保存，立即生效"); }
  else toast(r.error || "保存失败", true);
}

/* ---- A：diff 高亮。行级按 +/-/@@/文件头分色；一段删除紧跟一段新增时按顺序 1:1 配对，
   剥掉公共前后缀，把真正变了的那几个字单独标出来。 ---- */
function diffLineClass(l) {
  if (l.startsWith("@@")) return "hunk";
  if (/^(--- |\+\+\+ )\S/.test(l)) return "file";
  if (l[0] === "+") return "add";
  if (l[0] === "-") return "del";
  if (/^(diff --git|index |new file mode|deleted file mode|old mode|new mode|similarity index|rename |copy |Index:|={3,})/.test(l)) return "meta";
  return "ctx";
}
/** 两条只在局部有别的行 → 各自把中间差异段包成 w-del / w-add */
function diffMarkWords(dRow, aRow) {
  const d = dRow.raw.slice(1), a = aRow.raw.slice(1);
  let s = 0;
  while (s < d.length && s < a.length && d[s] === a[s]) s++;
  let e = 0;
  while (e < d.length - s && e < a.length - s && d[d.length - 1 - e] === a[a.length - 1 - e]) e++;
  if (e === 0 && s === 0) return;               // 整行都不同：行色已经说清楚了，不再叠一层
  const cut = (t, cls) => esc(t.slice(0, s)) + `<span class="${cls}">${esc(t.slice(s, t.length - e)) || "&nbsp;"}</span>` + esc(t.slice(t.length - e));
  dRow.html = esc(dRow.raw[0]) + cut(d, "w-del");
  aRow.html = esc(aRow.raw[0]) + cut(a, "w-add");
}
const DIFF_LEGEND = `<div class="diff-legend"><span><i style="background:#0a2f18;border:1px solid #059669"></i>新增行</span>
  <span><i style="background:#3a1418;border:1px solid #dc2626"></i>删除行</span>
  <span><i style="background:#15803d"></i>行内改动点</span>
  <span><i style="background:#0b2a4a;border:1px solid #38bdf8"></i>位置块 @@</span></div>`;
function renderDiff(text) {
  const src = String(text ?? "");
  if (!src.trim()) return "";
  const rows = src.split(/\r?\n/).map(l => ({ raw: l, cls: diffLineClass(l), html: esc(l) }));
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].cls !== "del") continue;
    let d = i; while (d < rows.length && rows[d].cls === "del") d++;
    let a = d; while (a < rows.length && rows[a].cls === "add") a++;
    const n = Math.min(d - i, a - d);
    for (let k = 0; k < n; k++) diffMarkWords(rows[i + k], rows[d + k]);
    i = a - 1;                                  // 整块处理完，从下一段继续找
  }
  return DIFF_LEGEND + `<pre class="diff">${rows.map(r => `<span class="ln ${r.cls}">${r.html || "&nbsp;"}</span>`).join("")}</pre>`;
}

/* ---- C：悬停说明。菜单与按钮各一个开关（系统设置→界面提示）；没配说明的按钮不猜，
   记进未配清单，方便在设置页看到还差哪些。 ---- */
const NAV_TIPS = {
  dashboard: "看板：数字全部来自本机库的提交、文件改动与执行记录汇总——同步过、跑过用例才会有变化，不联网取数。",
  projects: "项目与仓库：项目手工新建；仓库登记 Git/SVN 地址与本地工作副本路径。【同步】把提交和逐文件 diff 入库，【学习】从代码里抽接口卡、SQL 卡和服务字典。",
  changes: "改动流：一行一次提交，数据来自【同步】。测试状态是这里自己算出来的（该提交的改动点上有多少用例已通过），【生成用例】【一键回归】的结果都回写到这一行。",
  lab: "接口测试台：接口清单来自知识库的接口卡（学习或手工建的）。执行前会自动跑登录流注入会话，请求与断言结果写进执行记录。",
  sql: "SQL 直测：语句和参数来自 SQL/数据对象卡，动态片段按你填的判定变量现场拼装；直接打到「测试环境」里配的那个库。",
  knowledge: "知识库：接口卡 / SQL 卡 / 用例目录三张表。LLM 产物一律先落草稿，人工确认后才生效；手工编辑过的内容重学时不会被覆盖。",
  releases: "起飞检查单：从改动流里挑一批提交组成清单，逐项【判通过/跳过】签字，签字记录留在库里并可导出 HTML 结论单。",
  reports: "提测结论单：挑一个提交区间，按「受影响目标」逐行列出接口卡与 SQL 卡，每行带回最近一次执行留痕（点 run_id 看当时的请求与响应）与该区间动过的出入参字段，证据不足处逐条列成风险声明；出单前每行要点一次【已核对】或【跳过（写理由）】。只读本系统自己的元数据与盘上 diff，不碰目标库。",
  yapi: "YApi 文档同步：功能号清单来自学习建好的服务字典，入参取字典学到的业务参数＋调用点证据认出的请求级字段（普通 bex/service 不再无条件带老基准那 5 项）、出参按盘上真实代码推算（bexConf→mapper 语句→存储过程源码），viewConf 声明只当补源；先选 YApi 项目与目录，预览逐条确认后才推送。token 只从「系统设置 → 凭据保险库」取，不进这个页面也不进日志。",
  settings: "系统设置：大模型供应商、测试环境、登录流、凭据保险库（AES-GCM 加密）、用户与通知。这里是全系统唯一存放外部地址和账号的地方。",
};
const BTN_TIPS = [
  [/^×$|^关闭$|取消$/, "关掉这个弹屏，不保存任何改动"],
  [/^保存$/, el => el.closest(".modal-mask") ? "写回本机库；成功后弹屏关闭并刷新当前列表" : "写回本机库设置，其它卡片与数据不受影响"],
  [/^创建$|创建并登录$/, "提交表单并创建"],
  [/^删除$/, "不可撤销：本机库连同依赖它的历史一起删（环境/仓库还会牵动已入库的用例与执行记录）"],
  [/^编辑$/, "改这条记录的配置；保存前不会影响其它数据"],
  [/^去\S+$/, "跳到对应菜单——数据是同一份本机库，不会另算一套"],
  [/选择接口|选择 SQL/, "弹屏挑一张学习到的卡：可按仓库筛选、关键字搜索、翻页；选中后地址与参数自动带过来"],
  [/^生成$/, "按上面的标题和「范围起 → 范围止」两个提交，把这段区间的改动汇总成一份检查单"],
  [/同步/, "从 Git/SVN 拉新提交与逐文件 diff 入库（只读代码，不改你的仓库）"],
  [/^学习|重新学习/, "解析代码抽接口卡 / SQL 卡 / 服务字典：先草稿，人工确认后生效；手工编辑过的不会被覆盖"],
  [/拉取/, "拉取更新，与【同步】同类操作"],
  [/详情 \/ Diff|详情/, "看这条提交的逐文件 diff 与改动点归因"],
  [/生成摘要/, "调用大模型给这条提交写一句话改动摘要（离线时降级为改动清单摘要）"],
  [/生成用例/, "按该提交归因到的接口卡/SQL 卡生成用例草稿，生成后可在知识库的用例目录看到"],
  [/一键回归|执行测试|^▶ 执行$/, "把该提交下「待执行」的用例逐条真实打一次接口/库，结果写入执行记录"],
  [/标已测|标部分测|标跳过/, "手工登记测试状态（会留审计记录）；【标跳过】需要你填理由"],
  [/查看用例|只看这一目标/, "跳到知识库的用例目录，并按这条提交过滤"],
  [/到测试台打开|到直测打开|打开$/, "带着这张卡跳转到接口测试台 / SQL 直测；从用例行过来时，这条用例存好的参数一起带过去并预填"],
  [/^搜索$|回车搜索/, "按当前条件查询：条件都发给后端，走服务端分页"],
  [/清空条件/, "清掉本段列表的过滤条件重新加载"],
  [/上一页|下一页|跳页/, "翻页：每页条数可在旁边的下拉里改，最多 200"],
  [/批量确认/, "按置信度阈值把草稿卡一次性确认为已确认（会先给条数预览）"],
  [/确认全部草稿|确认这/, "把当前过滤条件下的全部草稿确认掉，量大时请分批"],
  [/新建接口/, "手工建一张接口卡（学习漏掉的接口用它补）"],
  [/OpenAPI|Postman|HAR/, "从存量接口文档或抓到的真实流量导入成接口卡，导入后仍是草稿"],
  [/导出知识包/, "把本项目的接口卡 / SQL 卡 / 服务字典 / 用例打包下载（不含密码与环境地址）"],
  [/导入知识包|导入存量资产|^导入$/, "上传知识包或文档，按名称合并入库；同名记录只补不覆盖"],
  [/重建服务字典|重建字典/, "重扫该项目的 metaData 与前端调用点，重建老分发工程的服务字典"],
  [/重新体检|体检/, "环境体检：探一次 Base URL 是否可达 + 数据库能否连上，只读，不改任何配置"],
  [/添加登录流/, "新建一条登录流：弹屏一打开就按<b>这个项目盘上学到的东西</b>把步骤、名称、会话注入填好（换项目就换内容）——铺得出整条链时默认铺证据最多的那一条，要换点别的链【＋ 铺这条链】，只想补一步点【＋ 填成一步】；学不到值的格照空串摆并逐条说为什么空，系统不编示例值、也不替你新增一行登录流；你手写过的内容永不顶掉，铺完每一格都能手改，点【保存】才落库；账号密码用 {{cred.username}} / {{cred.password}} 引用（明文只进凭据保险库）"],
  [/添加环境|添加凭据|添加供应商|添加仓库目录|新建项目|添加仓库/, "新建一条记录（弹屏里带示例值，照着改就能用）"],
  [/连通/, "拿这个 Key 真实调一次模型接口，验证地址与密钥可用"],
  [/试跑/, "在指定环境上真实跑一遍这个登录流：输出里的值全部打码，不落任何数据"],
  [/表结构/, "直连该库读取列与主键（会弹元数据查询），用于核对 SQL 写的列名"],
  [/复制语句/, "把左边这条只读语句复制到剪贴板——本系统不会替你向目标库发它"],
  [/填入默认/, "把系统内置的默认那份抄进输入框：还没生效，要点旁边的【保存】"],
  [/复制 cURL|复制/, "把这次请求复制成可粘贴的 cURL 命令"],
  [/加断言/, "追加一条断言行（表达式 + 期望值）"],
  [/保存断言到用例/, "把当前断言与忽略字段写回这条用例，下次执行生效"],
  [/设为基线|基线/, "把这次响应存成基线，之后执行自动和新响应做差异对比"],
  [/清除基线/, "删掉这条用例的基线，不再做差异比对"],
  [/修复/, "按最新接口定义重建这条用例的报文骨架，保留已填参数"],
  [/前后置钩子|钩子/, "配执行前后各跑哪些 SQL（准备数据 / 清理数据）"],
  [/带入|按参数拼装|拼装/, "把这些参数代入 SQL 的占位符，写回上方的 SQL 框"],
  [/用这条写回/, "把「值已代入」的等价语句写回上方 SQL 框：语句里不再有 ?，执行的就是这段文本"],
  [/展开.*段|展开全部|只看拼进的/, "切换片段表显示范围：400+ 段的卡全量渲染会拖慢页面"],
  [/上报/, "把这次失败上报为一条缺陷记录"],
  [/签字|判通过|跳过/, "对清单里的这一项做结论并留审计记录；【跳过】要填理由"],
  [/导出 HTML|结论单/, "把这份检查单连同签字结论导成可发给别人看的 HTML：走浏览器下载（下载条里找），本页面不会跳转"],
  [/运行评测|评测/, "用黄金基线实测大模型抽取的精确率/召回率，判断能不能放开自动草稿"],
  [/发送测试通知/, "往配置的 Webhook 真实发一条测试消息，验证通知链路通不通"],
  [/重建$/, "重建该派生缓存，让统计口径跟上当前的库内容"],
];
window.__pfTipMiss = new Set();
function tipTextFor(el) {
  if (el.dataset && el.dataset.tip) return el.dataset.tip;
  const a = el.matches("nav a") ? el : el.closest("nav a");
  if (a) {
    const v = (a.dataset.view || (a.textContent || "").trim());
    if (NAV_TIPS[v]) return NAV_TIPS[v];
    window.__pfTipMiss.add("菜单：" + v);
    return "";
  }
  const label = (el.textContent || "").trim().replace(/\s+/g, " ");
  if (!label) return "";
  // 文案可以是字符串，也可以是 fn(el)——同一按钮在弹屏里和在页面里含义不同
  for (const [re, tip] of BTN_TIPS) {
    const t = typeof tip === "function" ? tip(el) : tip;
    if (re.test(label)) return t || "";
  }
  window.__pfTipMiss.add(label);
  return "";
}
function tipBox() {
  let el = document.getElementById("pfTip");
  if (!el) { el = document.createElement("div"); el.id = "pfTip"; document.body.appendChild(el); }
  return el;
}
function tipHtml(txt) {
  const i = txt.indexOf("：");
  return i > 0 && i <= 24 ? `<b>${esc(txt.slice(0, i))}</b>${esc(txt.slice(i + 1))}` : esc(txt);
}
let tipOwner = null;
function hideTip() { tipOwner = null; const el = document.getElementById("pfTip"); if (el) el.classList.remove("on"); }
document.addEventListener("mouseover", e => {
  const el = e.target.closest ? e.target.closest("button, nav a, [data-tip]") : null;
  if (!el || el === tipOwner) return;
  hideTip();
  const isNav = !!(el.closest && el.closest("nav"));
  if (isNav ? !tipMenuOn() : !tipButtonsOn()) return;
  const txt = tipTextFor(el);
  if (!txt) return;
  const box = tipBox();
  box.innerHTML = tipHtml(txt);
  box.classList.add("on");
  const r = el.getBoundingClientRect();
  const x = Math.max(8, Math.min(r.left, innerWidth - box.offsetWidth - 8));
  const y = r.bottom + 6 + box.offsetHeight > innerHeight - 8 ? Math.max(8, r.top - box.offsetHeight - 6) : r.bottom + 6;
  box.style.left = x + "px";
  box.style.top = y + "px";
  tipOwner = el;
});
document.addEventListener("mouseout", e => { if (e.target === tipOwner) hideTip(); });
addEventListener("scroll", hideTip, true);
addEventListener("resize", hideTip);

/* ---- F：要填的地方给示例值（学习来的真值优先，其次系统本身需要的格式）。
   已有 placeholder 或已有值的不动；没规则的字段不猜，免得给出错示例。 ---- */
const PH_RULES = [
  [/#pName|项目名/, () => "回访平台 / work_app 后端"],
  [/^#pDesc|描述/, () => "一句话说清这个项目测什么：接口 + 库 + 环境"],
  [/#rName/, () => "work_app 后端 / zl200081 主仓"],
  [/分支|SVN 相对路径/, () => "Git 填分支名 master；SVN 填相对路径 trunk 或 trunk/subsys/work"],
  [/#eName/, () => "本地演示 / 测试一 / 预发"],
  [/#eDbUser|数据库用户/, () => "达梦 SYSDBA / MySQL root / Oracle system"],
  [/#lName/, () => "内网 Qwen / 本地 Ollama"],
  [/#lModel|模型/, () => "qwen2.5:14b / gpt-4o-mini / deepseek-chat"],
  [/用途/, () => "summary / testcase / extract（留空=通用）"],
  [/#cName|凭据命名约定/, () => "<仓库名>-svn，如 work_app-svn"],
  [/user:password/, () => "user:password"],
  [/SQL 位置|sql ?参数/, () => "1001 / 2026-09-22 / PAID（按该位置在库里的类型填）"],
  [/query ?参数|业务入参/, () => "留空 = 不带这个参数"],
  [/#liName|登录名/, () => "你的登录名"],
  [/^#suName/, () => "admin"],
  [/^#rlTitle|标题/, () => "发布检查单 2026-09-22 / v3.2 上线前"],
  [/base url/i, () => "http://127.0.0.1:8080（环境没填时，测试台只能靠手工指定地址）"],
];
function labelOf(el) {
  const item = el.closest ? el.closest(".form-item") : null;
  const lab = item && item.querySelector("label");
  return (el.getAttribute("aria-label") || (lab ? lab.textContent : "")).trim();
}
function applyPlaceholders(root) {
  (root || document).querySelectorAll("input, textarea").forEach(el => {
    if (el.placeholder || el.value || el.type === "hidden" || el.type === "file") return;
    const key = "#" + (el.id || "") + " " + labelOf(el)
      + (el.dataset.sqlp != null ? " SQL 位置参数" : "")
      + (el.dataset.dynp != null ? " 动态参数" : "")
      + (el.dataset.q != null ? " query参数" : "");
    const hit = PH_RULES.find(([re]) => re.test(key));
    if (hit) el.placeholder = "示例：" + hit[1](el);
  });
}

/* ---- E：统一翻页条。默认每页条数取系统设置（默认 2，最多 200），
   单条列表改过的记在浏览器本地，不牵动其它列表；上下页优先渲染成 <a href>，
   保留不依赖 JS 也能翻页的手动能力。
   翻页动作按名字登记在 PAGER_ACT 里（各列表在自己代码段末尾注册一次），
   这里只发名字 + 页码，不拼字符串代码去 eval。 ---- */
const PAGE_SIZES = [2, 5, 10, 20, 50, 100, 200];
const PAGER_ACT = new Map();
function pagerDefine(name, go, size) { PAGER_ACT.set(name, { go, size }); return name; }
function pagerGo(name, page) {
  const a = PAGER_ACT.get(name);
  if (!a) { toast("翻页控件已失效，请刷新页面", true); return; }
  a.go(Math.max(1, Math.floor(Number(page) || 1)));
}
function listSize(key) {
  const saved = Number(localStorage.getItem("pf.size." + key));
  if (saved >= 1 && saved <= 200) return saved;
  const d = Number(pref("ui_page_size"));
  return Math.min(200, Math.max(1, d || 2));
}
function setListSize(key, n) {
  localStorage.setItem("pf.size." + key, String(Math.min(200, Math.max(1, Number(n) || 2))));
}
function pagerJump(inp) {
  if (!inp) return;
  const n = Math.floor(Number(inp.value));
  if (!Number.isFinite(n) || n < 1) { toast("请输入要跳到的页码", true); return; }
  inp.value = "";
  pagerGo(inp.dataset.pager, n);
}
/** 只出「每页 N 条」下拉：弹屏内自己接事件的列表用它（改完由调用方监听并回跳第 1 页） */
function sizeSelHtml(key, cur, pagerName) {
  return `<label class="muted" style="display:inline-flex;align-items:center;gap:4px">每页
    <select class="jump" data-size-key="${esc(key)}" data-size-go="${esc(pagerName || "")}">${
      PAGE_SIZES.map(n => `<option value="${n}"${n === cur ? " selected" : ""}>${n}</option>`).join("")}</select>条</label>`;
}
/** o = { key, total, page, pager, href }：pager 是 pagerDefine 登记的名字；给了 href 则上下页走 <a> */
function pagerBar(o) {
  const size = listSize(o.key);
  const total = Math.max(0, Number(o.total) || 0);
  const pages = Math.max(1, Math.ceil(total / size));
  const page = Math.min(Math.max(1, Number(o.page) || 1), pages);
  const step = (p, label) => (p < 1 || p > pages)
    ? `<button class="small ghost" disabled>${label}</button>`
    : (o.href ? `<a class="small ghost btnlike" href="${esc(o.href(p))}">${label}</a>`
              : `<button class="small ghost" onclick="pagerGo('${esc(o.pager)}',${p})">${label}</button>`);
  const jumpId = "pfj_" + String(o.key).replace(/[^A-Za-z0-9_-]/g, "");
  return `<div class="pager">
    ${step(page - 1, "← 上一页")}
    <span class="muted">第 ${page} / ${pages} 页 · 共 ${total} 条</span>
    ${step(page + 1, "下一页 →")}
    ${o.pager ? `<input id="${jumpId}" class="jump" type="number" min="1" max="${pages}" placeholder="页码"
      data-pager="${esc(o.pager)}" onkeydown="if(event.key==='Enter')pagerJump(this)">
      <button class="small ghost" onclick="pagerJump(document.getElementById('${jumpId}'))">跳页</button>` : ""}
    ${sizeSelHtml(o.key, size, o.pager)}</div>`;
}
document.addEventListener("change", e => {
  const s = e.target.closest ? e.target.closest("select[data-size-key]") : null;
  if (!s) return;
  setListSize(s.dataset.sizeKey, s.value);
  const a = PAGER_ACT.get(s.dataset.sizeGo);
  if (a) a.size(Number(s.value));
});

/* ---- 部署后自动换新 UI（第十五轮）：SPA 只改 hash 不会重新拉 app.js，
   旧标签页会一直跑升级前的界面（用户报「你也没改」的另一半真因）。
   这里用静态资源的 Last-Modified 当版本号，变了就自己刷新一次。 ---- */
let _uiBuild = null;
async function checkUiBuild() {
  try {
    const r = await fetch("/app.js?probe=" + Date.now(), { method: "HEAD", cache: "no-store" });
    const tag = r.headers.get("last-modified") || r.headers.get("etag") || "";
    if (!_uiBuild) { _uiBuild = tag; return; }
    if (tag && tag !== _uiBuild) {
      _uiBuild = tag;
      toast("检测到新版界面，正在自动刷新…");
      setTimeout(() => location.reload(), 500);
    }
  } catch (e) { /* 服务重启的瞬间取不到，下一轮再试 */ }
}
setInterval(checkUiBuild, 30000);
document.addEventListener("visibilitychange", () => { if (!document.hidden) checkUiBuild(); });

const badge = (text, cls) => `<span class="badge b-${cls}">${esc(text)}</span>`;
/**
 * 信封依据怎么向用户自述——单点：YApi 维护弹屏和只读预览两处共用，色别也与这里一致。
 * call-site/framework-contract 都是盘上读出来的事实（绿），fallback-minimal 才是推的（琥珀）。
 */
function envBasisView(d) {
  const env = (d && d.envelope) || {};
  if (d && d.envelopeMode === "base5")
    return { badge: badge("老基准固定 5 项", "amber"), fact: true };
  if (env.basis === "call-site") return { badge: badge("按调用点证据", "green"), fact: true };
  if (env.basis === "framework-contract")
    return { badge: badge(`按${env.framework || "框架"}框架契约`, "green"), fact: true };
  return { badge: badge("兜底推断", "amber"), fact: false };
}
/** ③ 面板参数说明的语气色：与徽章同一套语义（绿＝会进 SQL、黄＝要小心、蓝＝库里回传） */
const PF_TIP_COLOR = { blue: "#2563eb", amber: "var(--amber)", green: "var(--green)" };
const riskBadge = r => r === "high" ? badge("高风险", "red") : r === "medium" ? badge("中风险", "amber") : badge("低风险", "green");
const statusBadge = s => ({
  untested: badge("未测", "gray"), partial: badge("部分测", "amber"),
  tested: badge("已测", "green"), skipped: badge("跳过", "blue"),
  draft: badge("草稿", "gray"), confirmed: badge("已确认", "green"),
  pass: badge("通过", "green"), fail: badge("失败", "red"),
  // 用例复核状态（第 15 轮：用例目录要有中文状态，不再露英文码）
  pending: badge("待评审", "gray"), approved: badge("已启用", "green"),
  suspect: badge("需复核", "amber"), deprecated: badge("已废弃", "gray"),
}[s] || badge(s, "gray"));

function fmtParams(v) { try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); } }

/* 动态表单：按 JSON Schema 渲染（string/number/integer/boolean/enum/body对象）
   samples = 后端按 DefaultValueRules 现算的预填值（按形参名分键）；缺失时退回前端本地骨架
   note    = 后端现算的「这条卡为什么没业务参数」（服务端带 <b> 标签，是它自己的话，不再前端转义） */
function renderSchemaForm(schema, samples, note) {
  const props = (schema && schema.properties) || {};
  const required = (schema && schema.required) || [];
  const bodyNames = Object.entries(props).filter(([, ps]) => ps.in === "body").map(([n]) => n);
  const flatKey = bodyNames.length === 1 ? "@flat" : null;   // 单一请求体：按真实 JSON 形状摊平，不再套一层形参名
  let html = "";
  for (const [name, ps] of Object.entries(props)) {
    const reqMark = required.includes(name) ? ' <span style="color:var(--red)">*</span>' : "";
    if (ps.in === "body" || ps.type === "object" || ps.type === "array") {
      const isBody = ps.in === "body";
      const dataKey = isBody && flatKey ? flatKey : name;
      const own = samples && samples[name] !== undefined;
      const skel = JSON.stringify(own ? samples[name] : bodySkeleton(name, ps), null, 2);
      html += `<div class="form-item"><label>${esc(name)}（JSON${reqMark}）</label>
        <textarea class="code" data-body="${esc(dataKey)}" data-param="${esc(name)}">${esc(skel)}</textarea>
        ${isBody ? bodyFieldTable(ps, own ? samples[name] : null, note) : ""}</div>`;
    } else if (ps.type === "boolean") {
      html += `<div class="form-item"><label>${esc(name)}${reqMark}</label>
        <select data-q="${esc(name)}"><option value=""></option><option value="true">true</option><option value="false">false</option></select></div>`;
    } else {
      const options = ps.enum || ps.options;
      if (options && options.length) {
        html += `<div class="form-item"><label>${esc(name)}（枚举）${reqMark}</label>
          <select data-q="${esc(name)}" data-in="${esc(ps.in || "query")}">${options.map(o => `<option value="${esc(o)}">${esc(o)}</option>`).join("")}</select></div>`;
      } else {
        html += `<div class="form-item"><label>${esc(name)}（${esc(ps.type || "string")}·${esc(ps.in || "query")}）${reqMark}</label>
          <input data-q="${esc(name)}" data-in="${esc(ps.in || "query")}" data-type="${esc(ps.type || "string")}" placeholder="${esc(ps.default != null ? "默认 " + ps.default : (ps.description || ""))}"></div>`;
      }
    }
  }
  return html || `<div class="empty">该接口无参数</div>` + paramNote(note, "");
}

/** 学习到的请求体 → 预填骨架：对象展开字段、数组给一个代表元素、自由结构留空对象待补填 */
function bodySkeleton(name, ps) {
  const t = ps.type || "string";
  if (t === "array") {
    const it = ps.items || {};
    return [Object.keys(it.properties || {}).length ? bodySkeleton(name, it) : sampleLeaf(name, it)];
  }
  if (ps.properties && Object.keys(ps.properties).length) {
    const out = {};
    for (const [k, v] of Object.entries(ps.properties)) out[k] = bodySkeleton(k, v);
    return out;
  }
  if (t === "object") return {};
  return sampleLeaf(name, ps);
}
function sampleLeaf(name, ps) {
  if (ps.default != null) return ps.default;
  const n = (name || "").toLowerCase(), t = ps.type || "string";
  if (n.startsWith("page") || n.includes("offset") || n.includes("pageno") || n.includes("pageindex")) return "1";
  if (t !== "integer" && t !== "number") {
    if (n.endsWith("time")) return "2026-01-01 00:00:00";
    if (n.endsWith("date")) return "2026-01-01";
  }
  if (t === "integer") return 1;
  if (t === "number") return 1.5;
  if (t === "boolean") return false;
  if (n.endsWith("code") || n.endsWith("id") || n.includes("code")) return "TEST";
  return "test";
}

/** 字段说明清单：恒显，标出每个字段的来源、置信度与预填值（代码学来的 vs 需人工补填）
    note = 后端现算的那句「为什么没业务参数」（带 <b>，服务端自己的话）；没有就退回通用话 */
function bodyFieldTable(ps, sample, note) {
  const rows = [];
  collectFieldRows(ps.properties || {}, "", rows, 0);
  const free = rows.filter(r => r.needFill);
  const head = `<div class="muted" style="font-size:12px;margin-top:4px">自动构建入参 · 学习自代码，共 ${rows.length} 个字段`
    + (free.length ? ` · <span style="color:var(--red)">${free.length} 处需按业务补填</span>` : "")
    + ` · <span style="color:#16a34a">绿色为规则/学习给出的值，可直接改；「（需手填）」是占位值</span></div>`;
  if (!rows.length) return paramNote(note, "未学习到请求体结构（形参类型不在本仓库或无字段），请按业务手工填写");
  return head + `<table style="font-size:12px;margin-top:4px"><tr><th>字段</th><th>类型</th><th>说明</th><th>预填值</th><th>来源</th></tr>`
    + rows.slice(0, 60).map(r => {
      const v = sampleAt(sample, r.path);
      const cell = v == null || v === "" ? `<span style="color:var(--red)">（需手填）</span>`
        : `<code style="color:${v.real ? "#16a34a" : "#94a3b8"}">${esc(v.text)}</code>`;
      return `<tr><td class="wrap"><code>${esc(r.path)}</code></td><td>${esc(r.type)}</td>
        <td class="wrap">${esc(r.desc || "-")}</td><td>${cell}</td>
        <td style="color:${r.color}">${esc(r.from)}</td></tr>`;
    }).join("")
    + `</table>` + (rows.length > 60 ? `<div class="muted" style="font-size:12px">仅显示前 60 个字段</div>` : "")
    + paramNote(note, "");
}
/** 「为什么没学到参数」那一行：后端现算、恒显；note 里的标签是服务端自己写的，不再转义 */
function paramNote(note, fallback) {
  const text = note ? String(note) : String(fallback || "");
  if (!text) return "";
  return `<div class="muted" style="font-size:12px;margin-top:4px;color:var(--amber)">${text}</div>`;
}
/** 取预填样例里这个路径的值；标出它是不是 test/TEST 这类无意义占位 */
function sampleAt(obj, path) {
  let cur = obj;
  for (const seg of String(path).split(".")) {
    if (cur && typeof cur === "object") cur = Array.isArray(cur) ? cur[0] : cur[seg];
    else return null;
  }
  if (cur == null || cur === "") return null;
  if (typeof cur === "object") return null;
  const text = String(cur);
  const placeholder = /^(test|TEST|2026-01-01( 00:00:00)?)$/.test(text);
  return { text, real: !placeholder };
}
function collectFieldRows(props, prefix, out, depth) {
  if (depth > 3) return;
  for (const [name, ps] of Object.entries(props || {})) {
    if (!ps || typeof ps !== "object") continue;
    const path = prefix ? prefix + "." + name : name;
    const inferred = ps.inferred === true;
    const child = ps.properties;
    // 数组的字段长在 items 上（REQUESTS → [元素] → REQ_COMM_DATA → USER_ID）：
    // 不下钻就只剩一行 "REQUESTS array"，用户看到的还是"啥参数也没学到"（第四十四轮）。
    // 路径不再加一段：sampleAt 遇到数组就取第一个元素，正好对上预填的那份骨架。
    const itemChild = (ps.items || {}).properties;
    if (!child && itemChild && Object.keys(itemChild).length) {
      out.push({ path, type: "array", desc: ps.description || "数组（下面列的是元素里的字段，预填按第一个元素）",
        from: inferred ? "代码取值 · 数组元素见下" : "DTO 字段", color: inferred ? "#b45309" : "#2563eb" });
      collectFieldRows(itemChild, path, out, depth + 1);
      continue;
    }
    if (child && Object.keys(child).length) {
      if (ps.freeForm) out.push({ path, type: "object", desc: ps.description || "自由结构，键名由取值代码反推",
        from: "取值反推", color: "#b45309" });
      collectFieldRows(child, path, out, depth + 1);
      continue;
    }
    if (ps.freeForm || ps.type === "object") {
      out.push({ path, type: "object", desc: ps.description || "", from: "需按业务补填", color: "#dc2626", needFill: true });
      continue;
    }
    if (inferred) {
      const conf = ps.confidence === "high" ? "高" : ps.confidence === "medium" ? "中" : "低";
      out.push({ path, type: ps.type || "string", desc: ps.description || "",
        from: `代码取值 · 置信度${conf}`, color: ps.confidence === "high" ? "#16a34a" : ps.confidence === "medium" ? "#b45309" : "#64748b" });
      continue;
    }
    out.push({ path, type: ps.type === "array" ? "array" : (ps.type || "string"),
      desc: ps.description || (ps.hint || ""), from: "DTO 字段", color: "#2563eb" });
  }
}
function collectForm(root) {
  const params = { path: {}, query: {}, body: {} };
  root.querySelectorAll("[data-q]").forEach(inp => {
    let v = inp.value;
    if (v === "") return;
    if (inp.dataset.type === "integer" || inp.dataset.type === "number") v = Number(v);
    if (inp.dataset.in === "path") { params.path[inp.dataset.q] = v; }
    else { params.query[inp.dataset.q] = v; }
  });
  root.querySelectorAll("[data-body]").forEach(ta => {
    let v;
    try { v = JSON.parse(ta.value || "{}"); } catch (e) { v = ta.value; }
    if (ta.dataset.body === "@flat") {
      params.body = (v && typeof v === "object") ? v : {};   // 整个请求体就是这段 JSON，不再套形参名
    } else {
      params.body[ta.dataset.body] = v;
    }
  });
  return params;
}

/* ---------- 分发框架调用（第 10 轮：一个 URL 承载全部业务服务的老工程） ----------
   这类接口卡本身没有入参，参数按服务码存在项目级字典里，这里只负责选码 + 生成前端同款信封。
   第四十一轮补的第二种形态：mode="call-path"（一个功能号一条真实路径，如统一访问转发中心）——
   路径与功能号都写死在这张卡上，面板不再给"选码"，只按框架契约把请求体拼出来（N57）。 */
let dsp = null;

function dispatchFormHtml(schema, itf) {
  const d = schema.dispatch;
  const cp = d.mode === "call-path";   // 一码一条真实路径的那种框架（转发中心）
  dsp = { projectId: itf.project_id, entry: itf.path, spec: d, kind: cp ? String(d.kind || "service") : "service",
    code: cp ? String(d.code || "") : "", codeName: "", params: [], evidence: [],
    plan: null };   // 第四十六轮：读取期服务端算的那份「报文头发什么值 + 这张卡学到哪些入参」
  const fixed = [];
  String(d.fixedQuery || "").split("&").forEach(kv => {
    const i = kv.indexOf("=");
    if (i > 0) fixed.push([kv.slice(0, i), kv.slice(i + 1)]);
  });
  const hdrCount = Object.keys(d.hdrTemplate || {}).length;
  // 自动等于功能号的那几个头键（"$code" 占位）：文案只报实际会填的键，不再写死某一家框架的字段名
  const autoHdr = Object.entries(d.hdrTemplate || {}).filter(([, v]) => v === "$code").map(([k]) => k);
  return `
  <div class="form-item" style="border:1px solid #c7d2fe;background:#f5f7ff;border-radius:10px;padding:10px 12px">
    <div class="row spread" style="align-items:center">
      <label style="font-weight:600;color:#3730a3">📮 分发调用 · ${esc(d.framework)}${cp ? " · 一码一路径" : ""}</label>
      <span class="row" style="gap:8px;align-items:center"><span class="muted" id="dspStat" style="font-size:12px">服务字典规模读取中…</span>
        <button class="small ghost" onclick="rebuildDispatchDict()">↻ 重建服务字典</button></span></div>
    <div class="muted" style="font-size:12px;margin:6px 0">
      ${cp
        ? `<b>${esc(itf.path)}</b> 就是这个功能号自己的请求路径（不是"一个地址承载全部服务"），所以这里没有信封、也没有可选的其它代码。
          <br>请求体按<b>这个仓自己的源码</b>拼：报文头是 <code>${esc(d.hdrKey)}</code>${d.dataKey
            ? `，业务参数平铺在 <code>${esc(d.dataKey)}</code> 里`
            : "，业务参数平铺在请求体根上（这个请求体类自己继承自由结构，键名由取值代码决定）"}；${
              autoHdr.length
                ? `${autoHdr.map(k => `<code>${esc(d.hdrKey)}.${esc(k)}</code>`).join(" / ")} 必须等于功能号（切面拿它查参数声明做补默认值与非空校验）`
                : `报文头里哪些键要发什么值，由【下方那张报文头清单】在打开面板时现算：这个框架自己写死的值优先，`
                  + `空着的键按<b>跨项目默认值规则表</b>补（op_code／op_role 这类＝1，server_id／msg_id 这类＝本次功能号），`
                  + `规则和都没命中的就留空串（要功能号而这次没有功能号的那几个键，会在下面点名说清是哪一个规矩缺输入）`
                  + `——所以这里不会预先替你断言"哪一格等于功能号"`}
          报文头里其余的公共参数与业务参数分别见下方两张清单——都是按这个仓自己的源码学的，所以不同模块的公共参数可以不一样。面板打开时已回填，值可以照旧手改。`
        : `${esc(itf.path)} 一个地址承载全部业务服务，所以接口卡上没有入参；先选业务代码，再【构建通用入参】生成与前端一致的请求信封。
          <br>⚠ 这类接口 HTTP 恒为 200，业务成败看返回码：建议在下方断言里加一条 L2 JSONPath
          <code>$.RESPONSES[0].RESP_COMM_DATA[0].IRETCODE</code> = 0，否则失败也会显示「通过」。`}
      ${cp ? `<br>⚠ 这类接口的返回形态由各自的控制器决定（同一批里就有两种），断言请按实际返回写，别照抄别家的 JSONPath。` : ""}
    </div>
    ${cp ? `<label>功能号（这条路径自带，不用选）</label>
      <div class="row" style="gap:8px"><input id="dspCodeLabel" readonly style="flex:1" value="${esc(dsp.kind)} = ${esc(d.code || "")}">
        <button class="small ghost" onclick="applyDispatchCode(dsp.code, dsp.codeName)">↻ 按字典重新回填</button></div>`
      : `<label>调用方式</label>
      <label class="f" style="display:block;color:#2563eb"><input type="radio" name="dspKind" value="service" checked onchange="dspKindChanged()">
        service · 老式服务代码（入参来自 metaData serviceConf 声明）</label>
      <label class="f" style="display:block;color:#7c3aed"><input type="radio" name="dspKind" value="bex" onchange="dspKindChanged()">
        bex · BEX 业务代码（信封会自动补 ${esc(d.serviceKey)}=${esc(d.autoService)}，与前端 ka.ajax 行为一致）</label>
      <div class="row mt" style="gap:8px">
        <input id="dspCodeLabel" readonly style="flex:1" placeholder="尚未选择业务代码（代码量大，点右侧弹屏搜索）">
        <button class="small" onclick="pickDispatchTarget()">🔍 选择代码</button></div>`}
    <div class="row mt" style="gap:10px;align-items:center">
      <button onclick="buildDispatchEnvelope()">🧩 ${cp ? "构建请求体" : "构建通用入参"}</button>
      <button class="small ghost" onclick="saveDispatchCase()">💾 存为用例</button>
      <span class="muted" id="dspHint" style="font-size:12px">${cp
        ? `请求体形状：<code>${esc(d.hdrKey)}</code>${d.dataKey ? ` + <code>${esc(d.dataKey)}</code>（业务参数平铺在 ${esc(d.dataKey)} 里` : " + 根上的业务键（这个请求体类继承自由结构，业务参数不在子对象里）"}，${esc(d.contentType)}）`
        : `信封结构：${esc(d.rootKey)} → ${esc(d.hdrKey)} + ${esc(d.dataKey)}（业务参数平铺在 ${esc(d.dataKey)} 里）`}</span></div>
    <div id="dspFields" class="mt"></div>
  </div>
  ${fixed.map(([k, v]) => `<div class="form-item"><label>${esc(k)}（分发框架固定参数 · query）</label>
    <input data-q="${esc(k)}" data-in="query" value="${esc(v)}"></div>`).join("")}
  <div class="form-item"><label>请求体（${cp ? "按框架契约拼" : "通用信封"} · ${hdrCount} 个报文头字段${cp
    ? `（${esc((d.hdrFields || []).length ? "清单按这个仓自己的类学出来" : "这套信封的模板由适配器给")}；${
        autoHdr.length ? autoHdr.map(k => `<code>${esc(k)}</code>`).join(" / ") + " 自动等于上面的功能号" : ""
        }发出去的值与出处见下方报文头清单：框架自己声明的值优先，空着的键按跨项目默认值规则表补，都没命中的留空串）`
    : "发出去的值见下方报文头清单：这个框架自己写死的值优先，空着的键按跨项目默认值规则表补（操作员类字段＝1，功能号类字段＝本次代码）"}）</label>
    <textarea class="code" data-body="@flat" id="dspBody"></textarea></div>`;
}

function dspKindChanged() {
  const el = document.querySelector('input[name=dspKind]:checked');
  if (!dsp || !el || dsp.kind === el.value) return;
  dsp.kind = el.value;
  // service 码与 bex 码不通用：换了方式就把已选码和回填一并清掉，避免把 bex 码当 service 码发出去
  dsp.code = ""; dsp.codeName = ""; dsp.params = []; dsp.evidence = []; dsp.out = null; dsp.plan = null;
  const label = $("#dspCodeLabel"); if (label) label.value = "";
  drawDispatchFields();
  renderDispatchBody();
}

function dispatchEnvelope(d, kind, code, params) {
  const plan = dsp && dsp.plan;
  const hdr = {};
  if (plan && plan.headers && plan.headers.length) {
    // 服务端读取期算好的那份（框架声明的字面量优先 → 默认值规则表补空 → 都没命中留空串；
    // 功能号类键已经换成<b>本次</b>码）。读不到就退回下面按模板现算，老面板照旧能用。
    for (const h of plan.headers) hdr[h.key] = h.value == null ? "" : h.value;
  } else {
    // "$code" 占位（KJDP 的 SERVER_ID/MSG_ID、转发中心的 server_id）换成当前业务代码；没选代码时留空，不发假值
    for (const [k, v] of Object.entries(d.hdrTemplate || {})) hdr[k] = v === "$code" ? (code || "") : v;
  }
  const data = {};
  if (d.mode === "call-path") {
    // 一码一路径：报文头那一段 + 业务负载（形状由适配器的 callSpec 按这个仓自己的 @RequestBody 推）
    for (const p of params || []) {
      if (data[p.key] != null) continue;
      data[p.key] = dispatchSample(p);
    }
    const body = {};
    body[d.hdrKey] = hdr;
    // dataKey 为空＝这个请求体类自己继承自由结构（Map/JSONObject），业务键直接落在根上，塞进子对象服务端读不到
    if (d.dataKey) body[d.dataKey] = data;
    else Object.assign(body, data);
    // 这张卡自己学到的入参也要默认拼进请求体（第四十六轮：学到却没拼进去，屏上就成了"参数没进包"）。
    // 服务端那份点路径是相对<b>请求体根</b>给的（报文头段与业务负载段都在根上），所以这里铺在 body 上：
    // 铺进 data 会把 req_comm_data.xxx 套成 req_comm_data.req_comm_data.xxx。
    for (const k of (plan && plan.bodyKeys) || []) dspSetPath(body, k.path, dispatchSample(k));
    return body;
  }
  if (kind === "bex") {
    data[d.bexKey] = code;
    if (d.autoService) data[d.serviceKey] = d.autoService;
  } else {
    data[d.serviceKey] = code;
  }
  for (const p of params || []) {
    if (data[p.key] != null) continue;
    data[p.key] = dispatchSample(p);
  }
  const req = {};
  req[d.hdrKey] = hdr;
  req[d.dataKey] = data;
  const body = {};
  body[d.rootKey] = [req];
  return body;
}

/**
 * 把点路径（{@code head.opCode} / {@code list[].jsbh} / {@code tags[]}）写进骨架对象。
 * 元素是对象时给一个占位元素（{@code list: [{}]}），纯量数组留空数组——都是空骨架，不编值。
 */
function dspSetPath(obj, path, val) {
  if (!obj || !path) return;
  const tokens = [];
  String(path).split(".").forEach(seg => {
    if (!seg) return;
    if (seg.endsWith("[]")) tokens.push({ key: seg.slice(0, -2), arr: true });
    else tokens.push({ key: seg });
  });
  if (!tokens.length) return;
  let cur = obj;
  for (let i = 0; i < tokens.length - 1; i++) {
    const t = tokens[i];
    if (!t.key) continue;
    if (t.arr) {
      if (!Array.isArray(cur[t.key]) || !cur[t.key].length) cur[t.key] = [{}];
      cur = cur[t.key][0];
    } else {
      if (typeof cur[t.key] !== "object" || cur[t.key] === null || Array.isArray(cur[t.key])) cur[t.key] = {};
      cur = cur[t.key];
    }
  }
  const last = tokens[tokens.length - 1];
  if (!last.key) return;
  cur[last.key] = last.arr ? [] : val;
}

/** 回填值：学习/字典给的 def 优先（命中默认值规则表的也在这里），其余按类型给空值待人工补 */
function dispatchSample(p) {
  const def = p.def == null ? "" : String(p.def);
  if (def === "") return "";
  if (p.type === "number" || p.type === "integer") {
    const n = Number(def);
    return Number.isNaN(n) ? def : n;
  }
  return def;
}

function renderDispatchBody() {
  const ta = $("#dspBody");
  if (!ta || !dsp) return;
  ta.value = JSON.stringify(dispatchEnvelope(dsp.spec, dsp.kind, dsp.code, dsp.params), null, 2);
  fitTextarea(ta);
}

/**
 * 「这一格的值是哪来的」只有这几个值，界面按这套认（服务端 DispatchBodyPlanService 的 FROM_* 一一对应）：
 * schema 学习/人工给的 · framework 框架自己声明的 · rule 规则表补的常量 ·
 * rule-code／framework-code 规则或框架要求"等于本次功能号"且这次拿得到 ·
 * rule-code-missing／framework-code-missing <b>要求功能号但这次没有</b>：值照空串发，但原因必须摆出来，
 * 不退成 none（那会被读成"没这条规矩"，下游就按字段名现编一个值发出去，比留空更坏）。
 */
const FROM_LABEL = {
  schema: { text: "学习/人工", color: "#0891b2" },
  framework: { text: "框架声明", color: "#0891b2" },
  rule: { text: "默认值规则表", color: "#16a34a" },
  "rule-code": { text: "默认值规则表＝本次功能号", color: "#7c3aed" },
  "framework-code": { text: "框架声明＝本次功能号", color: "#7c3aed" },
  "rule-code-missing": { text: "规则表要求＝本次功能号，但这次没有功能号", color: "#b45309" },
  "framework-code-missing": { text: "框架要求＝本次功能号，但这次没有功能号", color: "#b45309" },
};
/** 出处标签：认得出来的才着色，认不出来（老数据、缺字段）就什么也不标，绝不猜成某一种 */
function fromMark(from) {
  const f = FROM_LABEL[from];
  return f ? ` <span style="font-size:11px;color:${f.color}">${f.text}</span>` : "";
}

function drawDispatchFields() {
  const box = $("#dspFields");
  if (!box || !dsp) return;
  if (!dsp.code) {
    // 没选码（普通接口卡上的分发标记缺失等）时，报文头那份清单照给——它是这张卡自己的事实
    box.innerHTML = dsp.plan ? hdrFieldBlock(dsp.spec) : "";
    return;
  }
  const ps = dsp.params || [];
  const learned = ((dsp.plan && dsp.plan.bodyKeys) || [])
    .filter(k => !ps.some(p => p.key === k.path || p.key === k.key));
  const all = ps.concat(learned);
  const needFill = all.filter(p => p.def == null || String(p.def) === "").length;
  const ruleN = all.filter(p => p.defFrom === "rule" || p.defFrom === "rule-code").length
    + ((dsp.plan && dsp.plan.headers) || []).filter(h => h.from === "rule" || h.from === "rule-code").length;
  const srcCell = p => p.source === "metaData"
    ? `<span style="color:#2563eb">metaData 声明</span>`
    : p.source === "chain" ? `<span style="color:#7c3aed">调用链</span>`
      : p.source ? `<span style="color:#b45309">前端调用点</span>`
        : `<span style="color:#0891b2">这张卡学到的</span>`;
  box.innerHTML = `${hdrFieldBlock(dsp.spec)}
    <div class="muted" style="font-size:12px">${esc(dsp.kind)} = ${esc(dsp.code)}${dsp.codeName ? " · " + esc(dsp.codeName) : ""}
      · 业务参数 ${all.length} 个（服务字典 ${ps.length} 个 · 这张卡自己学到 ${learned.length} 个）
      · <span style="color:#0891b2">已全部拼进下方请求体</span>${needFill ? ` · <span style="color:var(--red)">${needFill} 个没默认值需按业务补填</span>` : ""}${
        ruleN ? ` · <span style="color:#16a34a">${ruleN} 个值来自跨项目默认值规则表</span>` : ""}</div>
    <table style="font-size:12px;margin-top:4px"><tr><th>业务参数</th><th>类型</th><th>中文说明</th><th>回填值</th><th>来源</th></tr>
      ${all.slice(0, 60).map(p => {
        const val = p.def == null || String(p.def) === ""
          ? `<span style="color:var(--red)">（空）</span>` : `<code>${esc(p.def)}</code>`;
        const mark = fromMark(p.defFrom);
        return `<tr><td><code>${esc(p.path || p.key)}</code></td><td>${esc(p.type || "string")}</td>
        <td class="wrap">${esc(p.label || p.name || "-")}</td>
        <td>${val}${mark}</td>
        <td>${srcCell(p)}${p.via ? `<div class="muted" style="font-size:11px">${esc(String(p.via).slice(0, 90))}</div>` : ""}</td></tr>`;
      }).join("")
      || `<tr><td colspan="5" class="empty">字典里这个代码没有入参（metaData 未声明、前端调用点没写字面量、调用链也没取到值），请按业务手工补</td></tr>`}
    </table>
    ${all.length > 60 ? `<div class="muted" style="font-size:12px">仅显示前 60 个</div>` : ""}
    ${learned.length && dsp.spec.mode !== "call-path"
      ? `<div class="muted" style="font-size:11px;margin-top:3px">「这张卡学到的」这一档只在<b>一码一条真实路径</b>那种框架才拼进请求体：`
        + `信封式框架的卡是入口卡，它学到的字段属于别的服务码，塞进这次的红包里就是假契约。</div>` : ""}
    ${dsp.evidence && dsp.evidence.length ? `<div class="muted" style="font-size:12px;margin-top:4px">代码依据：${dsp.evidence.map(esc).join(" ┃ ")}</div>` : ""}
    ${outParamBlock(dsp.out)}`;
}

/**
 * 报文头公共参数区（第四十二轮补：用户「其它公共参数又没了，比方说 op_code 什么的，不同模块公共参数不一样啊」；
 * 第四十六轮补：这一区同时也是<b>发出去的值</b>的出处清单）。
 *
 * <p>清单不是这里写死的，是学习期按<b>这张卡所在那个仓</b>的源码学出来的（哪个类声明了 req_msg_hdr
 * 这个字段，就读那个类的成员），所以换一个框架、换一套键，界面跟着变。卡上没有 hdrFields
 * （老知识包、或那个仓根本没有这样的头类）时整段不出现——不凭空造清单。
 *
 * <p>「发出去的值」这一列读服务端读取期现算的那份（{@code dsp.plan.headers}）：<b>框架自己声明的字面量
 * 优先 → 空值按跨项目默认值规则表补 → 都没命中留空串</b>，每行标出是哪一种，所以"这个 1 是哪儿来的"
 * 当场看得见，而不是界面替它编一个。服务端那份读不到时退回卡上的模板自己算，老行为一字不变。
 */
function hdrFieldBlock(spec) {
  const rows = (dsp && dsp.plan && dsp.plan.headers) || [];
  const fs = rows.length ? rows : ((spec && spec.hdrFields) || []);
  if (!fs.length) return "";
  const tpl = (spec && spec.hdrTemplate) || {};
  const labeled = fs.filter(f => f.label).length;
  const via = (fs.find(f => f.via) || {}).via || "";
  const ruleKeys = fs.filter(f => f.from === "rule" || f.from === "rule-code").map(f => f.key);
  // 「要等于本次功能号、这次没有」的那几个键：值照空串发，但每一格都要点名，不许看着像"没这条规矩"
  const missCodeKeys = fs.filter(f => f.from === "rule-code-missing" || f.from === "framework-code-missing");
  const valueCell = f => {
    if (!rows.length) {   // 没有服务端那份时的旧算法：只认模板里的 "$code" 与字面量
      const v = tpl[f.key];
      return v === "$code" ? `<span style="color:#7c3aed">自动＝功能号 ${esc(String(dsp.code || ""))}</span>`
        : v != null && String(v) !== "" ? `<code>${esc(String(v))}</code>`
          : `<span style="color:var(--red)">（空，按业务在手改的请求体里填）</span>`;
    }
    if (f.value === "" || f.value == null) {
      const m = FROM_LABEL[f.from];
      return m ? `<span style="color:#b45309">（空）${m.text}</span>`
        : `<span style="color:var(--red)">（空，按业务在手改的请求体里填）</span>`;
    }
    if (f.from === "framework-code") return `<span style="color:#7c3aed">自动＝功能号 <code>${esc(f.value)}</code></span>（框架声明）`;
    if (f.from === "rule-code") return `<span style="color:#7c3aed">自动＝功能号 <code>${esc(f.value)}</code></span>（默认值规则表）`;
    if (f.from === "rule") return `<code>${esc(f.value)}</code>（默认值规则表）`;
    if (f.from === "schema") return `<code>${esc(f.value)}</code>（学习/人工）`;
    return `<code>${esc(f.value)}</code>（框架声明）`;
  };
  return `<div class="mt" style="border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff;font-size:12px">
      <b style="color:#5b21b6">报文头公共参数 ${fs.length} 个</b>
      <span class="muted">· 按这个仓自己的类学出来的（<code>${esc(via)}</code>），不同模块各学各的，不是抄别家的清单</span>
      ${ruleKeys.length ? `<div class="muted" style="font-size:11px;margin-top:2px">其中 <b style="color:#16a34a">${ruleKeys.length} 个键的空值由跨项目默认值规则表补</b>：${
        ruleKeys.map(k => `<code>${esc(k)}</code>`).join(" · ")}</div>` : ""}
      ${missCodeKeys.length ? `<div style="font-size:11px;margin-top:2px;color:#b45309">另有 ${missCodeKeys.length} 个键<b>按规矩要等于本次功能号，而这一次没有功能号</b>（${
        missCodeKeys.map(k => `<code>${esc(String(k.key))}</code>`).join(" · ")}）：这一格发的是空串，不是"没这条规矩"——先在上面的功能号那一栏挑一个码，或自己填这个码</div>` : ""}
      <table style="font-size:12px;margin-top:4px"><tr><th>报文头字段</th><th>类型</th><th>中文说明</th><th>发出去的值</th></tr>
        ${fs.map(f => `<tr><td><code>${esc(f.key)}</code></td><td>${esc(f.type || "string")}</td>
          <td class="wrap">${esc(f.label || "") || `<span style="color:#b45309">（那个类里没写注释，学不到）</span>`}</td>
          <td>${valueCell(f)}</td></tr>`).join("")}
      </table>
      ${labeled < fs.length ? `<div class="muted" style="font-size:11px;margin-top:3px">这 ${fs.length - labeled} 个键在那个类里<b>没写注释</b>，所以只有名、没有中文说明（盘上事实，不编）。
        要补说明：请求体直接手改，或在 [接口文档 → YApi 待推送预览] 的入参表里改一次（人工覆盖，重学不冲掉）。</div>` : ""}
      <div class="muted" style="font-size:11px;margin-top:2px">这些键已经拼进下方【请求体】，可以照旧手改；【构建请求体】按同一份清单重拼（重拼会回到这份默认值，手改的值会被覆盖，执行前请核对）。</div>
    </div>`;
}


/**
 * 出参区（第卅五轮改口径）：先按**真实代码**推算（bexConf 的 attr1 → mapper 语句 → 盘上的存储过程/包源码），
 * viewConf 的 col_item 声明只作补源——用户口径「几乎没有几个接口会把出入参配置到 xml 里面」。
 *
 * <p>三个必须恒显的事实：① 每个字段是谁给的（徽章＝代码认出 / 代码推断 / viewConf 声明，带文件行号）；
 * ② 整条依据链（这一步是从哪句话推出来的）；③ 认不出的地方与原因。绝不空表冒充「没有出参」。
 *
 * <p>changed：本次改动碰到的字段 → added/removed/modified，传进来就按变动类型着色——
 * 用户要的是「预览整个接口 + 变动的参数特殊颜色区分」，不是两张互不相干的表。
 */
const CF_LABEL = { high: "代码认出", low: "代码推断", inner: "内层语句", star: "列名要连库", declared: "viewConf 声明" };
const CF_COLOR = { high: "#16a34a", low: "#d97706", inner: "#d97706", star: "#dc2626", declared: "#2563eb" };

function outParamBlock(o, changed) {
  if (!o) return "";
  const outs = o.outputs || [], ins = o.inputs || [], vs = o.views || [];
  const chain = o.chain || [], unres = o.unresolved || [], from = o.from || {};
  const head = `<div class="muted" style="font-size:11px;margin-top:2px">口径：真实代码认出 ${Number(from.code || 0)} 个字段
      · viewConf 声明 ${Number(from.viewConf || 0)} 个（其中只有声明、代码没认出的 ${Number(from.onlyViewConf || 0)} 个）
      · 读取期现算，不落库</div>`;
  const chainBox = chain.length ? `<details class="mt" open style="font-size:11px"
      ><summary class="muted">依据链（${chain.length} 步：代码 → 语句 → 过程体）</summary>
      ${chain.slice(0, 40).map(s => `<div class="wrap" style="margin-top:2px;border-left:3px solid ${CF_COLOR[s.conf] || "#94a3b8"};padding-left:6px">
        ${esc(s.text || "")}${s.file ? ` ｜ <code>${esc(s.file)}${s.line ? ":" + esc(String(s.line)) : ""}</code>` : ""}
      </div>`).join("")}${chain.length > 40 ? `<div class="muted">仅列前 40 步（共 ${chain.length} 步）</div>` : ""}</details>` : "";
  const unresBox = unres.length ? `<div class="mt" style="border-left:3px solid #d97706;padding:6px 10px;background:#fffbeb;font-size:12px">
      <b style="color:#b45309">这些位置按代码没能认出字段（${unres.length} 处，逐条照实列）</b>
      ${unres.slice(0, 20).map(u => `<div class="wrap" style="margin-top:3px">· ${esc(u.reason || "")}${u.file
        ? ` ｜ <code>${esc(u.file)}${u.line ? ":" + esc(String(u.line)) : ""}</code>` : ""}</div>`).join("")}
      ${unres.length > 20 ? `<div class="muted" style="font-size:11px">仅列前 20 处（共 ${unres.length} 处）</div>` : ""}</div>` : "";

  if (!outs.length && !ins.length) {
    return `<div class="mt" style="border-left:3px solid #f59e0b;padding:6px 10px;background:#fffbeb;font-size:12px">
      <b>出参：按代码与 viewConf 都没认出字段</b>
      <div class="wrap" style="margin-top:4px">${esc(o.note || "没读到这个代码的出入参声明")}</div>
      ${head}${unresBox}${chainBox}</div>`;
  }
  const ch = changed || {};
  const row = f => {
    const c = ch[String(f.field || "").toLowerCase()];
    const col = c ? (PI_COLOR[c] || "#334155") : "";
    const cf = CF_LABEL[f.conf] ? f.conf : (f.via && String(f.via).indexOf("viewConf") >= 0 ? "declared" : "low");
    return `<tr${c ? ` style="background:${c === "removed" ? "#fef2f2" : c === "modified" ? "#fffbeb" : "#f0fdf4"}"` : ""}>
      <td><code${col ? ` style="color:${col};font-weight:700"` : ""}>${esc(f.field)}</code>
        ${c ? `<span class="badge" style="background:${col};color:#fff">${esc(PI_LABEL[c] || c)}</span>` : ""}</td>
      <td class="wrap">${esc(f.title || "-")}${f.titleFrom ? `<span class="muted" style="font-size:11px">（${esc(f.titleFrom)}）</span>` : ""}</td>
      <td>${esc(f.type || "-")}</td>
      <td class="wrap"><span class="badge" style="background:${CF_COLOR[cf]};color:#fff">${esc(CF_LABEL[cf] || cf)}</span>
        <span class="muted" style="font-size:11px">${esc(f.via || "")}</span>
        ${f.file ? `<div><code style="font-size:11px">${esc(f.file)}${f.line ? ":" + esc(String(f.line)) : ""}</code></div>` : ""}</td></tr>`;
  };
  const nCh = rows => rows.filter(f => ch[String(f.field || "").toLowerCase()]).length;
  const tbl = (rows, color) => `<table style="font-size:12px;margin-top:4px">
      <tr><th style="color:${color}">字段</th><th style="color:${color}">中文说明</th><th style="color:${color}">控件类型</th><th style="color:${color}">来源与证据</th></tr>
      ${rows.slice(0, 80).map(row).join("")}</table>
      ${rows.length > 80 ? `<div class="muted" style="font-size:12px">仅显示前 80 个（共 ${rows.length} 个）</div>` : ""}`;
  const marked = nCh(outs) + nCh(ins);
  return `<div class="mt" style="border-left:3px solid #16a34a;padding:6px 10px;background:#f0fdf4;font-size:12px">
      <b style="color:#166534">出参 ${outs.length} 个字段</b>
      <span class="muted">· ${esc(String(o.kind || ""))} ${esc(String(o.code || ""))}：按 bexConf / serviceConf → mapper 语句 → 盘上过程体推，viewConf 声明只补中文说明</span>
      ${o.note ? `<div class="wrap muted" style="font-size:11px;margin-top:2px">${esc(o.note)}</div>` : ""}
      ${head}
      ${marked ? `<div class="muted" style="font-size:11px;margin-top:2px">着色＝本次改动碰到的字段（共 ${marked} 个）：<b style="color:#16a34a">新增</b> · <b style="color:#dc2626">删除</b> · <b style="color:#d97706">就地改</b>；没着色的这次没动</div>` : ""}
      ${tbl(outs, "#166534")}
      ${ins.length ? `<div class="mt" style="font-size:12px"><b style="color:#2563eb">入参 ${ins.length} 个</b>
        <span class="muted">（过程形参 / SQL 绑定 / 动态条件判定变量，与上方字典学到的业务参数互为印证）</span>${tbl(ins, "#2563eb")}</div>` : ""}
      ${unresBox}${chainBox}
      ${vs.length > 1 ? `<div class="muted" style="margin-top:4px">viewConf 里有 ${vs.length} 个视图声明绑到这个代码：${vs.map(v => esc((v.viewId || "") + " · " + (v.file || ""))).join(" ┃ ")}</div>` : ""}
    </div>`;
}

async function applyDispatchCode(code, name) {
  dsp.code = code;
  dsp.codeName = name || "";
  const label = $("#dspCodeLabel");
  if (label) label.value = `${dsp.kind} = ${code}${dsp.codeName ? " · " + dsp.codeName : ""}`;
  const r = await api(`/api/dispatch/target?projectId=${dsp.projectId}&kind=${encodeURIComponent(dsp.kind)}`
    + `&code=${encodeURIComponent(code)}&path=${encodeURIComponent(dsp.entry || "")}`);
  if (!r.ok) {
    dsp.params = []; dsp.evidence = []; dsp.out = null; dsp.plan = null;
    toast(r.error, true);
  } else {
    dsp.params = r.params || [];
    dsp.evidence = r.evidence || [];
    dsp.out = r.outParams || null;   // 出入参是读取期按盘上代码推算的（viewConf 只补源），跟字典一起给
    // 第四十六轮：报文头发什么值 + 这张卡自己学到哪些入参，同一次查询一起给（服务端一个单点算）
    dsp.plan = r.bodyPlan && r.bodyPlan.found ? r.bodyPlan : null;
  }
  drawDispatchFields();
  renderDispatchBody();
  // 计数按"拼进请求体的业务键"说，不按字典条数说——否则用户看到 3 个，包里却有 5 个键
  const inBody = new Set((dsp.params || []).map(p => p.key));
  ((dsp.plan && dsp.plan.bodyKeys) || []).forEach(k => {
    if (dsp.spec.mode === "call-path") inBody.add(k.path);
  });
  return inBody.size;
}

async function buildDispatchEnvelope() {
  if (!dsp) return;
  if (!dsp.code) {
    renderDispatchBody();
    return toast(dsp.spec.mode === "call-path"
      ? "这张卡上没有功能号（字典里这条码没学到代码），已生成只含报文头键的请求体，值请手填"
      : "未选业务代码：已生成空信封（REQ_COMM_DATA 里的 " + dsp.spec.serviceKey + " 待填），点【🔍 选择代码】可自动回填业务参数", true);
  }
  const n = await applyDispatchCode(dsp.code, dsp.codeName);
  toast(n ? `已${dsp.spec.mode === "call-path" ? "构建请求体" : "构建信封"}并回填 ${n} 个业务参数（${dsp.kind} ${dsp.code}）`
    : `已构建${dsp.spec.mode === "call-path" ? "请求体" : "信封"}：${dsp.kind} ${dsp.code} 没有学到入参，请按业务手工补`);
}

function pickDispatchTarget() {
  if (!dsp) return;
  const kind = dsp.kind;
  const kindLabel = kind === "bex" ? "BEX 业务代码" : "服务代码";
  const m = modal(`<h3>选择${kindLabel} · ${esc(dsp.spec.framework)}</h3>
    <div class="muted mb">字典有三处证据：后端 metaData 声明、前端 JS 调用点、代码调用链（service→业务→bex/atom）；按代码或中文名搜索，留空看全部</div>
    <div class="row" style="gap:8px"><input id="dpQ" style="flex:1" placeholder="如 12020001 / 员工单位查询">
      <button class="small" id="dpGo">搜索</button></div>
    <table style="margin-top:10px"><tr><th>代码</th><th>名称</th><th>入参</th><th>证据</th><th>操作</th></tr><tbody id="dpRows"><tr><td class="empty" colspan="5">加载中…</td></tr></tbody></table>
    <div id="dpPager" class="pager-host"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
  const st = { page: 1, q: "", total: 0 };
  const rowsEl = m.querySelector("#dpRows"), pagerEl = m.querySelector("#dpPager");
  const drawPager = () => { pagerEl.innerHTML = pagerBar({ key: "pickerDict", total: st.total, page: st.page, pager: "pickerDict" }); fitControls(pagerEl); };
  pagerDefine("pickerDict", p => { st.page = p; refresh(); }, () => { st.page = 1; refresh(); });
  async function refresh() {
    rowsEl.innerHTML = `<tr><td class="empty" colspan="5">加载中…</td></tr>`;
    const size = listSize("pickerDict");
    let url = `/api/dispatch/targets?projectId=${dsp.projectId}&kind=${kind}&page=${st.page}&size=${size}`;
    if (st.q) url += "&q=" + encodeURIComponent(st.q);
    const r = await api(url);
    const rows = r.targets || [];   // kind 由服务端过滤（第 35 轮起）：这里再筛一遍会让"每页 N 条"实际少于 N
    st.total = r.total || 0;
    const pages = Math.max(1, Math.ceil(st.total / size));
    if (st.page > pages) { st.page = pages; return refresh(); }
    drawPager();
    rowsEl.innerHTML = rows.length ? rows.map(t => `<tr>
        <td><code>${esc(t.code)}</code></td><td class="wrap">${esc(t.name || "-")}</td>
        <td>${t.paramCount ? `<span style="color:#16a34a">${t.paramCount} 个</span>` : `<span class="muted">无</span>`}</td>
        <td class="muted">${esc(sourceLabel(t.sources))}</td>
        <td><button class="small ghost" data-code="${esc(t.code)}" data-name="${esc(t.name || "")}">选</button></td></tr>`).join("")
      : `<tr><td class="empty" colspan="5">字典里没有匹配的代码（可换关键字，或点【↻ 重建服务字典】）</td></tr>`;
    rowsEl.querySelectorAll("button[data-code]").forEach(btn => btn.onclick = async () => {
      const row = rows.find(x => x.code === btn.dataset.code);
      m.remove();
      await applyDispatchCode(row.code, row.name);
      toast("已选中 " + kind + " " + row.code + (row.name ? " · " + row.name : "") + "，点【构建通用入参】写入请求体");
    });
  }
  m.querySelector("#dpGo").onclick = () => { st.q = m.querySelector("#dpQ").value.trim(); st.page = 1; refresh(); };
  m.querySelector("#dpQ").addEventListener("keydown", e => { if (e.key === "Enter") m.querySelector("#dpGo").click(); });
  refresh();
}

/** 证据源字符串（后端 sourcesOf 用 + 拼，三源可任意组合）→ 中文标签，不在前端再写死组合 */
function sourceLabel(s) {
  const label = { metaData: "元数据声明", js: "前端调用", chain: "调用链" };
  const parts = String(s || "").split("+").map(x => label[x.trim()] || x.trim()).filter(Boolean);
  return parts.length ? parts.join(" + ") : "元数据声明";
}

async function loadDispatchStatus() {
  if (!dsp) return;
  const el = $("#dspStat");
  if (!el) return;
  const r = await api("/api/dispatch/status?projectId=" + dsp.projectId).catch(() => null);
  if (!r || !r.ok) { el.textContent = "服务字典状态读取失败"; return; }
  el.textContent = r.detected
    ? `服务字典 ${r.targets} 个代码（框架：${r.framework || "未标注"}）· ${r.withParams} 个带入参（${r.learnedByChain || 0} 个来自调用链）· ${r.calledByFrontend} 个被前端真实调用`
    : "字典为空（点右侧重建，或先学习该仓库）";
}

async function rebuildDispatchDict() {
  if (!dsp) return;
  toast("重建中…（扫描 metaData 与前端调用点）");
  const r = await api("/api/dispatch/rebuild", { projectId: dsp.projectId }, null, { progress: true });
  if (!r.ok) return toast(r.error, true);
  if (r.notice) toast(r.notice, true);
  else toast(`字典已重建：${r.targets} 个代码（${r.withParams} 个带入参，其中 ${r.fromCallSites} 个来自前端调用点）`);
  if (r.loginFlowNote) toast(r.loginFlowNote);
  (r.warnings || []).slice(0, 2).forEach(w => toast(w, true));
  loadDispatchStatus();
}

/** 分发调用存成用例：用例名自带服务码，否则几千个业务代码在用例列表里分不清 */
async function saveDispatchCase() {
  if (!dsp || !dsp.code) return toast("先选一个业务代码再存用例（用例名要带服务码）", true);
  const name = `${String(dsp.entry || "").replace(/^\//, "")} · ${dsp.kind}=${dsp.code}${dsp.codeName ? " " + dsp.codeName : ""}`;
  const r = await api("/api/cases/manual", {
    targetType: "interface", targetId: state.labIfcId, name,
    scenario: `分发调用 ${dsp.kind}=${dsp.code}`,
    params: collectForm($("#labForm")), origin: "dispatch"
  });
  if (!r.ok) return toast(r.error, true);
  toast("用例已保存：" + name);
  loadLabIfc(true);
}

/* ============ 视图注册 ============ */
const views = {};
let state = { projects: [], projectId: null };

async function loadProjects() {
  state.projects = (await api("/api/projects")).projects || [];
  if (!state.projectId && state.projects.length) state.projectId = state.projects[0].id;
}
const projectSelect = (onchange) => `
  <select id="projSel">${state.projects.map(p =>
    `<option value="${p.id}" ${p.id == state.projectId ? "selected" : ""}>${esc(p.name)}</option>`).join("")}</select>`;
function bindProjectSelect() {
  const sel = $("#projSel");
  if (sel) sel.addEventListener("change", () => {
    state.projectId = Number(sel.value);
    if (state.caseFilter) state.caseFilter.commit = null;
    // 切项目要把上一个项目的 YApi 体检结论一起清掉：留着就是拿别的环境的话糊这一格
    ypCfgNote = ""; ypCfgNoteProj = 0;
    route();
  });
}

/* ---------- 看板 ---------- */
views.dashboard = async () => {
  const { stats } = await api("/api/stats");
  const runs = await api("/api/test-runs?size=8");
  const cov = stats.coverage || 0;
  const commits = (await api("/api/commits?size=100")).commits || [];
  const runRows = runs.runs || [];
  return `
  <div class="grid cols4">
    <div class="stat"><div class="num">${stats.interfaces}</div><div class="lbl">接口卡</div></div>
    <div class="stat"><div class="num">${stats.dbObjects}</div><div class="lbl">SQL / 数据对象</div></div>
    <div class="stat"><div class="num">${stats.testCases}</div><div class="lbl">测试用例</div></div>
    <div class="stat ${stats.runFail ? "red" : "green"}"><div class="num">${stats.runPass}/${stats.runPass + stats.runFail}</div><div class="lbl">执行通过/总数</div></div>
  </div>
  <div class="grid cols2 mt">
    <div class="card"><h3>🛡 测试覆盖率（已测改动占比）</h3>
      <div style="background:#e2e8f0;border-radius:999px;height:14px;overflow:hidden">
        <div style="width:${cov}%;height:100%;background:linear-gradient(90deg,#6366f1,#059669)"></div></div>
      <div class="muted mt">${cov}% · 已测 ${stats.tested} / 总改动 ${stats.changes}（未测 ${stats.untested}）</div>
    </div>
    <div class="card"><h3>🔥 高风险未测改动</h3>
      ${(() => { const rows = commits.filter(c => c.risk_level === "high" && c.untested_count > 0).slice(0, 5);
        return rows.length ? rows.map(c => `<div class="row spread" style="padding:6px 0;border-bottom:1px solid #f1f5f9">
          <span>${esc(c.message)} <span class="muted">· ${esc(c.repo_name || "")}</span></span>${riskBadge(c.risk_level)}</div>`).join("")
        : `<div class="empty">暂无高风险未测改动 🎉</div>`; })()}
    </div>
  </div>
  <div class="card"><h3>🧾 最近执行记录
      <span class="row" style="float:right;gap:8px"><button class="small ghost" onclick="openRuns({},'最近执行记录')">📜 查看全部与筛选</button></span></h3>
    ${runRows.length ? `<table><tr><th>#</th><th>时间</th><th>归属</th><th>目标</th><th>方式</th><th>结果</th></tr>
      ${runRows.map(x => `<tr><td>${x.id}</td>
        <td class="muted" style="white-space:nowrap">${esc(fmtTime(x.executed_at))}</td>
        <td>${x.case_id ? esc(x.case_name || ("用例 #" + x.case_id)) : badge("直测", "blue")}</td>
        <td class="wrap">${esc(x.target_name || (x.target_type + " #" + x.target_id))}</td>
        <td class="muted">${esc(MODE_CN[x.mode] || x.mode || "")}</td>
        <td>${statusBadge(x.status)}${bizBadge(x.biz_error_code, x.biz_error_count)}<span class="muted" style="font-size:11px"> ${x.latency_ms}ms</span></td></tr>`).join("")}</table>`
      : `<div class="empty">还没有任何执行记录：到「接口测试台」或「SQL 直测」点【▶ 执行】，或在「知识库 → 🧪 用例」行内点【▶ 执行这条】</div>`}
    <div class="muted mt" style="font-size:12px">直测（未挂用例）只留这一笔痕，不计入「执行通过/总数」与覆盖率</div>
  </div>
  <div class="card"><h3>🕒 最近提交（更新点留痕）</h3>
    ${commits.length ? `<table><tr><th>时间</th><th>仓库</th><th>作者</th><th>说明</th><th>风险</th><th>状态</th></tr>
      ${commits.slice(0, 10).map(c => `<tr>
        <td class="muted">${esc(fmtTime(c.committed_at))}</td><td>${esc(c.repo_name || "")}</td>
        <td>${esc(c.author || "")}</td><td>${esc(c.message)}</td>
        <td>${riskBadge(c.risk_level || "low")}</td>
        <td>${c.untested_count == 0 && c.change_count > 0 ? statusBadge("tested") : c.untested_count > 0 ? statusBadge("untested") : badge("-", "gray")}</td>
      </tr>`).join("")}</table>` : `<div class="empty">暂无提交，去「项目与仓库」添加并同步</div>`}
  </div>`;
};

/* ---------- 项目与仓库 ---------- */
views.projects = async () => {
  await loadProjects();
  const repos = (await api("/api/repos?projectId=" + state.projectId)).repos || [];
  return `
  <div class="row spread mb"><div class="row">${projectSelect()}<button class="ghost" onclick="views.projectsEditProject()">＋ 新建项目</button></div>
    <span class="muted">学习 = 扫描代码生成接口/SQL 知识；同步 = 拉取 Git/SVN 更新并留痕</span></div>
  <div class="card" id="testCard"><h3>🩺 可测性体检（学完到底能不能测，逐条给结论）
      <span class="row" style="float:right;gap:8px"><label class="muted" style="font-size:12px;font-weight:400">
        <input type="checkbox" id="tbDeep" onchange="loadTestability(this.checked)"> 深度体检（真实打地址、连库）</label>
        <button class="small ghost" onclick="loadTestability(false)">↻ 重新体检</button></span></h3>
    <div id="testBody"><div class="empty">体检中…</div></div>
  </div>
  <div class="card"><h3>📦 仓库列表（一个项目可挂多个目录：前端 / 中台 / 后台 / 数据库，可随时增挂并单独学习）</h3>
    ${repos.length ? `<table><tr><th>名称</th><th>类型</th><th>VCS</th><th>本地路径</th><th>远程</th><th>服务地址</th><th>接口卡</th><th>SQL卡</th><th>提交</th><th>学习状态</th><th>操作</th></tr>
      ${repos.map(r => `<tr><td>${esc(r.name)}</td>
        <td>${r.repo_type === "backend" ? badge("后台", "indigo") : r.repo_type === "frontend" ? badge("前端", "blue") : r.repo_type === "middle" ? badge("中台", "amber") : badge("数据库", "green")}</td>
        <td>${badge(r.vcs_type.toUpperCase(), "gray")}</td>
        <td class="wrap muted">${esc(r.local_path)}</td>
        <td class="wrap muted">${esc(r.url || "仅本地")}</td>
        <td class="wrap">${r.base_url ? `<span style="color:var(--green)">${esc(r.base_url)}</span>` : `<span class="muted">未配置（走项目环境）</span>`}</td>
        <td>${r.iface_count || 0}</td>
        <td>${r.db_count || 0}</td>
        <td>${r.commit_count || 0}</td>
        <td id="learnState-${r.id}">${r.learned_at ? badge("已学习 " + esc(fmtTime(r.learned_at)), "green") : badge("未学习", "gray")}</td>
        <td><button class="small" onclick="doSync(${r.id})">同步</button>
            <button class="small ghost" onclick="doLearn(${r.id})">学习</button>
            <button class="small ghost" onclick="views.projectsEditRepo(${r.id})">编辑</button>
            <button class="small danger" onclick="doDelRepo(${r.id})">删除</button></td></tr>`).join("")}</table>`
      : `<div class="empty">还没有仓库，点击下方添加（可先只学习一个目录，其余后续增挂）</div>`}
    <div class="mt"><button onclick="views.projectsEditRepo()">＋ 添加仓库目录</button></div>
  </div>`;
};
views.projectsEditProject = () => {
  const m = modal(`<h3>新建项目</h3>
    <div class="form-item"><label>项目名</label><input id="pName"></div>
    <div class="form-item"><label>描述</label><input id="pDesc"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button>
    <button id="pSave">保存</button></div>`);
  m.querySelector("#pSave").onclick = async () => {
    const r = await api("/api/projects", { name: m.querySelector("#pName").value, description: m.querySelector("#pDesc").value });
    if (r.ok) { m.remove(); toast("已创建"); route(); } else toast(r.error, true);
  };
};
views.projectsEditRepo = async (id) => {
  const repos = (await api("/api/repos?projectId=" + state.projectId)).repos || [];
  const r = repos.find(x => x.id === id) || {};
  const m = modal(`<h3>${id ? "编辑" : "添加"}仓库</h3>
    <div class="grid cols2">
      <div class="form-item"><label>名称</label><input id="rName" value="${esc(r.name || "")}"></div>
      <div class="form-item"><label>类型</label><select id="rType">
        ${["backend", "frontend", "middle", "database"].map(t => `<option value="${t}" ${r.repo_type === t ? "selected" : ""}>${{ backend: "后台", frontend: "前端", middle: "中台/服务", database: "数据库" }[t]}</option>`).join("")}</select></div>
      <div class="form-item"><label>VCS 类型</label><select id="rVcs">
        <option value="git" ${r.vcs_type === "git" ? "selected" : ""}>Git</option>
        <option value="svn" ${r.vcs_type === "svn" ? "selected" : ""}>SVN</option></select></div>
      <div class="form-item"><label>分支 / SVN 相对路径</label><input id="rBranch" value="${esc(r.branch || "")}"></div>
    </div>
    <div class="form-item"><label>本地路径（必填）</label><input id="rPath" value="${esc(r.local_path || "")}" placeholder="D:\\code\\backend"></div>
    <div class="form-item"><label>远程地址（可选，填写后自动拉取更新）</label><input id="rUrl" value="${esc(r.url || "")}" placeholder="https://git.example.com/repo.git 或 svn://..."></div>
    <div class="form-item"><label>服务基础地址（Base URL，可选）</label><input id="rBaseUrl" value="${esc(r.base_url || "")}" placeholder="如 http://127.0.0.1:8080（该仓库服务单独启动时的访问地址）">
      <div class="muted" style="font-size:12px">填写后，接口测试台选中该仓库的接口时可切「🟩 仓库直连」：请求直接打到这个地址、不跑登录流；留空则只能走项目环境全流程。</div></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button>
    <button id="rSave">保存</button></div>`);
  m.querySelector("#rSave").onclick = async () => {
    const body = { id: id || null, projectId: state.projectId, name: m.querySelector("#rName").value,
      repoType: m.querySelector("#rType").value, vcsType: m.querySelector("#rVcs").value,
      branch: m.querySelector("#rBranch").value, localPath: m.querySelector("#rPath").value, url: m.querySelector("#rUrl").value,
      baseUrl: m.querySelector("#rBaseUrl").value.trim() };
    const res = await api("/api/repos", body);
    if (res.ok) { m.remove(); toast("已保存"); route(); } else toast(res.error, true);
  };
};
async function doSync(id) {
  const r = await api(`/api/repos/${id}/sync`, {}, null, { progress: true });
  toast(r.ok ? `同步完成：${r.report.newCommits} 新提交 / ${r.report.newChanges} 文件变更` : r.error, !r.ok);
  route();
}
/** 学习是异步任务：POST 秒回，活儿在后台跑几分钟。徽标里带上真实阶段，别让人对着"学习中…"干等。 */
function learnStageText(p) {
  if (!p || !p.found) return "";
  const total = Number(p.total) || 0;
  if (total > 0) return `${p.label} ${Number(p.done)}/${total}`;
  return p.note ? `${p.label} · ${p.note}` : String(p.label || "");
}
async function doLearn(repoId) {
  const pid = newPid();
  const r = await api("/api/learn?pid=" + pid, { projectId: state.projectId, repoId });
  if (!r.ok) return toast(r.error, true);
  toast("已提交后台学习（不阻塞页面），进度看仓库行徽标");
  const cell = () => document.getElementById("learnState-" + repoId);
  if (cell()) cell().innerHTML = badge("学习中…", "amber");
  const timer = setInterval(async () => {
    const t = await api("/api/learn/task/" + r.taskId).catch(() => null);
    const c = cell();
    if (c && t && t.status === "running") {
      const p = await fetch("/api/progress?id=" + pid).then(x => x.json()).catch(() => null);
      const st = learnStageText(p);
      c.innerHTML = badge(st || "学习中…", "amber");
      c.title = st ? "点其它地方不会打断学习；这一行会一直更新到学完" : "";
    }
    if (!t || !t.ok || t.status === "running") return;
    clearInterval(timer);
    if (t.status === "done") {
      const x = t.result || {};
      toast(`学习完成：接口 ${x.interfacesCreated}+${x.interfacesUpdated}u · SQL ${x.dbObjectsCreated} · ${x.llmEnriched ? "LLM已增强" : "降级模式"}`);
      route();
    } else {
      if (c) c.innerHTML = badge("学习失败", "red");
      toast("学习失败：" + (t.error || "未知错误"), true);
    }
  }, 1500);
}
async function doDelRepo(id) {
  if (!confirm("删除仓库及其同步留痕（提交、逐文件改动、归因缓存）？\n学习到的接口卡不会被删，只解掉仓库归属。")) return;
  const r = await api(`/api/repos/${id}`, null, "DELETE");
  if (!r.ok) { toast(r.error || "删除失败", true); return; }
  route();
  toast(`仓库已删除：同步留痕一并清掉，接口卡解绑保留 ${r.detachedInterfaces || 0} 张（在知识库筛「未归属仓库」可看到）`);
}

/* ---------- 可测性体检（第 11 轮：学完就能测的硬标准，十项恒显） ---------- */
const TB_ICON = { ok: "🟢", warn: "🟡", fail: "🔴", skip: "⚪" };

async function loadTestability(deep) {
  const box = document.getElementById("testBody");
  if (!box || !state.projectId) return;
  box.innerHTML = `<div class="empty">${deep ? "深度体检中…（真实打 Base URL、真连数据库，内网不可达时最长约 10 秒）" : "体检中…"}</div>`;
  const r = await api("/api/testability?projectId=" + state.projectId + (deep ? "&deep=true" : ""),
    undefined, null, { progress: true }).catch(() => null);
  if (!r || !r.ok) { box.innerHTML = `<div class="result-fail">体检失败：服务无响应</div>`; return; }
  box.innerHTML = testCardHtml(r, !!deep);
}

function testCardHtml(r, deep) {
  const color = r.fail > 0 ? "var(--red)" : r.warn > 0 ? "#b45309" : "var(--green)";
  const icon = r.fail > 0 ? "🔴" : r.warn > 0 ? "🟡" : "🟢";
  // 直达目标就是本页时不做"跳自己"：hash 不变 → 看着像点了没反应；navTo 会硬重渲染、把勾选和滚动位置清掉。
  // 这里直接说明"就在本页"，按钮只出现在真能跳走的地方。
  const here = (location.hash.replace("#/", "") || "dashboard").split("?")[0];
  const rows = (r.items || []).map(it => {
    const go = !it.link ? "" : it.link === here
      ? `<span class="muted" style="font-size:12px">📍 就在本页</span>`
      : `<button class="small ghost" onclick="navTo('#/${esc(it.link)}')">去${esc(titles[it.link] || it.link)}</button>`;
    const fix = it.key === "dispatch" && (it.level === "fail" || it.level === "warn")
      ? `<button class="small" onclick="rebuildDictFromProject()">↻ 重建服务字典</button>` : "";
    return `<tr><td style="white-space:nowrap">${TB_ICON[it.level] || "⚪"} ${esc(it.label)}</td>
      <td class="wrap">${esc(it.fact)}${it.fix ? `<div class="muted" style="font-size:12px">怎么补：${esc(it.fix)}</div>` : ""}</td>
      <td style="white-space:nowrap">${go}${fix}</td></tr>`;
  }).join("");
  return `<div class="row spread mb" style="align-items:center">
      <div><b style="color:${color};font-size:15px">${icon} 结论：${esc(r.verdict)}</b>
        <span class="muted" style="margin-left:10px">${esc(r.summary)} · 体检时间 ${esc(fmtTime(new Date()))}</span></div>
      <span class="muted" style="font-size:12px">${deep ? "已真实打地址与连库" : "轻量体检（未联网探测）"}</span></div>
    <table><tr><th>检查项</th><th>事实与怎么补</th><th>直达</th></tr>${rows}</table>
    <div class="muted mt" style="font-size:12px">每次【学习】结束会自动跑一遍轻量体检并把结论写进审计（action=learn.testability），所以学完不必手动点；改了环境、凭据或仓库后可点右上角【↻ 重新体检】。</div>`;
}

/** 项目页里直接重建字典（测试台那个按钮要先选中分发入口卡才可用，这里按项目重建） */
async function rebuildDictFromProject() {
  toast("重建中…（扫描 metaData 声明、前端调用点与调用链）");
  const r = await api("/api/dispatch/rebuild", { projectId: state.projectId }, null, { progress: true });
  if (!r.ok) return toast(r.error, true);
  if (r.notice) toast(r.notice, true);
  else toast(`字典已重建：${r.targets} 个代码（${r.withParams} 个带入参 · 前端调用点 ${r.fromCallSites} · 调用链 ${r.fromChain || 0}）`);
  if (r.loginFlowNote) toast(r.loginFlowNote);
  (r.warnings || []).slice(0, 2).forEach(w => toast(w, true));
  loadTestability(false);
}

/* ---------- 改动流 ---------- */
/* 第 16 轮：过滤条件全部放进 hash（切页/刷新/分享都不丢），列表改为服务端分页。
   原先固定拉 100 条提交、状态在浏览器里过滤——一页塞满屏幕，而且翻页时口径会错位。
   第 18 轮：每页条数交给统一的翻页条（默认取系统设置，本列表单独改过的记在浏览器本地）。 */
function changesHref(o) {
  const p = new URLSearchParams();
  if (o.st) p.set("st", o.st);
  if (o.repo) p.set("repo", String(o.repo));
  if (o.author) p.set("author", o.author);
  if (o.contains) p.set("contains", "1");
  if (Number(o.page) > 1) p.set("page", String(Number(o.page)));
  const s = p.toString();
  return "#/changes" + (s ? "?" + s : "");
}
/** 跳页/改每页条数用 JS 走（上下页仍是 <a href>，不点 JS 也能翻页的手动能力保留） */
function chGoTo(p) {
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  if (Number(p) > 1) st.set("page", String(Number(p))); else st.delete("page");
  const q = st.toString();
  navTo("#/changes" + (q ? "?" + q : ""));
}
pagerDefine("changes", chGoTo, () => chGoTo(1));
function chRepoGo(v) {
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  if (v) st.set("repo", v); else st.delete("repo");
  st.delete("author");   // 人名列表跟着仓库收窄：换仓库还留着上一个人，多半是一片空
  st.delete("page");     // 换仓库回到第一页，否则容易停在越界页码上
  const q = st.toString();
  navTo("#/changes" + (q ? "?" + q : ""));
}
function chAuthorGo(v) {
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  if (v) st.set("author", v); else st.delete("author");
  st.delete("page");
  const q = st.toString();
  navTo("#/changes" + (q ? "?" + q : ""));
}
/** 匹配方式：精确（默认）/ 包含。同一个人 Git 落姓名、SVN 落账号，两种写法差一个后缀，勾选才并到一起看 */
function chContainsGo(on) {
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  if (on) st.set("contains", "1"); else st.delete("contains");
  st.delete("page");
  const q = st.toString();
  navTo("#/changes" + (q ? "?" + q : ""));
}
/** 下拉里的「未记录」：Git 给 NULL、SVN 兜底写字面量问号，两条并成一个选项，与后端哨兵同一口径 */
function authorOptions(rows) {
  let none = 0;
  const named = [];
  (rows || []).forEach(a => {
    const v = String(a.author == null ? "" : a.author), n = Number(a.commit_count) || 0;
    if (!v.trim() || v === "?") none += n; else named.push({ author: v, commit_count: n });
  });
  return { named, none };
}
views.changes = async () => {
  await loadProjects();
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  const cur = { st: st.get("st") || "", repo: st.get("repo") || "", page: Math.max(1, Number(st.get("page") || 1)),
    author: st.get("author") || "", contains: st.get("contains") === "1" ? 1 : 0 };
  const repos = (await api("/api/repos?projectId=" + state.projectId)).repos || [];
  const authors = authorOptions((await api(`/api/commit-authors?projectId=${state.projectId}`
    + (cur.repo ? "&repoId=" + encodeURIComponent(cur.repo) : ""))).authors);
  const size = listSize("changes");
  const r = await api(`/api/commits?projectId=${state.projectId}&page=${cur.page}&size=${size}`
    + (cur.repo ? "&repoId=" + encodeURIComponent(cur.repo) : "")
    + (cur.st ? "&testStatus=" + encodeURIComponent(cur.st) : "")
    + (cur.author ? "&author=" + encodeURIComponent(cur.author) : "")
    + (cur.contains ? "&contains=1" : ""));
  const commits = r.commits || [];
  const total = Number(r.total) || 0;
  const chip = (o, label, cls) => `<a href="${changesHref(Object.assign({}, cur, o))}" class="badge ${cls}"
    style="text-decoration:none">${label}</a>`;
  // 精确匹配只认原文：同一个人换个写法（姓名 / 账号 / 带邮箱后缀）就是另一条，界面必须说清还剩哪些没进来
  const exact = cur.author && !cur.contains && cur.author !== "__none__";
  const others = exact ? authors.named.filter(x => x.author !== cur.author
    && (x.author.includes(cur.author) || cur.author.includes(x.author.split("@")[0]))).slice(0, 4) : [];
  const authorHint = exact ? `<div class="muted" style="font-size:11px;margin-top:3px;max-width:520px">
      当前按原文精确匹配「${esc(cur.author)}」。${others.length
        ? `另有 ${others.length} 种写法像是同一个人但没进来：${others.map(x => esc(x.author) + "（" + x.commit_count + "）").join("、")}——勾上右边「按包含」一起看。`
        : "同一人在 Git 里落姓名、在 SVN 里落账号，写法不同就是两条，需要一起看时勾「按包含」。"}</div>` : "";
  return `
  <div class="row spread mb"><div class="fbar">
    <div class="fgrp"><label class="f">项目</label>${projectSelect()}</div>
    <div class="fgrp"><label class="f">仓库</label>
      <select id="chRepo" onchange="chRepoGo(this.value)">${[`<option value="">全部仓库</option>`]
        .concat(repos.map(x => `<option value="${x.id}"${String(x.id) === String(cur.repo) ? " selected" : ""}>${esc(x.name)}（${x.commit_count || 0} 提交）</option>`)).join("")}
      </select></div>
    <div class="fgrp"><label class="f">改动人</label>
      <select id="chAuthor" onchange="chAuthorGo(this.value)" style="min-width:190px">
        <option value="">全部改动人（${authors.named.length + (authors.none ? 1 : 0)} 种写法）</option>
        ${authors.named.map(x => `<option value="${esc(x.author)}"${x.author === cur.author ? " selected" : ""}>${esc(x.author)}（${x.commit_count} 提交）</option>`).join("")}
        ${authors.none ? `<option value="__none__"${cur.author === "__none__" ? " selected" : ""}>（未记录）（${authors.none} 提交）</option>` : ""}
      </select>
      <label class="muted" style="font-size:11px;display:inline-flex;align-items:flex-start;gap:4px;max-width:230px;line-height:1.35">
        <input type="checkbox" id="chContains" style="width:auto;resize:none" ${cur.contains ? "checked" : ""}
          onchange="chContainsGo(this.checked)">按包含匹配（并上同一个人的其它写法）</label>
      ${authorHint}
    </div>
    <div class="fgrp"><label class="f">测试状态</label>
      <div class="row" style="gap:6px">
        ${chip({ st: "", page: 1 }, "全部", "b-gray")}
        ${chip({ st: "untested", page: 1 }, "全部未测", "b-amber")}
        ${chip({ st: "partial", page: 1 }, "部分已测", "b-indigo")}
        ${chip({ st: "tested", page: 1 }, "全部已测", "b-green")}
      </div></div></div>
    <span class="muted">共 ${total} 条提交 · 每页 ${size} 条（下方可改，最多 200）；更新点全部留痕，可只测关注项</span></div>
  <div class="card">
    ${commits.length ? commits.map(c => `
      <div style="border-bottom:1px solid #f1f5f9;padding:12px 0">
        <div class="row spread">
          <div class="row"><b>${esc(c.message)}</b><span class="muted">${esc(c.revision.slice(0, 8))} · ${esc(c.author || "")} · ${esc(fmtTime(c.committed_at))} · ${badge(c.repo_name || "未知仓库", "gray")}</span></div>
          <div class="row">${riskBadge(c.risk_level || "low")}
            ${c.untested_count > 0 ? badge(`未测 ${c.untested_count}/${c.change_count}`, "amber") : badge("全部已测", "green")}</div>
        </div>
        ${c.summary ? `<div class="muted mt" style="line-height:1.6">${esc(c.summary)}</div>` : `<div class="muted mt">（未生成摘要）</div>`}
        ${caseStatLine(c)}
        <div class="row mt">
          <button class="small ghost" onclick="showCommit(${c.id})">详情 / Diff</button>
          <button class="small" onclick="summarize(${c.id})">生成摘要</button>
          <button class="small ghost" onclick="genCases(${c.id}, true)">生成用例</button>
          <button class="small ghost" onclick="runRegression(${c.id})">⚡ 一键回归</button>
          <button class="small ghost" onclick="paramImpact(${c.id}, this)">📤 出入参影响</button>
          <button class="small ghost" onclick="markStatus(${c.id}, 'tested')">标已测</button>
          <button class="small ghost" onclick="markStatus(${c.id}, 'partial')">标部分测</button>
          <button class="small ghost" onclick="markStatus(${c.id}, 'skipped')">标跳过</button>
        </div>
      </div>`).join("") : `<div class="empty">${total ? "这一页没有提交，换个页码或清掉筛选条件"
        : (cur.repo || cur.st || cur.author ? "当前筛选条件下没有提交记录——把上面的仓库 / 改动人 / 状态切回「全部」再看看" : "没有提交记录，先到「项目与仓库」点【同步】")}</div>`}
    ${pagerBar({ key: "changes", total, page: cur.page, pager: "changes",
      href: p => changesHref(Object.assign({}, cur, { page: p })) })}
  </div>`;
};
async function summarize(id) { toast("生成摘要中…"); const r = await api(`/api/commits/${id}/summarize`, {}); toast(r.ok ? "摘要已更新" : r.error, !r.ok); route(); }
/** 第 15 轮：生成用例后必须当场回答「几条、去哪儿看」——toast 带条数和跳转，不再让人自己找 */
async function genCases(id, useLlm) {
  toast("生成用例中…");
  const r = await api(`/api/commits/${id}/generate-cases?useLlm=${useLlm}`, {}, null, { progress: true });
  if (!r.ok) return toast(r.error, true);
  const targets = r.targets || [];
  const created = targets.reduce((n, t) => n + (Number(t.created) || 0), 0);
  const total = Number(r.caseTotal) || 0;
  if (!targets.length) {
    return toast("这条提交的改动文件没命中任何已学习目标——改动文件既不是任何接口/SQL 卡的来源文件，也不在调用链记账里。"
      + "调用链记账是第四十四轮新增的，老仓库要点【项目与仓库 → 学习】重学一次才会有；仍不命中就在提交详情里确认改动路径", true);
  }
  // 只有真跳得过去才给跳转入口：第 16 轮用户报"查看用例点进去啥也看不到"，就是这里曾无条件给按钮
  const by = (() => { const line = targets.filter(t => t.basis === "line").length;
    const chain = targets.filter(t => t.basis === "chain").length;
    return line ? `按改动行命中 ${line} 条语句` : (chain ? `${chain} 个目标按调用链（服务层文件，可能偏宽）` : "按整文件归因"); })();
  if (total) {
    toast(`已为 ${targets.length} 个影响目标生成用例（${by}）：新增 ${created} 条，本提交现有 ${total} 条${created ? "" : "（同名用例已存在，未重复生成）"}`,
      false, { label: "🧪 点这里查看", onClick: () => gotoCases(id) });
  } else {
    toast(`扫到 ${targets.length} 个影响目标，但没有生成出用例（目标可能是前端/配置文件，不挂接口卡或 SQL 卡）`, true);
  }
  route();
}
/**
 * 提交行的用例统计：常驻一行（含 0 的情况），数字来自后端整页批量算，不额外发请求。
 * 第 17 轮：同时写明这些用例是"改动行落在具体语句"还是"整个文件的"——原先一个 mapper 改 1 行
 * 会挂上整文件几百张卡的用例，光给个总数没法判断数字合不合理。
 */
function caseStatLine(c) {
  const total = Number(c.case_count) || 0;
  const pass = Number(c.case_pass) || 0;
  const fail = Number(c.case_fail) || 0;
  const never = Math.max(0, total - pass - fail);
  const detail = total === 0 ? '<span class="muted">还没生成过</span>'
    : `<span style="color:var(--green)">通过 ${pass}</span> / <span style="color:var(--red)">失败 ${fail}</span> / <span class="muted">未跑 ${never}</span>`;
  const stmt = Number(c.case_stmt_targets) || 0;
  const file = Number(c.case_file_targets) || 0;
  const chain = Number(c.case_chain_targets) || 0;
  const anchors = (c.case_anchors || []).map(String);
  let scope = "";
  if (stmt || file || chain) {
    const parts = [];
    if (stmt) parts.push(`<b style="color:var(--green)">按改动行命中 ${stmt} 条语句</b>${anchors.length ? `（${esc(anchors.slice(0, 2).join("、"))}${stmt > anchors.length ? " …" : ""}）` : ""}`);
    if (chain) parts.push(`<b style="color:#b45309">${chain} 个目标按调用链</b>`);
    if (file) parts.push(`${file} 个目标按整文件`);
    scope = `<span class="muted" title="按调用链＝改的是这张卡调用链上的服务层／DAO 文件，按代码可达给出、可能偏宽；按整文件＝改动细不到语句（改的不是 SQL 映射文件，或这个版本的源码没法按行定位）">${parts.join(" + ")}</span>`;
  }
  return `<div class="row mt" style="gap:10px;font-size:12px;align-items:center">
    <span><b>本提交用例：${total}</b>（${detail}）</span>
    ${scope}
    ${c.case_run_at ? `<span class="muted">最近执行 ${esc(fmtTime(c.case_run_at))}</span>` : ""}
    ${total ? `<button class="small ghost" onclick="gotoCases(${c.id})">🧪 查看用例</button>`
      : `<span class="muted">点左边【生成用例】后这里会出现条数与入口</span>`}</div>`;
}
async function markStatus(id, status) { const r = await api(`/api/commits/${id}/test-status`, { status }, "PATCH"); toast(r.ok ? "标记成功" : r.error, !r.ok); route(); }
/**
 * 出入参影响（第卅四轮）：点按钮才现算——后端要逐个文件读盘上的 diff 快照，
 * 整页自动算会把改动流主入口拖慢（与「进面板才查」同一口径）。
 */
async function paramImpact(id, btn) {
  const host = btn && btn.closest ? btn.closest(".row.mt") : null;
  if (!host) return toast("找不到这条提交的行", true);
  let box = host.nextElementSibling;
  if (!box || !box.classList.contains("pi-host")) {
    box = document.createElement("div");
    box.className = "pi-host";
    host.after(box);
  }
  if (btn) { btn.disabled = true; btn.textContent = "计算中…"; }
  box.innerHTML = `<div class="muted" style="font-size:12px;padding:8px 0">正在逐个文件读改动快照并比对参数声明…</div>`;
  const r = await api(`/api/commits/${id}/param-impact`).catch(e => ({ ok: false, error: String(e) }));
  if (btn) { btn.disabled = false; btn.textContent = "📤 出入参影响"; }
  box.innerHTML = paramImpactHtml(r);
  fitControls(box);
}

const PI_COLOR = { added: "#16a34a", removed: "#dc2626", modified: "#d97706" };
const PI_LABEL = { added: "新增", removed: "删除", modified: "就地改" };

function piRow(t) {
  const col = PI_COLOR[t.change] || "#334155";
  return `<tr><td><code style="color:${col};font-weight:600">${esc(String(t.field))}</code></td>
    <td><span class="badge" style="background:${col};color:#fff">${esc(PI_LABEL[t.change] || String(t.change))}</span></td>
    <td class="wrap muted">${esc(String(t.via || ""))}</td>
    <td class="wrap muted" style="font-size:11px">${esc(String(t.file || ""))}</td></tr>`;
}

function paramImpactHtml(r) {
  if (!r || !r.ok) return `<div class="empty" style="padding:8px 0">${esc((r && r.error) || "没算出结果")}</div>`;
  const legend = `<div class="muted" style="font-size:11px;margin:2px 0 6px">颜色：<b style="color:#16a34a">新增</b> ·
      <b style="color:#dc2626">删除</b> · <b style="color:#d97706">就地改</b>（同一个字段这次改动里既被删又被加）</div>`;
  // 整仓导入型提交能扫出上千行，界面必须限量并说清"还有多少没列"，排序上先给能回指到功能号的声明行
  const declRank = t => /viewConf|bexConf/.test(String(t.via || "")) ? 0 : 1;
  const group = (rows, title, color) => {
    if (!rows.length) return "";
    const sorted = rows.slice().sort((a, b) => declRank(a) - declRank(b) || String(a.file).localeCompare(String(b.file)));
    return `<div style="margin-top:8px">
      <b style="color:${color};font-size:12px">${title} ${rows.length} 个</b>
      ${rows.length > 80 ? `<span class="muted" style="font-size:11px">（只列前 80 个：先排 viewConf / bexConf 声明行，再按文件；全量看本行【详情 / Diff】）</span>` : ""}
      <table style="font-size:12px;margin-top:4px"><tr><th>字段</th><th>变动</th><th>依据</th><th>文件</th></tr>
      ${sorted.slice(0, 80).map(piRow).join("")}</table></div>`;
  };
  const outs = r.outputs || [], ins = r.inputs || [], blocks = (r.unknown || []).filter(t => t.io === "block");
  const codes = r.codes || [], codesTotal = r.codesTotal ?? codes.length;
  const targets = r.involvedTargets || [];
  const tTotal = r.involvedTotal ?? targets.length, tLine = r.involvedLineTotal ?? targets.length;
  const tChain = Number(r.involvedChainTotal) || 0;
  const noPatch = r.noPatch || [];
  // 字段 → 本次变动类型：预览整个接口时按这个着色（按钮把属于自己那份带在 data 上，不靠全局状态）
  const chg = {};
  [...outs, ...ins].forEach(t => { chg[String(t.field || "").toLowerCase()] = t.change || "modified"; });
  // 「涉及哪些接口」一律用第 17 轮那份归因（与详情弹屏、一键回归同口径），这里只补 viewConf 才有的 service/bex 码
  const targetLine = tTotal
    ? `<div class="mt"><b style="font-size:12px">涉及的接口 / SQL ${tTotal} 个</b>
        <span class="muted" style="font-size:11px">（归因口径与【本提交影响目标】、一键回归完全一致：${tLine} 个按改动行命中语句、${tChain} 个按调用链${
        tChain ? "（服务层文件，可能偏宽）" : ""}、${tTotal - tLine - tChain} 个只能到整文件）</span>
        <table style="font-size:12px;margin-top:4px"><tr><th>类型</th><th>名称</th><th>命中依据</th><th>置信度</th></tr>
        ${targets.slice(0, 30).map(t => `<tr><td>${badge(t.type === "interface" ? "接口" : "SQL", t.type === "interface" ? "blue" : "gray")}</td>
          <td class="wrap">${esc(t.name || t.detail || "-")}</td>
          <td class="wrap muted">${t.basis === "line" ? `按改动行命中语句 ${esc(String(t.anchor || ""))}`
            : t.basis === "chain" ? `按调用链（改的是这张卡链上的服务层文件 ${esc(String(t.anchor || ""))}，可能偏宽）`
            : "整文件（该文件挂在这张卡上）"}</td>
          <td>${esc(t.confidence || "-")}</td></tr>`).join("")}</table>
        ${tTotal > 30 ? `<div class="muted" style="font-size:12px">这里只列前 30 个（接口卡排在 SQL 卡前，服务端最多回传 200 个，共 ${tTotal} 个），完整清单看本行【详情 / Diff】</div>` : ""}</div>`
    : `<div class="muted mt" style="font-size:12px">这次改动没有归因到任何接口卡 / SQL 卡（改的是前端、配置，或该仓库还没学到卡）</div>`;
  if (!outs.length && !ins.length && !blocks.length) {
    return `<div class="muted mt" style="font-size:12px;border-left:3px solid #cbd5e1;padding:6px 10px">
      <b>这次改动没碰到任何出入参声明</b>（扫了 ${r.scanned || 0} 个改动文件）。
      判据是 diff 里的字段声明行：viewConf 的 col_item / qry_item、bexConf 的 param、MyBatis 的大括号占位符与 as "别名"、resultMap 列、代码里的 put("键")。${legend}
      ${noPatch.length ? `<div style="margin-top:4px;color:#b45309">其中 ${noPatch.length} 个文件没有 diff 快照，没法判：${noPatch.slice(0, 5).map(esc).join("、")}${noPatch.length > 5 ? " …" : ""}——点【详情 / Diff】可按需拉取快照后重算</div>` : ""}
      ${targetLine}
    </div>`;
  }
  return `<div class="card" style="box-shadow:none;border:1px solid var(--line);margin-top:8px;padding:10px">
    <div class="row spread"><b style="font-size:13px">出入参影响：出参 ${outs.length} · 入参 ${ins.length}</b>
      <span class="muted" style="font-size:11px">按 ${r.scanned || 0} 个改动文件的 diff 快照现算</span></div>
    ${legend}
    ${group(outs, "出参变动", "#166534")}
    ${group(ins, "入参变动", "#1d4ed8")}
    ${blocks.length ? `<div class="muted mt" style="font-size:12px">另有 ${blocks.length} 处整块视图/服务声明被增删（字段清单整块变了）：${blocks.slice(0, 10).map(b => esc(b.file || "") + "（" + esc(PI_LABEL[b.change] || b.change) + "）").join("、")}${blocks.length > 10 ? ` …（其余 ${blocks.length - 10} 处见【详情 / Diff】）` : ""}</div>` : ""}
    ${codes.length ? `<div class="mt"><b style="font-size:12px;color:#7c3aed">涉及的接口代码 ${codesTotal} 个</b>
      <div class="muted" style="font-size:11px">命中粒度有两条判据，逐条标在「依据」列：① 声明行＝被改的字段与代码声明在同一个文件里（viewConf / bexConf 写着出入参，多数接口没有）；② 代码链＝改动文件里有某码调用的那条 MyBatis 语句，或改动文件定义了某码调用的存储过程／包。跨文件同名一律不算命中${codesTotal > 30 ? `；这里只列前 30 个（服务端最多回传 ${codes.length} 个），全量 ${codesTotal} 个看【详情 / Diff】` : ""}</div>
      <table style="font-size:12px;margin-top:4px"><tr><th>方式</th><th>代码</th><th>依据</th><th>被碰到的字段</th><th>操作</th></tr>
      ${codes.slice(0, 30).map(c => `<tr><td>${badge(c.kind === "bex" ? "bex" : "service", c.kind === "bex" ? "amber" : "indigo")}</td>
        <td><code>${esc(c.code)}</code></td>
        <td class="wrap muted" style="font-size:11px" title="${esc((c.fields || []).slice(0, 3).map(f => f.via || "").filter(Boolean).join("\n"))}">${esc(c.basis || "-")}</td>
        <td class="wrap">${(c.fields || []).slice(0, 20).map(f => `<code style="color:${f.io === "out" ? "#166534" : "#1d4ed8"}" title="${esc(f.via || "")}">${esc(f.field)}</code>`).join("、")}${(c.fields || []).length > 20 ? ` 等 ${c.fields.length} 个` : ""}</td>
        <td><button class="small ghost" data-changed="${esc(JSON.stringify(Object.fromEntries((c.fields || [])
            .map(f => [String(f.field || "").toLowerCase(), chg[String(f.field || "").toLowerCase()]])
            .filter(x => x[0] && x[1]))))}"
          onclick="previewOutParams('${esc(c.kind)}','${esc(c.code)}',this)">👁 预览整个接口出参</button></td></tr>`).join("")}
      </table><div id="piPreview${r.commitId}" class="mt"></div></div>`
      : `<div class="muted mt" style="font-size:12px">被改的字段没能回指到任何 service/bex 代码——两条判据都没命中：这个仓库既没有把出入参声明在同一个文件里的 viewConf / bexConf，改动文件里也找不到「被某码调用的 MyBatis 语句」或「被某码调用的存储过程」。出入参变动本身仍按上面的 diff 判（SQL 别名、resultMap、占位符），接口归属见下方归因清单</div>`}
    ${targetLine}
    ${noPatch.length ? `<div class="mt" style="font-size:12px;color:#b45309">${noPatch.length} 个文件没有 diff 快照，未参与判定：${noPatch.slice(0, 5).map(esc).join("、")}${noPatch.length > 5 ? " …" : ""}——点【详情 / Diff】可拉取后重算</div>` : ""}
  </div>`;
}

/**
 * 预览整个接口的出入参：改动流里的功能号常常没学习过（字典是学习时建的），所以先试字典端点，
 * 拿不到就退回 /api/dispatch/outparams——那条只读盘上代码，出参与依据链照样给。
 */
async function previewOutParams(kind, code, btn) {
  const hostId = btn ? btn.closest(".card") : null;
  const box = hostId ? hostId.querySelector("[id^='piPreview']") : null;
  if (!box) return toast("找不到预览区", true);
  box.innerHTML = `<div class="muted" style="font-size:12px">正在按盘上代码读 ${esc(kind)} ${esc(code)} 的出入参…</div>`;
  let name = "";
  let o = {};
  const r = await api(`/api/dispatch/target?projectId=${state.projectId}&kind=${encodeURIComponent(kind)}&code=${encodeURIComponent(code)}`);
  if (r.ok) { name = r.name || ""; o = r.outParams || {}; }
  else {
    const c = await api(`/api/dispatch/outparams?projectId=${state.projectId}&kind=${encodeURIComponent(kind)}&code=${encodeURIComponent(code)}`);
    if (!c.ok) { box.innerHTML = `<div class="empty" style="padding:6px 0">${esc(c.error || r.error)}</div>`; return; }
    o = c;
    // note 会被 outParamBlock 再 esc 一次：这里只拼纯文本，不要提前转义
    o.note = "这个代码没在学习建好的服务字典里（" + (r.error || "") + "），下面是直接读盘上代码与 viewConf 的结果。"
      + (c.note ? " " + c.note : "");
  }
  let changed = {};
  try { changed = JSON.parse((btn && btn.dataset.changed) || "{}"); } catch (e) { changed = {}; }
  box.innerHTML = `<div class="row" style="gap:6px;align-items:center"><b style="font-size:12px">${esc(kind)} ${esc(code)}</b>
      ${name ? `<span class="muted">${esc(name)}</span>` : ""}</div>${outParamBlock(o, changed)}`;
  fitControls(box);
}

/* ================= YApi 文档同步（第卅四轮） =================
   候选来自学习建好的服务字典；token 只从凭据保险库取；推送必须逐条预览+勾选，服务端只认请求体里列出的项。 */
let ypPick = new Set();      // 勾选跨翻页/过滤存活：key = kind|code
let ypNames = {};            // 勾选时记下中文名，确认弹屏才有的可看
let ypTarget = { projects: [], cats: [] };
/** ① 卡底部的常驻结论（保存/现拉项目/现拉目录都写这里）：存成模块变量，重画后不会被吞；换项目即清 */
let ypCfgNote = "";
let ypCfgNoteProj = 0;

function yapiHref(o) {
  const p = new URLSearchParams();
  if (o.kind && o.kind !== "all") p.set("kind", o.kind);
  if (o.repo) p.set("repo", o.repo);
  if (o.q) p.set("q", o.q);
  if (Number(o.page) > 1) p.set("page", String(Number(o.page)));
  const s = p.toString();
  return "#/yapi" + (s ? "?" + s : "");
}

views.yapi = async () => {
  await loadProjects();
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  const cur = { kind: st.get("kind") || "all", repo: st.get("repo") || "", q: st.get("q") || "",
    page: Math.max(1, Number(st.get("page") || 1)) };
  const cfg = await api(`/api/yapi/config?projectId=${state.projectId}`);
  const size = listSize("yapi");
  const cand = await api(`/api/yapi/candidates?projectId=${state.projectId}&kind=${encodeURIComponent(cur.kind)}`
    + (cur.repo ? "&repoId=" + encodeURIComponent(cur.repo) : "")
    + `&page=${cur.page}&size=${size}` + (cur.q ? "&q=" + encodeURIComponent(cur.q) : ""));
  const last = await api(`/api/yapi/last-push?projectId=${state.projectId}`).catch(() => ({}));
  const items = cand.items || [];
  const chip = (o, label, cls) => `<a href="${yapiHref(Object.assign({}, cur, o))}" class="badge ${cls}"
      style="text-decoration:none">${label}</a>`;
  const creds = cfg.credentials || [];
  const targetLine = cfg.yapiProjectId && cfg.catId
    ? `<span style="color:var(--green)">当前目标：YApi 项目 ${esc(cfg.yapiProjectName || cfg.yapiProjectId)} · 目录 ${esc(cfg.catName || cfg.catId)}</span>`
    : cfg.targetSaved === false
      ? `<span style="color:var(--amber)">本项目在库里还没有「同步目标」这条配置（上面几格全是空的才是这个原因，不是刚才填的没了）：填好点【💾 保存同步目标】</span>`
      : `<span style="color:var(--amber)">还没选好 YApi 项目与目录——没选时【推送】会被直接拒绝，不会误写到别处</span>`;
  const credLine = creds.length
    ? `<span class="muted" style="font-size:11px">保险库里有 ${creds.length} 条 YApi 凭据，这里只选不填。两种都能用：`
      + `<b>账号密码</b>（内容填 邮箱:密码）由系统登录换 token、过期自动重登；<b>手填 token</b>（内容填 _yapi_token 那一整串 JWT）约 7 天要自己换一条。`
      + `到「系统设置 → 凭据保险库」新建时按这两类选类型</span>`
    : `<span style="color:var(--amber);font-size:11px">保险库里还没有 YApi 凭据：到「系统设置 → 凭据保险库」新建一条——`
      + `省事选「YApi 账号密码」（内容 邮箱:密码，系统自动登录续期），或手动选「YApi token」（粘 _yapi_token 那一整串，约 7 天过期）</span>`;
  const repos = cand.repos || [];
  const selRepo = repos.find(x => String(x.id) === String(cur.repo));
  const whyZero = selRepo && Number(selRepo.targets || 0) === 0;
  const repoWhy = [selRepo ? `仓库「${esc(selRepo.name)}」：${esc(selRepo.hint || "")}` : "",
                   cand.repo_note ? esc(cand.repo_note) : ""].filter(Boolean).join(" ｜ ");
  return `
  <div class="row spread mb"><div class="fbar">
    <div class="fgrp"><label class="f">项目</label>${projectSelect()}</div>
    <div class="fgrp"><label class="f" title="服务字典按仓库学，分开才数得清">仓库</label>
      <select id="ypRepo" onchange="ypHash({ repo: this.value, page: '1' })" style="min-width:200px">
        <option value="">全部仓库（${repos.reduce((s, x) => s + Number(x.targets || 0), 0)} 个功能号）</option>
        ${repos.map(x => `<option value="${x.id}"${String(x.id) === String(cur.repo) ? " selected" : ""} title="${esc(x.hint || "")}">${esc(x.name)}（${x.targets || 0} 个功能号）</option>`).join("")}
      </select></div>
    <div class="fgrp"><label class="f">方式</label>
      <div class="row" style="gap:6px">${chip({ kind: "all", page: 1 }, "全部", "b-gray")}
        ${chip({ kind: "service", page: 1 }, "service", "b-indigo")}${chip({ kind: "bex", page: 1 }, "bex", "b-amber")}</div>
    </div>
    <div class="fgrp"><label class="f">搜索代码或中文名</label>
      <form class="row" style="gap:6px" onsubmit="event.preventDefault();ypQGo(this.querySelector('input').value)">
        <input id="ypQ" value="${esc(cur.q)}" placeholder="如 200073 / 回访" style="min-width:220px">
        <button class="small" type="submit">搜索</button>
        ${cur.q ? `<a class="small ghost btnlike" href="${yapiHref(Object.assign({}, cur, { q: "", page: 1 }))}">清空</a>` : ""}
      </form></div>
  </div><span class="muted">共 ${cand.total || 0} 个功能号 · 每页 ${size} 条</span></div>
  ${repoWhy ? `<div class="mb" style="font-size:12px;color:${whyZero ? "var(--amber)" : "var(--muted)"}">${repoWhy}
    ${whyZero ? ' ｜ 这里数的"功能号"是服务字典，不是【知识库 → 接口】里的接口卡' : ""}</div>` : ""}

  <div class="card mb">
    <b>① 同步目标（按项目记住，切项目不串）</b>
    <div class="muted" style="font-size:12px;margin:4px 0 8px">${targetLine}</div>
    <div class="grid cols2">
      <div class="form-item"><label>YApi 实例地址（API 前缀）</label>
        <input id="ypBase" value="${esc(cfg.baseUrl)}" placeholder="http://内网地址:3000/api"
          oninput="ypBaseHint(this.value)"><div id="ypBaseHint" style="font-size:11px;margin-top:3px">${ypBaseHintText(cfg.baseUrl)}</div></div>
      <div class="form-item"><label>YApi 凭据（密码/token 在保险库里，这里只选不填）</label>
        <div class="row" style="gap:6px"><select id="ypCred" style="flex:1"><option value="">（未选）</option>
          ${creds.map(c => `<option value="${c.id}"${String(c.id) === String(cfg.credId) ? " selected" : ""}>${esc(c.name)}（${c.via === "login" ? "账号密码·自动登录" : "手填 token"}）</option>`).join("")}
        </select><button class="small" onclick="ypCheckAuth(this)" title="先做一次不带任何凭据的地址体检（一个只读 GET，没选凭据也照样测地址）；地址通了之后：账号密码那条会真登录一次，手填 token 那条只读 JWT 里的事实、不外呼换 token">🔑 检查这条凭据</button></div>
        <div id="ypAuthOut" class="muted" style="font-size:11px;margin-top:3px">点【🔑 检查这条凭据】分两条结论说——<b>地址通不通</b>（试的完整 URL 会打出来，没选凭据也会给这一条）与<b>凭据通不通</b>（走的是哪种方式、拿到哪个 uid、什么时候过期）。地址不通时不会把你的密码发出去，也就不会拿"邮箱/密码不对"误导你（不会显示 token 或密码）</div>
        ${credLine}</div>
      <div class="form-item"><label>service 固定值（写进文档固定入参）</label>
        <input id="ypService" value="${esc(cfg.serviceValue)}" placeholder="如 zszlForwardCenter / zsForwardCenter"></div>
      <div class="form-item"><label>func_url 前缀（拼成功能号完整路径）</label>
        <input id="ypPrefix" value="${esc(cfg.funcUrlPrefix)}" placeholder="如 /inside/instruct/manager/"></div>
      <div class="form-item"><label>入参里的「请求级字段（信封）」怎么定</label>
        <select id="ypEnvMode">
          <option value="auto"${cfg.envelopeMode === "base5" ? "" : " selected"}>按每个代码的前端调用点认（默认，调用点没带的不写进文档）</option>
          <option value="base5"${cfg.envelopeMode === "base5" ? " selected" : ""}>按老基准：每条固定带 service / func / func_url / pageoffset / pagecount</option>
        </select>
        <div class="muted" style="font-size:11px;margin-top:3px">普通 bex 直调只带 bex_codes、只带 service 的原子调用没有 func/func_url——
          一律塞五项会给接口编造入参，所以默认按调用点认。走转发中心的功能号（老基准那批）需要无条件带五项时切到这里。</div></div>
      <div class="form-item"><label>YApi 项目（点右边现拉，不用抄 id）</label>
        <div class="row" style="gap:6px"><select id="ypProj" style="flex:1">
          ${cfg.yapiProjectId ? `<option value="${esc(cfg.yapiProjectId)}">${esc(cfg.yapiProjectName || cfg.yapiProjectId)}（已保存）</option>` : `<option value="">（还没拉过）</option>`}
        </select><button class="small" onclick="ypPullProjects()">现拉项目</button></div></div>
      <div class="form-item"><label>YApi 目录（维护到哪个分类就选哪个）</label>
        <div class="row" style="gap:6px"><select id="ypCat" style="flex:1">
          ${cfg.catId ? `<option value="${esc(cfg.catId)}">${esc(cfg.catName || cfg.catId)}（已保存）</option>` : `<option value="">（还没拉过）</option>`}
        </select><button class="small" onclick="ypPullCats()">现拉目录</button></div></div>
    </div>
    <div class="row mt" style="gap:8px"><button onclick="ypSaveCfg()">💾 保存同步目标</button>
      <span class="muted" style="font-size:12px">${last.pushed != null
        ? `上次推送：成功 ${last.pushed} 条${last.failed ? ` · 失败 ${last.failed} 条` : ""} · ${esc(fmtTime(String(last.at || "").replace("T", " ").slice(0, 19)))}`
        : "本项目还没从这里推过 YApi"}</span></div>
    <div id="ypCfgOut" class="muted" style="font-size:11px;margin-top:4px">${ypCfgNote}</div>
    <div class="muted" style="font-size:11px;margin-top:2px">保存前系统会拿这一格生效的地址做一次只读地址体检：确知是页面地址时不存（免得后面每个按钮都失败），
      只是此刻连不上或认不出形态时照常存、在下面留一条提醒。</div>
  </div>

  <div class="card">
    <div class="row spread"><b>② 待同步的功能号（来自服务字典）</b>
      <div class="row" style="gap:8px">
        <button class="small ghost" onclick="ypBatchPreview()">👁 批量预览勾选（<span id="ypCount">${ypPick.size}</span>）</button>
        <button onclick="ypPushChecked()">📤 推送勾选的 <span id="ypCount2">${ypPick.size}</span> 条</button>
      </div></div>
    <div class="muted" style="font-size:12px;margin:6px 0">
      推送是写外部系统：<b>只有勾了的才会推</b>；YApi 上已有同功能号的走更新（会整块替换它的请求/响应 Schema），没有的走新增。
      入参＝请求级字段（调用点认出的信封／框架契约）+ 服务字典与调用链学到的业务参数；<b>报文头公共参数不列进文档</b>（它是框架在请求体里另起的一段，【接口测试台】发请求时照样按这个仓自己的类带上）；出参按盘上真实代码推算（properties→Controller→DAO→mapper 语句／bexConf→存储过程源码），viewConf 声明只补中文说明。认不准的不写进文档：只在 SQL 的 \${} 拼接里出现过的名字、没走到带列注释的表，预览里都会照实说漏了几个、为什么漏。
      <b>要改字段名、类型或中文说明，点行末的【👁 预览文档】进去改并保存</b>——【👁 批量预览勾选】只用来一次核对多条，不在里面编辑。
      「文档」列是本系统里存的人工维护处数（0＝完全按自动结果，没动过）。
    </div>
    <table><tr><th>选</th><th>方式</th><th>代码</th><th>名称</th><th>入参</th><th>文档</th><th>操作</th></tr>
      <tbody id="ypRows">${items.length ? items.map(t => {
        const key = t.kind + "|" + t.code;
        return `<tr><td><input type="checkbox" class="ypChk" data-key="${esc(key)}" data-name="${esc(t.name || "")}"
            style="width:auto;resize:none" ${ypPick.has(key) ? "checked" : ""} onchange="ypToggle(this)"></td>
          <td>${badge(t.kind === "bex" ? "bex" : "service", t.kind === "bex" ? "amber" : "indigo")}</td>
          <td><code>${esc(t.code)}</code></td><td class="wrap">${esc(t.name || "-")}</td>
          <td>${t.param_count ? `<span style="color:#16a34a">${t.param_count} 个</span>` : `<span class="muted">无</span>`}</td>
          <td data-mkey="${esc(key)}">${ypManualCell(t.manual)}</td>
          <td><button class="small ghost" onclick="ypDraft('${esc(t.kind)}','${esc(t.code)}')">👁 预览文档</button></td></tr>`;
      }).join("") : `<tr><td class="empty" colspan="7">${esc(cand.note || "当前过滤条件下没有功能号")}</td></tr>`}</tbody>
    </table>
    ${pagerBar({ key: "yapi", total: cand.total || 0, page: cur.page, pager: "yapi",
      href: p => yapiHref(Object.assign({}, cur, { page: p })) })}
    <div id="ypOut" class="mt"></div>
  </div>`;
};
pagerDefine("yapi", p => ypHash({ page: String(p) }), () => ypHash({ page: "1" }));
/** 只换 query 里的 page，其它过滤条件（方式/关键字）原样留着 */
function ypHash(sets) {
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  Object.entries(sets).forEach(([k, v]) => (v === "" || v == null) ? st.delete(k) : st.set(k, v));
  const s = st.toString();
  location.hash = "#/yapi" + (s ? "?" + s : "");
}

function ypQGo(q) { ypHash({ q: (q || "").trim(), page: "1" }); }

function ypToggle(el) {
  const k = el.dataset.key;
  if (el.checked) { ypPick.add(k); ypNames[k] = el.dataset.name || ""; }
  else ypPick.delete(k);
  ["ypCount", "ypCount2"].forEach(id => { const n = document.getElementById(id); if (n) n.textContent = String(ypPick.size); });
}

/** 「文档」列：0 处＝完全按自动结果，不必特意打扰；维护过才标出来（数字出自服务端 COUNT，界面不自己加） */
function ypManualCell(n) {
  const c = Number(n || 0);
  return c > 0 ? `<span class="badge b-indigo" title="这个功能号在本系统里存了 ${c} 处人工维护的内容（改过/新增/删掉的字段行，以及标题路径），推给 YApi 的就是这一份">人工 ${c} 处</span>`
    : `<span class="muted" style="font-size:11px">未维护</span>`;
}
/** 保存后就地把这一行的计数改掉：数字用服务端回的那份，列表与编辑器不会各算一套 */
function ypCandidatesRefresh(kind, code, rows) {
  const cell = document.querySelector(`#ypRows [data-mkey="${CSS.escape(kind + "|" + code)}"]`);
  if (cell) cell.innerHTML = ypManualCell(rows);
}

function ypForm() {
  return {
    projectId: state.projectId,
    baseUrl: (document.getElementById("ypBase") || {}).value || "",
    credId: (document.getElementById("ypCred") || {}).value || "",
    serviceValue: (document.getElementById("ypService") || {}).value || "",
    funcUrlPrefix: (document.getElementById("ypPrefix") || {}).value || "",
    envelopeMode: (document.getElementById("ypEnvMode") || {}).value || "auto",
    yapiProjectId: (document.getElementById("ypProj") || {}).value || "",
    yapiProjectName: ((document.getElementById("ypProj") || {}).selectedOptions[0] || {}).textContent || "",
    catId: (document.getElementById("ypCat") || {}).value || "",
    catName: ((document.getElementById("ypCat") || {}).selectedOptions[0] || {}).textContent || "",
  };
}

/**
 * 「现拉项目」「现拉目录」要把界面上这一格的地址与凭据一起带过去（第四十一轮）：
 * 只读的外呼应当验用户眼前这一格，而不是逼他先保存才看得见结果——库里那份可能还是上一个地址。
 */
function ypLiveQs() {
  const f = ypForm();
  return "&baseUrl=" + encodeURIComponent(f.baseUrl) + (f.credId ? "&credId=" + encodeURIComponent(f.credId) : "");
}

/** ① 卡的常驻结论区（toast 三秒就没了，指路的话必须留着看） */
function ypCfgSay(html) {
  ypCfgNote = html;
  ypCfgNoteProj = state.projectId;
  const el = document.getElementById("ypCfgOut");
  if (el) el.innerHTML = html;
}

async function ypSaveCfg() {
  const r = await api("/api/yapi/config", ypForm(), "POST");
  if (!r.ok) {
    // 地址体检没过到"确知是页面地址"这一档才拦：两条结论都要显示，且不清空用户已填的内容
    // toast 只指路、不复述——原因只写在常驻区一处，免得同一句话在两处各长一遍（第卅九轮教训）
    ypCfgSay(ypTwoLines(r, false, "没保存") + `<div class="muted">${esc(r.error || "保存失败")}</div>`);
    return toast("地址体检没过，这次没有保存：改法在 ① 下方那一行", true);
  }
  if (r.baseWarn) {
    // 存下了，但地址这条要提醒：只 warn 不拦，手动路径一直在
    ypCfgSay(ypTwoLines(r, true, "同步目标已保存（只存本机库），但地址这条有提醒")
      + `<div class="muted">${esc(r.baseWarn)}</div>`);
    return toast("已保存，但地址体检有提醒：细节在 ① 下方那一行", true);
  }
  ypCfgSay(ypTwoLines(r, true, "同步目标已保存（只存本机库）"));
  route();
}

/**
 * 「YApi 实例地址」这一格的口径单一出口：恒显说清要填到 /api 为止，填成页面地址时立刻琥珀提示。
 * 只做<b>形态</b>判断，不声称"这个地址能用"——真通不通要按【🔑 检查这条凭据】里的地址体检。
 */
function ypBaseHintText(v) {
  const s = (v || "").trim();
  if (!s) return `<span class="muted">还没填。要填到 <code>/api</code> 为止，例 <code>http://内网地址:3000/api</code>；`
    + `浏览器地址栏里的 <code>http://地址/project/26/interface/api</code> 是 YApi 的页面地址，不能当 API 前缀用</span>`;
  if (/\/project\/\d+/.test(s) || /\/interface\/api\/?$/.test(s))
    return `<span style="color:var(--amber)">⚠ 这看着是 YApi 的<b>页面地址</b>（浏览器地址栏里那一串）。`
      + `系统会在这后面直接拼 <code>/user/login</code>、<code>/interface/list</code>，那样必然 404。`
      + `改成 API 前缀：去掉 <code>/project/…</code>、以 <code>/api</code> 结尾</span>`;
  if (!/\/api\/?$/.test(s))
    return `<span class="muted">当前值不以 <code>/api</code> 结尾——YApi 的接口都在 <code>/api/…</code> 下面，`
      + `除非这台实例改过前缀。不确定就点【🔑 检查这条凭据】：地址不通时系统会只读试一下 <code>/api</code> 这个前缀，`
      + `试通了直接把该填的地址摆出来让你一键换上</span>`;
  return `<span style="color:var(--green)">✔ 形态像 API 前缀</span><span class="muted">（真通不通看【🔑 检查这条凭据】的地址体检）</span>`;
}
function ypBaseHint(v) { const e = document.getElementById("ypBaseHint"); if (e) e.innerHTML = ypBaseHintText(v); }

/**
 * 两条结论的<b>单一渲染出口</b>（第卅九~四十一轮）：任何一步 YApi 外呼都要分两行说——
 * 第一行「地址通不通」（带试过的完整 URL 与原话），第二行「这次动作成不成」。
 * 地址不通时第二行只能说"还没测/没开始"，不能说"凭据不对"：请求根本没打到 YApi 接口。
 * 【🔑 检查这条凭据】【💾 保存同步目标】【现拉项目】【现拉目录】【📤 推送】五处共用 ypTwoLines；
 * 文档预览屏只嵌地址那一行（动作那行它已有自己的红块），也走 ypAddrLine，不再自己拼一遍。
 */
/** 第一条结论：地址通不通（带试过的完整 URL 与原话）。没有体检数据时不给空行 */
function ypAddrLine(o) {
  if (!o || !o.baseChecked) return "";
  const head = `<div>${o.baseOk ? '<b style="color:var(--green)">✔ 地址通</b>'
      : '<b style="color:var(--red)">✘ 地址不通</b>'}
       <span class="muted">试 <code>${esc(o.baseChecked)}</code> → ${esc(o.baseNote || "")}</span></div>`;
  // 只差一段 /api 时服务端已经只读把候选前缀试过了（第四十三轮）：这里只把试出来的地址摆出来，
  // 采不采用由人点，而且点的只是界面上这一格——不点【💾 保存同步目标】不会落库。
  if (o.baseOk || !o.suggestBase || !document.getElementById("ypBase")) return head;
  return head + `<div style="color:var(--amber)">⚠ 只读试出来一个像 API 根的地址：<code>${esc(o.suggestBase)}</code>
       <span class="muted">${o.suggestChecked ? "试 <code>" + esc(o.suggestChecked) + "</code> → " : "→ "}${esc(o.suggestNote || "")}</span>
       <button class="small" data-v="${esc(o.suggestBase)}" onclick="ypUseSuggested(this)">↻ 用这个地址重测</button>
       <span class="muted">（只改上面那一格，不点【💾 保存同步目标】不会落库）</span></div>`;
}

/** 采用试出来的地址：写进那一格 → 让形态提示跟着刷新 → 再走一次原来的检查（这次地址就对了） */
function ypUseSuggested(btn) {
  const el = document.getElementById("ypBase");
  if (!el) return;
  el.value = btn.dataset.v || "";
  ypBaseHint(el.value);
  toast("地址已换成试出来的那一个（还没保存），再点一次【🔑 检查这条凭据】");
}

function ypTwoLines(r, actOk, actText, detail) {
  const act = `<div><b style="color:${actOk ? "var(--green)" : "var(--red)"}">${actOk ? "✔" : "✘"} ${esc(actText)}</b></div>`;
  return ypAddrLine(r) + act + (detail ? `<div class="muted">${esc(detail)}</div>` : "");
}

async function ypCheckAuth(btn) {
  const out = document.getElementById("ypAuthOut");
  if (!out) return;
  const cid = (document.getElementById("ypCred") || {}).value || "";
  if (btn) btn.disabled = true;
  out.textContent = "先体检地址，再验凭据…";
  const r = await api("/api/yapi/check-auth", { projectId: state.projectId, credId: Number(cid) || 0,
    baseUrl: (document.getElementById("ypBase") || {}).value || "" });
  if (btn) btn.disabled = false;
  // 两条结论分开说（第卅九轮用户被一句"核对邮箱/密码"卡住）：地址没通时那次请求根本没打到 YApi 接口，
  // 这时候提邮箱/密码就是假线索，界面要照实说"凭据还没测"。
  // 没选凭据也照样测地址（第四十四轮：用户问的就是"这个地址到底怎么填"，不能因为没凭据连地址都不给结论）。
  // 整块用 muted 打底：错误文案里带 YApi/页面原话（HTML 片段），内联色只留给 ✔/✘ 两个结论词。
  out.className = "muted";
  if (!r.ok) {
    const notTested = r.problem === "address" || r.problem === "nocred";
    out.innerHTML = ypTwoLines(r, false, notTested ? (cid ? "凭据还没测" : "凭据还没测（这一格没选）") : "凭据不通",
      r.error || "检查失败");
    return;
  }
  const via = r.via === "login" ? "账号密码·系统自动登录" : "手填 token";
  out.innerHTML = ypTwoLines(r, true, `凭据「${r.credName}」可用`,
    `方式 ${via} · uid ${r.uid || "-"} · `
    + (r.exp ? `token 过期时间 ${fmtTime(String(r.exp))}` : "token 里没有过期时间")
    + (r.note ? ` · ${r.note}` : ""));
}

async function ypPullProjects() {
  const sel = document.getElementById("ypProj");
  if (!sel) return;
  sel.innerHTML = `<option value="">拉取中…</option>`;
  const r = await api(`/api/yapi/projects?projectId=${state.projectId}${ypLiveQs()}`);
  // 拉项目这一步同时验证了"地址 + 凭据 + 这个项目权限"，两条结论要分开常驻（toast 会消失）
  if (!r.ok) {
    ypCfgSay(ypTwoLines(r, false, r.problem === "address" ? "项目没拉到，凭据还没用上" : "项目没拉到")
      + `<div class="muted">${esc(r.error || "拉取失败")}</div>`);
    sel.innerHTML = `<option value="">（拉取失败）</option>`;
    return toast("现拉项目失败：两条结论在 ① 下方", true);
  }
  ypTarget.projects = r.projects || [];
  if (!ypTarget.projects.length) {
    sel.innerHTML = `<option value="">（这个 YApi 账号下没有项目）</option>`;
    ypCfgSay(ypTwoLines(r, true, "地址与凭据都通，但这个账号下没有 YApi 项目"));
    return toast("项目数为 0 的原因在 ① 下方那一行", true);
  }
  sel.innerHTML = ypTarget.projects.map(p =>
    `<option value="${esc(p.id)}">${esc(p.name || "（无名项目）")}${p.group ? "（分组 " + esc(p.group) + "）" : ""}</option>`).join("");
  // 分组是逐组取的：某一组失败时服务端会回 warn，这一句必须常驻（"只含成功那几组"≠"全部项目"）
  const extra = [r.warn, r.groupNote].filter(Boolean).map(t => `<div class="muted">${esc(t)}</div>`).join("");
  ypCfgSay(ypTwoLines(r, true,
    `地址与凭据都通，按 ${r.groups || 0} 个分组拉到 ${ypTarget.projects.length} 个 YApi 项目（只读，没写 YApi）`)
    + extra + `<div class="muted">选好后点【💾 保存同步目标】</div>`);
}

async function ypPullCats() {
  const sel = document.getElementById("ypCat");
  if (!sel) return;
  const pid = (document.getElementById("ypProj") || {}).value || "";
  sel.innerHTML = `<option value="">拉取中…</option>`;
  const r = await api(`/api/yapi/cats?projectId=${state.projectId}&yapiProjectId=${encodeURIComponent(pid)}${ypLiveQs()}`);
  if (!r.ok) {
    ypCfgSay(ypTwoLines(r, false, r.problem === "address" ? "目录没拉到，凭据还没用上" : "目录没拉到")
      + `<div class="muted">${esc(r.error || "拉取失败")}</div>`);
    sel.innerHTML = `<option value="">（拉取失败）</option>`;
    return toast("现拉目录失败：两条结论在 ① 下方", true);
  }
  ypTarget.cats = r.cats || [];
  if (!ypTarget.cats.length) {
    sel.innerHTML = `<option value="">（这个项目没有目录）</option>`;
    ypCfgSay(ypTwoLines(r, true, `地址与凭据都通，但 YApi 项目 ${r.yapiProjectId || "（当前所选）"} 下没有目录`));
    return toast("目录数为 0 的原因在 ① 下方那一行", true);
  }
  sel.innerHTML = ypTarget.cats.map(c => `<option value="${esc(c.id)}">${esc(c.name || "（无名目录）")}</option>`).join("");
  ypCfgSay(ypTwoLines(r, true, `地址与凭据都通，拉到 ${ypTarget.cats.length} 个目录（只读，没写 YApi）`)
    + `<div class="muted">选好后点【💾 保存同步目标】</div>`);
}

/** 单个功能号的文档草稿：预览即可编辑（第卅八轮）。withRemote=0 时纯本地算（YApi 不可达也能改） */
async function ypDraft(kind, code, withRemote = 1) {
  const m = modal(`<h3>YApi 文档维护 · ${esc(kind)} ${esc(code)}</h3>
    <div class="muted mb">正在按服务字典 + 盘上代码现算出入参${withRemote ? "，并比对该目录下已有文档" : ""}…
      <div style="font-size:11px;margin-top:3px">这一页就是维护接口文档的地方：字段名、类型、中文说明都能直接改，改完点【💾 保存这份文档】，
        下次打开、批量预览、推给 YApi 的都是保存后的这份。</div></div>
    <div id="ypdBody"><div class="empty">加载中…</div></div>
    <div class="actions"><button class="ghost" onclick="ypdClose()">关闭</button>
      ${withRemote ? `<button class="small ghost" onclick="ypDraftRetry('${esc(kind)}','${esc(code)}')">YApi 连不上？只按本地算一次</button>` : ""}</div>`, "wide");
  const r = await api(`/api/yapi/draft?projectId=${state.projectId}&kind=${encodeURIComponent(kind)}&code=${encodeURIComponent(code)}&withRemote=${withRemote ? 1 : 0}`);
  const box = m.querySelector("#ypdBody");
  if (!r.ok) { box.innerHTML = `<div class="empty">${esc(r.error)}</div>`; return; }
  ypdInit(r, withRemote);
  ypdRender();
}
function ypdClose() {
  if (ypd && ypd.dirty && !confirm("这份文档有改动还没保存：关掉就丢了。确定关掉？")) return;
  ypd = null;
  document.querySelector(".modal-mask")?.remove();
}
function ypDraftRetry(kind, code) {
  ypd = null;
  document.querySelector(".modal-mask")?.remove();
  ypDraft(kind, code, 0);
}

/** 编辑中的那一份（内存态）：行清单里含"已删（可恢复）"的行，界面重渲染时用户的输入不会被吞 */
let ypd = null;
function ypdInit(d, withRemote) {
  ypd = {
    kind: d.kind, code: d.code, withRemote: withRemote === undefined ? 1 : withRemote,
    title: d.title || "", path: d.path || "", autoTitle: d.autoTitle || "", autoPath: d.autoPath || "",
    inputs: ypdRows(d.inputs, "in", d.hiddenInputs), outputs: ypdRows(d.outputs, "out", d.hiddenOutputs),
    raw: d, dirty: false,
  };
}
/** 服务端已经把人工层合进 rows 了，这里只补上编辑要用的空壳：可见行在前、已删行在后（折叠区渲染） */
function ypdRows(rows, io, hidden) {
  const out = (rows || []).map(r => ypdRow(r, false));
  (hidden || []).forEach(r => out.push(ypdRow(r, true)));
  return out;
}
function ypdRow(r, isHidden) {
  return {
    slot: r.slot || String(r.field || "").toLowerCase(), field: r.field || "", type: r.type || "",
    title: r.title || "", value: r.value || "", fixed: !!r.fixed, at: r.at || "",
    conf: r.conf, via: r.via, titleVia: r.titleVia || "", file: r.file, line: r.line,
    added: !!r.added, orphan: !!r.orphan, manualCols: r.manualCols || [],
    autoField: r.autoField || "", autoType: r.autoType || "", autoTitle: r.autoTitle || "",
    editedAt: r.editedAt || "", hidden: !!isHidden,
  };
}
function ypdHidden(io) { return ypd[io === "in" ? "inputs" : "outputs"].filter(r => r.hidden).length; }
/** 把界面上敲进去的值收回内存，再整块重渲染——加行/删行/保存后都不丢用户已经打的字 */
function ypdCollect() {
  document.querySelectorAll("#ypdBody [data-cell]").forEach(el => {
    const list = ypd[el.dataset.io === "in" ? "inputs" : "outputs"];
    const row = list[Number(el.dataset.row)];
    if (row) row[el.dataset.cell] = el.value;
  });
}
function ypdWarn() {
  const el = document.getElementById("ypdDirty");
  if (el) el.innerHTML = ypd.dirty
    ? `<span style="color:var(--amber)">● 有改动没保存</span>——点下面的【💾 保存这份文档】才会落地`
    : `<span class="muted">改动保存在本系统的文档维护层里，一次 YApi 外呼都不会发</span>`;
}

function ypdAdd(io) {
  ypdCollect();
  const list = ypd[io === "in" ? "inputs" : "outputs"];
  list.splice(ypdHidden(io) ? list.length - ypdHidden(io) : list.length, 0,
    { slot: "", field: "", type: "string", title: "", value: "", fixed: false, added: true,
      orphan: false, manualCols: ["整行新增"], autoField: "", autoType: "", autoTitle: "", editedAt: "", hidden: false });
  ypd.dirty = true;
  ypdRender();
  const rows = document.querySelectorAll(`#ypdBody [data-io="${io}"][data-cell="field"]`);
  const n = (io === "in" ? ypd.inputs : ypd.outputs).filter(r => !r.hidden).length;
  if (rows[n - 1]) rows[n - 1].focus();
}
function ypdDel(io, i) {
  ypdCollect();
  const list = ypd[io === "in" ? "inputs" : "outputs"];
  const r = list[i];
  // 刚加、还没落库的那一行：直接撤掉。标成"删了可恢复"会凭空留一条没有对应自动字段的空记录
  if (r.added && !r.editedAt) list.splice(i, 1);
  else r.hidden = true;
  ypd.dirty = true;
  ypdRender();
}
function ypdBack(io, i) {
  ypdCollect();
  const list = ypd[io === "in" ? "inputs" : "outputs"];
  list[i].hidden = false;
  ypd.dirty = true;
  ypdRender();
}
/** 恢复自动：人工新增的直接撤掉，改过的按服务端给的自动值填回去 */
function ypdRestore(io, i) {
  ypdCollect();
  const list = ypd[io === "in" ? "inputs" : "outputs"];
  const r = list[i];
  if (r.added || r.orphan) { list.splice(i, 1); }
  else {
    r.field = r.autoField; r.type = r.autoType; r.title = r.autoTitle;
    r.manualCols = []; r.hidden = false;
  }
  ypd.dirty = true;
  ypdRender();
}
function ypdRestoreMeta() {
  ypdCollect();
  ypd.title = ypd.autoTitle; ypd.path = ypd.autoPath;
  ypd.dirty = true;
  ypdRender();
}

async function ypdSave(btn) {
  ypdCollect();
  const body = {
    projectId: state.projectId, kind: ypd.kind, code: ypd.code,
    title: ypd.title, path: ypd.path,
    inputs: ypd.inputs.map(ypdSend), outputs: ypd.outputs.map(ypdSend),
  };
  if (btn) btn.disabled = true;
  const r = await api("/api/yapi/draft/save", body, null, { progress: true });
  if (btn) btn.disabled = false;
  if (!r.ok) { toast(r.error || "保存失败", true); return; }
  const d = await api(`/api/yapi/draft?projectId=${state.projectId}&kind=${encodeURIComponent(ypd.kind)}&code=${encodeURIComponent(ypd.code)}&withRemote=${ypd.withRemote ? 1 : 0}`);
  if (!d.ok) {
    // 保存已经成了，只是重读这一步失败：留着当前这一屏（内容与刚落库的一致）比把界面清成 null 更诚实
    ypd.dirty = false; ypdWarn();
    return toast("已保存，但没能重新读取这份文档：" + d.error + "——关掉再点【👁 预览文档】重开一次", true);
  }
  const wasRemote = ypd.withRemote;
  ypdInit(d);
  ypd.withRemote = wasRemote;
  ypd.dirty = false;
  ypdRender();
  const parts = [];
  if (r.in) parts.push(`入参 改${r.in.changed} 增${r.in.added} 删${r.in.hidden} 复原${r.in.cleared}`);
  if (r.out) parts.push(`出参 改${r.out.changed} 增${r.out.added} 删${r.out.hidden} 复原${r.out.cleared}`);
  if (r.meta) parts.push(r.meta.manual ? "标题/路径已人工改" : "标题/路径与自动一致");
  toast("已保存这份文档：" + parts.join("；") + "（下次打开、批量预览、推送都是这一份）");
  ypCandidatesRefresh(ypd.kind, ypd.code, r.rows);
}
/** 送人的只带编辑得了的三列 + "人删了但留着"这一事实；是不是人工新增由服务端的自动集合判定，界面不自报身份 */
function ypdSend(r) {
  return { slot: r.slot, field: r.field, type: r.type, title: r.title, hidden: r.hidden };
}

function ypDiffMark(set, field) {
  const f = String(field || "").toLowerCase();
  return (set || []).map(String).includes(f);
}

/** 编辑态的一屏：结构与服务端合并结果一致，三列可改，身份徽章恒显（自动／人工改／人工新增／找不到／已删） */
function ypdEditorHtml() {
  const d = ypd.raw;
  const diff = d.diff || {};
  const remote = d.remote || {};
  const env = d.envelope || {};
  const mode = remote.found ? badge("更新（YApi 已有）", "indigo") : badge("新增", "green");
  const envView = envBasisView(d);
  const envBad = envView.badge;
  const envAnchor = env.anchor === "content"
    ? badge(`行号已漂移 · 按内容锚点找回（${env.contentUsed || 0}/${env.contentHits || 0} 行）`, "indigo") : "";
  const envFields = d.envelopeMode === "base5" ? "service、func、func_url、pageoffset、pagecount"
    : ((env.fields || []).length ? (env.fields || []).join("、") : "无（这个码的调用点不带信封键）");
  const man = d.manual || {};
  // 处数只认服务端那一个 COUNT（待同步列表「文档」列用的是同一个数）；界面上没保存的改动另说，不混进这个数
  const saved = Number(man.rows || 0);
  return `
    <div class="row spread"><div class="row" style="gap:8px">${mode}</div>
      <span class="muted" style="font-size:11px">${esc(ypd.kind)} ${esc(ypd.code)}</span></div>
    <div class="row mt" style="gap:8px;align-items:flex-end">
      <div class="form-item" style="flex:2"><label>文档标题（推给 YApi 的 title）</label>
        <input id="ypdTitle" value="${esc(ypd.title)}" oninput="ypdTitleChange(this)"></div>
      <div class="form-item" style="flex:2"><label>请求路径（推给 YApi 的 path）</label>
        <input id="ypdPath" value="${esc(ypd.path)}" oninput="ypdPathChange(this)"></div>
    </div>
    <div class="muted" style="font-size:11px;margin-top:2px">自动算的是：标题 <code>${esc(ypd.autoTitle)}</code>
      · 路径 <code>${esc(ypd.autoPath)}</code><span id="ypdMeta"></span></div>
    <div id="ypdManual" class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">
      <b>${saved ? `这一份里有 ${saved} 处人工维护的内容（改过/新增/删掉的行，加标题路径）`
        : "这一份还全是自动算出来的，一处都没人工维护过"}</b>
      <span class="muted">（与待同步列表「文档」列同一个数）</span>
      ${man.at ? `<span class="muted"> · 最近保存 ${esc(fmtTime(man.at))}</span>` : ""}
      <div class="muted" id="ypdDirty" style="font-size:11px;margin-top:2px"></div>
    </div>
    ${remote.found ? `<div class="muted mt" style="font-size:12px">YApi 现状：#${esc(remote.id)} · ${esc(remote.title)}
        · 比对过 ${remote.scanned || 0} 条 · 更新人时间 ${esc(remote.updAt || "")}</div>` : ""}
    ${remote.error && remote.baseChecked ? `<div class="mt" style="font-size:12px">${ypAddrLine(remote)}</div>` : ""}
    ${remote.error ? `<div class="mt" style="font-size:12px;color:var(--red);border-left:3px solid var(--red);padding:6px 10px;background:#fef2f2">
        YApi 侧没能读到：<b>${esc(remote.error)}</b>
        <div class="muted mt">这时仍能看并改这份文档（改动只存本系统）；推送会再判一次，读不到就拒绝写入，不会盲目新增重复条目</div></div>` : ""}
    ${remote.found ? "" : `<div class="muted mt" style="font-size:12px">${esc(remote.note || "所选目录下没有这条功能号，推送走新增")}</div>`}
    ${ypd.dirty ? `<div class="mt" style="font-size:11px;color:var(--amber)">下面与 YApi 的字段级差异是按${esc(man.at ? "上次保存" : "打开时")}那份比的，保存后才刷新</div>` : ""}
    <div class="mt row spread"><b style="font-size:12px;color:#1d4ed8">入参 ${ypd.inputs.filter(r => !r.hidden).length} 个
      ${ypdHidden("in") ? `<span class="muted">（另有 ${ypdHidden("in")} 个被你删掉，见折叠区）</span>` : ""}</b>
      <button class="small ghost" onclick="ypdAdd('in')">＋ 加一列入参</button></div>
    <div class="mt" style="font-size:12px;border-left:3px solid ${envView.fact ? "#16a34a" : "#f59e0b"};padding:6px 10px;background:${envView.fact ? "#f0fdf4" : "#fffbeb"}">
      ${envBad} ${envAnchor} <b>请求级字段（信封）：${esc(envFields)}</b>
      <div class="muted mt" style="font-size:11px">${esc(d.envelopeNote || env.note || "")}
        —— 这一条是「哪些位属于框架自己带的」的事实，人工改字段名不会改掉它；要挪动请改下面那一行。</div>
      ${(env.evidence || []).length ? `<div class="muted" style="font-size:11px">证据：${env.evidence.map(e => `<code>${esc(e)}</code>`).join(" ┃ ")}</div>` : ""}
    </div>
    ${ypdTable("in", ypd.inputs, diff)}
    ${d.inputNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #f59e0b;padding:6px 10px;background:#fffbeb">${esc(d.inputNote)}</div>` : ""}
    ${d.hdrNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">${esc(d.hdrNote)}</div>` : ""}
    <div class="mt row spread"><b style="font-size:12px;color:#166534">出参 ${ypd.outputs.filter(r => !r.hidden).length} 个
      ${ypdHidden("out") ? `<span class="muted">（另有 ${ypdHidden("out")} 个被你删掉，见折叠区）</span>` : ""}</b>
      <button class="small ghost" onclick="ypdAdd('out')">＋ 加一列出参</button></div>
    ${ypd.outputs.some(r => !r.hidden) ? ypdTable("out", ypd.outputs, diff)
      : `<div class="mt" style="font-size:12px;border-left:3px solid #f59e0b;padding:6px 10px;background:#fffbeb">${esc(d.outputNote || "没有出参声明")}
         <div class="muted mt">上面【＋ 加一列出参】可以手工补——字典与代码都没认出来时，人工写的就是文档的准稿。</div></div>`}
    ${d.titleNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">${esc(d.titleNote)}${ypd.dirty ? `<span class="muted"> · 这一句按${esc(man.at ? "上次保存" : "打开时")}那份算的，界面上没保存的改动不算在内</span>` : ""}</div>` : ""}
    ${ypdHidden("in") || ypdHidden("out") ? `<details class="mt"><summary class="muted" style="font-size:12px">
        被删掉的行（${ypdHidden("in") + ypdHidden("out")} 个）——留着是为了能捞回来，不会推给 YApi</summary>
        ${ypdHiddenTable("in", ypd.inputs)}${ypdHiddenTable("out", ypd.outputs)}</details>` : ""}
    ${(d.outputEvidence || []).length ? `<div class="muted mt" style="font-size:11px">出参证据（按可信度排序）：
      ${d.outputEvidence.map(e => `<span style="color:${e.conf === "high" ? "#16a34a" : e.conf === "declared" ? "#2563eb" : "#d97706"}">${esc(e.via || "")}</span>${e.file ? `<code> ${esc(e.file)}${e.line ? ":" + esc(String(e.line)) : ""}</code>` : ""}`).join("<br>")}</div>` : ""}
    <details class="mt"><summary class="muted" style="font-size:12px">展开将推送的请求体（req_body_other / res_body 原文）
      </summary><div class="muted" style="font-size:11px">这是按${ypd.dirty ? "当前界面这份现算（未保存的改动也在这里）" : "已保存的这份"}算出来的请求体——保存并推送后 YApi 上就是这个形状</div>
      <textarea class="code" readonly style="min-height:160px">${esc(JSON.stringify(ypdPayload(), null, 2))}</textarea></details>
    <div class="actions"><button id="ypdSaveBtn" onclick="ypdSave(this)">💾 保存这份文档</button>
      <span class="muted" style="font-size:11px">保存只写本系统的文档维护层；要让它出现在 YApi 上，还得回到列表勾选后点【📤 推送】</span></div>`;
}
/** 界面上的这份行清单 → 推给 YApi 的 req_body_other / res_body（与服务端 schemaOf 同形：type + description） */
function ypdPayload() {
  const d = ypd.raw;
  const schema = JSON.parse(JSON.stringify(d.schema || {}));
  const mk = (list) => ({
    type: "object", title: "empty object",
    properties: Object.fromEntries(list.filter(r => !r.hidden && String(r.field).trim())
      .map(r => [String(r.field).trim(), { type: ypdJsonType(r.type), description: r.title || "" }])),
  });
  schema.req_body_other = JSON.stringify(mk(ypd.inputs));
  schema.res_body = JSON.stringify(mk(ypd.outputs));
  schema.title = ypd.title;
  schema.path = ypd.path;
  return schema;
}
function ypdJsonType(t) {
  const s = String(t || "").toLowerCase();
  return /int|number|num|double|float/.test(s) ? "integer" : "string";
}
function ypdTitleChange(el) { ypd.title = el.value; ypd.dirty = true; ypdWarn(); ypdMetaWarn(); ypdSyncPayload(); }
function ypdPathChange(el) { ypd.path = el.value; ypd.dirty = true; ypdWarn(); ypdMetaWarn(); ypdSyncPayload(); }
/** 折叠区那份"将要推送的请求体"标榜按当前界面现算，敲键盘时不重渲染（重渲染会吞输入），就单独把这一格刷新 */
function ypdSyncPayload() {
  const ta = document.querySelector("#ypdBody details textarea");
  if (ta) ta.value = JSON.stringify(ypdPayload(), null, 2);
}
/** 标题/路径是不是人工的，只看"跟自动那份一样吗"——两个框各自改，判定必须一起看，不然改回来一个另一个就漏标了 */
function ypdMetaAuto() {
  return ypd.title.trim() === ypd.autoTitle.trim() && ypd.path.trim() === ypd.autoPath.trim();
}
function ypdMetaWarn() {
  const el = document.getElementById("ypdMeta");
  if (el) el.innerHTML = ypdMetaAuto() ? ""
    : ` <span class="badge b-indigo">已人工改</span> <button class="small ghost" onclick="ypdRestoreMeta()">↺ 恢复自动</button>`;
}

/** 「信封」那一栏要自述请求级字段是从哪一条路认出来的：调用点真认出的，还是框架契约声明的 */
function ypFixLabel(r) {
  return String(r.at || "").includes("框架契约") ? "框架契约" : "信封";
}

function ypdBadges(r) {
  // 折叠区里的行也要说清它原来是谁：只写"自动"会让人以为没动过，其实是被删掉了
  if (r.hidden) return badge(r.added ? "人工新增 · 已删" : "自动行 · 已删", "gray");
  if (r.orphan) return badge("代码里已找不到", "amber");
  if (r.added) return badge("人工新增", "green");
  if (r.manualCols && r.manualCols.length) return badge("人工改：" + r.manualCols.join("/"), "indigo");
  return `<span class="muted" style="font-size:11px">自动</span>`;
}
/* 这一行的事实：字段是从哪儿认出来的（via），以及它的中文说明是从哪儿来的（titleVia）。
   两件事分开说（第四十三轮）：按键取值那条证据只说得出「代码读了这个键」，说不出意思；
   意思是同一条链后面那条语句里这一列的列注释给的，界面不并成一句，免得看着像声明里就写了这个意思。 */
function ypSrcLine(f) {
  return esc(f.via || "") + (f.titleVia ? `｜中文说明取自 ${esc(f.titleVia)}` : "");
}

function ypdTable(io, rows, diff) {
  const isOut = io === "out";
  const body = rows.map((r, i) => r.hidden ? "" : ypdTr(io, i, r, diff, isOut)).join("");
  return `<div class="tbl-scroll"><table class="doc-tb"><tr><th>字段</th><th>类型</th><th>中文说明</th>
      <th>${isOut ? "来源与证据" : "请求级字段 / 示例值"}</th><th>身份</th><th>操作</th></tr>${body}</table></div>`;
}
function ypdTr(io, i, r, diff, isOut) {
  const added = ypDiffMark(io === "in" ? diff.inputAdded : diff.outputAdded, r.field);
  const style = r.orphan ? ' style="background:#fffbeb"' : added ? ' style="background:#f0fdf4"' : "";
  const cell = (col, w, tip) => `<td${tip || ""}><input data-io="${io}" data-row="${i}" data-cell="${col}"
      value="${esc(r[col] || "")}" style="width:${w}" oninput="ypdCellDirty(this)"></td>`;
  // 悬停给"自动那份是什么"：只改过的行才有自动值可比，人工新增/找不到的行没有，硬给就是假信息
  const autoTip = r.added || r.orphan ? "" :
    ` title="自动算的是：${esc(r.autoField)} · ${esc(r.autoType)} · ${esc(r.autoTitle || "无说明")}"`;
  const last = isOut
    ? `<td class="wrap"><span class="badge" style="background:${CF_COLOR[r.conf] || "#64748b"};color:#fff"
        title="${ypSrcLine(r)}${r.file ? " ｜ " + esc(r.file) + (r.line ? ":" + esc(String(r.line)) : "") : ""}">${esc(CF_LABEL[r.conf] || (r.via || "来源"))}</span>
        <span class="muted" style="font-size:11px">${ypSrcLine(r)}</span></td>`
    : `<td>${r.fixed ? `<span class="badge b-indigo" title="${esc(r.at || "请求级字段（信封）")}">${ypFixLabel(r)}</span>` : ""}
        <div class="muted wrap" style="font-size:11px">${esc(r.value || "")}</div>
        ${r.via || r.titleVia ? `<div class="muted wrap" style="font-size:11px">${ypSrcLine(r)}</div>` : ""}</td>`;
  return `<tr${style}>
    ${cell("field", "150px", autoTip)}
    ${cell("type", "92px")}
    ${cell("title", "230px")}
    ${last}
    <td>${ypdBadges(r)}</td>
    <td style="white-space:nowrap"><button class="small ghost" onclick="ypdDel('${io}',${i})">删（可恢复）</button>
      ${(r.manualCols.length || r.added || r.orphan) ? `<button class="small ghost" onclick="ypdRestore('${io}',${i})">↺ 恢复自动</button>` : ""}</td></tr>`;
}
function ypdHiddenTable(io, rows) {
  const body = rows.map((r, i) => r.hidden ? `<tr style="opacity:.75">
      <td><code>${esc(r.field)}</code></td><td class="wrap muted">${esc(r.title || "-")}</td>
      <td>${ypdBadges(r)}</td>
      <td><button class="small ghost" onclick="ypdBack('${io}',${i})">放回文档</button>
        <button class="small ghost" onclick="ypdDrop('${io}',${i})">彻底不要</button></td></tr>` : "").join("");
  if (!body) return "";
  return `<div class="muted" style="font-size:11px;margin-top:4px">${io === "in" ? "入参" : "出参"}里被删掉的行</div>
    <div class="tbl-scroll"><table class="doc-tb"><tr><th>字段</th><th>中文说明</th><th>身份</th><th>操作</th></tr>${body}</table></div>`;
}
function ypdDrop(io, i) {
  ypdCollect();
  const list = ypd[io === "in" ? "inputs" : "outputs"];
  list.splice(i, 1);
  ypd.dirty = true;
  ypdRender();
}
function ypdCellDirty(el) {
  const list = ypd[el.dataset.io === "in" ? "inputs" : "outputs"];
  const r = list[Number(el.dataset.row)];
  if (!r) return;
  r[el.dataset.cell] = el.value;
  const col = { field: "字段名", type: "类型", title: "说明" }[el.dataset.cell];
  if (col && !r.added && !r.orphan) {
    const auto = { field: r.autoField, type: r.autoType, title: r.autoTitle }[el.dataset.cell];
    const set = new Set(r.manualCols);
    if (String(el.value || "").trim() === String(auto || "").trim()) set.delete(col);
    else set.add(col);
    r.manualCols = [...set];
  }
  ypd.dirty = true;
  ypdWarn();
  ypdSyncPayload();
}
function ypdRender() {
  const box = document.getElementById("ypdBody");
  if (!box || !ypd) return;
  box.innerHTML = ypdEditorHtml();
  fitControls(box);
  ypdWarn();
  ypdMetaWarn();
}

function ypDraftHtml(d) {
  const diff = d.diff || {};
  const remote = d.remote || {};
  const env = d.envelope || {};
  const mode = remote.found ? badge("更新（YApi 已有）", "indigo") : badge("新增", "green");
  // 信封判定恒显：call-site＝调用点真认出来的；fallback-minimal＝没证据时的最小推断，必须显式标出来
  const envView = envBasisView(d);
  const envBad = envView.badge;
  // 锚点自述（第卅六轮 N46）：字典行号漂了时系统会改按内容找回，必须让用户看得见这次是"找回来的"而不是"行号对上的"
  const envAnchor = env.anchor === "content"
    ? badge(`行号已漂移 · 按内容锚点找回（${env.contentUsed || 0}/${env.contentHits || 0} 行）`, "indigo") : "";
  const envFields = d.envelopeMode === "base5" ? "service、func、func_url、pageoffset、pagecount"
    : ((env.fields || []).length ? (env.fields || []).join("、") : "无（这个码的调用点不带信封键）");
  // 只读视图也要说清哪几行是人工维护过的：不然看到的是合并结果，却以为是系统自动算的
  const mark = f => f.manual ? ` ${badge("人工", "indigo")}` : "";
  const inTbl = (d.inputs || []).map(f => `<tr${ypDiffMark(diff.inputAdded, f.field) ? ' style="background:#f0fdf4"' : ""}>
      <td><code>${esc(f.field)}</code>${mark(f)}</td><td>${esc(f.type || "string")}</td>
      <td class="wrap">${esc(f.title || "-")}${f.titleVia ? `<div class="muted" style="font-size:11px">中文说明取自 ${esc(f.titleVia)}</div>` : ""}</td>
      <td>${f.fixed ? `<span class="badge b-indigo" title="${esc(f.at || "请求级字段（信封）")}">${ypFixLabel(f)}</span>` : ""}</td>
      <td class="wrap muted" style="font-size:11px">${esc(f.value || "")}</td></tr>`).join("");
  const outTbl = (d.outputs || []).map(f => `<tr${ypDiffMark(diff.outputAdded, f.field) ? ' style="background:#f0fdf4"' : ""}>
      <td><code>${esc(f.field)}</code>${mark(f)}</td><td>${esc(f.type || "string")}</td>
      <td class="wrap">${esc(f.title || "-")}</td>
      <td class="wrap"><span class="badge" style="background:${CF_COLOR[f.conf] || "#64748b"};color:#fff"
        title="${ypSrcLine(f)}${f.file ? " ｜ " + esc(f.file) + (f.line ? ":" + esc(String(f.line)) : "") : ""}">${esc(CF_LABEL[f.conf] || (f.via || "来源"))}</span>
        <span class="muted" style="font-size:11px">${ypSrcLine(f)}</span></td></tr>`).join("");
  const removed = [...(diff.outputRemoved || []), ...(diff.inputRemoved || [])];
  const man = d.manual || {};
  const manLine = Number(man.rows || 0) ? `<div class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">
      <b>这一份有 ${man.rows} 处人工维护的内容</b>（标了 <span class="badge b-indigo">人工</span> 的行就是），推出去的就是这一份
      ${man.at ? `<span class="muted"> · 最近保存 ${esc(fmtTime(man.at))}</span>` : ""}
      <div class="muted" style="font-size:11px">要改到【👁 预览文档】里改，这一屏只用来核对</div></div>` : "";
  return `
    <div class="row spread"><div class="row" style="gap:8px">${mode}
        <b>${esc(d.title)}</b><span class="muted">${esc(d.method)} ${esc(d.path)}</span></div>
      <span class="muted" style="font-size:11px">${esc(d.kind)} ${esc(d.code)}</span></div>
    ${manLine}
    ${remote.found ? `<div class="muted mt" style="font-size:12px">YApi 现状：#${esc(remote.id)} · ${esc(remote.title)}
        · 比对过 ${remote.scanned || 0} 条 · 更新人时间 ${esc(remote.updAt || "")}</div>` : ""}
    ${remote.error && remote.baseChecked ? `<div class="mt" style="font-size:12px">${ypAddrLine(remote)}</div>` : ""}
    ${remote.error ? `<div class="mt" style="font-size:12px;color:var(--red);border-left:3px solid var(--red);padding:6px 10px;background:#fef2f2">
        YApi 侧没能读到：<b>${esc(remote.error)}</b>
        ${d.kind ? `<div class="muted mt">这时仍能看草稿；推送会再判一次，读不到就拒绝写入，不会盲目新增重复条目</div>` : ""}</div>` : ""}
    ${remote.found ? "" : `<div class="muted mt" style="font-size:12px">${esc(remote.note || "所选目录下没有这条功能号，推送走新增")}</div>`}
    <div class="mt"><b style="font-size:12px;color:#1d4ed8">入参 ${(d.inputs || []).length} 个</b>
      ${diff.inputAdded && diff.inputAdded.length ? `<span style="color:#16a34a">（绿底＝YApi 上还没有的入参 ${diff.inputAdded.length} 个）</span>` : ""}</div>
    <div class="mt" style="font-size:12px;border-left:3px solid ${envView.fact ? "#16a34a" : "#f59e0b"};padding:6px 10px;background:${envView.fact ? "#f0fdf4" : "#fffbeb"}">
      ${envBad} ${envAnchor} <b>请求级字段（信封）：${esc(envFields)}</b>
      <div class="muted mt" style="font-size:11px">${esc(d.envelopeNote || env.note || "")}</div>
      ${(env.evidence || []).length ? `<div class="muted" style="font-size:11px">证据：${env.evidence.map(e => `<code>${esc(e)}</code>`).join(" ┃ ")}</div>` : ""}
    </div>
    <table style="font-size:12px"><tr><th>字段</th><th>类型</th><th>中文说明</th><th>请求级字段</th><th>示例值</th></tr>${inTbl}</table>
    ${d.inputNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #f59e0b;padding:6px 10px;background:#fffbeb">${esc(d.inputNote)}</div>` : ""}
    ${d.hdrNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">${esc(d.hdrNote)}</div>` : ""}
    <div class="mt" style="font-size:12px"><b style="color:#166534">出参 ${(d.outputs || []).length} 个</b>
      ${diff.outputAdded && diff.outputAdded.length ? `<span style="color:#16a34a">（绿底＝YApi 上还没有的字段 ${diff.outputAdded.length} 个）</span>` : ""}</div>
    ${outTbl ? `<table style="font-size:12px"><tr><th>字段</th><th>类型</th><th>中文说明</th><th>来源与证据</th></tr>${outTbl}</table>`
      : `<div class="mt" style="font-size:12px;border-left:3px solid #f59e0b;padding:6px 10px;background:#fffbeb">${esc(d.outputNote || "没有出参声明")}</div>`}
    ${d.titleNote ? `<div class="mt" style="font-size:12px;border-left:3px solid #7c3aed;padding:6px 10px;background:#f5f3ff">${esc(d.titleNote)}</div>` : ""}
    ${removed.length ? `<div class="mt" style="font-size:12px;border-left:3px solid var(--red);padding:6px 10px;background:#fef2f2">
      <b>推送后这些字段会从 YApi 文档里消失（${removed.length} 个）</b>：${removed.map(x => `<code style="color:var(--red)">${esc(x)}</code>`).join("、")}
      <div class="muted mt">它们在本系统的字典与 viewConf 里都没有了；确认没错再推，或先回到代码里补声明</div></div>` : ""}
    ${diff.remoteIsSchema === false ? `<div class="mt" style="font-size:12px;color:var(--amber)">YApi 上现有文档不是 JSON Schema 形态：${esc(diff.note || "")}</div>` : ""}
    ${(d.outputEvidence || []).length ? `<div class="muted mt" style="font-size:11px">出参证据（按可信度排序）：
      ${d.outputEvidence.map(e => `<span style="color:${e.conf === "high" ? "#16a34a" : e.conf === "declared" ? "#2563eb" : "#d97706"}">${esc(e.via || "")}</span>${e.file ? `<code> ${esc(e.file)}${e.line ? ":" + esc(String(e.line)) : ""}</code>` : ""}`).join("<br>")}</div>` : ""}
    <details class="mt"><summary class="muted" style="font-size:12px">展开将推送的请求体（req_body_other / res_body 原文）</summary>
      <textarea class="code" readonly style="min-height:160px">${esc(JSON.stringify(d.schema, null, 2))}</textarea></details>`;
}

async function ypBatchPreview() {
  const keys = [...ypPick];
  if (!keys.length) return toast("先勾选要同步的功能号（左侧方框）", true);
  const box = document.getElementById("ypOut");
  if (!box) return;
  box.innerHTML = `<div class="muted" style="font-size:12px">逐条现算 ${keys.length} 份草稿（要访问 YApi 比对现状）…</div>`;
  const parts = [];
  for (const k of keys.slice(0, 20)) {
    const [kind, code] = k.split("|");
    const r = await api(`/api/yapi/draft?projectId=${state.projectId}&kind=${encodeURIComponent(kind)}&code=${encodeURIComponent(code)}`);
    parts.push(`<div class="card" style="box-shadow:none;border:1px solid var(--line);margin-bottom:8px;padding:8px">
      ${r.ok ? ypDraftHtml(r) : `<b>${esc(kind)} ${esc(code)}</b><div class="empty" style="padding:6px 0">${esc(r.error)}</div>`}</div>`);
  }
  box.innerHTML = `<b style="font-size:13px">逐条预览（${keys.length} 条${keys.length > 20 ? "，本页只展开前 20 条，推送仍按勾选全量" : ""}）</b>
    <div class="muted" style="font-size:12px;margin:4px 0">确认过内容无误，再点上面的【📤 推送勾选的 N 条】；没勾的绝不会被写。
      这里是<b>只读核对</b>视图：要改字段名/类型/中文说明，关掉这一屏，点行末的【👁 预览文档】进去编辑并保存。</div>${parts.join("")}`;
  fitControls(box);
  toast(`已现算 ${Math.min(20, keys.length)} 份草稿，核对后点【📤 推送勾选的 N 条】`);
}

async function ypPushChecked() {
  const keys = [...ypPick];
  if (!keys.length) return toast("一条都没勾：勾选要同步的功能号后再推", true);
  const items = keys.map(k => { const [kind, code] = k.split("|"); return { kind, code }; });
  const m = modal(`<h3>确认推送到 YApi？</h3>
    <div class="mb">将写入 <b>${keys.length}</b> 条到当前项目的 YApi 目标（写外部系统，逐条按已保存的目录判新增还是更新）：</div>
    <div style="max-height:220px;overflow:auto"><table><tr><th>方式</th><th>代码</th><th>名称</th></tr>
      ${items.map(i => `<tr><td>${esc(i.kind)}</td><td><code>${esc(i.code)}</code></td><td class="wrap">${esc(ypNames[i.kind + "|" + i.code] || "")}</td></tr>`).join("")}
    </table></div>
    <div class="muted mt" style="font-size:12px">已有同功能号的会被更新（请求/响应 Schema 整块替换，YApi 自身保留版本历史）</div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消，不推</button>
      <button id="ypDoPush">确认推送 ${keys.length} 条</button></div>`);
  m.querySelector("#ypDoPush").onclick = async () => {
    const btn = m.querySelector("#ypDoPush");
    btn.disabled = true; btn.textContent = "推送中…";
    const r = await api("/api/yapi/push", { projectId: state.projectId, items }, null, { progress: true });
    btn.disabled = false; btn.textContent = "确认推送";
    // 推送没开始就把两条结论摆在这个弹屏里，并且<b>不关屏</b>：以前是关屏 + 一条 toast，
    // 三秒后只剩"YApi 返回 HTTP 404"这种半句话，人就再去翻密码了（第卅九~四十轮的原路径）
    if (!r.ok) {
      btn.textContent = "确认推送 " + keys.length + " 条";
      m.innerHTML = `<h3>推送没有开始</h3>
        <div style="font-size:12px">${ypTwoLines(r, false, "一条都没推")}</div>
        <div class="muted mt" style="font-size:12px">${esc(r.error || "推送失败")}</div>
        <div class="muted mt" style="font-size:12px">勾着的那些条还留着：改好上面 ① 的地址或凭据，关掉这个弹屏再点一次【📤 推送勾选的】就行。</div>
        <div class="actions"><button onclick="this.closest('.modal-mask').remove()">关闭</button></div>`;
      return;
    }
    const rows = (r.results || []).map(x => `<tr><td>${esc(x.kind)} <code>${esc(x.code)}</code></td>
        <td>${esc(x.mode || "-")}</td><td class="wrap">${esc(x.title || "")}</td>
        <td>${x.ok ? badge("已写入", "green") : badge("失败", "red")}</td>
        <td class="wrap muted">${esc(x.error || "")}${x.ok && x.yapiId ? ` YApi #${esc(x.yapiId)}` : ""}</td></tr>`).join("");
    m.innerHTML = `<h3>推送结果：成功 ${r.pushed} · 失败 ${r.failed}</h3>
      ${r.failed > 0 ? `<div style="font-size:12px">${ypAddrLine(r)}</div>` : ""}
      <div style="max-height:320px;overflow:auto"><table><tr><th>代码</th><th>走哪个端点</th><th>标题</th><th>结果</th><th>说明</th></tr>${rows}</table></div>
      <div class="actions"><button onclick="this.closest('.modal-mask').remove()">关闭</button></div>`;
    const out = document.getElementById("ypOut");
    if (out) out.innerHTML += `<div class="muted mt" style="font-size:12px">刚才推送：成功 ${r.pushed} 条 · 失败 ${r.failed} 条（失败原因原样转述 YApi）</div>`;
    (r.results || []).forEach(x => { if (x.ok) ypPick.delete(x.kind + "|" + x.code); });
    toast(`推送完成：成功 ${r.pushed} 条，失败 ${r.failed} 条`, r.failed > 0);
  };
}
/**
 * 第五十轮：回归前先问「打到哪、怎么打」——原来这个按钮不带任何参数，环境由后端默认兜，
 * 经网关的项目就会拿错 Base URL（对外路径是按所选环境的地址算的），30 条一起 405。
 */
async function runRegression(id) {
  const envs = (await api("/api/environments?projectId=" + state.projectId)).environments || [];
  const m = modal(`<h3>⚡ 一键回归：这条提交影响到的用例</h3>
    <div class="muted">最多跑 30 条已启用用例；跑完当场回写这一条提交的测试标记。</div>
    <div class="form-item mt"><label>执行环境</label>
      <select id="rgEnv">${envs.map(e => `<option value="${e.id}">${esc(e.name)} · ${esc(e.base_url || "无 Base URL")}</option>`).join("")}</select>
      <div class="muted" style="font-size:12px">这个环境的 Base URL 就是「对外路径」拼地址用的那一个；钩子 SQL 与 DB 回读断言也走这个环境的库</div></div>
    <div class="form-item"><label>访问方式</label>
      <label class="f" style="display:block;color:#2563eb"><input type="radio" name="rgAccess" value="env" checked>
        🟦 项目环境 · 全流程（自动登录 + 上面这个环境的 Base URL）</label>
      <label class="f" style="display:block;color:#16a3b8"><input type="radio" name="rgAccess" value="repo">
        🟩 仓库直连 · 不登录（打到各用例所属仓库自己配置的服务地址；没配的仓库会在结果里点名）</label></div>
    <div class="form-item"><label class="f" style="display:block"><input type="checkbox" id="rgInternal">
        ✎ 本次改按接口卡学到的<b>内部路径</b>发（默认按盘上证据算出的对外路径；只在怀疑前缀补错时勾）</label></div>
    ${envs.length ? "" : `<div class="result-fail">这个项目还没有测试环境：先到「系统设置 → 测试环境」添加后再来。</div>`}
    <div id="rgOut" class="mt"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button>
      <button id="rgGo" ${envs.length ? "" : "disabled"}>⚡ 开始回归</button></div>`);
  const out = m.querySelector("#rgOut"), go = m.querySelector("#rgGo");
  go.onclick = async () => {
    const qs = new URLSearchParams();
    const envId = Number(m.querySelector("#rgEnv").value || 0);
    if (envId) qs.set("envId", envId);
    if (m.querySelector('input[name="rgAccess"]:checked').value === "repo") qs.set("repoDirect", "true");
    if (m.querySelector("#rgInternal").checked) qs.set("pathMode", "internal");
    out.innerHTML = `<div class="empty">执行中…（最多 30 条，进度在按钮上）</div>`;
    const r = await api(`/api/commits/${id}/run-regression?${qs}`, {}, null, { progress: true });
    if (!r.ok) { out.innerHTML = `<div class="result-fail">回归失败：${esc(r.error || "服务无响应")}</div>`; return; }
    renderRegression(id, r, m);
    route();
  };
}

/** 结果表：每行点名"这一条实际发出去的是什么"——路径不对与用例本来就红，在这一列上分得开 */
function renderRegression(id, r, host) {
  const s = r.summary;
  const rows = (r.results || []).map(x => {
    const sent = String(x.sent || "");
    return `<tr><td class="wrap">${esc(String(x.target).slice(0, 46))}</td><td>${esc(x.case)}</td>
      <td>${x.status === "pass" ? badge("通过", "green") : badge("失败", "red")}</td>
      <td class="wrap muted" style="font-size:11px;word-break:break-all">${esc(sent || "—")}</td>
      <td class="wrap muted">${esc(x.error || "")}</td></tr>`;
  }).join("");
  const box = host && host.querySelector("#rgOut");
  const html = `<div class="row spread"><b>通过 ${s.pass} · 失败 ${s.fail} · 跳过 ${s.skipped}</b></div>
    <div class="muted mt">失败项含「绑定参数缺失」等拒执原因；「实际发出」这一列是这一条真正打出去的地址，
      经网关的项目若这里仍是卡片学到的内部路径，到 [项目与仓库] 补登记网关仓/前端仓后重学</div>
    ${rows ? `<div style="max-height:340px;overflow:auto"><table><tr><th>影响目标</th><th>用例</th><th>结果</th>
      <th>实际发出</th><th>说明</th></tr>${rows}</table></div>`
      : '<div class="empty">该提交没有已启用的关联用例——先【生成用例】或到用例目录把要跑的启用</div>'}
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button>
    <button class="ghost" onclick="openRuns({commitId:${id}},'提交 #${id} 的执行记录')">📜 看这一批的执行记录</button></div>`;
  if (box) box.innerHTML = html; else modal(`<h3>⚡ 一键回归</h3>${html}`);
}
async function showCommit(id, host) {
  const { commit } = await api(`/api/commits/${id}`);
  const m = `<h3>${esc(commit.message)}</h3>
    ${commit.summary ? `<div class="muted mb" style="line-height:1.7">${esc(commit.summary)}</div>` : ""}
    ${(commit.changes || []).map(ch => `
      <div class="card" style="box-shadow:none;border:1px solid var(--line);margin-bottom:10px">
        <div class="row spread"><div class="row">${badge(ch.change_type, ch.change_type === "A" ? "green" : ch.change_type === "D" ? "red" : "blue")}
          ${ch.category ? badge({ sql: "SQL", api: "接口", ddl: "DDL", frontend: "前端", config: "配置" }[ch.category] || ch.category, ch.category === "ddl" ? "red" : ch.category === "config" ? "blue" : "green") : ""}
          ${ch.suggested_mode && ch.suggested_mode !== "none" ? `<span class="muted" style="font-size:12px">建议${ch.suggested_mode === "db" ? " SQL 直测" : "接口测试"}</span>` : ""}
          <code style="font-size:12px">${esc(ch.path)}</code></div>
          <div class="row">${riskBadge(ch.risk_level || "low")}${statusBadge(ch.test_status)}</div></div>
        ${(() => { const t = ch.impacts || []; // 后端按当前归因口径现算，不再读 impacts_json 旧缓存
            if (!t.length) return "";
            if (t.every(x => x.type === "unmapped")) return `<div class="muted mt">影响：${esc(t[0].name)}</div>`;
            const names = t.slice(0, 12).map(x => esc(x.name)).join("、") + (t.length > 12 ? ` 等 ${t.length} 个` : "");
            const line = t.filter(x => x.basis === "line").length;
            const chain = t.filter(x => x.basis === "chain").length;
            const by = line === t.length ? `<b style="color:var(--green)">按改动行命中 ${line} 条语句</b>`
              : line ? `<b>其中 ${line} 条按改动行命中语句</b>，其余按整文件`
              : chain === t.length ? `<b style="color:#b45309">按调用链归因 ${chain} 个目标</b>（这个文件是服务层／DAO，靠代码可达挂到接口卡上，可能偏宽）`
              : chain ? `<b style="color:#b45309">其中 ${chain} 个按调用链</b>，其余按整文件`
              : `<b>按整文件归因</b>（改的不是 SQL 映射文件，或这个版本的源码没法按行定位）`;
            return `<div class="muted mt">影响：${by} · ${names}</div>`;
          })()}
        ${patchBlock(ch)}
      </div>`).join("")}
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`;
  // 有 host 就地重渲染（按需拉快照之后要用最新归因口径刷新整个弹屏），否则新开一个
  const mask = host || modal(m);
  if (host) { host.querySelector(".modal").innerHTML = m; fitControls(host); }
  mask.querySelector(".modal").style.width = "min(1080px, 96vw)";   // diff 要横向看得开，640px 会一直换行
}

/* ---------- 第卅七轮：diff 上下文换档 + 改动文件全文（标改动） ----------
   diff 是派生视图，真值在仓库里：上下文行数做成参数（3 / 10 / 30 / 整文件），换档就地重算。
   后端每一档各存各的缓存文件，只有默认档（3 行）会动 patch_file 那一列——它还喂着归因、摘要、出参与导出。 */
const PATCH_CTX = [[3, "3 行"], [10, "10 行"], [30, "30 行"], [1000000, "整文件"]];
const patchCtx = {}, patchText = {}, patchSrc = {};   // 改动 id -> 选的档位 / 那一档的原文 / 渲染用的改动行
const ctxLabel = v => (PATCH_CTX.find(p => p[0] === v) || [v, v + " 行"])[1];

/**
 * 快照区头部：上下文下拉 + 看全文 + 恒显「现在看的是哪一档、多少行」。
 * 换档后要整条重画，不然行数还报上一档的；hideCount 用在重算失败那一刻——
 * 屏上已经没有 diff 了，再挂一句「当前 N 行」就是句谎话。
 */
function patchBar(ch, hideCount) {
  const ctx = patchCtx[ch.id] || 3;
  const text = (ch.id in patchText) ? patchText[ch.id] : ch.patch;
  return `<div class="pbbar">
      <span class="lbl">上下文</span>
      <select data-tip="改动点前后各带几行代码。默认 3 行＝与「按改动行归因」同一口径；评审要看整段业务逻辑时换 10 / 30 行，「整文件」＝把这个文件的两个版本从头到尾对比。换档只重算这里的显示，不会改动归因用的默认快照。"
             onchange="setPatchContext(${ch.id}, Number(this.value), this)"
        >${PATCH_CTX.map(([v, t]) => `<option value="${v}"${v === ctx ? " selected" : ""}>${t}</option>`).join("")}</select>
      <button class="small" data-tip="整份文件按行号列出，本提交新增/改过的行直接标绿；未改动的长段先折叠，点【展开】再看。可切「本提交那一版 / 本地工作副本」"
              onclick="openChangeFile(${ch.id},'rev')">📄 看全文（标改动）</button>
      ${text && !hideCount ? `<span class="muted">当前 ${patchLines(text)} 行${ctx !== 3 ? " · 按「" + ctxLabel(ctx) + "」上下文重算" : ""}</span>` : ""}
    </div>`;
}

/** 快照区：头部恒显【上下文】与【看全文】；没有差异就写清「为什么没有」，SVN 历史提交给按需拉取入口，不留空框。 */
function patchBlock(ch) {
  patchSrc[ch.id] = ch;
  const text = (ch.id in patchText) ? patchText[ch.id] : ch.patch;
  return `<div class="patchbox" id="pb-${ch.id}">${patchBar(ch)}<div class="pbbody">${text ? renderDiff(text) : patchAbsence(ch)}</div></div>`;
}

const patchLines = t => t.replace(/\n+$/, "").split(/\r?\n/).length;

function patchAbsence(ch) {
  const why = ch.patch_stale ? "快照文件已不在本机（换过 data 目录或清理过归档），重新拉取即可再生成"
    : ch.fetch_hint ? ch.fetch_hint
    : "这条改动没有登记文件级差异";
  return `<div class="muted" style="font-size:12px;padding:8px 0 2px">${esc(why)}`
    + (ch.can_fetch ? ` <button class="small" data-tip="只读比对这一个文件在该版本与上一版之间的文本差异（要到 SVN 服务器上取两个版本，可能慢几秒）；生成后落盘缓存，下次直接读"
         onclick="fetchPatch(${ch.id}, this)">拉取该文件改动</button>` : "") + `</div>`;
}

/** 【拉取该文件改动】：走同一套换档逻辑，按当前选的那一档生成 */
async function fetchPatch(changeId, btn) {
  await setPatchContext(changeId, patchCtx[changeId] || 3, btn);
}

/**
 * 换上下文档（或首次拉快照）：POST ?context=，成功就地重画这一块。
 * 默认档要整块弹屏重渲染——快照一到，归因可能从「整文件」收窄成「按改动行命中语句」。
 */
async function setPatchContext(chId, ctx, ctrl) {
  const ch = patchSrc[chId];
  const prev = patchCtx[chId];
  patchCtx[chId] = ctx;
  const box = document.getElementById("pb-" + chId);
  const host = box && box.closest(".modal-mask");
  const repaintBar = hide => { const bar = box && box.querySelector(".pbbar"); if (bar && ch) bar.outerHTML = patchBar(ch, hide); };
  if (ctrl) ctrl.disabled = true;
  const r = await api(`/api/file-changes/${chId}/fetch-patch?context=${ctx}`, {}, null, { progress: true });
  if (ctrl && ctrl.isConnected) ctrl.disabled = false;
  const body = box && box.querySelector(".pbbody");
  if (!r.ok) {
    // 失败原因写回弹屏本体：toast 三秒就消失，照着排查的人得看得见原文
    patchCtx[chId] = prev || 3;   // 下拉退回实际还显示着的那一档，别指着没算出来的档说"当前"
    repaintBar(true);
    if (body) body.innerHTML = `<div class="muted" style="font-size:12px;padding:8px 0 2px">
        <b style="color:var(--red)">重算失败：</b>${esc(r.error || "服务无响应")}
        <button class="small" onclick="setPatchContext(${chId}, ${ctx}, this)">再试一次</button></div>`;
    toast("重算失败：原因已写在快照区里", true);
    return;
  }
  patchText[chId] = r.patch;
  if (ctx === 3) await showCommit(r.commit_id, host);   // 整块重渲染：影响行口径同时刷新
  else { repaintBar(); if (body) body.innerHTML = renderDiff(r.patch); }
  toast(r.cached ? `已按「${ctxLabel(ctx)}」上下文读到缓存的快照` : `已按「${ctxLabel(ctx)}」上下文重算并落盘缓存`);
}

/* ---- 改动文件全文：整份按行号列出，改动行标绿，未改动长段折叠 ---- */
const FV_NEAR = 8;      // 每处改动上下各留 8 行，看得懂它在干什么
const FV_CHUNK = 300;   // 折叠段一次展开这么多行：几万行的文件一次灌进来页面会卡死
let fv = null;          // { id, at, r, flags, hits, cursor }——展开/跳转都按行号回这里取原文

async function openChangeFile(chId, at) {
  at = at || "rev";
  let mask = document.getElementById("fvMask");
  if (!mask) {
    mask = modal(`<h3>改动文件全文</h3><div id="fvBody" class="muted">正在读这一版的原文…</div>`);
    mask.id = "fvMask";
    const m = mask.querySelector(".modal");
    m.style.width = "min(1420px, 97vw)";
    m.style.maxHeight = "90vh";
  }
  const body = mask.querySelector("#fvBody");
  body.innerHTML = `<div class="muted">正在读${at === "work" ? "本地工作副本里的当前文件" : "本提交那一版的原文"}…（SVN 要到服务器上取文件，可能慢几秒）</div>`;
  const r = await api(`/api/file-changes/${chId}/file?at=${at}`, undefined, "GET", { progress: true });
  if (!r.ok) {
    body.innerHTML = `<div class="muted"><b style="color:var(--red)">读不到：</b>${esc(r.error || "服务无响应")}`
      + ` <button class="small" onclick="openChangeFile(${chId},'${at}')">再试一次</button>`
      + ` <button class="small" onclick="openChangeFile(${chId},'${at === "rev" ? "work" : "rev"}')">换看${at === "rev" ? "本地工作副本" : "本提交那一版"}</button></div>`;
    toast("读不到该文件：原因已写在弹屏里", true);
    return;
  }
  const f = fvFlags(r);
  fv = { id: chId, at, r, flags: f.flags, hits: f.hits, cursor: 0 };
  body.innerHTML = fileViewHtml();
}

function fileViewHtml() {
  const { r, at, id } = fv;
  const hitCount = (r.marks || []).length;
  return `<div class="fvbar">
      <code style="font-size:12px">${esc(r.path)}</code>
      <span class="muted">版本 ${esc(r.revision || "")} · 共 ${r.total_lines} 行 · 编码 ${esc(r.charset)} · 改动 ${hitCount} 处</span>
      <button class="small${at === "rev" ? " on" : ""}" data-tip="仓库里这个提交之后的那一版：行号与上面的 diff、影响面归因完全对得上"
              onclick="openChangeFile(${id},'rev')">本提交那一版</button>
      <button class="small${at === "work" ? " on" : ""}" data-tip="本地工作副本目录里现在的这个文件：可能已被后续提交改过，行号仅供参考"
              onclick="openChangeFile(${id},'work')">本地工作副本</button>
      <button class="small" data-tip="依次跳到下一处标绿的行（在本弹屏里滚过去）" onclick="jumpNextMark()">↓ 下一处改动</button>
      <button class="ghost small" onclick="document.getElementById('fvMask').remove()">关闭</button>
    </div>
    <div class="muted" style="line-height:1.7;padding:2px 0 8px">${esc(r.note || "")}</div>
    <div class="fv-legend"><span><i style="background:#0a2f18;border:1px solid var(--green)"></i>本提交新增/改过的行</span>
      <span><i style="background:#1e293b"></i>未改动（已折叠，点一下展开 ${FV_CHUNK} 行）</span></div>
    <div class="fileview" id="fvRows">${fvRuns(r)}</div>`;
}

/** 每处改动上下各 FV_NEAR 行直显（flags）；改动行本身另记（hits），标绿只给 hits */
function fvFlags(r) {
  const n = r.lines.length, flags = new Array(n + 1).fill(false), hits = new Array(n + 1).fill(false);
  (r.marks || []).forEach(m => {
    for (let i = Math.max(1, m.start); i <= Math.min(n, m.end); i++) hits[i] = true;
    for (let i = Math.max(1, m.start - FV_NEAR); i <= Math.min(n, m.end + FV_NEAR); i++) flags[i] = true;
  });
  return { flags, hits };
}

function fvRuns(r) {
  const out = [];
  let i = 1;
  while (i <= r.lines.length) {
    if (fv.flags[i]) { out.push(fvLine(i, r, fv.hits[i])); i++; continue; }
    let j = i;
    while (j <= r.lines.length && !fv.flags[j]) j++;
    out.push(fvGap(i, j - 1));
    i = j;
  }
  return out.join("");
}

const fvLine = (i, r, hit) =>
  `<div class="ln${hit ? " hit" : ""}"><span class="no">${i}</span><span class="tx">${esc(r.lines[i - 1]) || "&nbsp;"}</span></div>`;
const fvGap = (a, b) => `<div class="fv-gap" data-a="${a}" data-b="${b}">
    <button class="small" data-tip="这一段与本提交无关；一次展开 ${FV_CHUNK} 行，剩下的仍留成可再点的折叠段"
            onclick="expandRun(this)">⤵ 展开第 ${a}–${b} 行（未改动 ${b - a + 1} 行）</button></div>`;

/** 就地展开一段折叠：只灌 FV_CHUNK 行，剩下的仍留成一个可再点的折叠段 */
function expandRun(btn) {
  const box = btn.parentElement, parent = box.parentElement;
  const a = Number(box.dataset.a), b = Number(box.dataset.b);
  const html = [];
  let i = a;
  while (i <= b && i < a + FV_CHUNK) { html.push(fvLine(i, fv.r, fv.hits[i])); i++; }
  if (i <= b) html.push(fvGap(i, b));
  box.insertAdjacentHTML("beforebegin", html.join(""));
  box.remove();
  if (i <= b) {
    const rest = parent.querySelector(`.fv-gap[data-a="${i}"]`);
    if (rest) rest.scrollIntoView({ block: "nearest" });
  }
}

function jumpNextMark() {
  const rows = [...document.querySelectorAll("#fvRows .ln.hit")];
  if (!rows.length) { toast("这个文件里没有标出的改动行（原因看弹屏上面那行说明）", true); return; }
  const k = fv.cursor % rows.length;
  fv.cursor = k + 1;
  rows.forEach(x => x.classList.remove("flash"));
  const el = rows[k];
  el.scrollIntoView({ block: "center" });
  el.classList.add("flash");
  toast(`第 ${k + 1} / ${rows.length} 处标绿改动：第 ${el.querySelector(".no").textContent} 行`);
}


/* ---------- 选卡弹屏（第八轮，替换全量下拉：仓库筛选 + 关键字搜索 + 后端分页，防大列表卡死） ---------- */
async function openCardPicker(kind, onPick) {
  const isIfc = kind === "interface";
  const repos = (await api("/api/repos?projectId=" + state.projectId)).repos || [];
  const st = { page: 1, total: 0, q: "", repo: "" };
  const m = modal(`<h3>${isIfc ? "选择接口" : "选择学习到的 SQL"}</h3>
    <div class="row mb" style="gap:8px">
      <select id="cpRepo">
        <option value="">全部仓库</option>
        ${repos.map(r => `<option value="${r.id}">${esc(r.name)}（${(isIfc ? r.iface_count : r.db_count) || 0} ${isIfc ? "接口卡" : "SQL卡"}）</option>`).join("")}
        <option value="none">未归属仓库（手工卡/未绑定）</option></select>
      <input id="cpQ" style="flex:1" placeholder="关键字：搜${isIfc ? "名称或路径" : "名称或 SQL 文本"}，回车搜索">
      <button id="cpGo">搜索</button></div>
    <div style="max-height:52vh;overflow:auto">
      <table><tr>${isIfc ? "<th>方法</th><th>路径</th><th>名称</th><th>仓库</th><th>状态</th>" : "<th>类型</th><th>名称</th><th>归属仓库</th>"}</tr>
      <tbody id="cpRows"><tr><td class="empty">加载中…</td></tr></tbody></table></div>
    <div id="cpPager" class="pager-host"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
  const rowsEl = m.querySelector("#cpRows"), pagerEl = m.querySelector("#cpPager");
  const drawPager = () => { pagerEl.innerHTML = pagerBar({ key: "pickerCard", total: st.total, page: st.page, pager: "pickerCard" }); fitControls(pagerEl); };
  pagerDefine("pickerCard", p => { st.page = p; refresh(); }, () => { st.page = 1; refresh(); });
  async function refresh() {
    rowsEl.innerHTML = `<tr><td class="empty">加载中…</td></tr>`;
    const size = listSize("pickerCard");
    let url = isIfc
      ? `/api/interfaces?projectId=${state.projectId}&page=${st.page}&size=${size}`
      : `/api/db-objects?projectId=${state.projectId}&page=${st.page}&size=${size}`;
    if (st.q) url += "&q=" + encodeURIComponent(st.q);
    if (st.repo === "none") url += "&noRepo=1";
    else if (st.repo) url += "&repoId=" + st.repo;
    const r = await api(url);
    const rows = (isIfc ? r.interfaces : r.dbObjects) || [];
    st.total = r.total || 0;
    const pages = Math.max(1, Math.ceil(st.total / size));
    if (st.page > pages) { st.page = pages; return refresh(); }
    drawPager();
    rowsEl.innerHTML = rows.length ? rows.map(x => isIfc
      ? `<tr style="cursor:pointer" data-id="${x.id}"><td>${badge(x.http_method, x.http_method === "GET" ? "green" : "indigo")}</td>
         <td class="wrap"><code>${esc(x.path)}</code></td><td>${esc(x.name)}</td>
         <td class="muted">${esc(x.repo_name || "未归属")}</td><td>${statusBadge(x.status)}</td></tr>`
      : `<tr style="cursor:pointer" data-id="${x.id}"><td>${esc(x.obj_type)}</td>
         <td class="wrap">${esc(x.name)}</td><td class="muted">${esc(x.repo_names || "未归属")}</td></tr>`).join("")
      : `<tr><td class="empty" colspan="5">没有匹配的卡片${isIfc ? "（试试换仓库或清空关键字）" : ""}</td></tr>`;
    rowsEl.querySelectorAll("tr[data-id]").forEach(tr => tr.onclick = () => {
      const row = rows.find(x => x.id === Number(tr.dataset.id));
      m.remove();
      onPick(row);
    });
  }
  m.querySelector("#cpRepo").onchange = e => { st.repo = e.target.value; st.page = 1; refresh(); };
  m.querySelector("#cpGo").onclick = () => { st.q = m.querySelector("#cpQ").value.trim(); st.page = 1; refresh(); };
  m.querySelector("#cpQ").addEventListener("keydown", e => { if (e.key === "Enter") m.querySelector("#cpGo").click(); });
  await refresh();
}
function pickLabIfc() {
  openCardPicker("interface", row => {
    state.labIfcId = row.id;
    state.labIfcLabel = `[${row.http_method}] ${row.path} · ${row.name}（仓库：${row.repo_name || "未归属"}）`;
    const el = $("#labIfcLabel"); if (el) el.value = state.labIfcLabel;
    resetLabIdentity();
    loadLabIfc();
  });
}

/** 手动换接口卡 = 换了目标：用例身份、断言草稿、作用域全清，避免把这次执行记到不相干的用例/提交上 */
function resetLabIdentity() {
  window.__labCaseId = null; window.__labCaseName = null;
  window.__labAssertions = []; window.__labIgnore = [];
  state.labCommitId = null;
}

/* ---------- 接口测试台 ---------- */
views.lab = async () => {
  await loadProjects();
  return `
  <div class="row mb">${projectSelect()}<span class="muted">动态表单由接口卡的参数定义自动生成，接口形式不同无需改页面</span></div>
  <div class="grid cols2">
    <div class="card"><h3>① 选择接口</h3>
      <div class="row" style="gap:8px">
        <input id="labIfcLabel" readonly style="flex:1" placeholder="尚未选择接口（点右侧按钮搜索）" value="${esc(state.labIfcLabel || "")}">
        <button onclick="pickLabIfc()">🔍 选择接口</button></div>
      <div class="muted mt" style="font-size:12px">接口太多不再用下拉：弹屏按仓库筛选 + 关键字搜索 + 分页</div>
      <div id="labCaseBar" class="mt"></div>
      <div id="labRecent" class="mt"></div><div id="labCases" class="mt"></div></div>
    <div class="card"><h3>② 填参并执行</h3><div id="labForm" class="empty">${state.labIfcId ? "载入中…" : "先选择接口"}</div></div>
  </div>
  <div class="card" id="labResult" style="display:none"></div>`;
};
async function loadLabIfc(keepForm) {
  const id = state.labIfcId;
  if (!id) return;
  const { interface: itf } = await api("/api/interfaces/" + id);
  const cases = (await api("/api/cases?targetType=interface&targetId=" + id)).cases;
  const schema = itf.params_schema ? JSON.parse(itf.params_schema) : null;
  const repoDirectOk = itf.repo_id != null && itf.repo_base_url && String(itf.repo_base_url).trim();
  const flows = (await api("/api/login-flows?projectId=" + itf.project_id)).loginFlows || [];
  const directDesc = itf.repo_id == null ? "该接口无仓库归属（手工卡或未学习），只能走项目环境"
    : repoDirectOk ? `直接打「${itf.repo_name}」→ ${itf.repo_base_url}，跳过登录流`
    : `仓库「${itf.repo_name || "?"}」未配置服务地址，请到 [项目与仓库→编辑] 填写后才可直连`;
  if (!keepForm) $("#labForm").innerHTML = `
    <div class="row spread muted mb" style="align-items:center"><span>${esc(itf.http_method)} ${esc(itf.path)} · ${esc(itf.name)}${
      itf.function_no ? ` <span style="color:#16a34a">· 功能号 ${esc(itf.function_no)}（报文头按规则预填，你只改业务值）</span>` : ""
    }</span>
      <span><button class="small ghost" onclick="copyCurl(${id})">复制 cURL</button>
      <button class="small ghost" onclick="ifcHooks(${id})">⚙ 前后置钩子</button></span></div>
    <div class="form-item"><label>访问方式</label>
      <label class="f" style="color:#2563eb;display:block"><input type="radio" name="labAccess" value="env" checked>
        🟦 项目环境 · 全流程（自动登录 + 环境 Base URL）</label>
      <label class="f" style="color:${repoDirectOk ? "#16a34a" : "#94a3b8"};display:block"><input type="radio" name="labAccess" value="repo" ${repoDirectOk ? "" : "disabled"}>
        🟩 仓库直连 · 不登录（${esc(repoDirectOk ? directDesc : "不可用")}${itf.access === "write" || itf.access === "mixed" ? "；⚠ 写接口直连会真实落库" : ""}）</label>
      <div class="muted" style="font-size:12px">${esc(directDesc)}；两种方式的钩子 SQL 与 DB 回读断言都走下方所选环境的数据库连接</div></div>
    <div class="form-item"><label>这次真正发出去的路径</label><div id="labPath"><span class="muted">正在按盘上证据算这一次要发的路径…</span></div></div>
    <div class="form-item"><label>测试环境</label><select id="labEnv">
      ${(await api("/api/environments?projectId=" + itf.project_id)).environments.map(e => `<option value="${e.id}">${esc(e.name)}</option>`).join("") || "<option value=''>（无环境，请先到系统设置添加）</option>"}</select></div>
    <div class="form-item"><label>登录流（执行前自动登录并注入会话）</label><select id="labFlow" style="width:100%">
      ${flows.map(f => `<option value="${f.id}">${esc(f.name)}${f.cred_id ? "" : " · 未绑定凭据"}</option>`).join("")}
      <option value="-1">不登录（用环境里的 Cookie 接管或接口本身免登录）</option></select>
      <div class="muted" style="font-size:12px">${flows.length
        ? "一个项目有多条登录流时在这里显式选一条（不同系统/不同账号）；🟩 仓库直连方式不跑登录流。登录流与账号在 [系统设置→登录流] 维护"
        : "该项目还没有登录流：可在 [系统设置→登录流] 自己添加。学到分发框架仓库（如 work_app）且这个项目一条登录流都没有时，系统才会自动生成一条 KJDP 模板流给你绑凭据；已有登录流的项目不再插模板流（第卅一轮）"}</div></div>
    ${schema && schema.dispatch ? dispatchFormHtml(schema, itf) : renderSchemaForm(schema, itf.body_sample, itf.param_note)}
    <div id="labAssert" class="mt">
      <h3 style="font-size:13px">断言 <button class="small ghost" onclick="addAssertion()">＋ 加断言</button>
      <span class="muted">L1 状态/耗时 · L2 JSONPath · L4 DB回读（{{ret.xx}} 引用响应）· 临时断言对本次执行即时生效，点【带入用例】→【保存断言到用例】可永久保存</span></h3>
      <div id="labAssertRows"></div>
    </div>
    <div class="mt"><button onclick="runLab(${id})">▶ 执行测试</button></div>`;
  if (!(schema && schema.dispatch)) dsp = null;
  else if (!keepForm) {
    renderDispatchBody();
    loadDispatchStatus();
    // 一码一路径：功能号就是这张卡自己带的，不必等人再选一次——进面板就把字典里的入参与出参证据填上
    if (dsp.spec.mode === "call-path" && dsp.code) applyDispatchCode(dsp.code, dsp.codeName);
  }
  if (!keepForm) {
    labPathMode = "";   // 换一张卡就回到"听单点的"，上次的手动开关不许留在下一张卡上
    bindLabPath(id);
  }
  refreshLabPath(id);
  // R14：最近参数快选（历史执行过的参数一键带入）
  api(`/api/interfaces/${id}/recent-params`).then(rp => {
    const box2 = $("#labRecent");
    if (!box2) return;
    box2.innerHTML = rp.items && rp.items.length ? `<h3 style="font-size:13px" class="mt">最近参数</h3>` +
      rp.items.slice(0, 5).map((it, i) => `<button class="small ghost" style="margin:2px" onclick='applyRecent(${esc(JSON.stringify(it))})'>#${i + 1} ${esc(it.name)}</button>`).join("")
      : "";
  }).catch(() => {});
  $("#labCases").innerHTML = cases && cases.length ? `<h3 style="font-size:13px">关联用例</h3>` +
    cases.map(c => `<div class="row spread" style="padding:5px 0;border-bottom:1px solid #f1f5f9">
      <span>${esc(c.name)} <span class="muted">${esc(ORIGIN_CN[c.origin] || c.origin || "")}</span></span>
      <span><button class="small ghost" onclick='applyCase(${esc(JSON.stringify(c))})'>带入</button>
      <button class="small ghost" onclick="repairCase(${c.id})">修复</button></span></div>`).join("")
    : `<div class="muted mt">暂无用例：这个接口还没生成过。到「改动流」找到改动它的提交点【生成用例】，或在「知识库 → 🧪 用例」按目标类型=接口筛选后【到测试台打开】</div>`;
  renderLabCaseBar();
  fitControls($("#labForm")?.parentElement);
  consumeLabCaseHandoff(cases);
}

/**
 * 用例目录【到测试台打开】→ 接口测试台的交接（第卅三轮）。
 * 表单是这里异步重建出来的，所以值只能在重建完之后灌一次：
 * 挂成"待灌"而不是每次刷新都灌，否则用户后来手改的参数会被这条用例悄悄盖回去。
 */
function consumeLabCaseHandoff(cases) {
  const pid = window.__pendingLabCaseId;
  if (pid == null) return;
  window.__pendingLabCaseId = null;
  const c = (cases || []).find(x => String(x.id) === String(pid));
  if (!c) { toast("这条用例的参数没带过来：它已经不在当前这张接口卡名下（被删过或改过目标），值请按场景手填", true); return; }
  applyCase(c);
}

/**
 * 「当前用例」常驻一行（第 20 轮）：执行结果记在谁头上，必须在点【▶ 执行】之前就看到，
 * 而不是跑完了才发现"原来刚才那次算的是另一条用例"。
 */
function renderLabCaseBar() {
  const el = document.getElementById("labCaseBar");
  if (!el) return;
  const id = window.__labCaseId;
  el.innerHTML = id
    ? `<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">${badge("已挂用例", "green")}
        <b>${esc(window.__labCaseName || ("#" + id))}</b>
        <span class="muted">这次执行计入该用例的通过率${state.labCommitId ? `，并回写提交 #${state.labCommitId} 的覆盖率` : ""}</span>
        <button class="small ghost" onclick="unbindLabCase()">改为直测（不挂用例）</button></div>`
    : `<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">${badge("直测", "blue")}
        <span class="muted">未挂用例：只留痕，不计入用例通过率与覆盖率${state.labCommitId ? `（作用域提交 #${state.labCommitId}）` : ""}。
        要让它算进某条用例，点下面【关联用例】里的「带入」</span></div>`;
}
function unbindLabCase() {
  window.__labCaseId = null; window.__labCaseName = null;
  window.__labAssertions = []; window.__labIgnore = [];
  loadLabIfc();
}
/** 用例 → 测试台时把身份带过来；SQL 侧同理（runSql 会读这两个变量）。
 *  作用域 state.labCommitId 只由跳转入口显式设置，这里不猜——猜错会把覆盖率写到别的提交上。 */
function bindLabCase(id, name) {
  window.__labCaseId = id || null;
  window.__labCaseName = name || null;
  renderLabCaseBar();
}
function applyRecent(it) {
  let p = it.params;
  if (typeof p === "string") { try { p = JSON.parse(p); } catch (e) { return toast("参数解析失败", true); } }
  applyCase({ id: it.case_id, name: "最近#" + it.case_id, params: p, assertions: "[]", ignore_fields: "[]" });
}
/**
 * 本次请求要发的路径口径（第五十轮）。
 * "" = 听后端单点的（学到的内部路径经网关补前缀）；"internal" = 手动改按卡片学到的内部路径发。
 * 这个开关<b>只活在这一次点击</b>：不落库、不改卡片、换一张卡就回零——手动能力留着，但不是默认。
 */
let labPathMode = "";

function bindLabPath(id) {
  const env = $("#labEnv");
  if (env) env.addEventListener("change", () => refreshLabPath(id));
  document.querySelectorAll('input[name=labAccess]').forEach(r =>
    r.addEventListener("change", () => refreshLabPath(id)));
}

const LAB_PATH_COLOR = { evidenced: "#16a34a", inferred: "#2563eb", missing_input: "#d97706",
  manual: "#64748b", not_applicable: "#64748b" };

/**
 * 四态 → 颜色的第二个出口（徽章版）：与上面的 LAB_PATH_COLOR 同档位、同配色，
 * 知识库「对外要发的路径」列用它，测试台那行用上面那份——一个事实一套色，两处各调会打架。
 */
const CALL_BADGE = { evidenced: "green", inferred: "blue", missing_input: "amber",
  manual: "gray", not_applicable: "gray" };
const callBadge = (state, label) => `<span class="badge b-${CALL_BADGE[state] || "gray"}">${esc(label || state || "?")}</span>`;

/** 恒显「学到的是哪条路径、这次发出去的是哪条、依据是哪个文件哪一行」，并随访问方式与环境实时重问 */
async function refreshLabPath(id) {
  const box = $("#labPath");
  if (!box) return;
  const acc = document.querySelector('input[name=labAccess]:checked');
  const env = $("#labEnv");
  const qs = new URLSearchParams();
  if (env && env.value) qs.set("envId", env.value);
  if (acc && acc.value === "repo") qs.set("repoDirect", "true");
  if (labPathMode) qs.set("pathMode", labPathMode);
  let r;
  try { r = await api("/api/interfaces/" + id + "/call-path?" + qs.toString()); }
  catch (e) { box.innerHTML = `<span style="color:var(--red)">这一趟没问到路径依据：${esc(String(e))}</span>`; return; }
  if (!r || !r.ok) { box.innerHTML = `<span style="color:var(--red)">${esc((r && r.error) || "路径依据端点没答")}</span>`; return; }
  const col = LAB_PATH_COLOR[r.state] || "#64748b";
  const seg = (r.segments || []).map(s => `<code>${esc(s.value)}</code> <span class="muted" style="font-size:12px">${esc(s.evidence)}</span>`).join("　+　");
  let html = `<div style="font-size:12.5px">学到的内部路径：<code>${esc(r.internal)}</code></div>`;
  /** 改口按钮只有这一个出口：两档各摆一次，切过去的方向永远还能切回来（手动能力不给收走） */
  const flipBtn = `<button class="small ghost" onclick="setLabPathMode(${id}, '${labPathMode ? "" : "internal"}')">
        ${labPathMode ? "↩ 改回按对外路径发" : "✎ 本次改按内部路径发"}</button>`;
  if (r.changed) {
    html += `<div style="font-size:12.5px;color:${col};margin-top:3px"><b>这次真正发出：${esc(r.path)}</b>
      <span class="badge" style="background:${col};color:#fff">${esc(r.state_label)}</span>
      ${flipBtn}</div>
      <div class="muted" style="font-size:12px;margin-top:2px">补上去的段：${seg}</div>`;
    if ((r.evidence || []).length)
      html += `<div class="muted" style="font-size:12px;margin-top:2px">依据：${r.evidence.map(esc).join("；")}</div>`;
    html += `<div class="muted" style="font-size:12px;margin-top:2px">那个按钮只改本次执行与本次复制 cURL，不改卡片、不落库。${esc(r.basis)}</div>`;
  } else {
    html += `<div style="font-size:12.5px;color:${col};margin-top:3px">这次发出：<code>${esc(r.path)}</code>
      <span class="badge" style="background:${col};color:#fff">${esc(r.state_label)}</span>
      ${labPathMode ? flipBtn : ""}</div>
      <div class="muted" style="font-size:12px;margin-top:2px">${esc(r.note)}</div>`;
  }
  box.innerHTML = html;
}

function setLabPathMode(id, mode) {
  labPathMode = mode || "";
  refreshLabPath(id);
}
async function copyCurl(id) {
  const envEl = $("#labEnv");
  const acc = document.querySelector('input[name=labAccess]:checked');
  const body = { params: collectForm($("#labForm")) };
  if (acc && acc.value === "repo") body.repoDirect = true;
  else if (envEl && envEl.value) body.envId = Number(envEl.value);
  if (labPathMode) body.pathMode = labPathMode;
  const r = await api(`/api/interfaces/${id}/curl`, body);
  if (!r.ok) return toast("cURL 生成失败：" + r.error, true);
  const srcLabel = acc && acc.value === "repo" ? "仓库直连地址" : "环境 base_url";
  try { await navigator.clipboard.writeText(r.curl); toast(r.curl.includes("http") ? `cURL 已复制（含${srcLabel}）` : "cURL 已复制，但未配置服务地址（生成的是相对路径）", !r.curl.includes("http")) }
  catch (e) { modal(`<h3>cURL（复制失败，可手动选取）</h3><pre class="json">${esc(r.curl)}</pre>`); }
}
function applyCase(c) {
  const p = typeof c.params === "string" ? JSON.parse(c.params || "{}") : (c.params || {});
  const form = $("#labForm");
  let filled = 0, missed = [];
  for (const src of [p.path || {}, p.query || {}]) {
    for (const [k, v] of Object.entries(src)) {
      const inp = form.querySelector(`[data-q="${k}"]`);
      if (inp) { inp.value = v; filled++; } else missed.push(k);
    }
  }
  const b = p.body || {};
  const flat = form.querySelector('[data-body="@flat"]');
  if (flat) {
    let payload = b;
    const pname = flat.dataset.param;
    // 老用例按 Java 形参名嵌套存过（{"requestModel":{...}}），带进来时剥掉那一层
    if (Object.keys(b).length === 1 && b[pname] && typeof b[pname] === "object") payload = b[pname];
    flat.value = JSON.stringify(payload, null, 2);
    filled += Object.keys(payload).length;
  } else {
    for (const [k, v] of Object.entries(b)) {
      const ta = form.querySelector(`[data-body="${k}"]`);
      if (ta) { ta.value = JSON.stringify(v, null, 2); filled++; } else missed.push(k);
    }
  }
  bindLabCase(c.id, c.name);
  renderAssertionEditor(c);
  // 灌了多少、哪些名字这张卡根本没有，必须当场说清：只报"已带入"会让人以为场景值全进去了
  toast(missed.length
      ? `用例参数已带入 ${filled} 项；${missed.length} 项这个接口没有对应字段，没填上：${missed.slice(0, 6).join("、")}${missed.length > 6 ? "…" : ""}`
      : `用例参数已带入 ${filled} 项`,
    missed.length ? true : false);
}

/* 断言编辑器（L1 状态码/耗时 · L2 JSONPath · L4 DB 回读） */
function renderAssertionEditor(c) {
  const box = $("#labAssert");
  if (!box) return;
  const assertions = typeof c.assertions === "string" ? JSON.parse(c.assertions || "[]") : (c.assertions || []);
  const ignore = typeof c.ignore_fields === "string" ? JSON.parse(c.ignore_fields || "[]") : (c.ignore_fields || []);
  window.__labAssertions = assertions;
  window.__labIgnore = ignore;
  drawAssertionRows();
  box.insertAdjacentHTML("beforeend", `
    <div class="row mt"><span class="muted">diff 忽略字段（逗号分隔）</span></div>
    <input id="ignoreFields" style="width:100%" value="${esc(ignore.join(","))}" placeholder="createTime,traceId,orderId">
    <div class="row mt"><button class="ghost" onclick="saveAssertions(${c.id})">保存断言到用例</button></div>`);
}
function drawAssertionRows() {
  const box = $("#labAssertRows");
  box.innerHTML = (window.__labAssertions || []).map((a, i) => `
    <div class="row" style="padding:4px 0">
      <select data-ai="${i}" data-ak="level" style="width:64px">${[1,2,4].map(l => `<option value="${l}" ${a.level==l?"selected":""}>L${l}</option>`).join("")}</select>
      <select data-ai="${i}" data-ak="type" style="width:110px">${[["status","状态码"],["latency","耗时ms"],["jsonpath","JSONPath"],["db","DB回读"]].map(([v,t]) => `<option value="${v}" ${a.type==v?"selected":""}>${t}</option>`).join("")}</select>
      <input data-ai="${i}" data-ak="expr" style="flex:1" placeholder="表达式 / SQL（L4 支持 {{ret.xx}}）" value="${esc(a.expr || "")}">
      <select data-ai="${i}" data-ak="op" style="width:96px">${["eq","ne","contains","regex","gt","lt","range","notempty"].map(o => `<option ${a.op==o?"selected":""}>${o}</option>`).join("")}</select>
      <input data-ai="${i}" data-ak="expected" style="width:140px" placeholder="期望值" value="${esc(a.expected == null ? "" : a.expected)}">
      <button class="small danger" onclick="delAssertion(${i})">×</button>
    </div>`).join("") || `<div class="empty">无断言（默认 2xx 即通过）</div>`;
}
function addAssertion() {
  window.__labAssertions = window.__labAssertions || [];
  window.__labAssertions.push({ level: 2, type: "jsonpath", expr: "$.code", op: "eq", expected: "0" });
  drawAssertionRows();
}
function delAssertion(i) { window.__labAssertions.splice(i, 1); drawAssertionRows(); }
function collectAssertionEdits() {
  document.querySelectorAll("#labAssertRows [data-ai]").forEach(el => {
    window.__labAssertions[Number(el.dataset.ai)][el.dataset.ak] =
      ["level"].includes(el.dataset.ak) ? Number(el.value) : el.value;
  });
  return window.__labAssertions || [];
}
async function saveAssertions(caseId) {
  collectAssertionEdits();
  const ignore = ($("#ignoreFields").value || "").split(",").map(s => s.trim()).filter(Boolean);
  const r = await api(`/api/cases/${caseId}`, { assertions: window.__labAssertions, ignoreFields: ignore }, "PATCH");
  toast(r.ok ? "断言已保存到用例" : r.error, !r.ok);
}
async function setBaseline(caseId, runId) {
  if (!caseId) return toast("请先在左侧「关联用例」或「最近参数」点【带入】，基于用例执行才能设基线", true);
  const r = await api(`/api/cases/${caseId}/set-baseline`, { runId });
  toast(r.ok ? (runId ? "已设为基线，后续执行自动 diff" : "已清除基线") : r.error, !r.ok);
}
async function repairCase(caseId) {
  const r = await api(`/api/cases/${caseId}/repair`, {}, null, { progress: true });
  toast(r.ok ? ("修复完成：" + ((r.notes && r.notes.length) ? r.notes.join("；") : "参数已与最新 schema 对齐")) : r.error, !r.ok);
  if (r.ok) loadLabIfc(true);
}
async function ifcHooks(id) {
  const { interface: itf } = await api("/api/interfaces/" + id);
  const lines = v => { try { const a = JSON.parse(typeof v === "string" ? v : JSON.stringify(v || [])); return (a || []).join("\n"); } catch (e) { return ""; } };
  const m = modal(`<h3>⚙ 前后置钩子 · ${esc(itf.name)}</h3>
    <div class="muted mb">每行一条 SQL：前置在执行接口前跑（准备数据），后置在响应后跑（回读校验/清理）；全部写入审计留痕</div>
    <div class="form-item"><label>前置 SQL（pre）</label><textarea id="hkPre" class="code" placeholder="UPDATE t_order SET status='PAID' WHERE id=1">${esc(lines(itf.pre_sql))}</textarea></div>
    <div class="form-item"><label>后置 SQL（post）</label><textarea id="hkPost" class="code" placeholder="SELECT COUNT(*) FROM t_order WHERE id=1">${esc(lines(itf.post_sql))}</textarea></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="hkSave">保存</button></div>`);
  m.querySelector("#hkSave").onclick = async () => {
    const toArr = v => v.split("\n").map(s => s.trim()).filter(Boolean);
    const r = await api(`/api/interfaces/${id}/hooks`, { preSql: toArr(m.querySelector("#hkPre").value), postSql: toArr(m.querySelector("#hkPost").value) }, "PATCH");
    toast(r.ok ? "钩子已保存，下次执行生效（清空即移除）" : r.error, !r.ok);
    if (r.ok) m.remove();
  };
}
async function runLab(interfaceId) {
  const envId = Number($("#labEnv").value || 0);
  if (!envId) return toast("请先配置测试环境", true);
  const params = collectForm($("#labForm"));
  const body = { envId, overrides: params };
  const acc = document.querySelector('input[name=labAccess]:checked');
  if (acc && acc.value === "repo") body.repoDirect = true;
  if (labPathMode) body.pathMode = labPathMode;
  const flowSel = $("#labFlow");
  if (flowSel) body.loginFlowId = Number(flowSel.value);
  // 第 20 轮：身份只认"人明确带进来的那条用例"。原先没挂用例会偷偷挑一条"承载用例"，
  // 结果记到别的用例头上（连基线都可能被串台）——现在改成目标级直测，只留痕不记别人账上。
  if (window.__labCaseId) body.caseId = window.__labCaseId;
  else { body.targetType = "interface"; body.targetId = interfaceId; }
  if (state.labCommitId) body.commitId = state.labCommitId;
  toast("执行中…");
  collectAssertionEdits();
  if ((window.__labAssertions || []).length) body.assertions = window.__labAssertions;
  const r = await api("/api/test-runs", body, null, { progress: true });
  const box = $("#labResult");
  box.style.display = "block";
  if (!r.ok) { box.className = "card result-fail"; box.innerHTML = `<h3>执行失败</h3><div>${esc(r.error)}</div>`; return; }
  const run = r.run;
  const assertions = run.assertions || [];
  const diffs = typeof run.baselineDiff === "string" ? JSON.parse(run.baselineDiff || "[]") : [];
  box.className = "card " + (run.status === "pass" ? "result-ok" : "result-fail");
  box.innerHTML = `<h3>执行结果 ${statusBadge(run.status)} <span class="muted">${run.latencyMs}ms · HTTP ${run.httpStatus}</span></h3>
    ${runOwnerLine(body.caseId ? { id: body.caseId, name: window.__labCaseName } : null, run.runId, state.labCommitId)}
    ${run.request ? `<div class="muted mb">${esc(run.request)}</div>` : ""}
    ${run.error ? `<div class="mb" style="color:var(--red)">失败原因：${esc(run.error)}</div>` : ""}
    ${assertions.length ? `<div class="mb"><b style="font-size:13px">断言明细</b>
      <table><tr><th>层</th><th>类型</th><th>表达式</th><th>期望</th><th>实际</th><th>结果</th></tr>
      ${assertions.map(a => `<tr><td>L${a.level}</td><td>${esc(a.type)}</td><td class="wrap"><code>${esc(a.expr || "")}</code></td>
        <td>${esc(a.expected == null ? "" : a.expected)}</td><td class="wrap">${esc(a.actual == null ? "-" : a.actual)}</td>
        <td>${a.pass ? statusBadge("pass") : statusBadge("fail")}</td></tr>`).join("")}</table></div>` : ""}
    ${diffs.length ? `<div class="mb"><b style="font-size:13px">与基线差异（${diffs.length} 处）</b>
      <table><tr><th>路径</th><th>基线</th><th>本次</th></tr>
      ${diffs.slice(0, 20).map(d => `<tr><td class="wrap"><code>${esc(d.path)}</code></td>
        <td class="wrap">${esc(d.baseline)}</td><td class="wrap">${esc(d.current)}</td></tr>`).join("")}</table></div>`
      : (run.baselineDiffCount === 0 ? `<div class="muted mb">与基线完全一致 ✅</div>` : "")}
    <pre class="json">${esc(typeof run.body === "string" ? run.body : fmtParams(run.body))}</pre>
    <div class="row mt">${window.__labCaseId
      ? `<button class="ghost small" onclick="setBaseline(${window.__labCaseId}, ${run.runId})">📌 设本次为基线</button>
         <button class="ghost small" onclick="setBaseline(${window.__labCaseId}, null)">清除基线</button>`
      : `<span class="muted">基线只能挂在具体用例上：点【关联用例 → 带入】后才可设基线（直测没有用例可挂）</span>`}
    <button class="ghost small" onclick="reportFinding(${run.runId})">🚩 转缺陷留痕</button>
    <button class="ghost small" onclick="openRuns({targetType:'interface',targetId:${interfaceId}},'接口卡 #${interfaceId} 的执行记录')">📜 这个接口的执行记录</button></div>`;
}

/**
 * 结果区的归属行（第 20 轮）：一眼看清"这次执行算谁的"。
 * 用户报的"我明明测过了，这里怎么还是未跑过"，根因之一就是原先界面上从不说明留痕记到了哪儿。
 */
function runOwnerLine(owner, runId, commitId) {
  return `<div class="row mb" style="gap:8px;align-items:center;font-size:13px;flex-wrap:wrap">
    ${owner ? `${badge("计入用例", "green")}<b>${esc(owner.name || ("#" + owner.id))}</b>
        <span class="muted">通过/失败与覆盖率都算在这条用例头上</span>`
      : `${badge("直测留痕", "blue")}<span class="muted">未挂用例：只记录这次执行，不计入用例通过率与覆盖率</span>`}
    ${commitId ? `<span class="muted">· 作用域：提交 #${commitId}</span>` : ""}
    <span class="muted">· 执行记录 #${runId}</span>
    <button class="small ghost" onclick="openRunDetail(${runId})">看这条记录</button></div>`;
}

async function reportFinding(runId) {
  const r = await api("/api/findings", { runId });
  toast(r.ok ? "已转缺陷留痕（findings #" + r.id + "）" : r.error, !r.ok);
}

/* ---------- 执行记录（第 20 轮补的洞）----------
   test_runs 里躺了两百多条执行留痕，全站却没有一处能查——用户"测过了"却看不到任何凭据，
   只能理解成"点了没反应"。这里给一个通用抽屉：入口带什么过滤条件（用例/目标/提交/直测）就按什么查，
   全部条件都在服务端生效并分页，列表刻意不返回快照（单条可达数百 KB），点开详情再取。 */
const MODE_CN = { http: "接口调用", db: "SQL 执行", direct: "直测" };
let runsCtx = null;   // 当前抽屉的 { filter, title }，翻页/改条件都复用它
async function openRuns(filter, title) {
  const f = Object.assign({}, filter || {});
  runsCtx = { filter: f, title: title || "执行记录", page: 1, status: f.status || "", via: f.via || "" };
  delete f.status; delete f.via;
  const m = modal(`<h3>📜 ${esc(runsCtx.title)}</h3>
    <div class="row mb" style="gap:8px;align-items:flex-end">
      <span style="display:flex;flex-direction:column;gap:2px"><label class="f" style="margin:0">结果</label>
        <select id="rnStatus"><option value="">全部</option><option value="pass">通过</option><option value="fail">失败</option></select></span>
      <span style="display:flex;flex-direction:column;gap:2px"><label class="f" style="margin:0">归属</label>
        <select id="rnVia"><option value="">用例与直测</option><option value="case">挂了用例的执行</option><option value="direct">直测留痕（未挂用例）</option></select></span>
      <span class="muted" id="rnScope"></span></div>
    <div style="max-height:56vh;overflow:auto">
      <table><tr><th>#</th><th>时间</th><th>归属</th><th>目标</th><th>方式</th><th>环境</th><th>结果</th><th>操作</th></tr>
      <tbody id="rnRows"><tr><td class="empty" colspan="8">加载中…</td></tr></tbody></table></div>
    <div id="rnPager" class="pager-host"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
  const rowsEl = m.querySelector("#rnRows"), pagerEl = m.querySelector("#rnPager");
  m.querySelector("#rnStatus").value = runsCtx.status;
  m.querySelector("#rnVia").value = runsCtx.via;
  const scopeBits = [];
  if (f.caseId) scopeBits.push("用例 #" + f.caseId);
  if (f.targetType) scopeBits.push("目标 " + f.targetType + " #" + f.targetId);
  if (f.commitId) scopeBits.push("提交 #" + f.commitId);
  if (f.envId) scopeBits.push("环境 #" + f.envId);
  // 项目是每次请求隐式带上的过滤条件（手敲语句的直测按环境归项目），所以必须在范围行里明说，
  // 不然用户看到"直测一条都没有"会以为记录丢了
  const projBit = () => state.projectId
    ? "项目「" + ((state.projects.find(p => p.id === state.projectId) || {}).name || ("#" + state.projectId)) + "」" : "";
  const drawScope = () => {
    const bits = [...scopeBits, projBit()].filter(Boolean);
    m.querySelector("#rnScope").textContent = bits.length ? "筛选范围：" + bits.join(" · ") : "全部执行记录";
  };
  drawScope();
  pagerDefine("runs", p => { runsCtx.page = p; refresh(); }, () => { runsCtx.page = 1; refresh(); });
  const drawPager = () => { pagerEl.innerHTML = pagerBar({ key: "runs", total: runsCtx.total, page: runsCtx.page, pager: "runs" }); fitControls(pagerEl); };
  async function refresh() {
    drawScope();
    rowsEl.innerHTML = `<tr><td class="empty" colspan="8">加载中…</td></tr>`;
    const size = listSize("runs");
    const p = new URLSearchParams({ page: runsCtx.page, size });
    // 看板上的入口没有项目上下文（state.projectId 可能还没选过项目）：没选就不传，绝不高传 "null"
    if (state.projectId) p.set("projectId", state.projectId);
    for (const [k, v] of Object.entries(f)) if (v !== null && v !== undefined && v !== "") p.set(k, v);
    if (runsCtx.status) p.set("status", runsCtx.status);
    if (runsCtx.via) p.set("via", runsCtx.via);
    const r = await api("/api/test-runs?" + p.toString());
    if (!r.ok) { rowsEl.innerHTML = `<tr><td class="empty" colspan="8">加载失败：${esc(r.error || "服务无响应")}</td></tr>`; return; }
    runsCtx.total = Number(r.total) || 0;
    const pages = Math.max(1, Math.ceil(runsCtx.total / size));
    if (runsCtx.page > pages) { runsCtx.page = pages; return refresh(); }
    drawPager();
    const rows = r.runs || [];
    runsCtx.rows = rows;
    rowsEl.innerHTML = rows.length ? rows.map(x => `<tr>
        <td>${x.id}</td>
        <td class="muted" style="white-space:nowrap">${esc(fmtTime(x.executed_at))}<br><span style="font-size:11px">${esc(x.executed_by || "")}</span></td>
        <td>${x.case_id ? `<span class="wrap">${esc(x.case_name || ("用例 #" + x.case_id))}</span>` : badge("直测", "blue")}</td>
        <td class="wrap">${esc(x.target_name || (x.target_type + " #" + x.target_id))}${x.commit_id ? `<br><span class="muted" style="font-size:11px">提交 #${x.commit_id}</span>` : ""}</td>
        <td class="muted">${esc(MODE_CN[x.mode] || x.mode || "")}</td>
        <td class="muted">${esc(x.env_name || "-")}</td>
        <td>${statusBadge(x.status)}${bizBadge(x.biz_error_code, x.biz_error_count)}<span class="muted" style="font-size:11px"> ${x.latency_ms}ms</span>
            ${x.error_brief ? `<br><span style="color:var(--red);font-size:11px">${esc(String(x.error_brief).slice(0, 60))}</span>` : ""}</td>
        <td style="white-space:nowrap"><button class="small ghost" onclick="openRunDetail(${x.id})">详情</button></td></tr>`).join("")
      : `<tr><td class="empty" colspan="8">这个范围里还没有执行记录${runsCtx.via === "direct" ? "（直测不会自动产生记录：要在测试台/SQL 直测点过【▶ 执行】）" : "——这条用例还没跑过：回到列表点【▶ 执行这条】"}</td></tr>`;
    fitControls(rowsEl);
  }
  m.querySelector("#rnStatus").onchange = e => { runsCtx.status = e.target.value; runsCtx.page = 1; refresh(); };
  m.querySelector("#rnVia").onchange = e => { runsCtx.via = e.target.value; runsCtx.page = 1; refresh(); };
  await refresh();
}
/** 单条记录详情：请求/响应快照、断言明细、基线差异、钩子结果都在这里看（列表页不返回这些大字段） */
async function openRunDetail(id) {
  const r = await api("/api/test-runs/" + id);
  if (!r.ok) return toast(r.error || "记录不存在", true);
  const x = r.run;
  const j = s => { try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return String(s || ""); } };
  const big = (label, s) => {
    if (!s) return "";
    const kb = Math.round(s.length / 1024 * 10) / 10;
    return `<div class="mt"><b style="font-size:13px">${esc(label)}</b><span class="muted" style="font-size:11px"> ${kb} KB</span>
      <pre class="json" style="max-height:320px;overflow:auto">${esc(kb > 200 ? s.slice(0, 200 * 1024) + "\n…（内容过大，此处截断）" : s)}</pre></div>`;
  };
  const parsed = (() => { try { return JSON.parse(x.response_snapshot || "{}"); } catch (e) { return {}; } })();
  const a = parsed.assertions || [];
  const d = parsed.baselineDiff || x.baseline_diff;
  const diffs = typeof d === "string" ? (() => { try { return JSON.parse(d || "[]"); } catch (e) { return []; } })() : (d || []);
  modal(`<h3>执行记录 #${x.id} ${statusBadge(x.status)}${bizBadge(parsed.bizErrorCode, (parsed.bizErrors || []).length)}</h3>
    <div class="muted" style="font-size:12px">${esc(fmtTime(x.executed_at))} · ${esc(x.executed_by || "")} ·
      ${esc(MODE_CN[x.mode] || x.mode || "")} · ${x.latency_ms}ms ·
      ${x.case_id ? "计入用例 #" + x.case_id : "直测留痕（未挂用例，不计入通过率与覆盖率）"}
      ${x.commit_id ? " · 作用域提交 #" + x.commit_id : ""}</div>
    <div class="mt"><b style="font-size:13px">目标</b> ${esc(x.target_type)} #${x.target_id}${parsed.httpStatus ? " · HTTP " + parsed.httpStatus : ""}
      ${parsed.updateCount >= 0 ? " · 影响行数 " + parsed.updateCount : ""}${parsed.committed ? ` <span style="color:var(--red)">已提交（不可回滚）</span>` : ""}</div>
    ${a.length ? `<div class="mt"><b style="font-size:13px">断言明细</b>
      <table><tr><th>层</th><th>类型</th><th>表达式</th><th>期望</th><th>实际</th><th>结果</th></tr>
      ${a.map(y => `<tr><td>L${y.level}</td><td>${esc(y.type)}</td><td class="wrap"><code>${esc(y.expr || "")}</code></td>
        <td>${esc(y.expected == null ? "" : y.expected)}</td><td class="wrap">${esc(y.actual == null ? "-" : y.actual)}</td>
        <td>${y.pass ? statusBadge("pass") : statusBadge("fail")}</td></tr>`).join("")}</table></div>` : ""}
    ${diffs.length ? `<div class="mt"><b style="font-size:13px">与基线差异（${diffs.length} 处）</b>
      <table><tr><th>路径</th><th>基线</th><th>本次</th></tr>
      ${diffs.slice(0, 20).map(y => `<tr><td class="wrap"><code>${esc(y.path)}</code></td><td class="wrap">${esc(y.baseline)}</td><td class="wrap">${esc(y.current)}</td></tr>`).join("")}</table></div>` : ""}
    ${parsed.error ? `<div class="mt" style="color:var(--red)">失败原因：${esc(parsed.error)}</div>` : ""}
    ${renderBiz(parsed)}
    ${big("请求快照", x.request_snapshot)}
    ${big("响应快照", x.response_snapshot)}
    <div class="actions">
      <button class="ghost" onclick="reportFinding(${x.id})">🚩 转缺陷留痕</button>
      <button onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
}

/* ---------- SQL 直测 ---------- */
/* 第 26 轮实测（N41）：这里原先先 await 项目、再 await 环境，两个请求没回来之前 #view 里只有
   一句"加载中…"，整页白屏到 27 秒被判"页面无响应"。现在壳子同步返回，数据到位后各自回填。 */
views.sql = () => {
  sqlBootstrap();
  return `
  <div class="row mb">${projectSelect()}<span class="muted">不启动被调服务，直连数据库验证 SQL / 存储过程；默认事务回滚，测完无痕</span></div>
  <div class="grid cols2">
    <div class="card"><h3>① 选择数据对象</h3>
      <div class="row" style="gap:8px">
        <input id="sqlObjLabel" readonly style="flex:1" placeholder="尚未选择 SQL 卡（也可直接在下方输入）" value="${esc(window.__sqlObjLabel || "")}">
        <button onclick="pickSqlObj()">🔍 选择 SQL</button></div>
      <div class="muted mt" style="font-size:12px">SQL 卡太多不再用下拉：弹屏按仓库筛选 + 关键字搜索 + 分页</div>
      <div id="sqlCaseBar" class="mt"></div>
      <label class="f">或直接输入 SQL</label>
      <textarea id="sqlText" class="code" placeholder="SELECT * FROM t_order WHERE id = ?" oninput="sqlEdited()"></textarea>
      <div id="sqlTplHint" class="mt" style="font-size:12px">${sqlTplHint()}</div>
    </div>
    <div class="card"><h3>② 绑定参数与执行</h3>
      <div id="sqlParams"><div class="empty">选择左侧 SQL 后自动生成参数行</div></div>
      <label class="f mt">环境（需配置数据库连接）</label>
      <select id="sqlEnv" onchange="candEnvChanged()"><option value="">（环境列表加载中…）</option></select>
      <div class="row mt"><label><input type="checkbox" id="sqlCommit"> 显式提交（默认回滚）</label>
        <label><input type="checkbox" id="sqlProc"> 存储过程</label>
        <button onclick="runSql()">▶ 执行</button></div>
      <div class="muted mt">这里每一行对应 ① 区里的一个 <code>?</code>（源码原样写法会在行首标出它是哪个 <code>#{}</code>），按顺序填；DROP/TRUNCATE/GRANT/REVOKE 已被拦截</div>
    </div>
  </div>
  <div class="card"><h3>③ 条件与参数（填了才拼进 SQL）</h3>
    <div id="sqlDyn"><div class="empty">先在上方选一张 SQL 卡——选了带 &lt;if&gt;/&lt;choose&gt; 动态条件的卡，这里会列出全部条件片段与需要填的参数</div></div></div>
  <div class="card" id="sqlResult" style="display:none"></div>`;
};
/** 壳子先出，项目与环境随后回填。顺序不能换：候选值查询要带 projectId，环境没到位就发请求等于发一条必错的。 */
async function sqlBootstrap() {
  try {
    await loadProjects();
    const ps = $("#projSel");
    if (ps) ps.innerHTML = state.projects.map(p =>
      `<option value="${p.id}" ${p.id == state.projectId ? "selected" : ""}>${esc(p.name)}</option>`).join("");
    const envs = (await api("/api/environments?projectId=" + state.projectId)).environments || [];
    const sel = $("#sqlEnv");
    if (!sel) return;                                    // 已经切去别的页面：这一份数据不相干了
    sel.innerHTML = envs.map(e => `<option value="${e.id}">${esc(e.name)}</option>`).join("")
      || "<option value=''>（本项目没有环境，去「环境与凭据」配一个）</option>";
    if (window.__candPending) { window.__candPending = false; loadCand(false); }   // 壳子那一次没发出去（环境还没到手），现在补
  } catch (e) {
    const sel = $("#sqlEnv");
    if (sel) sel.innerHTML = `<option value="">（环境列表没拉到：${esc(e.message)}）</option>`;
  }
}
function pickSqlObj() {
  openCardPicker("db", row => {
    window.__sqlObj = row;
    window.__sqlObjLabel = `[${row.obj_type}] ${row.name}（仓库：${row.repo_names || "未归属"}）`;
    const el = $("#sqlObjLabel"); if (el) el.value = window.__sqlObjLabel;
    // 第 20 轮：手动换卡 = 换了目标，原来那条用例的身份不该跟着走（否则执行记到不相干的用例头上）
    const prevCase = window.__sqlCaseName;
    bindSqlCase(null, null);
    if (prevCase) toast("已改为直测：手动换过 SQL 卡后不再挂原来的用例「" + prevCase + "」");
    loadSqlObj();
  });
}
async function loadSqlObj() {
  const d = window.__sqlObj;
  if (!d) return;
  window.__sqlManual = false;
  window.__sqlComposed = false;
  $("#sqlText").value = d.sql_template || "";
  fitTextarea($("#sqlText"));
  window.__dynInputs = {};
  window.__dynOverrides = {};
  window.__dynView = { showAll: false, page: 1 };
  window.__dynEdit = null;   // 换卡：上一张卡在预览框里手改的文本不能带进这张卡
  // 第 27 轮：换卡也不带上一张卡的候选值——那是另一张卡的列在另一个库里的取值，挂在这张卡的框上就是假事实
  window.__cand = {}; window.__candKey = ""; window.__candItems = {};
  await composeDyn(false);
  // ② 区必须等派生结果到手再渲染：出参提示与占位符名都读 window.__dyn，先渲染会拿到上一张卡的事实
  renderSqlBinds(window.__sqlExec || d.sql_template || "", null);
  await consumeSqlCaseHandoff(d);
}
/* 第 23 轮：① 区原样显示源码写法（? 还原成 #{OP_CODE}、'DOLLAR' 还原成 ${RQ}），这样"哪个值进哪个位置"一眼对得上。
   还原由后端读取期现算（sql_template + dynamic_body 逐位配对），不落库、不重学。
   三个状态量：__sqlNamed=展示文本、__sqlExec=与之逐位等价的执行文本、__sqlManual/__sqlComposed=框已不属于这份还原视图。 */
function applyNamedView(r) {
  window.__sqlNamed = r.namedSql || "";
  window.__sqlExec = r.sqlTemplate || "";
  if (window.__sqlManual || window.__sqlComposed) return;   // 手工改过、或框里是拼好的语句：绝不覆盖
  if (!r.namedSql) { paintSqlHint(); return; }
  $("#sqlText").value = r.namedSql;
  fitTextarea($("#sqlText"));
  paintSqlHint();
}
function sqlEdited() {
  window.__sqlManual = true;
  paintSqlHint();
}
function paintSqlHint() {
  const el = $("#sqlTplHint");
  if (el) el.innerHTML = sqlTplHint();
}
/** 框里还剩多少没替换的占位符（原样写法与 DOLLAR 哨兵都算——它们发进库里就是字面文本） */
function sqlResidue(text) {
  const h = (text.match(/#\{[^{}]*}/g) || []).length;
  const d = (text.match(/\$\{[^{}]*}/g) || []).length;
  const s = (text.match(/(?<![A-Za-z0-9_$#])DOLLAR(?![A-Za-z0-9_$])/g) || []).length;
  return { h, d, s, any: h + d + s };
}
function sqlTplHint() {
  const r = window.__dyn, box = $("#sqlText");
  const text = box ? box.value : "";
  const res = sqlResidue(text);
  const manual = !!(window.__sqlManual || window.__sqlComposed);
  if (!r) {
    return `<span class="muted">选了 SQL 卡会显示源码原样的写法（带参数名）；也可直接在这里输入。填值用【③ 条件与参数】，拼好的语句会写回这个框</span>`;
  }
  let line;
  if (window.__sqlNamed && !manual) {
    line = `<span style="color:var(--green)">① 区是源码原样：第 N 个 <code>#{}</code> 就是执行时的第 N 个 <code>?</code>（值在 ② 区按位填），
      <code>${'$'}{}</code> 是字面拼接。实际发送的是与之逐位等价的带 <code>?</code> 语句；手工改过这个框后就按你改的这条原样发送。</span>`;
  } else if (manual && res.any) {
    line = `<span style="color:var(--red)">框里还有 ${res.h ? res.h + " 处 <code>#{}</code>" : ""}${
      res.h && (res.d + res.s) ? "、" : ""}${res.d ? res.d + " 处 <code>${'$'}{}</code>" : ""}${
      res.s ? (res.d ? "、" : "") + res.s + " 处 <code>DOLLAR</code> 占位" : ""
      } 没替换——执行发的是你框里这条原文，占位符会按字面文本进库（多半报错）。
      要按位填值就点【🧩 按参数拼装】写回，或在 ③ 面板里填。</span>`;
  } else if (manual) {
    line = `<span class="muted">执行发的是你框里这条原文（② 区的值按 <code>?</code> 出现顺序逐位绑定）</span>`;
  } else {
    /* 提示里指的按钮必须真的存在：没有动态原文时 ③ 面板给的是「按位置填 \${}」那张表，不是【按参数拼装】 */
    line = `<span style="color:var(--amber)">① 区只能显示学习时拍平的模板（<code>?</code> 与 <code>'DOLLAR'</code>）：${esc(r.namedReason || "占位符名字找不回来")}。
      ${r.hasDynamic ? `按位填值请点【🧩 按参数拼装】。`
        : res.s ? `按位置填值请在 ③ 面板里填 \${} 后点【↓ 代入并写回 SQL 框】。`
        : `模板里没有变量，上方这条就是最终语句，可直接执行或手工改。`}</span>`;
  }
  return line + `<div class="muted" style="font-size:11px;margin-top:2px">DROP/TRUNCATE/GRANT/REVOKE 与 DDL 提交仍被护栏拦截；默认事务回滚</div>`;
}
/** SQL 里真正参与绑定的 ? 位置（返回下标）。单引号字面量内部的 ? 不算——Oracle 里 '' 才是转义单引号。
    拼装结果会把 ${} 代成字面值，字面值里带 ? 时按裸计数会多生成一行绑定、并把值填错位。 */
function bindSites(sql) {
  const out = [];
  let inStr = false;
  for (let i = 0; i < sql.length; i++) {
    const ch = sql[i];
    if (inStr) {
      if (ch === "'") { if (sql[i + 1] === "'") i++; else inStr = false; }
    } else if (ch === "'") inStr = true;
    else if (ch === "?") out.push(i);
  }
  return out;
}
/** 按位把绑定值代成 SQL 字面量（仅用于让人核对/复制）；给不出字面量的值（对象等）就整个不展开，不猜 */
function sqlLiteral(v) {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (typeof v !== "string") return null;
  return "'" + v.replace(/'/g, "''") + "'";
}
function expandBinds(sql, values) {
  const sites = bindSites(sql || "");
  if (!sites.length || sites.length !== (values || []).length) return null;
  let out = "", at = 0;
  for (let i = 0; i < sites.length; i++) {
    const lit = sqlLiteral(values[i]);
    if (lit === null) return null;
    out += sql.slice(at, sites[i]) + lit;
    at = sites[i] + 1;
  }
  return out + sql.slice(at);
}
/** 按 SQL 文本里的 ? 生成顺序绑定行；values 传入时（拼装结果）按序预填。
 *  outSlots（服务端拼装时逐位登记的出参）不向人要值：那一位在库里是 OUT，发进去反而报错。 */
function renderSqlBinds(sqlText, values, outSlots) {
  const tpl = (sqlText || "").replace(/\s+/g, " ");
  const qpos = bindSites(tpl);
  const outs = {};
  (outSlots || []).forEach(o => { outs[o.slot] = o; });
  // 第 23 轮：① 区显示的是源码原样（#{x} / ${x}），② 区这行就要说清"我对应的是哪一位"——名字由后端逐位配对给出
  const hnames = (sqlText && sqlText === window.__sqlExec && window.__sqlNamed)
    ? (((window.__dyn || {}).hashNames) || []) : [];
  $("#sqlParams").innerHTML = (qpos.length === 0 ? `<div class="empty">无绑定参数</div>` :
    qpos.map((p, i) => {
      const hint = tpl.slice(0, p).slice(-24);
      const o = outs[i + 1];
      if (o) {
        const cur = isCursorType(o.type);
        return `<div class="form-item"><label>参数 ${i + 1}（出参 ${esc(o.name || ("#" + (i + 1)))}） ${
            badge(cur ? "出参 · 游标 · 不用填值" : "出参 · 不用填值", "blue")}</label>
          <input data-sqlp="${i}" value="" disabled placeholder="${cur ? "库里回传数据集，结果显示在下方结果区" : "库里回写这一位，不用填"}">
          <div class="muted" style="font-size:11px;margin-top:2px">这一位由存储过程回传（${esc(o.type || "OUT")}），执行时按出参注册，不回传值时这里为空</div></div>`;
      }
      const v = values && values[i] != null ? String(values[i]) : "";
      const pos = hnames[i] ? `<span style="color:var(--green)">${esc(hnames[i])}</span>`
        : `SQL 位置：…${esc(hint)}?`;
      return `<div class="form-item"><label>参数 ${i + 1}（${pos}）</label><input data-sqlp="${i}" value="${esc(v)}"></div>`;
    }).join("") + (qpos.length ? sqlOutHint(window.__dyn) : ""));
}
function isCursorType(t) { const s = (t || "").toUpperCase(); return s.startsWith("CURSOR") || s.startsWith("REF"); }
/** ① 区这次实际会发出去的那条文本：显示源码原样时发逐位等价的带 ? 语句，手工改过框之后发框里这条原文 */
function sqlSendText() {
  const el = document.querySelector("#sqlText");
  const shown = el ? el.value : "";
  const named = !window.__sqlManual && !window.__sqlComposed && window.__sqlNamed && shown === window.__sqlNamed;
  return named ? (window.__sqlExec || shown) : shown;
}
/** 这次到底会不会带出参注册：只看上面那条文本还是不是 ③ 面板自动拼出来的那条。
    runSql 与 ② 区提示共用它——两处各判各的，界面就会说"已注册"而实际发的是入参。 */
function outParamsSent() {
  const dyn = window.__dyn || {};
  return (dyn.sql || "").trim() === sqlSendText().trim() ? (dyn.outSlots || []) : [];
}
/** ② 区顶部提示：有出参但 SQL 不是刚拼出来的（比如用的是拍平模板、或预览被手改过）——这时执行会把游标当入参发出去 */
function sqlOutHint(dyn) {
  // 只数真正拼进 SQL 的出参位：第 28 轮起参数清单里还带着"这一段没拼进"的出参，它们不在语句里，
  // 算进来会让这句提示报出根本不存在的出参个数（②区/执行一律以 outSlots 为准）
  const ps = ((dyn || {}).params || []).filter(p => p.outType && p.emitted !== false);
  if (!ps.length) return "";
  if (outParamsSent().length) return `<div class="muted mt" style="color:var(--blue)">本条有 ${ps.length} 个出参（${ps.map(p => esc(p.name)).join("、")
    }），② 区已按出参注册，不用填值；执行前请勾选同区下方的【存储过程】，只有 CallableStatement 能注册出参。</div>`;
  if (dynEdited()) return `<div class="muted mt" style="color:var(--amber)">本条声明了 ${ps.length} 个出参（${ps.map(p => esc(p.name)).join("、")
    }），但 ③ 面板预览里那条是你手改的，? 的位次不再保证对得上，所以没给它注册出参——直接执行会把它们当入参发出去、库会报错。`
    + `要自动注册出参就先点【恢复成自动拼的】再写回；坚持用改过的这条，就得自己核对第几位 ? 是游标。</div>`;
  return `<div class="muted mt" style="color:var(--amber)">本条声明了 ${ps.length} 个出参（${ps.map(p => esc(p.name)).join("、")
    }）。当前 SQL 没带出参位序，直接执行会把它们当入参发出去、库会报错——点 ③ 面板的【🧩 按参数拼装】写回后再执行。</div>`;
}
/* 第 23b 轮：③ 面板的预览框可编辑——值漏替换或替换错了，用户要能就地改，改完写回的就是他改的那条。
   写回时只有"这条就是自动拼出来的那条"才带得出参位序：改过一个字，? 的位次就不一定对得上，
   宁可不注册出参（判据集中在 outParamsSent()，runSql 与 ② 区提示共用），也绝不把游标当入参发出去。 */
function dynPreviewText() {
  const r = window.__dyn || {};
  return window.__dynEdit != null ? window.__dynEdit : (r.sql || "");
}
function dynEdited() {
  const r = window.__dyn || {};
  return window.__dynEdit != null && window.__dynEdit !== (r.sql || "");
}
/** 只刷状态行与对照块，不整块重渲染：否则每敲一个字光标就没了 */
function dynEditInput(v) {
  const r = window.__dyn || {};
  window.__dynEdit = v === (r.sql || "") ? null : v;
  const st = document.querySelector("#dynEditState");
  if (st) st.innerHTML = dynEditState();
  const auto = document.querySelector("#dynEditAuto");
  if (auto) auto.style.display = dynEdited() ? "" : "none";
  // 恢复按钮常驻但按需显隐：改一个字就该能一键退回去，可它要等下一次整块渲染才出现就晚了（渲染会吞光标）
  const rb = document.querySelector("#dynEditRestore");
  if (rb) rb.style.display = dynEdited() ? "" : "none";
}
function dynEditReset() {
  window.__dynEdit = null;
  renderDyn();
  toast("已恢复成自动拼的那条");
}
function dynEditState() {
  const r = window.__dyn || {};
  const oc = (r.outSlots || []).length;
  if (!dynEdited()) return `<span class="muted">这条与 ③ 面板参数自动拼出来的一致，还没手改过</span>`;
  const n = (bindSites(dynPreviewText().replace(/\s+/g, " ")).length);
  return `<span style="color:var(--amber)">✎ 这条是你在预览框里手改的（自动拼的那条在下方对照）。`
    + `改后共 ${n} 个 ?`
    + (oc && n !== (r.values || []).length ? `，与自动拼的 ${(r.values || []).length} 位对不上` : "")
    + `；写回后执行发的是你改的这条，${oc ? "出参位序不再自动注册（勾选【存储过程】也要自己核对 ? 的位次）" : "② 区按这条的 ? 顺序重新列"}</span>`;
}
/** 把预览这条（自动拼的或手改的）写回 ① 区；不重新向服务端要一遍 */
function dynWriteBackPreview() {
  const r = window.__dyn || {};
  const text = dynPreviewText();
  if (!text.trim()) return toast("预览框是空的，没有可写回的语句", true);
  const edited = dynEdited();
  $("#sqlText").value = text;
  window.__sqlNamed = ""; window.__sqlExec = text;
  window.__sqlManual = false; window.__sqlComposed = true;
  fitTextarea($("#sqlText"));
  // 出参位序只在这条仍是自动拼出来的那条时带走：与 runSql 同一个判据，提示与实际发送不会各说各话
  const outs = outParamsSent();
  renderSqlBinds(text, r.values || [], outs);
  paintSqlHint();
  renderDyn();
  toast("已写回 ① 区：" + (edited ? "预览框里你手改的那条（" : "自动拼的那条（")
    + bindSites(text.replace(/\s+/g, " ")).length + " 个 ?）"
    + (edited && (r.outSlots || []).length ? "；这条有出参位但被你改过，出参不再自动注册" : "")
    + (outs.length ? "；其中 " + outs.length + " 位是出参，执行前勾【存储过程】" : ""));
}
/** 第 14 轮：拉服务端读取期派生的「条件片段 + 参数」表；writeBack=true 时把拼好的 SQL 写回编辑框 */
async function composeDyn(writeBack) {
  const d = window.__sqlObj;
  const box = $("#sqlDyn");
  if (!d || !box) return;
  const r = await api("/api/db-objects/" + d.id + "/compose",
    { params: window.__dynInputs || {}, overrides: window.__dynOverrides || {} });
  const editedBefore = dynEdited();
  const kept = editedBefore ? window.__dynEdit : null;
  window.__dyn = r;
  // 参数或条件一变动，自动拼的那条就换了：手改的文本原样留在预览框里，绝不被服务端结果悄悄顶掉
  window.__dynEdit = kept;
  if (!r.hasDynamic) { box.innerHTML = renderNoDynamic(r); fitControls(box); applyNamedView(r); return; }
  renderDyn();
  loadCand(false);         // 第 27 轮：进面板才打库取候选值（同一张卡+同一个环境只发一次）
  if (!writeBack) { applyNamedView(r); return; }   // 只刷新 ③ 面板：① 区保持在源码原样那份视图
  if (r.ok && r.sql) {
    if (dynEdited()) {
      dynWriteBackPreview();
      return;
    }
    $("#sqlText").value = r.sql;
    // 拼出来的这条本身就是执行文本：还原视图到此结束，位序以它为准
    window.__sqlNamed = ""; window.__sqlExec = r.sql;
    window.__sqlManual = false; window.__sqlComposed = true;
    fitTextarea($("#sqlText"));
    renderSqlBinds(r.sql, r.values || [], r.outSlots || []);
    paintSqlHint();
    const oc = (r.outSlots || []).length;
    toast("已拼好并写回 SQL 框：" + (r.values || []).length + " 个 #{} 位转成 ? 并按顺序预填了绑定值（② 区可改）"
      + (oc ? "，其中 " + oc + " 位是出参（存储过程游标），执行后回传的数据在结果区单独成表" : "")
      + "；" + (oc ? "有出参时不能改用「值已代入」那条写法执行" : "③ 面板下方另有「值已代入」的等价写法"));
    // 第 24 轮：没填不再拦整条，但绝不能静默——漏填了要当场看得见（③ 面板另有 ⚠ 原文）
    if ((r.warnings || []).length) toast(r.warnings[0], true);
  } else toast((r.errors || [])[0] || "未能拼出 SQL", true);
}

/* 第 16 轮：老学习产物没有 dynamic_body（V13 之后学的才有），③ 面板原先只留一句"去重学"，
   用户从用例目录跳过来就看到一条满是 'DOLLAR' 的模板、没有任何输入口——这就是"变量没法输入，只能改SQL"。
   这里在读取期把拍平模板里的每个 ${} 残留位列成输入框（推导值不落库），填完一键代回 SQL 框。 */
function dollarSites(sql) {
  const out = [];
  const re = /DOLLAR/g;
  let m;
  while ((m = re.exec(sql))) {
    out.push({ at: m.index, label: sql.slice(Math.max(0, m.index - 46), m.index).replace(/\s+/g, " ").trim() });
  }
  return out;
}
function renderNoDynamic(r) {
  const d = window.__sqlObj || {};
  const sites = dollarSites(r.sqlTemplate || "");
  const relearn = `<div class="muted mt" style="font-size:12px">这张卡是旧学习产物、没有留下动态 SQL 原文，所以「按条件拼装」用不了；
    到 <a href="#/projects">「项目与仓库」</a> 给仓库 ${esc(d.repo_names || "所属仓库")} 点一次【学习】即可启用（条件与参数会整段列出来）。
    源码一个字没改也会重扫——增量缓存按采集版本记账，旧版本学出来的文件会自动补采一次，人工编辑过的卡片不会被覆盖。</div>`;
  if (!sites.length) {
    return `<div class="empty">这张卡没有动态 SQL 原文${(r.warnings || [])[0] ? "：" + esc((r.warnings || [])[0]) : ""}，
      模板里也没有 \${} 变量——上方就是最终语句，直接执行或手工改都行。</div>${relearn}`;
  }
  window.__dollarSites = sites;
  return `<div class="mb">这条模板里有 <b>${sites.length}</b> 处 <code>\${}</code> 拼接型变量，学习时被换成了安全字面量
    <code>'DOLLAR'</code>（防止变量值未经确认直接进真库）。这张卡没留下动态原文，所以只知道"从左往右第几处"、不知道原本的变量名；
    下面按出现顺序填值，点【代入并写回】替换掉再执行：</div>
    <div class="tbl-scroll"><table><tr><th style="width:120px">位置</th><th>在 SQL 里的位置</th><th>值</th></tr>
    ${sites.map((s, i) => `<tr><td>第 ${i + 1} 处<br><span class="muted" style="font-size:11px">'DOLLAR'</span></td>
      <td class="wrap"><code style="font-size:11px">…${esc(s.label)}</code></td>
      <td><input id="dy${i}" placeholder="填真实值，留空则不替换这一处"></td></tr>`).join("")}</table></div>
    <div class="row mt" style="gap:8px;align-items:center">
      <button class="small" onclick="dynFillBack()">↓ 代入并写回 SQL 框</button>
      <span class="muted">替换的是字面文本：\${} 在原工程里就是直接拼进 SQL 的，填什么就进什么；DDL 仍会被护栏拦下</span>
    </div>${relearn}`;
}
function dynFillBack() {
  const sites = window.__dollarSites || [];
  let sql = (window.__dyn && window.__dyn.sqlTemplate) || "";
  const filled = [];
  for (let i = sites.length - 1; i >= 0; i--) {
    const el = document.getElementById("dy" + i);
    const v = el ? el.value : "";
    if (v === "") continue;
    sql = sql.slice(0, sites[i].at) + v + sql.slice(sites[i].at + "DOLLAR".length);
    filled.unshift(i + 1);
  }
  if (!filled.length) return toast("还没有填任何一处", true);
  $("#sqlText").value = sql;
  window.__sqlNamed = ""; window.__sqlExec = sql; window.__sqlManual = false; window.__sqlComposed = true;
  fitTextarea($("#sqlText"));
  renderSqlBinds(sql, null);
  paintSqlHint();
  toast(`已代入第 ${filled.join("、")} 处，共 ${sites.length - filled.length} 处仍是 'DOLLAR' 占位，执行前请核对`);
}
/* 第 15 轮：片段表默认只列「本次拼进 + 需要你决策」的段，并分页。
   真实卡有 400+ 段，全量渲染会把标签页拖到「页面无响应」（用户 2026-09-21 实测报障）。
   第 18 轮：每页条数走统一翻页条（listSize("dyn")），仍是前端切片——数据早就一次性在 __dyn 里。 */
function renderDyn() {
  const r = window.__dyn || {}, box = $("#sqlDyn");
  if (!r.hasDynamic || !box) return;
  const frags = r.fragments || [], ps = r.params || [];
  const need = {};
  const slot = n => (need[n] = need[n] || { req: false, body: [], cond: [], slots: [], dollar: false });
  const bare = t => t.replace(/^[#$]\{([^}]*)}$/, "$1");
  frags.forEach(f => {
    (f.params || []).forEach(t => { const p = slot(bare(t)); p.body.push("#" + f.id); if (t.startsWith("${")) p.dollar = true; });
    (f.tests || []).forEach(n => slot(n).cond.push("#" + f.id));
  });
  // hashSlots 由服务端在拼装时逐位登记（第 21 轮）：固定段里的 #{} 不属于任何片段，前端自己反推必然把它当成"只用于判定"
  // outType 同样由服务端从占位符自己声明的属性读出（第 22 轮）：出参不向人要值，前端猜不出哪一位是 OUT
  const facts = {};                               // 第 27 轮：参数 → 条款（列/运算符/多值/字典），认第一处出现
  ps.forEach(p => {
    const q = slot(p.name);
    // 第 28 轮：emitted=false = 这一段本次没拼进 SQL（未填参数时服务端也会把全部片段的事实给出来，
    // 否则"候选值要你先填对值"就把自己锁死了）。req/位次/绑定值只认真正拼进的那几位，未拼进的绝不算"要填"。
    if (p.emitted === false) { if (!q.req) q.off = true; }
    else { q.req = true; q.off = false; }
    q.kind = p.kind; q.slots = q.slots.concat(p.hashSlots || []);
    if (p.kind === "dollar") q.dollar = true;
    if (p.context) q.ctx = p.context;
    if (p.outType && p.emitted !== false) { q.out = p.outType; q.req = false; }
    if (p.clause && !facts[p.name]) facts[p.name] = p.clause;
  });
  const names = Object.keys(need);
  const statusOf = f => f.level === "forced" ? badge(f.included ? "人工强制拼进" : "人工强制排除", "blue")
    : f.included ? badge("拼进", "green")
    : f.level === "unknown" ? badge("认不出，默认不拼", "amber")
    : f.level === "blocked" ? badge("上层未拼进", "gray")
    : badge("未拼进（条件不成立）", "gray");
  const opOf = f => f.level === "blocked" ? `<span class="muted">—</span>`
    : `<button class="small ghost" onclick="dynForce(${f.id})">${f.level === "forced" ? "取消强制" : f.included ? "强制排除" : "强制包含"}</button>`;
  const v = window.__dynView ||= { showAll: false, page: 1 };
  const worth = frags.filter(f => f.included || f.level === "unknown" || f.level === "forced");
  // 一个参数都没填时 worth 为空：这时直接列出全部，别给一张空表让人以为坏了
  const list = (v.showAll || !worth.length) ? frags : worth;
  const dynSize = listSize("dyn");
  const pages = Math.max(1, Math.ceil(list.length / dynSize));
  if (v.page > pages) v.page = pages;
  const rows = list.slice((v.page - 1) * dynSize, (v.page - 1) * dynSize + dynSize);
  // 第 21 轮：#{} 拼出来是 ?，值在绑定侧——用户只看文本会以为"没替换"。同一条语句再给一份按值代入的等价写法。
  // 第 22 轮：有出参位时不给这条写法（出参只能代成 NULL，等于一条必错的语句），也不留 __dynExpanded 供误用。
  const outCount = (r.outSlots || []).length;
  const eq = !outCount && r.ok && r.sql && (r.values || []).length ? expandBinds(r.sql, r.values) : null;
  window.__dynExpanded = eq;
  box.innerHTML = `
    <div class="row mb" style="gap:8px;align-items:center;flex-wrap:wrap">
      <button onclick="composeDyn(true)">🧩 按参数拼装</button>
      <button class="ghost small" onclick="dynReset()">清空条件覆盖</button>
      ${candBtn()}
      ${candMetaBtn()}
      <span class="muted">没填的按空拼进去（\${} → 空串、#{} → NULL），条件判不成立的整段不拼（与 MyBatis 运行时一致）</span>
    </div>
    <div class="muted mb" style="font-size:12px">${esc(candScope())}</div>
    ${names.length ? `<label class="f">参数（${names.length} 个）</label>
    <div class="grid cols4">${names.map(n => {
      const p = need[n];
      // 徽章三态要说的是三件不同的事，不许互相顶掉（第 28 轮：未拼进的参数以前被标成"仅用于判定条件"，是谎话——
      // 它在 SQL 正文里比的就是某一列，只是那一段这次没拼进来）
      const tag = p.out ? badge("出参 · " + (isCursorType(p.out) ? "游标" : p.out), "blue")
        : p.req ? badge(p.dollar ? "不填＝空串" : "不填＝NULL", "amber")
        : p.off && facts[n] ? badge("段未拼进 · 正文比这一列", "gray")
        : p.off ? badge("段未拼进", "gray")
        : p.cond.length ? badge("仅用于判定条件", "amber")
        : badge("所在条件段未拼进", "gray");
      // 第 30 轮：长说明不再逐框固定铺开（一屏 24 个框时把面板撑没），改成"悬停字段名 + 点一下展开"。
      // 关键：悬停那份与展开那份读的是同一个 notes 数组，两处各写一遍必然分叉（§十二单点谓词）。
      const notes = [];
      if (p.out) notes.push({ c: "blue", t: "库里回传这一位（" + p.out + "），不发送值"
          + (isCursorType(p.out) ? "；回传的数据集在结果区单独成表" : "") });
      else if (p.off) notes.push({ c: "mut", t: "这一段本次没拼进 SQL，填了也用不上"
          + (facts[n] ? "；但源码里这一位比的就是下面那列，候选值照给" : "")
          + "——要让它生效：在下面片段表里找到那一段点【强制包含】（表被筛窄时先点【展开全部】），再拼一次" });
      else if (p.dollar) notes.push({ c: "amber", t: "字面替换（\\${}），值要写成 SQL 片段本身，如 order by xxx；留空就拼成空串（引号外会拼出空位，库自己报错）" });
      else if (p.slots.length) notes.push({ c: "green", t: "会进 SQL：作为第 " + p.slots.join("、") + " 个 ? 的绑定值送进库；留空按空值 NULL 绑定" });
      else notes.push({ c: "mut", t: "只参与条件判断，值本身不进 SQL" });
      // 「出自片段 #N」第 28 轮起未拼进也要给：那一段右边的【强制包含】就是让这个值生效的唯一入口
      const where = [p.cond.length ? "判定于片段 " + p.cond.join(" ") : "",
        p.body.length ? "出自片段 " + p.body.join(" ") : "",
        p.ctx ? "位于 " + p.ctx : ""].filter(Boolean).join(" · ");
      if (where) notes.push({ c: "mut", t: where });
      const fact = p.out ? "" : candFact(n, facts[n]);
      if (fact) notes.push({ c: "mut", t: fact });
      return `<div class="form-item"><label class="fn-lab" data-fn="${esc(n)}" data-tip="${esc(notes.map(x => x.t).join(" ｜ "))}"
          onclick="fnNoteToggle(this)">${esc(n)} ${tag} <i class="fn-mark">ⓘ</i></label>
        <input value="${esc((window.__dynInputs || {})[n] || "")}" placeholder="${p.out ? "出参 · 不用填值" : p.dollar ? "留空 = 拼成空串" : "留空 = 空值 NULL"}"
          ${p.out ? "disabled" : ""} data-dynp="${esc(n)}" oninput="dynInput(this.dataset.dynp, this.value)">
        ${p.out ? "" : candBlock(n, facts[n])}
        <div class="fn-note muted" data-fnfor="${esc(n)}" hidden>${notes.map(x =>
          `<span${x.c === "mut" ? "" : ` style="color:${PF_TIP_COLOR[x.c]}"`}>${esc(x.t)}</span>`).join("<br>")}</div></div>`;
    }).join("")}</div>` : `<div class="empty">本条语句没有需要填的参数</div>`}
    ${frags.length ? `<label class="f mt">条件片段 · 共 ${frags.length} 段${list.length === frags.length
      ? "，全部列出" : "，本次列出 " + list.length + " 段（拼进的 + 需你决策的）"}</label>
    <div class="tbl-scroll"><table class="dyn"><colgroup><col style="width:42px"><col style="width:72px"><col style="width:26%"><col style="width:13%"><col style="width:15%"><col><col style="width:92px"></colgroup>
      <tr><th>#</th><th>标签</th><th>条件表达式</th><th>判定变量</th><th>段内参数</th><th>状态与理由</th><th>操作</th></tr>
    ${rows.map(f => `<tr><td>${f.id}</td><td class="muted ell">&lt;${esc(f.kind)}&gt;</td>
      <td><code class="ell" style="font-size:11px" title="${esc(f.test || "")}">${esc(f.test || "—")}</code></td>
      <td class="muted ell" title="${esc((f.tests || []).join("、"))}">${esc((f.tests || []).join("、") || "—")}</td>
      <td class="muted ell" title="${esc((f.params || []).join("、"))}">${esc((f.params || []).join("、") || "—")}</td>
      <td>${statusOf(f)}<div class="muted ell" style="font-size:11px" title="${esc(f.reason || "")}">${esc(f.reason || "")}</div></td>
      <td>${opOf(f)}</td></tr>`).join("")}</table></div>
    <div class="row mt" style="gap:8px;align-items:center">
      ${pagerBar({ key: "dyn", total: list.length, page: v.page, pager: "dyn" })}
      ${worth.length && worth.length < frags.length ? `<button class="ghost small" onclick="dynShowAll(${v.showAll ? "false" : "true"})">${v.showAll
        ? "只看拼进的与需决策的（" + worth.length + " 段）"
        : "展开全部 " + frags.length + " 段（含 " + (frags.length - worth.length) + " 段本次不拼）"}</button>` : ""}
    </div>` : `<div class="empty mt">这条语句没有 &lt;if&gt;/&lt;choose&gt; 条件段——上面填的参数会按顺序直接绑定进 SQL，没有需要逐段决策的分支。</div>`}
    ${(r.errors || []).length ? `<div class="mt" style="color:var(--red)">${r.errors.map(e => `<div>✗ ${esc(e)}</div>`).join("")}</div>` : ""}
    ${(r.warnings || []).length ? `<div class="mt muted">${r.warnings.map(w => `<div>⚠ ${esc(w)}</div>`).join("")}</div>` : ""}
    ${r.ok && r.sql ? `<label class="f mt">拼出的 SQL 预览（可以直接在这条上改：值漏替换、替换错了，改完写回的就是你改的这条）</label>
      <textarea class="code" id="dynPreview" data-ta-ceil="420" oninput="dynEditInput(this.value)">${esc(dynPreviewText())}</textarea>
      <div class="row mt" style="gap:8px;align-items:center;flex-wrap:wrap" id="dynEditState">${dynEditState()}</div>
      <div class="row mt" style="gap:8px;align-items:center;flex-wrap:wrap">
        <button class="small" onclick="dynWriteBackPreview()">↓ 用这条写回 SQL 框</button>
        ${`<button class="ghost small" onclick="dynEditReset()" id="dynEditRestore"${dynEdited() ? "" : " style=\"display:none\""}>恢复成自动拼的</button>`}
        <span class="muted">改过之后执行发的是框里这条原文；② 区按这条的 ? 顺序重新列</span>
      </div>
      ${dynEdited() ? `<label class="f mt muted">自动拼的那条（只读对照）</label>
        <textarea class="code" readonly data-ta-ceil="420" id="dynEditAuto">${esc(r.sql)}</textarea>`
        : `<textarea class="code" readonly data-ta-ceil="420" id="dynEditAuto" style="display:none">${esc(r.sql)}</textarea>`}` : ""}
    ${outCount ? `<div class="mt" style="color:var(--amber)">本条有 ${outCount} 个出参位：「值已代入」那种写法只能把出参写成 NULL，发到库里必错，所以这里不给这条等价写法——存储过程要走上面带 ? 的绑定版（点【🧩 按参数拼装】写回），执行时出参按游标注册</div>`
      : (r.values || []).length ? `<label class="f mt">同一条语句·值已代入（等价写法，核对或复制去客户端跑就用这条）</label>
      ${eq ? `<textarea class="code" readonly data-ta-ceil="420">${esc(eq)}</textarea>
        <div class="row mt" style="gap:8px;align-items:center">
          <button class="small" onclick="dynUseExpanded()">↓ 用这条写回 SQL 框</button>
          <span class="muted">上面那条带 ? 的是绑定版（点【🧩 按参数拼装】写回它，值走绑定、不用管引号）；这条是把值直接拼进文本，写回后 ② 区不再有绑定参数</span>
        </div>`
      : `<div class="muted">本次的绑定值里有写不成 SQL 字面量的东西（对象或数组），只给带 ? 的那条</div>`}` : ""}
    ${candLiveNote()}`;
  fitControls(box);
  names.forEach(candSync);   // 重画之后按框里现在的值把选中态对上：翻页/改条件都会重画，不能只靠点击时同步
}
/** 这一发到底查没查库：范围必须说清楚，用户才知道"没列出来"是没查、查了没有、还是预算用完了 */
function candLiveNote() {
  const n = candWantLive().length;
  if (!n) return "";
  const st = Object.values(window.__cand || {}).map(x => x && x.status).filter(Boolean);
  if (!st.filter(s => s !== "loading").length) {
    return `<div class="muted mt" style="font-size:12px">正在只读查库取候选值（${n} 个参数打库，逐列取值、单条 8 秒上限）…</div>`;
  }
  const cnt = s => st.filter(x => x === s).length;
  const bad = cnt("failed") + cnt("skipped") + cnt("invalid") + cnt("ambiguous");
  return `<div class="muted mt" style="font-size:12px">库里取值：${cnt("ok")} 项已列在下面、`
    + `${cnt("too-many")} 项值太多不列、${cnt("empty")} 项库里没值、${bad} 项没取到`
    + `（每一档的原因都写在对应输入框下方）；其余参数按约定不打库，框里照旧自己填</div>`;
}
function dynShowAll(on) { window.__dynView = { showAll: !!on, page: 1 }; renderDyn(); }
/** 把「值已代入」的等价语句写回 SQL 框：写回后语句里没有 ?，② 区自然清空，执行的就是这段文本 */
function dynUseExpanded() {
  const sql = window.__dynExpanded;
  if (!sql) return toast("没有可写回的等价语句", true);
  $("#sqlText").value = sql;
  window.__sqlNamed = ""; window.__sqlExec = sql; window.__sqlManual = false; window.__sqlComposed = true;
  fitTextarea($("#sqlText"));
  renderSqlBinds(sql, null);
  paintSqlHint();
  const n = ((window.__dyn || {}).values || []).length;
  toast("已写回值代入版：" + n + " 个 ? 换成了字面值（引号已按 SQL 规则加倍转义），执行前再核对一遍");
}
function dynGoTo(p) { const v = window.__dynView || (window.__dynView = { showAll: false, page: 1 }); v.page = Math.max(1, p); renderDyn(); }
pagerDefine("dyn", dynGoTo, () => dynGoTo(1));
function dynInput(name, value) {
  window.__dynInputs = window.__dynInputs || {};
  if (value === "") delete window.__dynInputs[name]; else window.__dynInputs[name] = value;
  candSync(name);           // 手打的值也要让候选条上的选中状态跟着变（同一段文字两边口径必须一致）
}

/* ==================== 第 27 轮：③ 面板候选条 ====================
   三个来路，越靠前越便宜：
   1) 源码里本来就写着的枚举 / 判定字面量 / 字典键 —— 拼装时已随条款一起到手，不查库
   2) 库里这一列现在的真值、数据字典的取值 —— 进面板才查（服务端只读、有上限、有超时），结果带出处与查询时间
   3) 都没有 —— 就是一个空输入框，界面直说为什么没得选
   单选点一下就填进框；判成多值的按源码里那个分隔符拼（勾多个＝拼出 a,b）。输入框始终可手改。 */
function candFree(name) {
  const r = window.__dyn || {};
  const ps = (r.params || []).filter(p => p.name === name && p.clause);
  const out = [];
  // 芯片点了就整段填进框：值本身还是个 ${...}/#{...} 占位符时它不是"一个可填的值"，不列（为什么没有由事实行与黄条说）
  const placeholder = v => /[#$]\{/.test(String(v == null ? "" : v));
  if (ps.length && ps[0].clause.codeValues) {
    // 出处按服务端那句话分两种：条款自己说"这一位只出现在条件判断里"＝判定字面量，其余才是源码里写着的枚举
    const src = /只出现在条件判断里/.test(String(ps[0].clause.basis || "")) ? "判定字面量" : "源码枚举";
    ps[0].clause.codeValues.forEach(v => { if (!placeholder(v.value)) out.push({ value: v.value, label: v.label, src }); });
  }
  const tv = (r.testValues || {})[String(name).toUpperCase()] || [];   // 后端这张表按大写名记（与判定变量名归一口径一致）
  tv.forEach(v => { if (!placeholder(v.value) && !out.some(o => o.value === v.value)) out.push({ value: v.value, label: v.label, src: "判定字面量" }); });
  return out;
}
function candLive(name) {
  const it = (window.__cand || {})[name];
  return it && it.status === "ok" ? (it.values || []).map(v => ({
    value: v.value, label: v.label, src: it.kind === "dict" ? "数据字典" : "库里真值", where: it.where
  })) : [];
}
/** 进面板的取值范围说明：一句话说清"什么才会去打库"，用户才知道空白框是没查还是查不到 */
function candScope() {
  const n = candWantLive().length, mt = candMetaTables().length;
  return `这一屏每个参数只留「字段名 + 徽章 + 输入框 + ▾ 候选 N 项」，长说明收在字段名上：鼠标放上去看全文，点一下常驻在框下面。`
    + `候选值：光标进框或点【▾ 候选 N 项】才展开，里面先列「源码里本来就写着的取值」（枚举 / 判定字面量 / 字典键，不查库）`
    + (n ? `，另有 ${n} 个参数按你的约定去打库取真值——只有「判成多值」和「过滤时走数据字典」的才查，只读 SELECT、最多列 ${window.__candLimit || 200} 个` : "")
    + `；单值文本框不查库（库里这样的列值太多，列出来会拖死页面）。点一下候选值即填入，多值的可再点选，框里始终能手改。`
    + `条件段本次没拼进来的参数同样给候选值（标着「段未拼进」），要它生效就先点那一段的【强制包含】。`
    + (mt ? `「这列的含义」是真实库里的列注释，点上面那颗【🧲 只读拉取 ${mt} 张表的列注释】拉一次就有（只读字典视图，拉回来的东西存本地、以后不用再拉）。` : "");
}
/** 这一张卡要说明的表：只取条款真正定到的那些表——注释是挂在"参数比的那一列"上的，别的表拉回来也没地方显 */
function candMetaTables() {
  const seen = [];
  (((window.__dyn || {}).params || [])).forEach(p => {
    const t = p.clause && p.clause.table;
    if (t && !p.outType && !seen.includes(t)) seen.push(t);
  });
  return seen;
}
/** 取值按钮：只在真有值得打的参数时才亮；没得查的时候不给一个点了没反应的按钮 */
function candBtn() {
  if (!candWantLive().length) return "";
  return `<button class="ghost small" id="dynCandBtn" onclick="candRefresh()">↻ 只读查库取候选值</button>`;
}
/**
 * 列注释要连库拉一次字典视图（只读、一条写死的 SELECT、表名先过白名单），所以做成看得见的按钮：
 * 拉回来的东西落本地 table_meta，之后每次进面板都免费显出来，不必再点。
 */
function candMetaBtn() {
  const ts = candMetaTables();
  if (!ts.length) return "";
  return `<button class="ghost small" id="dynMetaBtn" onclick="candPullMeta(this)">🧲 只读拉取 ${ts.length} 张表的列注释</button>`;
}
async function candPullMeta(btn) {
  const sel = $("#sqlEnv");
  if (!sel || !sel.value) return toast("先选测试环境（列注释要从那个库的字典视图拉）", true);
  const ts = candMetaTables();
  btn.disabled = true;
  btn.textContent = "正在只读拉取 " + ts.length + " 张表…";
  let r;
  try {
    r = await api("/api/db-metadata/pull-batch",
      { projectId: state.projectId, envId: Number(sel.value), tables: ts });
  } catch (e) {
    r = { ok: false, error: "拉取没发出去：" + e.message };
  }
  btn.disabled = false;
  btn.textContent = "🧲 只读拉取 " + ts.length + " 张表的列注释";
  if (!r.ok) return toast(r.error || "拉取失败", true);
  window.__candKey = "";                      // 注释变了：这一张卡的候选结果重取一次才带得上（值仍走缓存，不再打库）
  await loadCand(false);
  const miss = (r.tables || []).filter(x => x.status === "missing").map(x => x.table);
  toast(r.pulled + " 张表已缓存（带注释的列 " + r.comments + " 个）"
    + (miss.length ? "；查不到列的表：" + miss.join("、") : "") + "——" + (r.note || ""), !r.pulled);
}
/**
 * 这一位在跟哪一列比、怎么比、能不能多值、这列什么含义。
 * 第 30 轮：这段文字不再逐框固定铺开（24 个框时把面板撑成一屏纯文字），改成悬停字段名看全文、点一下展开。
 * 定不到就把"为什么定不到"原样说出来（tableNote 由服务端给）。
 */
function candFact(name, clause) {
  const c = clause || {};
  const bits = [];
  // 条款里的列可能带着源码里的表别名前缀（a.lsh）：要说的是"哪张表的哪一列"，别把别名夹在中间拼成 t_zxthlsk.a.lsh
  const bareCol = c.column ? c.column.slice(c.column.lastIndexOf(".") + 1) : null;
  const alias = c.column && c.table && c.column.indexOf(".") >= 0
    ? "（源码里写作 " + c.column + "，前缀是表别名）" : "";
  if (c.column) bits.push("比的是 " + (c.table ? c.table + "." : "") + bareCol + alias);
  else if (c.table) bits.push("表 " + c.table + "（列名没认出来）");
  else if (c.tableNote) bits.push(c.tableNote);
  if (c.operator) bits.push("写法 " + c.operator);
  if (c.multi) bits.push('可多值（按 "' + (c.delimiter || ",") + '" 拼）');
  // 列注释来自本地已学到的 table_meta：跟这一项查没查库无关，所以单值框也能有说明（没拉过就一个字都不编）
  const cm = ((window.__cand || {})[name] || {}).columnComment;
  if (cm) bits.push("这列的含义：" + cm);
  return bits.join(" · ");
}
/** 框下面那一行：只剩「▾ 候选 N 项」这个可见入口 + 没查到东西时的理由（长说明在字段名上悬停/点开看） */
function candBlock(name, clause) {
  const items = candItems(name, clause);
  const note = candNote(name);
  if (!items.length) return note;   // 没候选就没下拉，但"为什么没有"必须留在脸上
  return `<div class="cand-line">
      <button type="button" class="ghost small cand-open" data-candopen="${esc(name)}"
        onclick="candShowPop('${esc(name)}',true)">▾ 候选 ${items.length} 项</button>
      <span class="muted cand-count">${esc(candSrcs(items))}｜${items.length} 项</span>
    </div>
    <div class="cand-pop" data-candfor="${esc(name)}" hidden>${candPopInner(name, items)}</div>
    ${note}`;
}
/** 去重：源码枚举在前、库里真值在后，同一个值只出现一次 */
function candItems(name, clause) {
  const items = [];
  [...candFree(name), ...candLive(name)].forEach(v => {
    if (!items.some(x => x.value === v.value)) items.push(v);
  });
  window.__candItems = window.__candItems || {};
  window.__candItems[name] = items;
  window.__candClause = window.__candClause || {};
  if (clause) window.__candClause[name] = clause;
  return items;
}
function candSrcs(items) {
  return [...new Set(items.map(v => v.src).filter(Boolean))].join(" · ") || "学到的候选值";
}
function candPopInner(name, items) {
  const it = (window.__cand || {})[name] || {};
  const bits = [candFact(name, (window.__candClause || {})[name]),
    candSrcs(items) + "｜" + items.length + " 项" + (it.queriedAt ? " · 库里取值时间 " + it.queriedAt : ""),
    (candDelim(name) ? "点一下多选，按源码里的分隔符拼" : "点一下填入，再点取消") + "；框里始终能手改"];
  if (it.status === "ok") bits.push("库里这一列去重后共 " + (it.rowsSeen || (it.values || []).length) + " 个非空值"
    + (it.fromCache ? "（缓存值，要新鲜的点【↻ 只读查库取候选值】）" : ""));
  const chips = items.map((v, i) => `<button type="button" class="cand-chip" data-cn="${esc(name)}" data-ci="${i}"
      onclick="candPick(this)" title="${esc(v.src || "")}${v.where ? " · " + esc(v.where) : ""}">${esc(v.value)}${v.label ? `<i>${esc(v.label)}</i>` : ""}</button>`).join("");
  return `<div class="cand-pop-h">${esc(bits.filter(Boolean).join(" ｜ "))}</div><div class="cand-bar">${chips}</div>`;
}
/** 没查到东西时"为什么"必须留在脸上——不然用户分不清"库里真没有"和"没查"，也不会有人去点开下拉。
 *  not-queried 不重复说：那是"按约定不查"，面板顶部范围行已经讲过一次，逐框再讲一遍就成噪音。 */
function candNote(name) {
  const it = (window.__cand || {})[name];
  if (!it || it.status === "not-queried") return "";
  if (it.status === "ok") return "";
  if (it.status === "loading") return `<div class="cand-src muted">正在只读查库取候选值…</div>`;
  return `<div class="cand-src cand-warn">${esc(it.note || it.status || "没查到")}</div>`;
}
/** 候选下拉：聚焦输入框就出现（用户口径），也可以点【▾ 候选 N 项】主动拉开 */
function candShowPop(name, keepFocus) {
  candHidePops();
  const pop = document.querySelector(`.cand-pop[data-candfor="${CSS.escape(name)}"]`);
  if (!pop) return;
  const items = (window.__candItems || {})[name] || [];
  if (!items.length) return;
  pop.innerHTML = candPopInner(name, items);
  pop.hidden = false;
  candSync(name);
  if (keepFocus !== false) {
    const inp = document.querySelector(`[data-dynp="${CSS.escape(name)}"]`);
    if (inp && !inp.disabled) inp.focus();
  }
}
function candHidePops() { document.querySelectorAll(".cand-pop").forEach(x => { x.hidden = true; }); }
/** 悬停只看得到、点一下才留得住：字段名点开后同一份说明常驻在框下面 */
function fnNoteToggle(lab) {
  const box = document.querySelector(`.fn-note[data-fnfor="${CSS.escape(lab.dataset.fn)}"]`);
  if (box) box.hidden = !box.hidden;
}
/* 光标进输入框就把这一位的候选拉出来（用户口径：放进文本框再做成下拉让我选，我也可以填） */
document.addEventListener("focusin", e => {
  const inp = e.target && e.target.closest ? e.target.closest("[data-dynp]") : null;
  if (inp) candShowPop(inp.dataset.dynp, false);
});
/* 下拉是长在自己那一行里的（.form-item 定位 + 绝对定位），滚动时它跟着那一行走，不会飘走，
   所以不像 #pfTip（挂在 body 上、位置算死）那样必须随滚动关掉——这里加滚动关闭只会误伤：
   浏览器为露出焦点框自己滚一下，刚出现的下拉就被关掉。 */
document.addEventListener("mousedown", e => {
  if (e.target && e.target.closest && e.target.closest(".cand-pop,[data-candopen],[data-dynp]")) return;
  candHidePops();
});
document.addEventListener("keydown", e => { if (e.key === "Escape") candHidePops(); });
/** 把候选条上的选中态对齐到输入框里现在这几个值：两处共用 splitCand，口径不会分叉 */
function candSync(name) {
  const el = document.querySelector(`[data-candfor="${CSS.escape(name)}"]`);
  if (!el) return;
  const cur = splitCand(name);
  const items = (window.__candItems || {})[name] || [];
  el.querySelectorAll(".cand-chip").forEach(b => {
    const v = items[+b.dataset.ci];
    b.classList.toggle("on", !!v && cur.includes(v.value));
  });
}
function candDelim(name) {
  const p = ((window.__dyn || {}).params || []).find(x => x.name === name && x.clause);
  const c = p && p.clause;
  return c && c.multi ? (c.delimiter || ",") : null;
}
/** 框里现在这一串按分隔符拆开（多值）；单值就是整串 */
function splitCand(name) {
  const d = candDelim(name), v = (window.__dynInputs || {})[name] || "";
  if (v === "") return [];
  return d ? v.split(d).map(s => s.trim()).filter(s => s !== "") : [v];
}
function candPick(btn) {
  const name = btn.dataset.cn, v = (window.__candItems[name] || [])[+btn.dataset.ci];
  if (!v) return;
  const d = candDelim(name), have = splitCand(name);
  let next;
  if (d) {                                  // 多值：点一下切换勾选，按源码里那个分隔符拼回去
    const at = have.indexOf(v.value);
    if (at >= 0) have.splice(at, 1); else have.push(v.value);
    next = have.join(d);
  } else {
    next = (have[0] === v.value) ? "" : v.value;   // 单值：再点一次取消，等于把框清空
  }
  dynInput(name, next);
  const inp = document.querySelector(`[data-dynp="${CSS.escape(name)}"]`);
  if (inp) inp.value = next;
  candSync(name);
}
/** 进面板才查：只查"判成多值的 + 本句用到数据字典的"，单值文本框一律不打库（用户口径：值太多会拖死） */
async function loadCand(force) {
  const d = window.__sqlObj, sel = $("#sqlEnv");
  const box = $("#sqlDyn");
  if (!d || !box) return;
  if (!sel || !sel.value) { window.__candPending = true; return; }   // 环境列表还在路上：这一发留到手后再补，不发必错的请求
  const key = d.id + "|" + sel.value;
  if (!force && window.__candKey === key) return;      // 这张卡这个环境已经查过（或正在查）：翻页/改条件不重复打库
  window.__candKey = key;
  window.__cand = {};
  const names = candWantLive();
  // 一项都不值得打库时这一发照发：列注释是本地 table_meta 现读的，跟着这一发回来。
  // 发几条查询仍由服务端按同一条谓词决定（candTargetable 只是它的镜像，两边都不许自己加戏）。
  const hasFacts = (((window.__dyn || {}).params || []).some(p => p.clause && !p.outType));
  if (!names.length && !hasFacts) { renderDyn(); return; }
  names.forEach(n => window.__cand[n] = { status: "loading" });
  if (names.length) renderDyn();             // 先把"正在只读查库"显出来：这一发最坏要到 20 秒预算，不能让人对着不动的界面猜
  let r;
  try {
    r = await api("/api/db-objects/" + d.id + "/candidates",
      { projectId: state.projectId, envId: Number(sel.value), force: force ? 1 : 0 });
  } catch (e) {
    r = { ok: false, note: "候选值查询没发出去：" + e.message, items: [] };
  }
  if (window.__candKey !== key) return;               // 中途换了卡/环境：这一份结果已经不相干了
  window.__cand = {};
  (r.items || []).forEach(it => window.__cand[it.param] = it);
  window.__candNote = r.note || "";
  window.__candLimit = r.listLimit || window.__candLimit;
  renderDyn();
  if (r.ok === false && r.error) toast(r.error, true);
  else if (names.length && r.note) toast(r.note, r.queried > 0 ? false : true);
}
/** 这一位值不值得为它发候选值请求：与服务端同一条谓词（KnowledgeController#candidates 那一段的镜像）。
    判成多值 **且表列都定得到** 才发；走数据字典要字典形状完整。
    字典的键本身是 ${...}/#{...} 时**照样发**——服务端在连库之前就把这一项判成 invalid 并带回原因，
    一分钱库开销没有，但界面由此能说清"这个字典为什么没有候选"，比默默不做强。
    这里收紧只为了一件事：整张卡一项都取值不到时不发无意义的往返，也不把范围行的数目说虚。 */
function candTargetable(c) {
  if (!c) return false;
  if (c.dict) return !!(c.dict.table && c.dict.keyColumn && c.dict.valueColumn);
  return c.multi === true && !!(c.table && c.column);
}
function candWantLive() {
  const seen = new Set();
  ((window.__dyn || {}).params || []).forEach(p => {
    if (p.outType) return;
    if (candTargetable(p.clause)) seen.add(p.name);
  });
  return [...seen];
}
function candRefresh() { window.__candKey = ""; loadCand(true); }
/** 换环境＝换一套真值：旧环境的结论一个字都不留，按新环境重取（服务端仍按缓存优先，不重复打库） */
function candEnvChanged() { window.__cand = {}; window.__candKey = ""; window.__dyn && renderDyn(); loadCand(false); }
function dynForce(id) {
  window.__dynOverrides = window.__dynOverrides || {};
  const f = ((window.__dyn || {}).fragments || []).find(x => x.id === id);
  if (!f) return;
  window.__dynOverrides[id] = f.level === "forced" ? null : !f.included;
  if (window.__dynOverrides[id] === null) delete window.__dynOverrides[id];
  composeDyn(false);
}
function dynReset() {
  window.__dynOverrides = {};
  composeDyn(false);
}
async function runSql() {
  const envId = Number($("#sqlEnv").value || 0);
  const shown = $("#sqlText").value;
  if (!envId || !shown.trim()) return toast("请选择环境并输入 SQL", true);
  // 第 23 轮：① 区显示的是源码原样（#{x} / ${x}），执行发的是与之逐位等价的带 ? 语句；
  // 手工改过这个框（或写回过拼装结果）之后，一切以框里的文本为准——还原视图不再有名字可保证，就不再用它。
  const sql = sqlSendText();
  const values = Array.from($("#sqlParams").querySelectorAll("[data-sqlp]")).map((inp, i) => {
    const v = inp.value;
    if (v === "") return null;
    try { return JSON.parse(v); } catch (e) { return v; }
  });
  // 第 20 轮：把"测的是哪张卡 + 算谁的账"一起报上去，服务端据此留痕（原先只写审计，界面上永远查不到）
  // 出参声明（第 22 轮）：只在 SQL 仍是 ③ 面板刚拼出来的那条时带上——改过一个字符位序就可能指错地方，宁可不带
  const outParams = outParamsSent();
  // 出参位只有 CallableStatement 能注册；走普通预编译会把游标当入参绑，驱动必报错。
  // 拦在「执行中…」之前：还没发请求就先报"执行中"，是界面在说谎。
  if (outParams.length && !$("#sqlProc").checked) {
    return toast("本条有 " + outParams.length + " 个出参位（"
      + outParams.map(o => o.name || ("#" + o.slot)).join("、")
      + "）：请先勾选 ② 区下方的【存储过程】再执行，否则这些位会被当入参绑定", true);
  }
  paintSqlHint();   // 红字提示随本次真正发送的文本刷新
  toast("执行中…");
  const body = { envId, sql, values, allowCommit: $("#sqlCommit").checked, isProcedure: $("#sqlProc").checked,
                 outParams,
                 objId: (window.__sqlObj || {}).id || null, caseId: window.__sqlCaseId || null,
                 commitId: window.__sqlCommitId || null };
  const r = await api("/api/db-test", body, null, { progress: true });
  const box = $("#sqlResult");
  box.style.display = "block";
  box.className = "card " + (r.ok ? "result-ok" : "result-fail");
  const commitLine = r.committed
    ? `<span style="color:var(--red)">⚠ 已提交，永久生效（不可回滚）</span>`
    : `已回滚（无痕）`;
  const tbl = (cols, rows) => `<div style="overflow:auto"><table><tr>${(cols || []).map(c => `<th>${esc(c)}</th>`).join("")}</tr>
      ${(rows || []).map(row => `<tr>${row.map(v => `<td>${esc(v)}</td>`).join("")}</tr>`).join("")}</table></div>`;
  let detail = "";
  if (!r.ok) detail = `<div>${esc(r.error)}</div>${renderDiag(r, body)}`;
  else if (r.rows && r.rows.length) {
    detail = `${tbl(r.columns, r.rows)}
      <div class="muted mt">${r.rows.length} 行 · ${r.latencyMs}ms · ${commitLine}${r.notice ? " · " + esc(r.notice) : ""}</div>`;
  } else detail = `<div>${r.updateCount >= 0 ? "影响行数 " + r.updateCount : "执行成功"} · ${r.latencyMs}ms · ${commitLine}${r.notice ? " · " + esc(r.notice) : ""}</div>`;
  const cs = r.outCursors || [];
  if (cs.length) detail += `<label class="f mt">出参游标 · ${cs.length} 个（每位一张表，列名来自库里回传的数据集）</label>`
    + cs.map(c => `<div class="mt"><b>${esc(c.name)}（第 ${c.slot} 个 ?）· ${c.rowCount} 行</b>
        ${(c.columns || []).length ? tbl(c.columns, c.rows) : `<div class="muted">这一位没回数据集（列数为 0）</div>`}</div>`).join("");
  // 第 26 轮：游标表下面恒显"报错定位"段（命中了什么、拿到/没拿到什么、为什么）
  if (r.ok) detail += renderBiz(r);
  box.innerHTML = `<h3>结果 ${statusBadge(r.ok ? "pass" : "fail")}${r.ok ? bizBadge((r.bizErrors || [])[0]?.value, (r.bizErrors || []).length) : ""}</h3>
    ${r.runId ? runOwnerLine(body.caseId ? { id: body.caseId, name: window.__sqlCaseName } : null,
                              r.runId, body.commitId) : ""}
    ${detail}
    ${r.runId ? `<div class="row mt"><button class="ghost small" onclick="openRuns({targetType:'${body.objId ? "db_object" : "adhoc_sql"}',targetId:${body.objId || 0}},'SQL 卡 #${body.objId || 0} 的执行记录')">📜 这条 SQL 的执行记录</button>
      <button class="ghost small" onclick="openRuns({via:'direct'},'全部直测留痕')">📜 全部直测记录</button></div>` : ""}`;
}

/**
 * 第 23b 轮：执行失败不再只回一句"存储过程报错了"。
 * 后端把 SQLException 整条链收上来（PL/SQL 的行号就住在 getNextException() 里，老代码顺 Java cause 走会全丢），
 * 这里如实铺开：完整链 → 解析出的对象与行号 → 本次逐位绑定值。行号解析不出时明说解析不出，不编位置。
 */
function renderDiag(r, body) {
  const d = r.diag || {};
  const chain = d.chain || [], frames = d.frames || [];
  let h = "";
  if (chain.length > 1) {
    h += `<label class="f mt">驱动回传的完整报错链 · ${chain.length} 条（按驱动给的原顺序，未重排）</label>
      <div class="mt" style="font-family:monospace;font-size:12px;line-height:1.7" data-diag="chain">${chain.map(x => `<div>${esc(x)}</div>`).join("")}</div>`;
  }
  if (frames.length) {
    h += `<label class="f mt">库里定位到的位置 · ${frames.length} 处（对象名与行号都来自上面那串原话）</label>
      <div class="tbl-scroll"><table><tr><th>对象</th><th style="width:64px">行号</th><th style="width:160px">取源码</th><th>驱动原话</th></tr>
      ${frames.map(f => `<tr><td><code>${esc(f.path || "（顶层调用：对象名没回传）")}</code></td>
        <td>${Number(f.line) || 0}</td>
        <td>${f.path ? `<button class="ghost small" data-env="${Number(body.envId) || 0}" data-path="${esc(f.path)}"
            data-line="${Number(f.line) || 0}" onclick="pullSrc(this)">🔍 取这一行的源码</button>` : `<span class="muted">—</span>`}</td>
        <td class="wrap muted" style="font-size:11px">${esc(f.raw || "")}</td></tr>`).join("")}</table></div>
      <div data-diag="src" class="mt"></div>
      <div class="muted mt" style="font-size:12px">栈帧按驱动回传的顺序列出（Oracle 一般把离报错点最近的那条放在前面）；
        【🔍 取这一行的源码】会拿这个环境的连接向库里补发一条只读 SELECT，不点就不发。</div>`;
  } else if (!r.ok) {
    h += `<div class="muted mt">这个驱动没回传库内行号——上面那串里解析不出 <code>ORA-06512: at "…", line N</code> 这类栈帧，
      所以定位不到具体哪一行，没有可补的位置。${chain.length ? "驱动给的就是上面这一整串原话。" : "驱动只给了那一句主错。"}</div>`;
  }
  const vals = body.values || [];
  if (vals.length) {
    h += `<label class="f mt">这次送进库的绑定值 · ${vals.length} 位（按 ? 出现顺序；出参位为空是正常的，那一位在库里由过程回写）</label>
      <div class="tbl-scroll"><table><tr><th style="width:64px">第几 ?</th><th>值</th></tr>
      ${vals.map((v, i) => `<tr><td>${i + 1}</td><td class="wrap"><code>${esc(v == null ? "" : String(v))}</code>${
        v == null || v === "" ? ` <span class="muted">（空）</span>` : ""}</td></tr>`).join("")}</table></div>`;
  }
  return h;
}
/**
 * 第 26 轮：库执行成功 ≠ 过程成功。过程把异常吞进 WHEN OTHERS 后自己回错误码，驱动只会说"执行成功"，
 * 行号在那一刻就没有产生。判定与定位全在后端一处（BizErrorScanner / SourceLocatorService），
 * 这里只如实转述：④ 结果区与执行记录详情共用下面两个函数，不各写一套口径。
 */
function bizBadge(value, count) {
  if (value == null || value === "") return "";
  // 状态仍是"通过"（通过率口径不动），橙色标只说明过程内部回了错误码
  return " " + badge(`过程返回错误码 ${value}（${count || 1} 处）`, "amber");
}

/** 命中表 + 只读定位证据。没有业务错误码时整段不出现；白名单被清空时只留一句说明。 */
function renderBiz(r) {
  const biz = r.bizErrors || [];
  if (!biz.length) {
    return (r.bizWhitelist && r.bizWhitelist.length === 0)
      ? `<div class="muted mt">系统设置里的「业务错误码列名」已被清空，本轮不判定业务错误码（库里执行成功就只报成功）。</div>`
      : "";
  }
  const tbl2 = (head, rows) => `<div class="tbl-scroll"><table><tr>${head.map(h => `<th>${esc(h)}</th>`).join("")}</tr>
    ${rows.map(c => `<tr>${c.map(v => `<td class="wrap">${v}</td>`).join("")}</tr>`).join("")}</table></div>`;
  let h = `<label class="f mt">🔎 报错定位（只读：没有重跑这条过程，也没有向目标库写任何东西）</label>`
    + `<div class="muted" style="font-size:12px">判定用的错误码列名（本轮）：${(r.bizWhitelist || []).map(x => `<code>${esc(x)}</code>`).join("、") || "（空）"}</div>`
    + tbl2(["位置", "列名", "第几行", "值", "同一行带回的错误文本"],
      biz.map(x => [`<b>${esc(x.whereLabel || x.where)}</b>`, `<code>${esc(x.column)}</code>`, x.row,
        `<span style="color:var(--amber);font-weight:600">${esc(x.value)}</span>`,
        x.message ? esc(x.message) : `<span class="muted">（过程没回错误文本列）</span>`]));
  const L = r.locate;
  if (!L) return h + `<div class="muted mt">这次没有定位证据（记录早于本功能，或当时没触发定位）。</div>`;
  const bits = [];
  if ((L.codes || []).length) bits.push(`错误码 ${L.codes.map(c => `<code>${esc(c)}</code>`).join("、")}`);
  if ((L.identifiers || []).length) bits.push(`标识符 ${L.identifiers.map(c => `<code>${esc(c)}</code>`).join("、")}`);
  if (L.objectPath) bits.push(`对象 <code>${esc(L.objectPath)}</code>（${esc(L.pathSource || "")}）`);
  h += `<div class="mt" style="font-size:12px">从错误文本里抽到的线索：${bits.join(" · ") || "无"}
    ${L.dbType ? ` · 库类型 ${esc(L.dbType)}` : ""}${L.tierLabel ? `<br>能力档位：${esc(L.tierLabel)}` : ""}</div>`;
  if ((L.driverLines || []).length) {
    h += `<label class="f mt">库自己回传的行号 · ${L.driverLines.length} 处（这一档才是确定的位置）</label>`
      + tbl2(["对象", "行号"], L.driverLines.map(f => [`<code>${esc(f.path)}</code>`, f.line]));
  }
  const cand = L.candidates || [];
  if (cand.length) {
    h += `<label class="f mt">按报错文本在库内源码里匹配到的候选行 · ${cand.length} 行</label>`
      + tbl2(["行号", "匹配到", "源码原文"],
        cand.map(c => [`<b>${c.line}</b>`, `<code>${esc(c.matchedOn)}</code>`, `<code style="font-size:12px">${esc(c.text)}</code>`]))
      + `<div class="muted mt" style="font-size:12px">${esc(L.lineNote || "")}${L.scannedLines ? ` （已扫 ${L.scannedLines} 行原文）` : ""}${L.candidateNote ? ` · ${esc(L.candidateNote)}` : ""}</div>`;
  } else {
    h += `<div class="mt" style="color:var(--amber);font-size:12px">拿不到候选行：${esc(L.missing || L.note || "库里没读到可匹配的源码原文")}</div>`
      + (L.lineNote ? `<div class="muted mt" style="font-size:12px">${esc(L.lineNote)}</div>` : "");
  }
  const dict = L.dictionary || [];
  if (dict.length) {
    h += `<label class="f mt">数据字典核对 · ${dict.length} 条（当前账号看得到的范围）</label>`
      + tbl2(["标识符", "核对什么", "命中", "命中的位置"], dict.map(x => [`<code>${esc(x.identifier)}</code>`,
          esc(x.check || ""), x.error ? `<span style="color:var(--red)">查不了</span>` : (x.found || 0) + " 处",
          x.error ? esc(x.error) : ((x.where || []).length ? (x.where || []).map(w => `<code style="font-size:11px">${esc(w)}</code>`).join("<br>") : `<span class="muted">没找到——能执行 ≠ 字典里可见，多半是没授权的别的 schema</span>`)]))
      + `<div class="muted mt" style="font-size:12px">${esc(L.dictionaryNote || "")}</div>`;
  }
  const sqls = L.diagnosticSql || [];
  if (sqls.length) {
    h += `<label class="f mt">${cand.length ? "补充核对" : "拿不到的部分"}：可以拿这几条只读语句到自己的库里跑一遍确认（本系统不会替你执行它们）</label>`
      + sqls.map(s => `<div class="row" style="align-items:flex-start"><pre class="json" style="flex:1;margin:4px 0;max-height:120px">${esc(s)}</pre>
        <button class="ghost small" data-txt="${esc(s)}" onclick="cpSql(this)">复制语句</button></div>`).join("");
  }
  if (L.note && cand.length) h += `<div class="muted mt" style="font-size:12px">${esc(L.note)}</div>`;
  return h;
}

async function cpSql(btn) {
  try {
    await navigator.clipboard.writeText(btn.dataset.txt || "");
    toast("已复制到剪贴板");
  } catch (e) {
    toast("浏览器不让直接复制，请在框里手工选中", true);
  }
}

/** 点按钮才发这一条只读查询：把 ALL_SOURCE 里那一行前后各两行取回来，标出报错那一行 */
async function pullSrc(btn) {
  const envId = Number(btn.dataset.env) || 0, path = btn.dataset.path || "", line = Number(btn.dataset.line) || 0;
  if (!envId || !path) return toast("缺少环境或对象名，没法查", true);
  const box = btn.closest("#sqlResult").querySelector('[data-diag="src"]');
  btn.disabled = true;
  let r;
  try {
    r = await api("/api/db-proc-source?envId=" + envId + "&line=" + line
      + "&path=" + encodeURIComponent(path), null, null, { progress: true });
  } finally {
    btn.disabled = false;   // 请求抛错也要把按钮放开，不然点一次就永久灰掉
  }
  if (!box || !r) return;
  if (!r.ok) {
    box.innerHTML = `<div style="color:var(--red)">✗ ${esc(r.error || "查询失败")}</div>`
      + ((r.chain || []).length > 1 ? `<div class="muted mt" style="font-family:monospace;font-size:12px">`
        + r.chain.map(x => `<div>${esc(x)}</div>`).join("") + `</div>` : "");
    return;
  }
  const ls = r.lines || [], es = r.compileErrors || [];
  box.innerHTML = `<div class="mb"><b>${esc((r.owner ? r.owner + "." : "") + r.name)}</b>
      <span class="muted">· 取第 ${r.line} 行前后各两行（${esc(r.dbType || "")}，只读查询）</span></div>`
    + (ls.length ? `<div class="tbl-scroll"><table><tr><th style="width:64px">行号</th><th style="width:120px">类型</th><th>原文</th></tr>
        ${ls.map(l => `<tr${l.hit ? ` style="background:var(--amber-soft,#fff7e6);font-weight:600"` : ""}>
          <td>${l.line}${l.hit ? " ←" : ""}</td><td class="muted">${esc(l.type)}</td>
          <td class="wrap"><code style="font-size:12px">${esc(l.text)}</code></td></tr>`).join("")}</table></div>` : "")
    + (es.length ? `<label class="f mt">ALL_ERRORS · 这个对象本身编译不过（${es.length} 条）</label>
        <div class="tbl-scroll"><table><tr><th style="width:64px">行</th><th style="width:64px">列</th><th>属性</th><th>消息</th></tr>
        ${es.map(e => `<tr><td>${e.line}</td><td>${e.position}</td><td>${esc(e.attribute || "")}</td>
          <td class="wrap">${esc(e.text || "")}</td></tr>`).join("")}</table></div>` : "")
    + (r.note ? `<div class="muted mt">${esc(r.note)}</div>` : "");
}

/**
 * SQL 直测的「当前用例」常驻行（第 20 轮，与测试台 renderLabCaseBar 对应）：
 * 从用例目录带过来的身份要一直看得见，手动换卡时必须清掉。
 */
function renderSqlCaseBar() {
  const el = document.getElementById("sqlCaseBar");
  if (!el) return;
  const id = window.__sqlCaseId;
  const fill = window.__sqlCaseFill;
  el.innerHTML = id
    ? `<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">${badge("已挂用例", "green")}
        <b>${esc(window.__sqlCaseName || ("#" + id))}</b>
        <span class="muted">这次执行计入该用例的通过率${window.__sqlCommitId ? `，并回写提交 #${window.__sqlCommitId} 的覆盖率` : ""}</span>
        <button class="small ghost" onclick="bindSqlCase(null,null,null)">改为直测（不挂用例）</button></div>
       ${fill ? `<div class="row mt" style="gap:6px;align-items:center;flex-wrap:wrap">${badge(fill.title, fill.kind === "none" ? "amber" : "blue")}
        <span class="muted" style="font-size:12px">${esc(fill.text)}</span></div>` : ""}`
    : `<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">${badge("直测", "blue")}
        <span class="muted">未挂用例：只留这一笔痕，不计入用例通过率与覆盖率${window.__sqlObj ? "" : "（还没选 SQL 卡，留痕记在「随手 SQL」上）"}。
        要算进某条用例：到「知识库 → 🧪 用例」那一行点【打开】带身份进来</span></div>`;
}
function bindSqlCase(id, name, commitId) {
  window.__sqlCaseId = id || null;
  window.__sqlCaseName = name || null;
  window.__sqlCommitId = commitId || null;
  window.__sqlCaseFill = null;   // 换用例/改直测：上一条用例的灌值结论不能再挂在这行上
  renderSqlCaseBar();
}

/**
 * 用例目录【到测试台打开】→ SQL 直测的交接（第卅三轮）：这条用例存好的值一并带过来。
 * 一条用例只走一种灌法，绝不混：
 *  ① 值是「参数名 → 值」且名字对得上 ③ 面板 → 灌 ③ 面板并重新拼装（位次随条件变，交给拼装引擎）
 *  ② 值是「按 ? 位次」的数组 → 按位次灌 ② 区绑定行（用例存的就是这条拍平模板的 ? 顺序，位次即事实）
 *  ③ 对不齐（位次数量与当前槽位不符、名字全对不上、值不是标量）→ 一个字都不灌，把值原样列出来并说清为什么
 * 混着灌会让同一个值既进条件又占一个绑定位，或者位次整体错位还看不出来——宁可少灌，不可错灌。
 */
async function consumeSqlCaseHandoff(d) {
  const pid = window.__pendingSqlCaseId;
  if (pid == null) return;
  window.__pendingSqlCaseId = null;   // 消费一次：以后再刷新这张卡不许悄悄盖掉用户手填的值
  let cases = [];
  try { cases = (await api("/api/cases?targetType=db_object&targetId=" + d.id)).cases || []; }
  catch (e) { /* 拉不到就按"没带过来"处理，下面统一说清 */ }
  const c = cases.find(x => String(x.id) === String(pid));
  if (!c) {
    window.__sqlCaseFill = { kind: "none", title: "参数没带过来",
      text: "这条用例已经不属于当前这张 SQL 卡（被删过或改过目标），值请按场景手填。" };
    renderSqlCaseBar();
    return;
  }
  applySqlCaseValues(c);
}

function applySqlCaseValues(c) {
  let p = c.params;
  if (typeof p === "string") { try { p = JSON.parse(p || "null"); } catch (e) { p = null; } }
  const dyn = window.__dyn || {};
  const byName = new Map((dyn.params || []).map(x => [x.name, x]));
  const fillNote = (kind, title, text) => { window.__sqlCaseFill = { kind, title, text }; renderSqlCaseBar(); };

  // 情形①：按名字的字典（手工建的用例、修复过的用例走这条）
  if (p && !Array.isArray(p) && !Array.isArray(p.values) && Object.keys(p).some(k => k !== "sql")) {
    const named = {}; const skipped = [];
    for (const [k, v] of Object.entries(p)) {
      if (k === "sql") continue;
      const q = byName.get(k);
      if (!q) { skipped.push(k + "（这张卡没有这个参数）"); continue; }
      if (q.out) { skipped.push(k + "（出参，填了库会报错）"); continue; }
      if (v !== null && typeof v === "object") { skipped.push(k + "（值是对象/数组，③ 面板要的是标量）"); continue; }
      named[k] = v;
    }
    const keys = Object.keys(named);
    if (!keys.length) {
      return fillNote("none", "没有灌值", `这条用例存的是 ${Object.keys(p).length} 个按名字的参数，没有一个对得上 ③ 面板的参数${skipped.length ? "：" + skipped.slice(0, 6).join("、") : ""}。值本身没丢，请对照 ① 区手填。`);
    }
    keys.forEach(k => dynInput(k, named[k] == null ? "" : String(named[k])));
    fillNote("named", "已灌 ③ 面板", `按参数名灌进 ③ 条件与参数 ${keys.length} 项${keys.length <= 8 ? "：" + keys.join("、") : ""}${skipped.length ? "；另有 " + skipped.length + " 项没灌（" + skipped.slice(0, 4).join("、") + "）" : ""}。`);
    return refreshAfterCaseInputs();
  }

  // 情形②：按 ? 位次的数组（模板生成的用例、LLM 按序给的数组都在这）
  const arr = Array.isArray(p) ? p : (p && Array.isArray(p.values) ? p.values : null);
  if (arr === null) {
    return fillNote("none", "没有灌值", "这条用例没有存参数值（或格式不认识），① ② ③ 区都按原样留着，直接手填即可。");
  }
  const inputs = Array.from(document.querySelectorAll("#sqlParams [data-sqlp]"));
  if (!inputs.length) {
    return fillNote("none", "没有灌值", `这条用例存了 ${arr.length} 个按位次的值，但当前语句没有绑定参数位——值在下面的执行结果里看不到，请对照 ① 区手填。`);
  }
  if (arr.length !== inputs.length) {
    return fillNote("none", "位次对不上，没灌",
      `这条用例存的是 ${arr.length} 个按位次的值，这张卡现在只有 ${inputs.length} 个绑定位${(dyn.hashNames || []).length ? "（③ 面板的条件决定拼进去哪些片段，位次会跟着变）" : ""}。位次错位比不灌危险得多，所以一个字都没填；请对照 ① 区手填。`);
  }
  let done = 0; const sk = [];
  arr.forEach((v, i) => {
    const el = inputs[i];
    if (el.disabled) { sk.push("第 " + (i + 1) + " 位是出参"); return; }
    if (v !== null && typeof v === "object") { sk.push("第 " + (i + 1) + " 位是对象/数组"); return; }
    el.value = v == null ? "" : String(v);   // null 只能是空串：拼成 "null" 就污染留痕
    done++;
  });
  if (!done) {   // 一位都没灌就别写"已灌"：界面说"灌了 0 位"等于什么都没说
    return fillNote("none", "一个字都没灌",
      `位次对得上（${inputs.length} 位），但这 ${arr.length} 个值没有一个是能直接填进去的标量${sk.length ? "：" + sk.slice(0, 5).join("、") + (sk.length > 5 ? "…" : "") : ""}。② 区保持空白，请对照 ① 区自己填。`);
  }
  fillNote("positional", "已灌 ② 区", `按位次灌进 ② 绑定参数 ${done} 位（用例存的就是拍平模板的 ? 顺序）${sk.length ? "；没灌 " + sk.length + " 位：" + sk.slice(0, 5).join("、") : ""}。③ 面板的条件参数没有自动改——位次与条件名是两套事实，不混。`);
}

/** 名字灌完要重新拼一次：① 区语句与 ② 区位次都以拼装结果为准，不重拼就是拿旧语句配新值 */
async function refreshAfterCaseInputs() {
  await composeDyn(false);
  renderSqlBinds(window.__sqlExec || (window.__sqlObj || {}).sql_template || "", null);
  paintSqlHint();
}

/* ---------- 知识库 ---------- */
/* 第 16 轮：三段各自异步加载 + 服务端过滤分页。
   原先 views.knowledge 一次拉两份全量 JSON（真实项目实测 822KB + 4.5MB）再在前端切 200/页：
   页面要先白屏几秒，从改动流【🧪 查看用例】跳过来这段时间里就是"啥也看不到"。
   现在三段并发请求，谁先回来谁先出 → 秒开。
   第 18 轮：每页条数不再写死，统一走翻页条的 listSize("kb_" + kind)。 */
views.knowledge = async () => {
  await loadProjects();
  return `
  <div class="row spread mb"><div class="row">${projectSelect()}
    <button class="ghost small" onclick="batchConfirm()">✔ 按置信度批量确认</button>
    <button class="ghost small" onclick="confirmAll()">✔ 确认全部草稿</button>
    <button class="ghost small" onclick="newIfc()">＋ 新建接口</button>
    <button class="ghost small" onclick="importOpenApi()">⬇ OpenAPI 导入</button>
    <button class="ghost small" onclick="importPostman()">⬇ Postman 导入</button>
    <button class="ghost small" onclick="importHar()">⬇ HAR 流量导入</button>
    <button class="ghost small" onclick="exportPack()">⬆ 导出知识包</button>
    <button class="ghost small" onclick="importPack()">⬇ 导入知识包</button></div>
    <span class="muted">LLM 产物均为草稿，人工确认后生效；人工编辑过的不被重学覆盖</span></div>
  <div class="card" id="kbIfc"><h3>🔗 接口卡</h3><div class="empty">载入中…</div></div>
  <div class="card" id="kbDob"><h3>🗄 SQL / 数据对象卡</h3><div class="empty">载入中…</div></div>
  <div class="card" id="kbCases"><div class="empty">用例目录载入中…</div></div>`;
};
function kbFilterOf(kind) {
  state.kbFilter ||= {};
  // call 是「这一屏要不要现算对外路径」的开关：开了才让后端去读盘（项目级证据 TTL 内共用）
  if (!state.kbFilter[kind]) state.kbFilter[kind] = { repo: "", status: "", q: "", page: 1, call: false };
  return state.kbFilter[kind];
}
async function kbRepos() {
  if (!state.reposCache || state.reposCache.pid !== state.projectId) {
    state.reposCache = { pid: state.projectId,
      repos: (await api("/api/repos?projectId=" + state.projectId)).repos || [] };
  }
  return state.reposCache.repos;
}
function kbSet(kind, key, value) {
  const f = kbFilterOf(kind);
  f[key] = value;
  if (key !== "page") f.page = 1;
  loadKbSection(kind);
}
function kbSearch(kind) {
  const el = document.getElementById(kind === "ifc" ? "kbIfcQ" : "kbDobQ");
  kbSet(kind, "q", el ? el.value.trim() : "");
}
/** 三块列表共用的过滤条：仓库 / 状态 / 关键字（接口卡多一个「算对外路径」的看列开关），都带当前值回显 */
async function kbBar(kind, f, counts) {
  const repos = await kbRepos();
  const opt = (v, l, n) => `<option value="${esc(v)}"${String(f.repo) === String(v) ? " selected" : ""}>${esc(l)}${n == null ? "" : "（" + n + "）"}</option>`;
  return `<div class="row mb" style="gap:10px;align-items:flex-end">
    <label class="f" style="margin:0;display:flex;flex-direction:column;gap:2px"><span style="font-size:11px;color:var(--muted)">仓库</span>
      <select onchange="kbSet('${kind}','repo',this.value)">
        ${opt("", "全部仓库", counts.all)}
        ${repos.map(r => opt(r.id, r.name, kind === "ifc" ? r.iface_count : r.db_count)).join("")}
        <option value="none"${f.repo === "none" ? " selected" : ""}>未归属仓库</option></select></label>
    <label class="f" style="margin:0;display:flex;flex-direction:column;gap:2px"><span style="font-size:11px;color:var(--muted)">状态</span>
      <select onchange="kbSet('${kind}','status',this.value)">
        <option value=""${!f.status ? " selected" : ""}>全部状态</option>
        ${[["draft", "草稿"], ["confirmed", "已确认"]].map(([v, l]) => `<option value="${v}"${f.status === v ? " selected" : ""}>${l}</option>`).join("")}</select></label>
    <label class="f" style="margin:0;flex:1;display:flex;flex-direction:column;gap:2px"><span style="font-size:11px;color:var(--muted)">关键字</span>
      <input id="kb${kind === "ifc" ? "Ifc" : "Dob"}Q" value="${esc(f.q)}"
        placeholder="${kind === "ifc" ? "接口名 / 路径" : "卡名 / SQL 文本"}，回车搜索"
        onkeydown="if(event.key==='Enter')kbSearch('${kind}')"></label>
    <button class="small" onclick="kbSearch('${kind}')">搜索</button>
    ${kind === "ifc" ? `<label style="display:flex;align-items:center;gap:5px;font-size:12px;color:var(--muted);padding-bottom:7px"
      data-tip="勾上才让后端按盘上证据现算每张卡「经网关要发的那条路径」：网关配置 / 模块 spring.application.name / 前端 axios baseURL 与调用点。不勾就只列学到的内部路径，这一屏秒开；勾了会读一次盘（同一项目十分钟内共用）。">
      <input type="checkbox" style="width:auto"${f.call ? " checked" : ""} onchange="kbSet('ifc','call',this.checked)">🛣 算对外路径</label>` : ""}
    ${f.q || f.status || f.repo ? `<button class="small ghost" onclick="kbClear('${kind}')">✕ 清空条件</button>` : ""}
  </div>`;
}
/** 清空的是过滤条件，不动「算对外路径」这个看列的开关——它不是条件，是这一屏要不要多读一次盘 */
function kbClear(kind) {
  state.kbFilter[kind] = { repo: "", status: "", q: "", page: 1, call: kbFilterOf(kind).call };
  loadKbSection(kind);
}
function kbPager(kind, total, page) {
  if (!total) return "";
  return pagerBar({ key: "kb_" + kind, total, page, pager: "kb_" + kind });
}
["ifc", "dob"].forEach(k => pagerDefine("kb_" + k, p => kbSet(k, "page", p), () => kbSet(k, "page", 1)));
/** 列头先点名「这一列按哪个环境算的」：同一张卡换个 Base URL 可能换一段前缀，不点名就是暗猜（第五十轮） */
function kbCallHead(r) {
  if (r.call_note) return `<div class="result-fail" style="margin-bottom:8px">${esc(r.call_note)}</div>`;
  if (!r.call_env) return "";
  return `<div class="muted" style="font-size:12px;margin-bottom:6px">🛣 这一列按环境 <b>${esc(r.call_env)}</b>
    的 Base URL 现算（换环境请看下面那一格）；某一张卡要逐段依据，到「测试台」打开它——同一套判据、同一个答案。</div>`;
}
/** 一格两件事都得看得见：发出去的完整路径 + 四态徽章；没补前缀的要把"为什么"摆在明面上，长依据收进悬停 */
function kbCallCell(i) {
  const col = LAB_PATH_COLOR[i.call_state] || "#64748b";
  const tip = [i.call_note_row, i.call_evidence ? "依据：" + i.call_evidence : ""].filter(Boolean).join(" ｜ ");
  const why = i.call_changed ? "" : `<div class="muted" style="font-size:11.5px">${esc(i.call_note_row
    || (i.call_state_label || "") + "：对外路径与学到的内部路径一致")}</div>`;
  return `<td class="wrap"${tip ? ` data-tip="${esc(tip)}"` : ""}>
    <div><code style="color:${col}">${esc(i.call_path || "")}</code> ${callBadge(i.call_state, i.call_state_label)}</div>
    ${why}</td>`;
}
async function loadKbSection(kind) {
  const box = document.getElementById(kind === "ifc" ? "kbIfc" : "kbDob");
  if (!box) return;
  const f = kbFilterOf(kind);
  const page = Math.max(1, Number(f.page) || 1);
  const base = kind === "ifc" ? "/api/interfaces" : "/api/db-objects";
  const p = new URLSearchParams({ projectId: state.projectId, page, size: listSize("kb_" + kind) });
  if (f.q) p.set("q", f.q);
  if (f.status) p.set("status", f.status);
  if (f.repo === "none") p.set("noRepo", "1"); else if (f.repo) p.set("repoId", f.repo);
  if (kind === "ifc" && f.call) p.set("callPath", "1");
  const r = await api(base + "?" + p.toString());
  if (!r.ok) { box.innerHTML = `<div class="result-fail">加载失败：${esc(r.error || "服务无响应")}</div>`; return; }
  const rows = (kind === "ifc" ? r.interfaces : r.dbObjects) || [];
  const total = Number(r.total) || 0;
  const title = kind === "ifc" ? "🔗 接口卡" : "🗄 SQL / 数据对象卡";
  const callHead = kind === "ifc" ? kbCallHead(r) : "";
  const head = `<h3>${title}（${total}）</h3>${await kbBar(kind, f, { all: total })}`;
  if (!rows.length) {
    box.innerHTML = head + callHead + `<div class="empty">${total ? "这一页没有卡片，换个页码"
      : (f.q || f.status || f.repo ? "当前条件下没有卡片——点上面的【✕ 清空条件】，或到「项目与仓库」点【学习】"
        : "还没有卡片——到「项目与仓库」点【学习】，或【＋ 新建接口】手工补")}</div>`;
    fitControls(box);
    return;
  }
  if (kind === "ifc") {
    box.innerHTML = head + callHead + `<div class="tbl-scroll"><table><tr><th>方法</th><th>学到的路径</th>
      ${f.call ? "<th>🛣 对外要发的路径</th>" : ""}<th>名称</th><th>仓库</th><th>状态</th><th>置信度</th><th>来源</th><th>操作</th></tr>
      ${rows.map(i => { let src = ""; try { src = JSON.parse(i.source || "[]").map(s => s.file).join(", "); } catch (e) {}
        return `<tr><td>${badge(i.http_method, i.http_method === "GET" ? "green" : "indigo")}</td>
        <td class="wrap"><code>${esc(i.path)}</code></td>${f.call ? kbCallCell(i) : ""}<td class="wrap">${esc(i.name)}</td>
        <td class="muted">${esc(i.repo_name || "未归属")}</td><td>${statusBadge(i.status)}</td>
        <td class="muted">${Math.round(i.confidence * 100)}%</td><td class="muted wrap">${esc(src)}</td>
        <td style="white-space:nowrap"><button class="small ghost" onclick="editIfc(${i.id})">编辑</button>
        <button class="small ghost" onclick="pickLabIfcById(${i.id})">到测试台打开</button></td></tr>`; }).join("")}</table></div>
      ${kbPager("ifc", total, page)}`;
  } else {
    window.__kbDobPage = rows;   // 行内只传下标，避免把卡名拼进 onclick 属性再踩引号转义
    box.innerHTML = head + `<div class="tbl-scroll"><table><tr><th>类型</th><th>名称</th><th>涉及表</th><th>仓库</th><th>状态</th><th>动态拼装</th><th>模板</th><th>操作</th></tr>
      ${rows.map((d, i) => { let tabs = d.tables_involved || "-"; try { tabs = JSON.parse(d.tables_involved).join("、"); } catch (e) {}
        const tpl = d.sql_template || "";
        return `<tr><td>${badge(d.obj_type, "amber")}</td><td class="wrap">${esc(d.name)}</td>
        <td class="muted wrap">${esc(tabs)}</td><td class="muted wrap">${esc(d.repo_names || "未归属")}</td>
        <td>${statusBadge(d.status)}</td>
        <td>${d.has_dynamic ? '<span style="color:var(--green)">可拼装</span>' : '<span class="muted">需重学</span>'}</td>
        <td class="wrap"><code style="font-size:11px">${esc(tpl.slice(0, 70))}${tpl.length > 70 ? "…" : ""}</code></td>
        <td style="white-space:nowrap"><button class="small ghost" onclick="kbDobOpen(${i})">到直测打开</button>
        <button class="small ghost" onclick="tableMeta(${d.id})">表结构</button></td></tr>`; }).join("")}</table></div>
      ${kbPager("dob", total, page)}`;
  }
  fitControls(box);
}
function kbDobOpen(i) {
  const d = (window.__kbDobPage || [])[i];
  if (!d) return toast("这一行已过期，请重新搜索", true);
  openCaseTarget("db_object", d.id, d.name);
}
async function pickLabIfcById(id) {
  // 行内只传 id：卡名本来就是这次请求取回来的，拼进 onclick 属性只会多一处引号坑
  const r = await api("/api/interfaces/" + id);
  if (!r.ok) return toast(r.error || "接口卡不存在", true);
  const itf = r.interface || {};
  state.labIfcId = id;
  state.labIfcLabel = `[${itf.http_method || "?"}] ${itf.path || ""} · ${itf.name || ""}（仓库：${itf.repo_name || "未归属"}）`;
  resetLabIdentity();
  navTo("#/lab");
}
async function tableMeta(dobId) {
  const r = await api("/api/db-objects/" + dobId);
  const d = (r.ok && r.dbObject) || {};
  let tables = [];
  try { tables = JSON.parse(d.tables_involved || "[]"); } catch (e) {}
  const envs = (await api("/api/environments?projectId=" + state.projectId)).environments || [];
  const m = modal(`<h3>🧲 表结构反哺（真实库元数据）</h3>
    <div class="muted mb">SQL 卡 <code>${esc(d.name || "#" + dobId)}</code> 涉及表：${tables.join("、") || "未识别"}</div>
    <div class="form-item"><label>环境（用它的数据库连接拉取）</label><select id="tmEnv">${envs.map(e => `<option value="${e.id}">${esc(e.name)}</option>`).join("") || '<option value="">（无环境，先去系统设置添加）</option>'}</select></div>
    <div class="form-item"><label>表名</label><input id="tmTable" value="${esc(tables[0] || "")}" placeholder="t_order"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button><button id="tmGo">▶ 拉取</button></div>
    <div id="tmOut" class="mt"></div>`);
  m.querySelector("#tmGo").onclick = async () => {
    const envId = Number(m.querySelector("#tmEnv").value);
    const table = m.querySelector("#tmTable").value.trim();
    if (!envId) return toast("请先在系统设置添加测试环境", true);
    if (!table) return toast("表名不能为空", true);
    const rr = await api("/api/db-metadata/pull", { envId, projectId: state.projectId, table });
    m.querySelector("#tmOut").innerHTML = rr.ok
      ? `<table><tr><th>列名</th><th>类型</th><th>长度</th><th>可空</th><th>注释</th></tr>${rr.columns.map(c => `<tr><td><code>${esc(c.name || c.column_name || "")}</code></td><td>${esc(String(c.data_type || ""))}</td><td>${esc(String(c.character_maximum_length ?? ""))}</td><td>${c.is_nullable === "NO" ? "否" : "是"}</td><td class="muted">${esc(c.comment || "")}</td></tr>`).join("")}</table><div class="muted">${rr.columns.length ? `已缓存到项目元数据（table_meta），共 ${rr.columns.length} 列` : "未拉到任何列：表名可能写错，或该库账号对这张表没有查询权限（字典视图只展示可访问的表）"}</div>`
      : `<div style="color:var(--red)">拉取失败：${esc(rr.error)}</div>`;
  };
}

/* ---------- 用例目录（第 15 轮建，第 16 轮改）----------
   补的是「SQL 卡生成的用例在全界面根本没有一处能看到」这个洞：接口测试台只列当前接口的用例，
   SQL 直测连列的地方都没有。这里按项目跨目标列全量，服务端分页。
   第 16 轮的三处改动：每页 200→50；加「仓库 / 执行状态」两个条件；默认视图改为【按目标】——
   真实项目上 125 条用例全挂在同一张 SQL 卡上，逐条铺开就是"搞不清楚"。
   第 18 轮：每页条数交给统一翻页条（listSize("cases")），不再写死。 */
const ORIGIN_CN = { llm: "LLM 生成", template: "模板生成", manual: "手工保存", import: "随知识包导入" };
const defaultCaseFilter = () => ({ targetType: "", origin: "", status: "", runState: "", repo: "", group: "target", targetId: "", q: "", commit: null, page: 1 });
function gotoCases(commitId) {
  state.caseFilter = Object.assign(defaultCaseFilter(), { commit: commitId || null });
  navTo("#/knowledge?sec=cases");
}
function caseSet(key, value) {
  const f = state.caseFilter || (state.caseFilter = defaultCaseFilter());
  f[key] = value;
  if (key !== "page") f.page = 1;
  loadCaseCatalog();
}
function caseSearchGo() {
  const q = document.getElementById("cfQ");
  caseSet("q", q ? q.value.trim() : "");
}
function clearCaseFilter() { state.caseFilter = defaultCaseFilter(); loadCaseCatalog(); }
async function loadCaseCatalog() {
  const box = document.getElementById("kbCases");
  if (!box) return;
  if (!state.caseFilter) state.caseFilter = defaultCaseFilter();
  const f = state.caseFilter;
  const p = new URLSearchParams({ projectId: state.projectId, page: f.page, size: listSize("cases") });
  if (f.targetType) p.set("targetType", f.targetType);
  if (f.origin) p.set("origin", f.origin);
  if (f.status) p.set("status", f.status);
  if (f.runState) p.set("runState", f.runState);
  if (f.repo === "none") p.set("noRepo", "1"); else if (f.repo) p.set("repoId", f.repo);
  if (f.group) p.set("group", f.group);
  if (f.targetId && f.targetType) { p.set("targetId", f.targetId); p.set("targetType", f.targetType); }
  if (f.q) p.set("q", f.q);
  if (f.commit) p.set("commitId", f.commit);
  const r = await api("/api/cases/catalog?" + p.toString());
  if (!r.ok) { box.innerHTML = `<h3>🧪 用例</h3><div class="result-fail">用例目录加载失败：${esc(r.error || "服务无响应")}</div>`; return; }
  const s = r.summary || {};
  const col = inner => `<span style="display:flex;flex-direction:column;gap:2px">${inner}</span>`;
  const sel = (id, cur, items) => col(`<label class="f" style="margin:0">${({ targetType: "目标类型", origin: "来源", status: "用例状态", runState: "执行结果", group: "视图" })[id] || id}</label>
    <select data-cf="${id}">${items.map(([v, l]) => `<option value="${esc(v)}"${v === cur ? " selected" : ""}>${esc(l)}</option>`).join("")}</select>`);
  const repos = await kbRepos();
  const repoSel = col(`<label class="f" style="margin:0">仓库</label>
    <select data-cf="repo"><option value="">全部仓库</option>
      ${repos.map(x => `<option value="${x.id}"${String(f.repo) === String(x.id) ? " selected" : ""}>${esc(x.name)}</option>`).join("")}
      <option value="none"${f.repo === "none" ? " selected" : ""}>未归属仓库</option></select>`);
  const filtered = !!(f.targetType || f.origin || f.status || f.runState || f.repo || f.q || f.targetId || f.commit);
  const n = x => Number(x) || 0;
  box.innerHTML = `<h3>🧪 用例（${n(f.group ? r.caseTotal : r.total)} 条 · ${n(f.group ? r.total : s.target_total)} 个目标）</h3>
    <div class="row" style="gap:10px;align-items:flex-end">
      ${sel("group", f.group, [["target", "按目标看（默认）"], ["", "逐条看"]])}
      ${sel("targetType", f.targetType, [["", "全部目标"], ["interface", "接口"], ["db_object", "SQL / 数据对象"]])}
      ${repoSel}
      ${sel("status", f.status, [["", "全部状态"], ["pending", "待评审"], ["approved", "已启用"], ["suspect", "需复核"], ["deprecated", "已废弃"]])}
      ${sel("runState", f.runState, [["", "跑没跑过都行"], ["never", "没跑过"], ["run", "跑过至少一次"], ["pass", "最近一次通过"], ["fail", "最近一次失败"]])}
      ${sel("origin", f.origin, [["", "全部来源"]].concat((r.origins || []).map(o => [o, ORIGIN_CN[o] || o])))}
      ${col(`<label class="f" style="margin:0">关键字</label><input id="cfQ" style="min-width:240px" placeholder="用例名 / 场景 / 目标名，回车搜索" value="${esc(f.q)}">`)}
      <button class="small" onclick="caseSearchGo()">搜索</button>
      ${filtered ? `<button class="small ghost" onclick="clearCaseFilter()">✕ 清空条件</button>` : ""}
    </div>
    <div class="muted mt" style="font-size:12px">已启用 ${n(s.approved)} · 待评审 ${n(s.pending)} · 需复核 ${n(s.suspect)} · 没跑过 ${n(s.never_run)}
      ${n(s.direct_covered) ? ` · <b style="color:#2563eb">其中 ${n(s.direct_covered)} 条的目标被人直测过（只算留痕，不算这条用例已测）</b>` : ""}
      ${f.commit ? ` · <b>当前只看提交 #${f.commit} 关联的 ${scopeTargets(r)} 个目标所挂用例</b>（与【⚡ 一键回归】同一作用范围）<br>${scopeWhy(r)}` : ""}</div>
    ${f.group ? caseTargetTable(r) : caseFlatTable(r, f)}`;
  box.querySelectorAll("select[data-cf]").forEach(x => {
    x.onchange = () => caseSet(x.dataset.cf, x.value);
  });
  const q = document.getElementById("cfQ");
  if (q) q.addEventListener("keydown", e => { if (e.key === "Enter") caseSearchGo(); });
  fitControls(box);
  // 只在「从改动流跳过来」那一次滚到位；之后翻页/改条件不再抢滚动位置
  if (location.hash.includes("sec=cases")) {
    history.replaceState(null, "", location.hash.replace("?sec=cases", ""));
    box.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}
/**
 * 提交作用域：这条提交到底归因到几个目标（第 17 轮）。数字来自 commit_targets，
 * 与改动流「本提交用例」、影响分析、一键回归同一份口径。
 */
function scopeTargets(r) {
  return Number((r.scope || {}).targetTotal) || 0;
}
/** 说清"为什么是这些用例"：按语句命中就列语句名，按整文件就明说是整文件（不假装细到语句） */
function scopeWhy(r) {
  const s = r.scope || {};
  const stmt = Number(s.stmtTargets) || 0;
  const file = Number(s.fileTargets) || 0;
  const chain = Number(s.chainTargets) || 0;
  if (!stmt && !file && !chain) return "这条提交还没归因出目标——改动文件没绑定到接口卡/SQL 卡，也不在任何卡的调用链记账里（可能没学习，或改的是前端与配置文件）";
  const anchors = (s.anchors || []).map(x => esc(String(x))).join("、");
  const parts = [];
  if (stmt) parts.push(`<b style="color:var(--green)">按改动行命中 ${stmt} 条语句</b>${anchors ? `：${anchors}${stmt > anchors.split("、").length ? " …" : ""}` : ""}`);
  if (chain) parts.push(`<b style="color:#b45309">${chain} 个目标按调用链归因</b>（改的是这张卡调用链上的服务层／DAO 文件，按代码可达给出、可能偏宽）`);
  if (file) parts.push(`<b>${file} 个目标按整文件归因</b>（改的不是 SQL 映射文件，或这个版本的源码没法按行定位，只能给整个文件的用例）`);
  return "用例作用域：" + parts.join("；");
}
function casePager(kind, total, page) {
  if (!total) return "";
  return pagerBar({ key: "cases", total, page, pager: "cases" });
}
pagerDefine("cases", p => caseSet("page", p), () => caseSet("page", 1));
/** 按目标看：一行一个目标，带用例数与跑况，点【只看这一目标】再下钻到逐条 */
function caseTargetTable(r) {
  const list = r.targets || [];
  window.__kbCaseTargets = list;
  if (!list.length) return `<div class="empty">${(r.caseTotal || r.total) ? "当前筛选条件下没有命中的目标——点上面的【✕ 清空条件】"
    : "本项目还没有用例——到「改动流」找到改动提交点【生成用例】，SQL 卡与接口卡都会按目标生成"}</div>`;
  return `<div class="tbl-scroll mt"><table><tr><th>目标类型</th><th>目标</th><th>用例数</th><th>状态分布</th><th>执行分布</th><th>最近改动</th><th>操作</th></tr>
    ${list.map((t, i) => `<tr>
      <td>${badge(t.target_type === "interface" ? "接口" : "SQL", t.target_type === "interface" ? "indigo" : "amber")}</td>
      <td class="wrap">${esc(t.target_name || "（目标已删除 #" + t.target_id + "）")}</td>
      <td><b>${t.case_count}</b></td>
      <td class="muted">已启用 ${t.approved} · 待评审 ${t.pending} · 需复核 ${t.suspect}${t.deprecated ? ` · 已废弃 ${t.deprecated}` : ""}${t.disabled ? ` · 已停用 ${t.disabled}` : ""}</td>
      <td>${t.run_pass ? `<span style="color:var(--green)">通过 ${t.run_pass}</span> ` : ""}${t.run_fail ? `<span style="color:var(--red)">失败 ${t.run_fail}</span> ` : ""}${t.never_run ? `<span class="muted">未跑 ${t.never_run}</span>` : ""}${Number(t.direct_count) ? `<br><span class="muted" style="font-size:11px">另有目标直测 ${t.direct_count} 次${t.last_direct_at ? "（最近 " + esc(fmtTime(t.last_direct_at)) + "）" : ""}，只算留痕</span>` : ""}</td>
      <td class="muted">${esc(fmtTime(t.updated_at))}</td>
      <td style="white-space:nowrap"><button class="small ghost" onclick="caseDrill(${i})">只看这一目标</button>
      <button class="small ghost" onclick="caseTargetOpen(${i})">到测试台打开</button>
      <button class="small ghost" onclick="openRunsForTarget(${i})">📜 执行记录</button></td></tr>`).join("")}</table></div>
    ${casePager("t", r.total, Number(state.caseFilter.page))}`;
}
/**
 * 「最近执行」三态（第 20 轮）。中间那态是用户报"我明明测过了，这里怎么还是未跑过"的直接来源：
 * 在测试台/SQL 直测上随手跑一把是不带用例身份的，界面过去只知道"这条用例没有记录"，
 * 于是把"这个目标测过"错报成"没测过"。现在两件事分开说，并写清哪一头才算已测。
 */
function runCell(c) {
  if (c.last_run_status) return `${statusBadge(c.last_run_status)}
      <span class="muted" style="font-size:11px"> ${esc(fmtTime(c.last_run_at))} · 共 ${Number(c.run_count) || 0} 次</span>`;
  const d = Number(c.direct_count) || 0;
  if (d) return `${badge("目标直测", "blue")}
      <span class="muted" style="font-size:11px"> ${esc(fmtTime(c.last_direct_at))} · 共 ${d} 次<br>
      直测只作标记：不算这条用例已测，也不回写覆盖率</span>`;
  return `<span class="muted">未跑过</span>`;
}
/** 行内执行：跑完立刻重查目录，「最近执行」当场从"未跑过"变过来（原来必须自己刷新才知道有没有记上） */
async function runCaseRow(i) {
  const c = (window.__kbCases || [])[i];
  if (!c) return toast("这一行已经过期，请重新搜索", true);
  if (!c.enabled) return toast("这条用例已停用：先到测试台带身份打开、改完再启用执行", true);
  const envs = (await api("/api/environments?projectId=" + state.projectId)).environments || [];
  const m = modal(`<h3>▶ 执行用例「${esc(c.name)}」</h3>
    <div class="muted">目标：${esc(c.target_name || ("#" + c.target_id))}。按这条用例存好的参数与断言原样跑一遍；写库默认回滚。</div>
    <div class="form-item mt"><label>执行环境</label>
      <select id="rcEnv">${envs.map(e => `<option value="${e.id}">${esc(e.name)}</option>`).join("")}</select></div>
    ${envs.length ? "" : `<div class="result-fail">还没有测试环境：先到「系统设置 → 测试环境」添加后再来。</div>`}
    <div class="muted mt" style="font-size:12px">要改参数、看响应细节或选登录流，取消这里改点【到测试台打开】（会带着这条用例的身份和参数过去，参数按上面这份值预填）。</div>
    <div id="rcOut" class="mt"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button>
      <button id="rcGo" ${envs.length ? "" : "disabled"}>▶ 执行</button></div>`);
  const out = m.querySelector("#rcOut"), go = m.querySelector("#rcGo");
  go.onclick = async () => {
    const body = { caseId: c.id, envId: Number(m.querySelector("#rcEnv").value) || null };
    if (state.caseFilter && state.caseFilter.commit) body.commitId = state.caseFilter.commit;
    out.innerHTML = `<div class="empty">执行中…（跑完这一行会自己刷新）</div>`;
    const r = await api("/api/test-runs", body, null, { progress: true });
    if (!r.ok) { out.innerHTML = `<div class="result-fail">执行失败：${esc(r.error || "服务无响应")}</div>`; return; }
    const run = r.run, a = run.assertions || [];
    const bad = a.filter(x => !x.pass).length;
    out.innerHTML = `<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap">
        ${statusBadge(run.status)}<span class="muted">${run.latencyMs}ms${run.httpStatus ? " · HTTP " + run.httpStatus : ""}
          · 断言 ${a.length ? `${a.length - bad} 通过 / ${bad} 失败` : "无"}</span></div>
        ${run.error ? `<div class="mt" style="color:var(--red)">失败原因：${esc(run.error)}</div>` : ""}
        <div class="row mt" style="gap:8px">
          <button class="small ghost" onclick="openRunDetail(${run.runId})">看这次的请求与响应</button>
          ${body.commitId ? `<button class="small ghost" onclick="openRuns({commitId:${body.commitId}},'提交 #${body.commitId} 的执行记录')">📜 这一批的记录</button>` : ""}
        </div>`;
    loadCaseCatalog();   // 让「最近执行」当场反映这次执行
  };
}
function openRunsForCase(i) {
  const c = (window.__kbCases || [])[i];
  if (!c) return toast("这一行已经过期，请重新搜索", true);
  openRuns({ caseId: c.id }, "用例「" + (c.name || "#" + c.id) + "」的执行记录");
}
function openRunsForTarget(i) {
  const t = (window.__kbCaseTargets || [])[i];
  if (!t) return toast("这一行已经过期，请重新搜索", true);
  openRuns({ targetType: t.target_type, targetId: t.target_id },
    "目标「" + (t.target_name || ("#" + t.target_id)) + "」的执行记录");
}
/** 逐条看：原来那张表 */
function caseFlatTable(r, f) {
  const list = r.cases || [];
  window.__kbCases = list;   // 行内按钮只传下标，避免把目标名拼进 onclick 属性里再踩引号转义的坑
  if (!list.length) return `<div class="empty">${r.total ? "当前筛选条件下没有用例——点上面的【✕ 清空条件】"
    : "本项目还没有用例——到「改动流」找到改动提交点【生成用例】，SQL 卡与接口卡都会按目标生成"}</div>`;
  return `<div class="tbl-scroll mt"><table><tr><th>目标类型</th><th>目标</th><th>用例</th><th>场景</th><th>来源</th><th>状态</th><th>最近执行</th><th>操作</th></tr>
    ${list.map((c, i) => `<tr>
      <td>${badge(c.target_type === "interface" ? "接口" : "SQL", c.target_type === "interface" ? "indigo" : "amber")}</td>
      <td class="wrap"><a href="#" onclick="openCaseTargetAt(${i});return false">${esc(c.target_name || "（目标已删除 #" + c.target_id + "）")}</a></td>
      <td class="wrap">${esc(c.name)}${c.enabled ? "" : " " + badge("已停用", "gray")}</td>
      <td class="muted wrap">${esc(c.scenario || "")}</td>
      <td class="muted">${esc(ORIGIN_CN[c.origin] || c.origin || "")}</td>
      <td>${statusBadge(c.status)}</td>
      <td>${runCell(c)}</td>
      <td style="white-space:nowrap"><button class="small" onclick="runCaseRow(${i})">▶ 执行这条</button>
      <button class="small ghost" onclick="openCaseTargetAt(${i})">到测试台打开</button>
      <button class="small ghost" onclick="openRunsForCase(${i})">📜 记录</button></td>
    </tr>`).join("")}</table></div>
    ${casePager("c", r.total, f.page)}`;
}
function caseDrill(i) {
  const t = (window.__kbCaseTargets || [])[i];
  if (!t) return toast("这一行已过期，请重新搜索", true);
  Object.assign(state.caseFilter, { group: "", targetType: t.target_type, targetId: t.target_id, page: 1 });
  loadCaseCatalog();
}
function caseTargetOpen(i) {
  const t = (window.__kbCaseTargets || [])[i];
  if (!t) return toast("这一行已过期，请重新搜索", true);
  openCaseTarget(t.target_type, t.target_id, t.target_name);
}
/** 用例行的目标 → 直接带到对应测试台：接口卡进「接口测试台」，SQL 卡进「SQL 直测」并自动选卡。
 *  第 20 轮：连同"这条是哪一号用例"一起带过去，过来直接点【执行】就算在该用例头上，
 *  不再像原先那样跳过去身份就丢了，测完回到目录还是"未跑过"。 */
function openCaseTargetAt(i) {
  const c = (window.__kbCases || [])[i];
  if (!c) return toast("这一行已经过期，请重新搜索", true);
  openCaseTarget(c.target_type, c.target_id, c.target_name, c.id, c.name);
}
async function openCaseTarget(type, id, label, caseId, caseName) {
  const commit = (state.caseFilter || {}).commit || null;
  if (type === "interface") {
    state.labIfcId = id;
    state.labIfcLabel = label || state.labIfcLabel || "";
    window.__labAssertions = []; window.__labIgnore = [];
    state.labCommitId = commit;
    window.__pendingLabCaseId = caseId || null;   // 表单是异步重建的，值要等重建完再灌（loadLabIfc 里消费一次）
    navTo("#/lab");
    bindLabCase(caseId, caseName);
    return;
  }
  const r = await api("/api/db-objects/" + id);
  if (!r.ok || !r.dbObject) return toast("该用例的目标 SQL 卡已不存在：" + (r.error || ""), true);
  const d = r.dbObject;
  window.__sqlObj = d;
  window.__sqlObjLabel = `[${d.obj_type}] ${d.name}（仓库：${d.repo_names || "未归属"}）`;
  bindSqlCase(caseId, caseName, commit);
  window.__pendingSqlCaseId = caseId || null;     // 同上：③ 面板要等派生结果到手才灌（loadSqlObj 里消费一次）
  navTo("#/sql");
}
/* N26：按置信度批量确认。置信度是学习器自报的（0.95 文档/流量导入 · 0.9 经 LLM 增强 · 0.6 纯静态解析），
   所以确认前必须让人抽查，不能一键放行全部。 */
const CONF_TIERS = [["0.9", "高（≥90%：文档/流量导入，或经 LLM 增强）"],
                    ["0.6", "中（≥60%：纯静态解析的学习产物）"],
                    ["0", "全部（不限置信度，需自行把关）"]];
function batchConfirm() {
  const m = modal(`<h3>✔ 按置信度批量确认接口卡</h3>
    <div class="form-item"><label>确认范围（只看草稿卡，已确认的不动）</label>
      <select id="bcTier">${CONF_TIERS.map(([v, t]) => `<option value="${v}" ${v === "0.9" ? "selected" : ""}>${t}</option>`).join("")}</select></div>
    <div id="bcBody" class="muted">统计中…</div>
    <div class="muted" style="margin-top:8px">批量确认只改状态，不算人工编辑：下次重学仍会按学习结果刷新这张卡。</div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="bcGo">确认这 0 张</button></div>`);
  const body = m.querySelector("#bcBody"), go = m.querySelector("#bcGo");
  let hits = 0;
  const refresh = async () => {
    body.innerHTML = `<span class="muted">统计中…</span>`;
    const min = m.querySelector("#bcTier").value;
    const r = await api(`/api/interfaces/confirm-preview?projectId=${state.projectId}&minConfidence=${min}`);
    if (!r.ok) { body.innerHTML = `<span style="color:var(--red)">${esc(r.error)}</span>`; hits = 0; }
    else {
      hits = r.total;
      const rows = (r.preview || []).map(i => `<tr><td>${badge(i.http_method, i.http_method === "GET" ? "green" : "indigo")}</td>
        <td class="wrap"><code>${esc(i.path)}</code></td><td>${esc(i.name)}</td>
        <td class="muted">${Math.round(i.confidence * 100)}%</td></tr>`).join("");
      body.innerHTML = `草稿共 ${r.drafts} 张，当前阈值命中 <b>${hits}</b> 张`
        + (hits > r.preview.length ? `（下表抽查前 ${r.preview.length} 张）` : "")
        + (rows ? `<table style="margin-top:6px"><tr><th>方法</th><th>路径</th><th>名称</th><th>置信度</th></tr>${rows}</table>`
                : `<div class="empty" style="margin-top:6px">没有命中，换个阈值或先去「项目与仓库」学习</div>`);
    }
    go.textContent = `确认这 ${hits} 张`;
    go.disabled = hits === 0;
  };
  m.querySelector("#bcTier").onchange = refresh;
  refresh();
  go.onclick = async () => {
    if (!hits) return;
    if (m.querySelector("#bcTier").value === "0" && !confirm("全部确认会把低置信度卡也放行，确定？")) return;
    const r = await api("/api/interfaces/confirm-by-confidence", { projectId: state.projectId, minConfidence: Number(m.querySelector("#bcTier").value) });
    if (!r.ok) return toast(r.error, true);
    m.remove();
    toast(`已确认草稿 ${r.confirmed} 张（置信度 ≥${Math.round(r.minConfidence * 100)}%）`);
    route();
  };
}
async function confirmAll() {
  const ifcs = (await api("/api/interfaces?projectId=" + state.projectId)).interfaces || [];
  const ids = ifcs.filter(i => i.status === "draft").map(i => i.id);
  const dobs = (await api("/api/db-objects?projectId=" + state.projectId)).dbObjects || [];
  const dids = dobs.filter(d => d.status === "draft").map(d => d.id);
  if (!ids.length && !dids.length) return toast("没有草稿");
  if (!confirm(`将把本项目 ${ids.length} 张接口卡 + ${dids.length} 张 SQL 卡草稿全部置为已确认（含低置信度卡），确定？`)) return;
  let done = 0;
  if (ids.length) done += (await api("/api/interfaces/confirm", { projectId: state.projectId, ids })).confirmed || 0;
  if (dids.length) done += (await api("/api/db-objects/confirm", { projectId: state.projectId, ids: dids })).confirmed || 0;
  toast("已确认 " + done + " 条");
  route();
}
function ifcFormHtml(i) {
  i = i || {};
  const methods = ["GET", "POST", "PUT", "DELETE", "PATCH"];
  let schema = i.params_schema || "";
  if (typeof schema === "string" && schema) { try { schema = JSON.stringify(JSON.parse(schema), null, 2); } catch (e) {} }
  return `
  <div class="form-item"><label>HTTP 方法</label><select id="niMethod">${methods.map(m => `<option ${i.http_method === m ? "selected" : ""}>${m}</option>`).join("")}</select></div>
  <div class="form-item"><label>路径（支持 {id} 占位符）</label><input id="niPath" value="${esc(i.path || "/")}" placeholder="/api/orders/{id}"></div>
  <div class="form-item"><label>接口名称</label><input id="niName" value="${esc(i.name || "")}" placeholder="例：订单详情查询"></div>
  <div class="form-item"><label>参数定义 JSON（可留空后补；in 取 path/query/body）</label>
    <textarea id="niSchema" placeholder='{"id":{"in":"path","required":true},"size":{"in":"query","type":"int"}}'>${esc(schema)}</textarea></div>`;
}
function newIfc() {
  const m = modal(`<h3>＋ 新建接口卡（手工补学习漏掉的端点）</h3>${ifcFormHtml()}
    <div class="muted">手工卡直接为「已确认」，测试台立即可选；人工编辑后重学不覆盖。</div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="niGo">创建</button></div>`);
  m.querySelector("#niGo").onclick = async () => {
    let schema = null;
    const s = m.querySelector("#niSchema").value.trim();
    if (s) { try { schema = JSON.parse(s); } catch (e) { return toast("参数定义不是合法 JSON", true); } }
    const r = await api("/api/interfaces", { projectId: state.projectId, httpMethod: m.querySelector("#niMethod").value, path: m.querySelector("#niPath").value, name: m.querySelector("#niName").value, paramsSchema: schema });
    if (r.ok) { m.remove(); toast("接口卡已创建（已确认状态）"); route(); } else toast(r.error, true);
  };
}
async function editIfc(id) {
  const { interface: i } = await api("/api/interfaces/" + id);
  const m = modal(`<h3>编辑接口卡 #${id} <span class="muted">${esc(i.http_method)} ${esc(i.path)}</span></h3>${ifcFormHtml(i)}
    <div class="actions" style="justify-content:space-between">
      <button class="danger" id="niDel">删除此卡</button>
      <span><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="niGo">保存</button></span></div>`);
  m.querySelector("#niDel").onclick = async () => {
    if (!confirm("删除接口卡及其关联用例？执行历史保留。")) return;
    const r = await api("/api/interfaces/" + id, null, "DELETE");
    if (r.ok) { m.remove(); toast("已删除"); route(); } else toast(r.error, true);
  };
  m.querySelector("#niGo").onclick = async () => {
    const body = { httpMethod: m.querySelector("#niMethod").value, path: m.querySelector("#niPath").value, name: m.querySelector("#niName").value };
    const s = m.querySelector("#niSchema").value.trim();
    if (s) { try { body.paramsSchema = JSON.parse(s); } catch (e) { return toast("参数定义不是合法 JSON", true); } }
    const r = await api("/api/interfaces/" + id, body, "PATCH");
    if (r.ok) { m.remove(); toast("已保存（人工编辑，重学不覆盖）"); route(); } else toast(r.error, true);
  };
}
async function exportPack() {
  busyStart();                        // 导出走裸 fetch（要拿 blob），不挂进在飞计数的话【导出知识包】点了就是干等
  let r;
  try { r = await fetch("/api/knowledge/export?projectId=" + state.projectId); }
  catch (e) { busyEnd(false); throw e; }
  if (!r.ok) {
    const j = await r.json().catch(() => ({}));
    busyEnd(false);
    return toast(j.error || ("导出失败（HTTP " + r.status + "）"), true);
  }
  const b = await r.blob();
  busyEnd(true);
  const a = document.createElement("a");
  a.href = URL.createObjectURL(b);
  a.download = "preflight-知识包-" + state.projectId + ".zip";
  a.click();
  URL.revokeObjectURL(a.href);
  toast("知识包已下载");
}
function fileImport(url) {
  const m = modal(`<h3>导入存量资产</h3><input type="file" id="impF" accept=".json,.har" style="width:100%">
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="impGo">导入</button></div>`);
  m.querySelector("#impGo").onclick = async () => {
    const f = m.querySelector("#impF").files[0];
    if (!f) return toast("请选择文件", true);
    const content = JSON.parse(await f.text());
    const r = await api(url, { projectId: state.projectId, content });
    toast(r.ok ? `导入成功：${r.created} 条` : r.error, !r.ok);
    m.remove(); route();
  };
}
const importOpenApi = () => fileImport("/api/import/openapi");
const importPostman = () => fileImport("/api/import/postman");
const importHar = () => fileImport("/api/import/har");
function importPack() {
  const m = modal(`<h3>导入知识包</h3>
    <input type="file" id="impFile" accept=".zip" style="width:100%">
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="impGo">导入</button></div>`);
  m.querySelector("#impGo").onclick = async () => {
    const f = m.querySelector("#impFile").files[0];
    if (!f) return toast("请选择 zip 文件", true);
    const fd = new FormData(); fd.append("file", f);
    const r = await api("/api/knowledge/import", fd, null, { progress: true });
    toast(r.ok ? `导入成功：${r.projectName}（接口 ${r.interfaces} · SQL ${r.dbObjects} · 用例 ${r.testCases} · 仓库 ${r.repos} · 环境 ${r.environments} · 登录流 ${r.loginFlows} · 绑定 ${r.fileBindings} · 服务字典 ${r.dispatchTargets || 0}）${r.notice ? " · " + r.notice : ""}` : r.error, !r.ok);
    m.remove(); route();
  };
}

/* ---------- 起飞检查单（04-补齐 §4.1） ---------- */
views.releases = async () => {
  await loadProjects();
  const releases = (await api("/api/releases?projectId=" + state.projectId)).releases || [];
  // 范围起/止是整页下拉选提交，不能跟着改动流的分页默认值缩水成 20 条（用户手选范围的能力要保住）
  const commits = (await api("/api/commits?projectId=" + state.projectId + "&size=100")).commits || [];
  return `
  <div class="row mb">${projectSelect()}<span class="muted">发布范围 → 逐条改动测试证据 → 未测项签字 → 导出"敢不敢起飞"结论单</span></div>
  <div class="muted mb" style="font-size:12px">这张按<b>改动文件</b>圈范围、条目里没有执行留痕；要按<b>受影响目标</b>逐行看"测过没有、证据是哪一次执行"，用
    <button class="small ghost" onclick="navTo('#/reports')">📑 提测结论单</button>（两份共用同一份归因口径，互不改动对方的数据）</div>
  <div class="card"><h3>📋 生成检查单</h3>
    <div class="row">
      <div><label class="f">标题</label><input id="rlTitle" value="发布检查单" style="width:180px"></div>
      <div><label class="f">范围起</label><select id="rlFrom" style="width:280px">${commits.map(c => `<option value="${c.id}">${esc(c.revision.slice(0, 8))} ${esc(c.message)}</option>`).join("")}</select></div>
      <div><label class="f">范围止</label><select id="rlTo" style="width:280px">${commits.map((c, i) => `<option value="${c.id}" ${i === 0 ? "selected" : ""}>${esc(c.revision.slice(0, 8))} ${esc(c.message)}</option>`).join("")}</select></div>
      <div style="align-self:flex-end"><button onclick="createRelease()">生成</button></div>
    </div></div>
  <div class="card"><h3>🗂 已有检查单</h3>
    ${releases.length ? `<table><tr><th>标题</th><th>范围</th><th>创建人</th><th>时间</th><th>操作</th></tr>
      ${releases.map(r => `<tr><td>${esc(r.title)}</td><td class="muted">#${r.from_revision} → #${r.to_revision}</td>
        <td>${esc(r.created_by || "")}</td><td class="muted">${esc(fmtTime(r.created_at))}</td>
        <td><button class="small" onclick="showRelease(${r.id})">查看/签字</button>
            <a href="/api/releases/${r.id}/export" target="_blank" onclick="exportHint()"><button class="small ghost">导出 HTML</button></a></td></tr>`).join("")}</table>`
      : `<div class="empty">暂无检查单</div>`}</div>`;
};
async function createRelease() {
  const from = Number($("#rlFrom").value), to = Number($("#rlTo").value);
  const r = await api("/api/releases", { projectId: state.projectId, title: $("#rlTitle").value, fromCommitId: from, toCommitId: to }, null, { progress: true });
  toast(r.ok ? `已生成，${r.items} 条改动` : r.error, !r.ok);
  route();
}
async function showRelease(id) {
  const d = await api("/api/releases/" + id);
  const isAdmin = currentUser && currentUser.role === "admin";
  const m = modal(`<h3>🛫 ${esc(d.release.title)} — 结论：${d.verdict === "READY" ? badge("可以起飞", "green") : badge("不建议起飞", "red")}</h3>
    <div class="muted mb">未处理改动 ${d.unhandled} 条 · 未闭环缺陷涉及 ${d.openFindings} 条</div>
    <div style="max-height:300px;overflow:auto"><table><tr><th>提交</th><th>文件</th><th>风险</th><th>状态</th><th>缺陷</th><th>签字</th></tr>
    ${d.items.map(it => `<tr><td class="wrap">${esc(String(it.commitMessage).slice(0, 30))}</td><td class="wrap"><code>${esc(it.path)}</code></td>
      <td>${riskBadge(it.risk || "low")}</td><td>${statusBadge(it.testStatus)}</td><td>${it.openFindings || 0}</td>
      <td>${it.testStatus === "tested" || it.testStatus === "skipped" ? "—" : `<button class="small ghost" onclick="signItem(${id}, ${it.fileChangeId}, 'passed')">判通过</button>
          <button class="small ghost" onclick="signItem(${id}, ${it.fileChangeId}, 'skipped')">跳过</button>`}</td></tr>`).join("")}</table></div>
    ${d.signoffs.length ? `<h3 style="font-size:13px" class="mt">签字记录</h3>` + d.signoffs.map(s => `<div class="muted">${esc(s.by)} · ${esc(s.decision)} · ${esc(s.reason || "")} · ${esc(fmtTime(s.at))}</div>`).join("") : ""}
    <div class="actions"><a href="/api/releases/${id}/export" target="_blank" onclick="exportHint()"><button class="ghost">导出 HTML 结论单</button></a>
    <button onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
}
async function signItem(releaseId, fileChangeId, decision) {
  let reason = "";
  if (decision === "skipped") {
    reason = prompt("跳过理由（留审计）：");
    if (!reason) return;
  }
  const r = await api(`/api/releases/${releaseId}/sign`, { fileChangeId, decision, reason });
  toast(r.ok ? "已签字" : r.error, !r.ok);
  const mask = document.querySelector(".modal-mask");
  if (mask) mask.remove();
  showRelease(releaseId);
}
/* ---------- 提测结论单（第卅三轮第一批 + 第卅五轮第二批） ---------- */
/* 与「起飞检查单」的分工：那张按<b>文件</b>圈范围、条目里没有执行证据；这张按<b>受影响目标</b>出行，
   每行带回 run_id 能点回留痕。两者共用 release_checklists，靠 kind 列分流，老页面一点没动。
   第二批补的是「出参证据 + 逐行签字」：出单之前每行的出入参变动读取期现算，出单那刻固化成留痕。
   这一页仍然没有「可以提测」的绿色大字——定性得由人逐行看完再【出单】，系统不替人点头。 */
const RP_GAP_CN = { no_case: "一条用例都没有", no_run: "从没执行过", direct_run: "只有直测留痕", coarse_attribution: "归因宽（整文件/调用链）",
  out_param_changed: "出参有变动", out_param_unanchored: "出参没核对上" };
const RP_NOTE_CN = { run_direct: "那次执行是直测（没挂用例）", run_case_missing: "那次执行挂的用例已被删", run_out_of_scope: "那次执行不指向本区间的提交" };
const RP_STATUS_CN = { draft: "草稿", issued: "已出单", superseded: "已被顶替" };
const RP_STATUS_COLOR = { draft: "amber", issued: "green", superseded: "gray" };
function rpFilter() { return state.rpFilter || (state.rpFilter = { page: 1, basis: "", evidence: "", q: "" }); }

views.reports = async () => {
  await loadProjects();
  const st = new URLSearchParams(location.hash.split("?")[1] || "");
  if (Number(st.get("id"))) return rpDetail(Number(st.get("id")));
  const reports = (await api("/api/reports?projectId=" + state.projectId)).reports || [];
  const commits = (await api("/api/commits?projectId=" + state.projectId + "&size=100")).commits || [];
  const envs = (await api("/api/environments?projectId=" + state.projectId)).environments || [];
  // 【↻ 再出一份】带过来的上一版参数：只灌这一次，刷完就清——否则下次进来还偷偷盖掉手选的区间
  const seed = state.rpSeed && state.rpSeed.projectId === state.projectId ? state.rpSeed : null;
  state.rpSeed = null;
  return `
  <div class="row mb">${projectSelect()}
    <span class="muted">改动区间 → 受影响目标清单 → 每个目标的执行留痕 → 缺口照实列出。只读本系统自己的元数据，不碰目标库。</span></div>
  <div class="card"><h3>📑 生成结论单（草稿）</h3>
    <div class="row">
      <div><label class="f">标题</label><input id="rpTitle" value="${esc(seed ? seed.title + "（再出一份）" : "提测结论单")}" style="min-width:180px"
        title="给自己看的名字，出了单就不改了；要改结论请再出一份新的"></div>
      <div><label class="f">范围起（提交）</label><select id="rpFrom" style="min-width:280px">${rpCommitOptions(commits, false, seed && seed.from)}</select></div>
      <div><label class="f">范围止（提交）</label><select id="rpTo" style="min-width:280px">${rpCommitOptions(commits, true, seed && seed.to)}</select></div>
      <div><label class="f">环境（可留空）</label><select id="rpEnv" style="min-width:200px">
        <option value="">不核对库结构</option>${envs.map(e => `<option value="${e.id}"${seed ? String(seed.env) === String(e.id) ? " selected" : "" : ""}>${esc(e.name)}</option>`).join("")}</select></div>
      ${seed ? `<input type="hidden" id="rpSupersedes" value="${seed.supersedes}">` : ""}
      <div style="align-self:flex-end"><button onclick="createReport()"
        title="把这一版结论固化下来：目标行、指纹、缺口都存成当时的值，之后重学或再执行都不会改动这一版">生成草稿</button></div>
    </div>
    <div class="muted mt" style="font-size:12px">环境留空＝这一版不核对目标库结构，结论单的「指纹」会恒显写明缺这一项。
      区间内一条提交都没归因到目标时不会出单，只会告诉你为什么出不了——空报告比没报告更坏。</div></div>
  <div class="card"><h3>🗂 已有结论单</h3>
    ${reports.length ? `<div class="tbl-scroll"><table><tr><th>标题</th><th>状态</th><th>区间</th><th>目标/留痕/缺口</th><th>环境</th><th>生成人</th><th>生成时间</th><th>操作</th></tr>
      ${reports.map(r => rpListRow(r)).join("")}</table></div>`
      : `<div class="empty">还没有结论单。上面选一个提交区间点【生成草稿】——第一份要圈的是「这次改动到底影响哪些接口和 SQL 卡」。</div>`}</div>`;
};

function rpCommitOptions(commits, latestFirst, wantId) {
  const list = commits || [];
  if (!list.length) return "<option value=''>（本项目还没有提交：先去「项目与仓库」学习）</option>";
  return list.map((c, i) => {
    const want = wantId ? String(c.id) === String(wantId) : (latestFirst && i === 0);
    return `<option value="${c.id}"${want ? " selected" : ""}>${esc(String(c.revision).slice(0, 8))} ${esc(String(c.message || "").slice(0, 40))}</option>`;
  }).join("");
}

function rpListRow(r) {
  let s = {};
  try { s = JSON.parse(r.snapshot_json || "{}"); } catch (e) { }
  return `<tr><td class="wrap"><b>${esc(r.title)}</b>${r.supersedes_id ? ` <span class="muted">（顶替 #${r.supersedes_id}）</span>` : ""}</td>
    <td>${badge(RP_STATUS_CN[r.status] || r.status, RP_STATUS_COLOR[r.status] || "gray")}</td>
    <td class="muted">#${esc(r.from_revision)} → #${esc(r.to_revision)}${s.commitCount ? ` · ${s.commitCount} 个提交` : ""}</td>
    <td><b>${s.targets ?? "—"}</b> 个目标 · <span style="color:var(--green)">有留痕 ${s.with_evidence ?? "?"}</span>
      / <span style="color:var(--red)">缺留痕 ${s.without_evidence ?? "?"}</span> · 缺口 ${s.gaps ?? "?"} 处
      ${s.out_param_changed ? ` · <span style="color:var(--red)">出参变动 ${s.out_param_changed}</span>` : ""}</td>
    <td>${esc(s.env || "未核对")}</td>
    <td>${esc(r.created_by || "")}</td>
    <td class="muted">${esc(fmtTime(r.created_at))}</td>
    <td style="white-space:nowrap"><button class="small" onclick="navTo('#/reports?id=${r.id}')">查看</button>
      ${r.status === "draft" ? `<button class="small" onclick="rpIssue(${r.id})">📤 出单</button>` : ""}
      <a href="/api/reports/${r.id}/export" target="_blank" onclick="exportHint()"><button class="small ghost">HTML</button></a>
      <a href="/api/reports/${r.id}/json" target="_blank" onclick="exportHint()"><button class="small ghost">JSON</button></a></td></tr>`;
}

async function createReport() {
  const from = Number(document.getElementById("rpFrom").value);
  const to = Number(document.getElementById("rpTo").value);
  if (!from || !to) return toast("这个项目还没有提交可选：先去「项目与仓库」学习", true);
  const env = document.getElementById("rpEnv").value;
  const sup = document.getElementById("rpSupersedes");
  const r = await api("/api/reports", {
    projectId: state.projectId, title: document.getElementById("rpTitle").value,
    fromCommitId: from, toCommitId: to, envId: env ? Number(env) : null,
    supersedesId: sup && sup.value ? Number(sup.value) : null
  }, null, { progress: true });
  if (!r.ok) return toast(r.error, true);
  toast(`已生成草稿 #${r.id}：${r.rows} 个受影响目标，${r.gaps} 处缺口。看完逐行确认再点【出单】`);
  // 生成的就是这一份，直接跳进去——留在列表还得自己在里面认出刚建的那条
  if (r.id) navTo("#/reports?id=" + r.id); else route();
}

async function rpIssue(id) {
  if (!confirm("出单＝定版：这份结论单之后不能再改，要改结论只能再出一份新的。确认出单？")) return;
  const r = await api(`/api/reports/${id}/issue`, {}, null, { progress: true });
  if (!r.ok) return rpIssueBlocked(r);
  toast("已出单，这一版定版不可改了（出入参变动也随这一刻固化成留痕）");
  route();
}

/** 出单被拦不能只弹一句话：把拦住的行点名列出来，并给一个跳到缺口区的落点 */
function rpIssueBlocked(r) {
  const rows = r.rows || [];
  modal(`<h3>还不能出单：${r.uncovered ?? rows.length} 个目标没确认</h3>
    <div class="muted">出单＝定版上线依据。下面这些行带着缺口却没签字，<b>系统不替你点头</b>。
      逐行点【已核对】或【跳过（写理由）】后再出单。</div>
    <div class="mt" style="max-height:300px;overflow:auto"><table><tr><th>目标</th><th>缺口</th></tr>
    ${rows.map(x => `<tr><td class="wrap"><code>${esc(x.unit_name || (x.unit_kind + "#" + x.unit_id))}</code></td>
      <td>${esc(RP_GAP_CN[x.gap_type] || x.gap_type)}</td></tr>`).join("")}</table></div>
    ${r.uncovered > rows.length ? `<div class="muted mt" style="font-size:12px">只列了前 ${rows.length} 个，其余在下面的缺口区</div>` : ""}
    <div class="actions"><button onclick="this.closest('.modal-mask').remove();document.getElementById('rpGapCard')?.scrollIntoView({behavior:'smooth'})">去逐行确认</button></div>`);
}

/** 再出一份：把这一版的区间与环境原样带过来当新草稿（旧版一个字都不动） */
async function rpRegenerate(id) {
  const d = await api("/api/reports/" + id);
  if (!d.ok) return toast(d.error, true);
  state.rpSeed = { projectId: d.report.project_id, title: d.report.title, from: Number(d.report.from_revision), to: Number(d.report.to_revision), env: d.report.env_id, supersedes: d.report.id };
  navTo("#/reports");
  toast("已带过来上一版的区间与环境，回来点【生成草稿】就是新的一份（旧的那份不会被动）");
}

async function rpDetail(id) {
  if (window.__rpId !== id) { window.__rpId = id; state.rpFilter = null; }   // 换一份结论单，上一份的过滤条件不该跟过来
  const d = await api("/api/reports/" + id);
  if (!d.ok) return `<div class="card result-fail">加载失败：${esc(d.error || "服务无响应")}</div>`;
  const r = d.report, t = d.totals || {}, fp = d.fingerprint || {}, scope = d.scope || {};
  window.__rpGapsBy = {};
  (d.gaps || []).forEach(g => {
    const k = g.unit_kind + ":" + g.unit_id;
    (window.__rpGapsBy[k] = window.__rpGapsBy[k] || []).push(g);
  });
  // 每行最后一次签字（详情页整份都在手上，缺口区按它决定"还要不要点确认"）
  window.__rpSignBy = {};
  (d.rows || []).forEach(x => { if (x.sign_decision) window.__rpSignBy[x.unit_kind + ":" + x.unit_id] = x; });
  window.__rpDraft = r.status === "draft";
  window.__rpBasis = d.basis_labels || {};   // 档位话术服务端一份，下拉与行内色标都读它
  window.__rpCurId = id;
  window.__rpEnvs = (await api("/api/environments?projectId=" + r.project_id)).environments || [];
  const f = rpFilter();
  const gapTypes = {};
  (d.gaps || []).forEach(g => { gapTypes[g.gap_type] = (gapTypes[g.gap_type] || 0) + 1; });
  return `
  <div class="row mb" style="gap:8px;align-items:center">
    <button class="ghost small" onclick="navTo('#/reports')">← 结论单列表</button>
    <b style="font-size:15px">${esc(r.title)}</b> ${badge(RP_STATUS_CN[r.status] || r.status, RP_STATUS_COLOR[r.status] || "gray")}
    <span class="muted">范围 #${esc(r.from_revision)} → #${esc(r.to_revision)}（${scope.commitCount ?? "?"} 个提交）· 生成人 ${esc(r.created_by || "")} · ${esc(fmtTime(r.created_at))}
      ${r.issued_at ? " · 出单 " + esc(fmtTime(r.issued_at)) : ""}</span>
    <span style="flex:1"></span>
    ${r.status === "draft" ? `<button onclick="rpIssue(${id})">📤 出单（定版，之后不可改）</button>` : ""}
    <button class="ghost small" onclick="rpRegenerate(${id})" title="旧版一个字都不动，新版是另一份草稿">↻ 再出一份</button>
    <a href="/api/reports/${id}/export" target="_blank" onclick="exportHint()"><button class="ghost small">导出 HTML</button></a>
    <a href="/api/reports/${id}/json" target="_blank" onclick="exportHint()"><button class="ghost small">导出 JSON</button></a>
  </div>
  <div class="card"><h3>🧭 这一版凭什么说它说的是同一份东西（指纹）</h3>
    <table>${rpFingerprintRows(fp)}</table>
    <div class="muted mt" style="font-size:12px">指纹只读本系统自己的元数据。缺哪项就照实写缺哪项——「没核对」和「核对过且一致」是两回事。</div></div>
  <div class="card"><h3>📊 结论</h3>
    <div class="row" style="gap:18px;flex-wrap:wrap">
      <div>受影响目标 <b style="font-size:18px">${t.targets}</b> 个</div>
      <div style="color:var(--green)">有执行留痕 <b style="font-size:18px">${t.with_evidence}</b> 个</div>
      <div style="color:var(--red)">缺留痕 <b style="font-size:18px">${t.without_evidence}</b> 个</div>
      <div title="归因宽＝没按改动行命中具体语句：只能到整文件，或只从调用链圈到">归因宽（整文件 / 调用链）
        <b style="font-size:18px">${t.coarse_basis}</b> 个${t.chain_basis ? `（其中按调用链 ${t.chain_basis} 个）` : ""}</div>
      <div style="color:var(--red)">出参有变动 <b style="font-size:18px">${t.out_param_changed}</b> 个</div>
      <div>缺口 <b style="font-size:18px">${t.gaps}</b> 处：${Object.keys(gapTypes).length
        ? Object.entries(gapTypes).map(([k, n]) => `${RP_GAP_CN[k] || esc(k)} ${n}`).join(" · ")
        : "无"}</div>
      ${window.__rpDraft ? `<div style="color:${t.gaps_uncovered ? "var(--red)" : "var(--green)"}">还差逐行确认 <b style="font-size:18px">${t.gaps_uncovered}</b> / ${t.rows_to_sign} 行</div>` : ""}
    </div>
    ${d.out_params_partial ? `<div class="result-fail mt">出参这一列<b>没核对</b>：区间提交数超过扫描上限，
      把区间拆小再出一份才有。这一列的空白是"没看"，不是"没变"。</div>` : ""}
    <div class="muted mt" style="font-size:12px">这一页<b>不做自动定性</b>：只把「改了什么、出入参动没动、测没测过、证据在哪」摆出来，
      通过/失败/无关的三分类还没做。所以这里没有「可以提测」的大字——出单前要把每个有缺口的行点一次【已核对】或【跳过】，
      <b>系统不替你点头</b>；出单之后这一版就定死了（签字也只在草稿期有效）。</div></div>
  <div class="card"><h3>🧾 逐目标证据</h3>
    <div class="row" style="gap:10px;align-items:flex-end">
      <div><label class="f" style="margin:0">归因粒度</label><select data-rpf="basis">
        <option value="">全部</option>${RP_BASIS_ORDER.map(k => `<option value="${esc(k)}"${f.basis === k ? " selected" : ""}>${
        esc((window.__rpBasis || {})[k] || k)}</option>`).join("")}</select></div>
      <div><label class="f" style="margin:0">有没有留痕</label><select data-rpf="evidence">
        <option value="">全部</option><option value="with"${f.evidence === "with" ? " selected" : ""}>有执行记录</option>
        <option value="without"${f.evidence === "without" ? " selected" : ""}>没有执行记录</option></select></div>
      <div><label class="f" style="margin:0">关键字</label><input id="rpQ" style="min-width:240px"
        placeholder="目标名 / 用例名，回车搜索" value="${esc(f.q)}"></div>
      <button class="small" onclick="rpSearch()">搜索</button>
    </div>
    <div id="rpRowsBox"><div class="empty">加载中…</div></div>
  </div>
  <div class="card" id="rpGapCard"><h3>⚠️ 风险声明（证据不足处）${
    window.__rpDraft ? " · 每行确认一次才能出单" : " · 这一版已定版，签字记录见下"}</h3>
    ${rpGapList(d)}</div>`;
}

function rpFingerprintRows(fp) {
  const code = fp.code || {};
  const cut = s => String(s == null ? "—" : s).slice(0, 10);
  const part = (label, html, missing) => `<tr><td style="white-space:nowrap"><b>${label}</b></td><td class="wrap">${
    missing ? `<span style="color:var(--red)">缺：${esc(missing)}</span>` : html}</td></tr>`;
  const learned = (code.learned || []).map(esc).join("<br>");
  const s = fp.schema || {};
  const ch = fp.channel || {};
  return part("代码版本", `${esc(cut(code.from))} → ${esc(cut(code.to))} · ${code.commits ?? "?"} 个提交 · 指纹 <code>${esc(code.digest || "—")}</code>${
      learned ? `<div class="muted" style="font-size:12px">学习时的仓库版本：<br>${learned}</div>` : ""}`,
      code.digest ? null : fp.code_missing)
    + part("库结构版本", `环境 ${esc(s.env_name)}（${esc(s.db_type || "库型未记录")}）· SQL 目标 ${s.sql_targets ?? 0} 个（其中 ${s.sql_targets_with_tables ?? 0} 个记了涉及的表）· 列元数据 ${s.meta_rows ?? 0} 张表${
        s.meta_pulled_at ? `（拉取于 ${esc(fmtTime(s.meta_pulled_at))}）` : ""}`,
      fp.schema_missing)
    + part("通道版本", `接口卡 ${ch.interfaces ?? 0} 张 · 参数结构 ${ch.distinct_schema_hash ?? 0} 种 · 指纹 <code>${esc(ch.digest || "—")}</code>`,
      fp.channel_missing)
    + part("人", esc(fp.by || "—") + ` · 口径 ${esc(fp.tool || "report-v1")}`, fp.by ? null : "没记录到操作人");
}

/** 缺口按「目标」成组：一个目标常常同时挂三条，判断只有一遍，所以签字也按行盖一次 */
function rpGapList(d) {
  const gaps = d.gaps || [];
  if (!gaps.length) return `<div class="empty">这个区间里每个目标都有执行留痕，也没有粗归因，出参也没动。</div>`;
  const groups = new Map();
  gaps.forEach(g => {
    const k = g.unit_kind + ":" + g.unit_id;
    if (!groups.has(k)) groups.set(k, { name: g.unit_name, kind: g.unit_kind, id: g.unit_id, items: [] });
    groups.get(k).items.push(g);
  });
  const shown = [...groups.values()].slice(0, 60);
  return `<div class="muted mb" style="font-size:12px">共 ${gaps.length} 处、${groups.size} 个目标${
      groups.size > 60 ? "，此处只列前 60 个目标，完整清单在导出的 HTML/JSON 里" : ""}。每条都是「这一行的证据为什么不足」，不是系统猜的结论。${
      d.report.status === "draft" ? "看完一个目标点一次【已核对】；这次确实不用测就点【跳过】并写理由——理由会跟着结论单一起导出。" : ""}</div>
    <div style="max-height:420px;overflow:auto"><table><tr><th>目标</th><th>缺口</th><th>说明</th>${
      d.report.status === "draft" ? "<th>核对</th>" : "<th>签字</th>"}</tr>
    ${shown.map(g => {
      const s = (window.__rpSignBy || {})[g.kind + ":" + g.id];
      const status = s ? `${badge(s.sign_decision === "skipped" ? "已跳过" : "已核对", s.sign_decision === "skipped" ? "blue" : "green")}
          <div class="muted" style="font-size:12px">${esc(s.sign_by || "")} ${esc(s.sign_at || "")}${
            s.sign_reason ? "｜" + esc(s.sign_reason) : ""}</div>`
        : `<span style="color:var(--red);font-size:12px">未确认</span>`;
      return `<tr><td class="wrap"><code>${esc(g.name || (g.kind + "#" + g.id))}</code></td>
        <td>${g.items.map(x => badge(RP_GAP_CN[x.gap_type] || x.gap_type,
            x.gap_type === "coarse_attribution" ? "blue" : x.gap_type === "out_param_changed" ? "red" : "amber")).join(" ")}</td>
        <td class="wrap muted">${g.items.map(x => `· ${esc(x.detail || "")}`).join("<br>")}</td>
        <td style="white-space:nowrap">${status}${d.report.status === "draft" ? `
          <div class="mt" style="display:flex;gap:6px">
            <button class="small${s ? " ghost" : ""}" onclick="rpSign('${g.kind}',${g.id},'confirmed')"
              title="这一行的缺口我都看过了，写进结论单的签字记录">已核对</button>
            <button class="small ghost" onclick="rpSign('${g.kind}',${g.id},'skipped')"
              title="这一行本次不测：必须写理由，理由随结论单一起导出">跳过</button></div>` : ""}</td></tr>`;
    }).join("")}</table></div>`;
}

/** 逐行签字（只认草稿）：跳过必须先写理由，理由不许藏在提示里 */
function rpSign(kind, id, decision) {
  if (decision === "skipped") {
    modal(`<h3>跳过这一行：写清理由</h3>
      <label class="f">理由（必填，会跟着结论单一起导出）</label>
      <textarea id="rpSkipWhy" rows="3" placeholder="例：这次只动了日志字段，业务侧不读它；上线后观察一天再补测"></textarea>
      <div class="muted mt" style="font-size:12px">跳过不等于没问题——它会把"这一行没测"连同你的理由一起留在定版的结论单上。</div>
      <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button>
        <button onclick="rpSignDo(${Number(id)},'skipped','${kind}')">确认跳过</button></div>`);
    const ta = document.getElementById("rpSkipWhy");
    if (ta) { ta.focus(); }
    return;
  }
  rpSignDo(Number(id), "confirmed", kind);
}

async function rpSignDo(unitId, decision, unitKind) {
  const reason = (document.getElementById("rpSkipWhy") || {}).value || "";
  if (decision === "skipped" && !reason.trim()) return toast("跳过必须写理由：这条理由会跟着结论单一起导出", true);
  const mask = document.querySelector(".modal-mask");
  if (mask) mask.remove();
  const r = await api(`/api/reports/${window.__rpCurId}/sign`, { unitKind, unitId, decision, reason: reason.trim() });
  if (!r.ok) return toast(r.error, true);
  toast(decision === "skipped" ? "已跳过这一行（理由已留痕）" : "已核对这一行");
  route();
}

function rpRowCells(r, i) {
  const gaps = (window.__rpGapsBy || {})[r.unit_kind + ":" + r.unit_id] || [];
  const envName = id => {
    const e = (window.__rpEnvs || []).find(x => String(x.id) === String(id));
    return e ? e.name : (id == null ? "—" : "环境 #" + id);
  };
  return `<tr>
    <td>${badge(r.unit_kind === "interface" ? "接口" : "SQL", r.unit_kind === "interface" ? "indigo" : "amber")}</td>
    <td class="wrap"><b>${esc(r.unit_name || "（目标已删除 #" + r.unit_id + "）")}</b></td>
    <td>${rpBasisCell(r.basis)}</td>
    <td class="muted">${esc(r.commit_ids || "").split(",").filter(Boolean).map(x => "#" + x).join(" ")}</td>
    <td class="wrap">${r.case_name ? esc(r.case_name) : `<span class="muted">${gaps.some(g => g.gap_type === "no_case") ? "一条用例都没有" : "那次执行没挂用例"}</span>`}</td>
    <td>${r.run_id == null ? `<span style="color:var(--red)">无留痕</span>`
      : `<button class="small ghost" onclick="openRunDetail(${r.run_id})" title="点开看当时的请求与响应快照">#${r.run_id}</button>`}</td>
    <td>${r.run_id == null ? "" : statusBadge(r.run_status)}</td>
    <td class="muted">${esc(envName(r.run_env_id))}</td>
    <td class="muted">${r.run_at ? esc(fmtTime(r.run_at)) : "—"}</td>
    <td class="wrap">${rpOutCell(r)}</td>
    <td>${rpSignCell(r, gaps.length)}</td>
    <td class="wrap">${r.note ? `<div class="muted" style="font-size:12px">${esc(RP_NOTE_CN[r.note] || r.note)}</div>` : ""}
      ${gaps.map(g => `<span class="muted" style="font-size:12px">· ${esc(RP_GAP_CN[g.gap_type] || g.gap_type)}</span>`).join("<br>")}</td>
    <td style="white-space:nowrap"><button class="small ghost" data-rp="${i}" onclick="rpOpenTarget(this.dataset.rp)"
      title="带这个目标进对应测试台（不挂用例身份，测哪条由你挑）">${r.run_id == null ? "去测它" : "打开目标"}</button></td></tr>`;
}

const RP_CHG_CN = { added: "新增", removed: "删除", modified: "就地改" };
/** 归因档位的固定顺序（只列档位标识，中文话术一律读服务端 basis_labels） */
const RP_BASIS_ORDER = ["line", "file", "chain"];
/**
 * 归因档位一格：话术来自服务端 basis_labels（与结论单网页、过滤下拉同一份），颜色按档位分——
 * 绿色＝按改动行命中语句（精确），琥珀色＝整文件／调用链（偏宽，缺口区另有说明）。
 */
function rpBasisCell(basis) {
  const label = (window.__rpBasis || {})[basis] || basis || "未记录";
  const color = basis === "line" ? "var(--green)" : "#b45309";
  const why = basis === "chain" ? "这一行的范围是按代码可达圈出来的（改的是这张卡调用链上的服务层／DAO 文件），比实际改动宽"
    : basis === "file" ? "改动细不到语句，只能给整个文件的用例" : "改动行落在这条语句里";
  return `<span style="color:${color}" title="${esc(why)}">${esc(label)}</span>`;
}
/** 证据落在哪一层：这条语句自己 / 同文件的别的语句 / 卡上没语句体可比 */
const RP_SCOPE_CN = { statement: "写在这条语句里", file: "只在同文件其它语句里", unknown: "这条卡没有语句体可比对，按整文件算在这一行" };

/**
 * 出参一格：四种状态必须一眼分开——<b>未核对</b>（红，"没看"）、<b>本行无变动</b>（灰，看了没动）、
 * <b>这一行自己变了</b>（字段徽章 + 悬停给证据：从哪条判据认出来的、哪个文件、哪些提交）、
 * 以及"变动其实在同文件的别的语句里"（灰徽章，不冒充本行的事）。
 */
function rpOutCell(r) {
  const fs = r.out_params;
  const why = r.out_params_frozen ? "出单那刻固化的留痕" : "草稿期按盘上 diff 现算";
  if (fs == null) return `<span style="color:var(--red)" title="这一列没核对（区间超过扫描上限，或这一行没有文件锚点）：空白是「没看」，不是「没变动」">未核对</span>`;
  // 归因宽的行（整文件 / 调用链）本来就宽（另一处缺口在说这事），行级归因只认写在自己语句里的字段
  const own = r.basis !== "line" ? fs : fs.filter(f => f.scope !== "file");
  const other = fs.length - own.length;
  if (!fs.length) return `<span class="muted" title="${why}：扫过这一行归因到的文件，出入参字段都没动">无变动</span>`;
  const chip = f => `<span class="badge b-${f.io === "out" ? "red" : "blue"}" title="${esc(f.field)} ${RP_CHG_CN[f.change] || ""}｜${
      esc(f.via || "")}｜${esc(f.file || "")}｜${RP_SCOPE_CN[f.scope] || "同文件"}｜提交 ${
      (f.commit_ids || []).map(x => "#" + x).join(" ")}">${esc(f.field)}·${RP_CHG_CN[f.change] || "?"}</span>`;
  return own.slice(0, 6).map(chip).join(" ")
    + (own.length > 6 ? ` <span class="muted" title="完整清单在导出的 HTML/JSON 里">+${own.length - 6}</span>` : "")
    + (other ? `<div><span class="muted" style="font-size:11px" title="这一行按改动行归因到具体语句，这 ${
        other} 个字段改在同一文件的别的语句里：看得见，但不算在这一行头上">${
        esc("另有 " + other + " 个字段在同文件其它语句")}</span></div>` : "")
    + `<div class="muted" style="font-size:11px">${why}</div>`;
}

/** 这一行的核对状态：只有<b>带缺口的行</b>才需要签字，没缺口的行不该被说成"未确认" */
function rpSignCell(r, gapCount) {
  if (r.sign_decision) {
    return `${badge(r.sign_decision === "skipped" ? "已跳过" : "已核对", r.sign_decision === "skipped" ? "blue" : "green")}
      <div class="muted" style="font-size:11px" title="${esc(r.sign_reason || "")}">${esc(r.sign_by || "")} ${esc(fmtTime(r.sign_at))}</div>`;
  }
  if (!gapCount) return `<span class="muted" style="font-size:12px">无缺口，不用签</span>`;
  return window.__rpDraft ? `<span style="color:var(--red);font-size:12px">未确认<br><span class="muted">出单会被拦</span></span>`
    : `<span style="color:var(--red);font-size:12px">出单时没签</span>`;
}

/** 本表第 i 行 → 对应测试台（接口卡进「接口测试台」，SQL 卡进「SQL 直测」并自动选卡） */
function rpOpenTarget(i) {
  const r = (window.__rpRows || [])[Number(i)];
  if (!r) return toast("这一行已过期，请重新翻页或搜索", true);
  openCaseTarget(r.unit_kind, r.unit_id, r.unit_name);
}

function rpRowsTable(rows) {
  window.__rpRows = rows;
  return `<div class="tbl-scroll mt"><table><tr><th>类型</th><th>受影响目标</th><th>归因</th><th>涉及提交</th><th>证据用例</th>
    <th>最近执行</th><th>结果</th><th>环境</th><th>执行时间</th><th>出参/入参变动</th><th>核对</th><th>缺口 / 备注</th><th></th></tr>
    ${rows.map((x, i) => rpRowCells(x, i)).join("")}</table></div>`;
}

async function loadRpRows() {
  const box = document.getElementById("rpRowsBox");
  if (!box) return;
  const id = Number(new URLSearchParams(location.hash.split("?")[1] || "").get("id"));
  if (!id) return;
  const f = rpFilter();
  const p = new URLSearchParams({ page: f.page, size: listSize("rp") });
  if (f.basis) p.set("basis", f.basis);
  if (f.evidence) p.set("evidence", f.evidence);
  if (f.q) p.set("q", f.q);
  const r = await api(`/api/reports/${id}/rows?` + p.toString());
  if (!r.ok) { box.innerHTML = `<div class="result-fail">明细加载失败：${esc(r.error || "服务无响应")}</div>`; return; }
  box.innerHTML = (r.total ? ` <span class="muted" style="font-size:12px">当前条件命中 ${r.total} 个目标</span>` : "")
    + (r.rows.length ? rpRowsTable(r.rows)
      : `<div class="empty">${r.total ? "这一页没有行了——翻页越界或条件收紧过" : "当前条件下没有目标。点下面的【✕ 清空条件】看全部。"}</div>`)
    + pagerBar({ key: "rp", total: r.total, page: f.page, pager: "rp" })
    + (f.basis || f.evidence || f.q ? `<div class="mt"><button class="small ghost" onclick="rpClear()">✕ 清空条件（回到全部目标）</button></div>` : "");
  // 过滤条在 #rpRowsBox 外面（同一张卡的上一行），所以要从 document 找，别在 box 里找
  document.querySelectorAll("select[data-rpf]").forEach(x => { x.onchange = () => rpSet(x.dataset.rpf, x.value); x.value = f[x.dataset.rpf] || ""; });
  const q = document.getElementById("rpQ");
  if (q && document.activeElement !== q) q.value = f.q || "";
  if (q && !q.dataset.bound) { q.dataset.bound = "1"; q.addEventListener("keydown", e => { if (e.key === "Enter") rpSearch(); }); }
  fitControls(box);
}
function rpSet(key, value) {
  const f = rpFilter();
  f[key] = value;
  if (key !== "page") f.page = 1;
  loadRpRows();
}
function rpSearch() {
  const q = document.getElementById("rpQ");
  rpSet("q", q ? q.value.trim() : "");
}
function rpClear() { state.rpFilter = { page: 1, basis: "", evidence: "", q: "" }; loadRpRows(); }
pagerDefine("rp", p => rpSet("page", p), () => rpSet("page", 1));

/* ---------- 系统设置 ---------- */
views.settings = async () => {
  await loadProjects();   // 第 47 轮：直接落到这一屏时项目表是空的——项目列只剩编号、登录线索下拉没项可选
  const [llm, envs, flows, creds, audits, settings, users, defs] = await Promise.all([
    api("/api/llm/providers"), api("/api/environments"), api("/api/login-flows"),
    api("/api/credentials"), api("/api/audit-logs?limit=30"), api("/api/settings"),
    api("/api/auth/users").catch(() => ({ users: [] })),
    api("/api/settings/defaults").catch(() => ({ defaults: {} }))]);
  const webhook = (settings.settings || []).find(s => s.key === "notify_webhook_url");
  const bizCols = (settings.settings || []).find(s => s.key === "biz_error_columns");
  window.__bizColsDefault = (defs && defs.defaults || {}).biz_error_columns || "";
  state.creds = creds.credentials || [];
  return `
  <div class="card"><h3>🤖 大模型供应商（支持多个，可按用途路由）</h3>
    <table><tr><th>名称</th><th>地址</th><th>模型</th><th>用途</th><th>状态</th><th>Key</th><th>操作</th></tr>
    ${(llm.providers || []).map(p => `<tr><td>${esc(p.name)} ${p.isDefault ? badge("默认", "indigo") : ""}</td>
      <td class="wrap muted">${esc(p.baseUrl)}</td><td>${esc(p.model)}</td><td class="muted">${esc(p.purpose || "通用")}</td>
      <td>${p.enabled ? badge("启用", "green") : badge("停用", "gray")}</td><td>${p.hasApiKey ? badge("已配置", "green") : badge("未配置", "gray")}</td>
      <td><button class="small ghost" onclick="pingLlm(${p.id})">连通</button>
        <button class="small ghost" onclick="editLlm(${p.id})">编辑</button>
        <button class="small danger" onclick="delLlm(${p.id})">删除</button></td></tr>`).join("")}</table>
    <div class="mt"><button onclick="editLlm()">＋ 添加供应商</button></div></div>
  <div class="card"><h3>🌐 测试环境</h3>
    <table><tr><th>名称</th><th>项目</th><th>Base URL</th><th>数据库</th><th>操作</th></tr>
    ${(envs.environments || []).map(e => `<tr><td>${esc(e.name)}</td><td class="muted">${esc((state.projects.find(p => p.id === e.project_id) || {}).name || e.project_id)}</td>
      <td class="wrap muted">${esc(e.base_url || "-")}</td><td class="wrap muted">${esc(e.db_url ? "已配置" : "-")}</td>
      <td><button class="small ghost" onclick="editEnv(${e.id})">编辑</button>
        <button class="small ghost" onclick="checkEnv(${e.id})">体检</button>
        <button class="small danger" onclick="delEnv(${e.id})">删除</button></td></tr>
      <tr id="envChk-${e.id}" style="display:none"><td colspan="5" class="wrap"></td></tr>`).join("")}</table>
    <div class="mt"><button onclick="editEnv()">＋ 添加环境</button></div></div>
  <div class="card"><h3>🔑 登录流（自动登录）</h3>
    <table><tr><th>名称</th><th>项目</th><th>步骤</th><th>登录凭据</th><th>操作</th></tr>
    ${(flows.loginFlows || []).map(f => `<tr><td>${esc(f.name)}</td>
      <td class="muted">${esc((state.projects.find(p => p.id === f.project_id) || {}).name || (f.project_id || "-"))}</td>
      <td class="wrap muted">${flowStepsSummary(f.steps)}</td>
      <td>${credCell(f.cred_id)}</td>
      <td><button class="small ghost" onclick="editFlow(${f.id})">编辑</button>
        <button class="small ghost" onclick="trialFlow(${f.id})">试跑</button>
        <button class="small danger" onclick="delFlow(${f.id})">删除</button></td></tr>`).join("")}</table>
    <div class="mt"><button onclick="editFlow(0, curFlwWhyProj())">＋ 添加登录流</button> <span class="muted">测试台执行前会自动运行登录流并注入会话</span></div>
    <div class="form-item mt"><label>按项目看登录线索与归因（学不到的部分系统不替你编，下面摆的是真学到的那几张卡）</label>
      <select id="flwWhyProj" onchange="loadFlwWhy()" style="width:260px">${(state.projects || []).map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select></div>
    <div id="flwWhy" class="muted" style="font-size:12px">正在读这个项目的登录线索…</div></div>
  <div class="card"><h3>🔒 凭据保险库（AES-GCM 加密）</h3>
    <table><tr><th>名称</th><th>类型</th><th>用途（按类型与命名约定现推）</th><th>操作</th></tr>
    ${(creds.credentials || []).map(c => `<tr><td>${esc(c.name)}</td><td>${esc(credKind(c.kind))}</td>
      <td>${c.purpose === "svn"
        ? badge("SVN 拉代码账号 · 仓库 " + (c.svn_repo || "?"), "amber")
        : c.purpose === "yapi"
        ? badge(credPurpose(c), "indigo")
        : badge("业务/其它 · 可作登录凭据", "green")}</td>
      <td><button class="small danger" onclick="delCred(${c.id})">删除</button></td></tr>`).join("")}</table>
    <div class="mt"><button onclick="editCred()">＋ 添加凭据</button> <span class="muted">SVN 凭据命名约定：&lt;仓库名&gt;-svn，格式 user:password；
      <b>被测系统的登录账号请另建一条（名字不带 -svn）</b>，两者用途不同、界面按这条约定区分；
      YApi 文档同步用的凭据另选 YApi 那两种类型（内容分别是 邮箱:密码 / 一整串 JWT），不会被当成登录账号</span></div></div>
  <div class="card"><h3>👥 用户管理（仅管理员）</h3>
    <table><tr><th>用户</th><th>角色</th><th>操作</th></tr>
    ${(users.users || []).map(u => `<tr><td>${esc(u.username)}</td><td>${badge(u.role, u.role === "admin" ? "indigo" : "gray")}</td>
      <td><button class="small danger" onclick="delUser(${u.id})">删除</button></td></tr>`).join("")}</table>
    <div class="row mt"><input id="newUserName" placeholder="用户名" style="width:140px">
      <input id="newUserPass" placeholder="密码(≥6位)" type="password" style="width:140px">
      <select id="newUserRole"><option value="tester">tester · 测试执行</option><option value="admin">admin · 管理员</option><option value="viewer">viewer · 只读</option></select>
      <button class="small" onclick="addUser()">添加</button></div></div>
  <div class="card"><h3>🧬 抽取质量评测（内网模型准入门槛）</h3>
    <p class="muted mb">用黄金基线对比 LLM 抽取的字段级精确率/召回率；召回 &lt;85% 时建议只做降级摘要，禁止自动草稿入库</p>
    <button onclick="runBenchmark()">▶ 运行评测（使用内置/自定义黄金基线）</button></div>
  <div class="card"><h3>🔔 通知与系统</h3>
    <div class="form-item"><label>Webhook 通知地址（钉钉/企业微信机器人）</label>
      <input id="webhookUrl" style="width:70%" value="${esc(webhook ? webhook.value : "")}" placeholder="https://oapi.dingtalk.com/robot/send?access_token=..."></div>
    <div class="row"><button onclick="saveWebhook()">保存</button><button class="ghost" onclick="testNotify()">发送测试通知</button></div>
    <div class="form-item mt"><label>业务错误码列名（判「库执行成功，但过程内部回了错误码」）</label>
      <input id="bizColsInput" style="width:70%" value="${esc(bizCols ? bizCols.value : "")}"
        placeholder="${esc(window.__bizColsDefault)}"></div>
    <div class="muted" style="font-size:12px">逗号分隔的列名：结果集或出参游标里出现同名列、且这一行的值不为 0，就在 ④ 结果区与执行记录上标橙，并附一段只读的报错定位证据。
      <b>执行状态仍算通过，通过率不动。</b>留空＝关掉这项判定（不会自己长回默认）。
      默认列名：${esc(window.__bizColsDefault)}</div>
    <div class="row mt"><button onclick="saveBizCols()">保存</button>
      <button class="ghost" onclick="fillDefaultBizCols()">填入默认列名</button></div></div>
  <div class="card"><h3>🖱 界面提示与列表条数</h3>
    <div class="muted mb">悬停说明（菜单项 / 按钮）与默认每页条数存本机库（跟着这个实例走，全组共用一套）；
      某个列表单独改过的每页条数只记在你这个浏览器里，不会牵动别人。</div>
    <div class="row" style="gap:18px;align-items:center;flex-wrap:wrap">
      <label><input type="checkbox" id="uiTipMenu" ${pref("ui_tip_menu") !== "0" ? "checked" : ""}> 鼠标悬停显示「左侧菜单」说明</label>
      <label><input type="checkbox" id="uiTipBtns" ${pref("ui_tip_buttons") !== "0" ? "checked" : ""}> 鼠标悬停显示「按钮」说明</label>
      <label class="row" style="gap:6px">列表默认每页
        <select id="uiPageSize" class="jump">${PAGE_SIZES.map(n => `<option value="${n}"${String(n) === pref("ui_page_size") ? " selected" : ""}>${n}</option>`).join("")}</select>条（最多 200）</label>
      <button onclick="saveUiPrefs()">保存</button>
      <button class="ghost" onclick="resetLocalSizes()">清掉本浏览器改过的每页条数</button>
      <button class="ghost" onclick="showTipMiss()">📋 看看哪些按钮还没配说明（${window.__pfTipMiss.size}）</button>
    </div></div>
  <div class="card"><h3>📜 审计日志（最近 ${audits.logs.length} 条）</h3>
    <div style="max-height:260px;overflow:auto"><table><tr><th>时间</th><th>动作</th><th>对象</th><th>详情</th></tr>
    ${audits.logs.map(l => `<tr><td class="muted">${esc(fmtTime(l.at))}</td><td>${esc(l.action)}</td><td class="wrap muted">${esc(l.target || "")}</td><td class="wrap muted">${esc(l.detail || "")}</td></tr>`).join("")}</table></div></div>`;
};
async function pingLlm(id) { toast("连通测试中…"); const r = await api(`/api/llm/providers/${id}/ping`, {}); toast(r.ok ? `连通成功：${r.reply}` : r.error, !r.ok); }
async function editLlm(id) {
  const llm = await api("/api/llm/providers");
  const p = (llm.providers || []).find(x => x.id === id) || {};
  const m = modal(`<h3>${id ? "编辑" : "添加"} LLM 供应商</h3>
    <div class="grid cols2">
      <div class="form-item"><label>名称</label><input id="lName" value="${esc(p.name || "")}"></div>
      <div class="form-item"><label>模型</label><input id="lModel" value="${esc(p.model || "")}"></div>
      <div class="form-item"><label>Base URL（OpenAI 兼容）</label><input id="lUrl" value="${esc(p.baseUrl || "")}" placeholder="https://api.openai.com/v1 或 http://localhost:11434/v1"></div>
      <div class="form-item"><label>用途（留空=通用；summary/testcase/extract）</label><input id="lPurpose" value="${esc(p.purpose || "")}"></div>
      <div class="form-item"><label>API Key（${p.hasApiKey ? "留空保持不变" : "可空"}）</label><input id="lKey" type="password"></div>
      <div class="form-item"><label>选项</label><label><input type="checkbox" id="lEnabled" ${p.enabled !== false ? "checked" : ""}> 启用</label>
        <label><input type="checkbox" id="lDefault" ${p.isDefault ? "checked" : ""}> 设为默认</label></div>
    </div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="lSave">保存</button></div>`);
  m.querySelector("#lSave").onclick = async () => {
    const r = await api("/api/llm/providers", { id: id || null, name: m.querySelector("#lName").value,
      baseUrl: m.querySelector("#lUrl").value, model: m.querySelector("#lModel").value,
      purpose: m.querySelector("#lPurpose").value, apiKey: m.querySelector("#lKey").value,
      enabled: m.querySelector("#lEnabled").checked, isDefault: m.querySelector("#lDefault").checked });
    if (r.ok) { m.remove(); toast("已保存"); route(); } else toast(r.error, true);
  };
}
async function delLlm(id) { if (!confirm("删除该供应商？")) return; await api(`/api/llm/providers/${id}`, null, "DELETE"); route(); }
async function editEnv(id) {
  const envs = await api("/api/environments");
  const e = (envs.environments || []).find(x => x.id === id) || {};
  await loadProjects();
  /* 第 18 轮自查抓到的两个真缺陷：
     ① 编辑态「项目」下拉原本可改，一次保存就把环境挪到别的项目（连带影响该项目下所有仓库/卡片的归属）；
     ② 这里读的是 e.vars_obj，但后端 /api/environments 只回原始 vars 字符串 → Cookie 框永远空，
        而保存又整列重建 vars，等于一保存就把已存的 Cookie 静默清空。 */
  let varsObj = {};
  try { varsObj = typeof e.vars === "string" ? JSON.parse(e.vars) : (e.vars || {}); } catch (err) { varsObj = {}; }
  if (!varsObj || typeof varsObj !== "object") varsObj = {};
  // Base URL 的示例优先用该项目仓库里已经配过的真实地址（学来的值，比编一个格式更省事）
  const repos = (await api("/api/repos?projectId=" + (e.project_id || state.projectId))).repos || [];
  const learnedBase = (repos.find(x => (x.base_url || "").trim()) || {}).base_url || "";
  const editing = !!id;
  const m = modal(`<h3>${editing ? "编辑" : "添加"}环境</h3>
    <div class="grid cols2">
      <div class="form-item"><label>名称</label><input id="eName" value="${esc(e.name || "")}"></div>
      <div class="form-item"><label>项目</label>
        <select id="eProj" ${editing ? "disabled" : ""}>${state.projects.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select>
        ${editing ? `<div class="muted" style="font-size:12px">环境归属固定在本项目：改归属会连带牵动该项目下的仓库、卡片与用例。要挪到别的项目请在新项目下新建环境后删除这条</div>` : ""}</div>
      <div class="form-item"><label>Base URL（HTTP 测试）</label><input id="eBase" value="${esc(e.base_url || "")}"
        ${learnedBase ? `placeholder="示例（该仓库实际地址）：${esc(learnedBase)}"` : ""}></div>
      <div class="form-item"><label>数据库 URL（SQL 直测）</label><input id="eDbUrl" value="${esc(e.db_url || "")}" placeholder="jdbc:dm://host:5236 或 jdbc:mysql://host:3306/db"></div>
      <div class="form-item"><label>数据库用户</label><input id="eDbUser" value="${esc(e.db_user || "")}"></div>
      <div class="form-item"><label>数据库密码（加密存储，留空保持）</label><input id="eDbPass" type="password"></div>
      <div class="form-item"><label>库类型（决定 DDL 闸门/变体选择/元数据）</label><select id="eDbType">
        <option value="">未知/其他</option>
        ${["mysql","tidb","oracle","dm","sqlserver","opengauss","gauss-mysql"].map(t => `<option value="${t}" ${e.db_type === t ? "selected" : ""}>${t}</option>`).join("")}</select></div>
      <div class="form-item"><label>Cookie 接管（浏览器登录后复制整串 Cookie，可选）</label><input id="eCookie" value="${esc(varsObj.pf_cookie || "")}" placeholder="k1=v1; k2=v2"></div>
    </div>
    <div class="form-item"><label><input type="checkbox" id="eDdl" ${e.allow_ddl_commit == 1 ? "checked" : ""}> 可弃库标记：允许 DDL 走提交通道（仅限专用测试 schema！）</label></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="eSave">保存</button></div>`);
  m.querySelector("#eProj").value = e.project_id || state.projectId;
  m.querySelector("#eSave").onclick = async () => {
    const patch = {};                       // 只覆盖填了的键：后端按键合并，没动的变量（含 Cookie）不会被冲掉
    const ck = m.querySelector("#eCookie").value.trim();
    if (ck) patch.pf_cookie = ck; else if (varsObj.pf_cookie) patch.pf_cookie = "";   // 清空 = 删除该键
    const r = await api("/api/environments", { id: id || null,
      projectId: Number(editing ? e.project_id : m.querySelector("#eProj").value),
      name: m.querySelector("#eName").value, baseUrl: m.querySelector("#eBase").value,
      dbUrl: m.querySelector("#eDbUrl").value, dbUser: m.querySelector("#eDbUser").value, dbPassword: m.querySelector("#eDbPass").value,
      dbType: m.querySelector("#eDbType").value, allowDdlCommit: m.querySelector("#eDdl").checked,
      vars: patch });
    if (r.ok) { m.remove(); toast("已保存"); route(); } else toast(r.error, true);
  };
}
async function checkEnv(id) {
  const row = $("#envChk-" + id);
  if (row) { row.style.display = "table-row"; row.cells[0].innerHTML = '<span class="muted">体检中…</span>'; }
  const [r, envs] = await Promise.all([api(`/api/environments/${id}/check`, {}, null, { progress: true }), api("/api/environments")]);
  const e = (envs.environments || []).find(x => x.id === id) || {};
  let baseHtml = '<span class="muted">Base URL：未配置（跳过）</span>';
  if (e.base_url) {
    busyStart();   // 浏览器探测也计入在飞：转圈要等它和数据库检查都回来才停
    try {
      await fetch(e.base_url.replace(/\/+$/, "") + "/", { mode: "no-cors", signal: AbortSignal.timeout(5000) });
      busyEnd(true);
      baseHtml = `<span style="color:var(--green)">✔ Base URL 可达：${esc(e.base_url)}</span>`;
    } catch (err) {
      busyEnd(false);
      baseHtml = `<span style="color:var(--red)">✘ Base URL 不可达：${esc(e.base_url)}（${esc(String(err.message || err)).slice(0, 80)}）</span>`;
    }
  }
  const dbHtml = r.ok
    ? `<span style="color:var(--green)">✔ 数据库 ${esc(r.product)} · ${esc(r.note)}</span>`
    : `<span style="color:var(--red)">✘ 数据库：${esc(r.error || "检查失败")}</span>`;
  if (row) row.cells[0].innerHTML = `${baseHtml}<br>${dbHtml}<br><span class="muted">体检时间 ${esc(fmtTime(new Date()))} · 再次点【体检】可刷新</span>`;
  toast(r.ok ? "体检完成，结果已固定在环境行下方" : "体检发现问题，详见环境行下方", !r.ok);
}
async function delEnv(id) {
  const e = ((await api("/api/environments")).environments || []).find(x => x.id === id) || {};
  if (!confirm(`删除环境「${e.name || id}」？\n\n环境本身可以重建，但它名下的历史（执行记录、基线、审计）会失去指向，且已绑它的登录流需要重新指定环境。确认删除？`)) return;
  await api(`/api/environments/${id}`, null, "DELETE");
  route();
}
/** 登录流的凭据列：未绑定时红色提示，避免"配了登录流却没绑账号"这种静默失败 */
function credCell(credId) {
  if (credId == null || credId === 0) return `<span style="color:#dc2626">未绑定</span>`;
  const c = (state.creds || []).find(x => x.id === credId);
  if (!c) return `#${credId}`;
  const warn = credNonLoginReason(c);
  return esc(c.name) + (warn ? " " + badge(warn, "amber") : "");
}

/** 凭据类型的中文标签：库里存的是 kind 字面量，界面上不直接吐英文（第卅六轮加了 YApi 账号密码这一种） */
const CRED_KIND_LABEL = { password: "登录账号 / SVN", yapi_login: "YApi 账号密码", yapi: "YApi token", token: "YApi token" };
const credKind = k => CRED_KIND_LABEL[k] || k || "?";

/** 这一条能不能当被测系统的登录账号用（第卅二轮那个用途单点的一个出口）：SVN 拉代码与 YApi 文档凭据都不算 */
function credNonLoginReason(c) {
  if (!c) return "";
  if (c.purpose === "svn") return "SVN 账号·非登录";
  if (c.purpose === "yapi") return "YApi 凭据·非登录";
  return "";
}

/** 凭据用途的人话（第卅二轮）：SVN 拉代码账号与被测系统登录账号同库不同用途，光看名字分不出来 */
function credPurpose(c) {
  if (!c) return "";
  if (c.purpose === "svn") return `SVN 拉代码账号 · 仓库 ${c.svn_repo || "?"}`;
  if (c.purpose === "yapi") return c.via === "login" ? "YApi 文档凭据 · 邮箱:密码（系统自动登录换 token）"
                                                     : "YApi 文档凭据 · 手填 token（约 7 天要自己换）";
  return "业务/其它账号";
}

/** 登录流弹窗里，选到 SVN/YApi 账号就当场说清"这不是被测系统的登录账号"，并给出该去哪儿建 */
function credHint(root) {
  const m = root || document;
  const sel = m.querySelector("#fCred"), box = m.querySelector("#fCredHint");
  if (!sel || !box) return;
  const base = "凭据在 [凭据保险库] 里以「名称 + user:password」添加，AES-GCM 加密存储，界面上不回显明文";
  const o = sel.options[sel.selectedIndex];
  const p = o ? o.dataset.purpose : "";
  if (p !== "svn" && p !== "yapi") { box.textContent = base; return; }
  const who = o.textContent.split("｜")[0];
  const why = p === "svn"
    ? `⚠ 这条是 <b>${esc(who)}</b> 仓库拉代码用的 SVN 账号，不是被测系统的登录账号：`
      + `登录步骤里的 {{cred.username}} / {{cred.password}} 会拿到这个仓库账号的用户名和密码，除非被测系统本来就是同一个账号。`
    : `⚠ 这条是 YApi 文档同步用的凭据（${esc(who)}），不是被测系统的登录账号：`
      + `它的内容是给 YApi 用的（邮箱:密码 或 一整串 JWT），拿去登录被测系统只会失败。`;
  const biz = (state.creds || []).filter(c => c.purpose !== "svn" && c.purpose !== "yapi").length;
  box.innerHTML = `<span style="color:#d97706">${why}</span>`
    + (biz ? `<div>${base}</div>` : `<div>这个项目还没有业务登录凭据：先【取消】，到本页 [🔒 凭据保险库] → ＋ 添加凭据 新建一条（名字别带 -svn），再回来选它。</div>`);
}

/** extract 表达式的来源自述：三种取值位置在列表页也要说清是打哪儿拿的 */
function extractSrcLabel(expr) {
  const v = String(expr == null ? "" : expr);
  if (v.startsWith("header:")) return `响应头 ${v.slice(7)}`;
  if (v.startsWith("cookie:")) return `Cookie ${v.slice(7)}`;
  return `响应体 ${v}`;
}

/** 登录流步骤的人话摘要：一行 JSON 太长，测试者只要看清每步打哪儿、怎么编码、拿回什么 */
function flowStepsSummary(raw) {
  let steps = raw;
  try {
    if (typeof steps === "string" && steps.trim().startsWith("\"")) steps = JSON.parse(steps);
    steps = typeof steps === "string" ? JSON.parse(steps) : steps;
  } catch (e) { steps = null; }
  if (!Array.isArray(steps)) return `<span style="color:var(--red)">步骤 JSON 解析不了：点【编辑】看原文</span>`;
  if (!steps.length) return `<span class="muted">（还没有步骤，所以这条流登录不了任何东西：点【编辑】，报文从该项目「学到的登录线索」里一步步填）</span>`;
  return steps.map((s, i) => {
    const bits = [`${i + 1}. ${esc(s.method || "POST")} ${esc(s.path || "?")}`];
    if (s.encode === "kjdp-login") bits.push("KJDP 线路编码（密码 ka.encrypt + 报文 DES）");
    else if (s.encode === "sm4-ecb") bits.push(`整块报文 SM4-ECB 加密（密钥 <code>${esc(String(s.encode_key || "未填，跑时会报错"))}</code>）`);
    else if (typeof s.encode === "string" && s.encode) bits.push(`未知编码 <code>${esc(s.encode)}</code>`);
    else if (typeof s.raw_body === "string" || typeof s.body === "string") bits.push("原文报文");
    else if (s.body) bits.push("JSON 报文");
    if (s.encode_fields && typeof s.encode_fields === "object") bits.push("字段级混淆 "
      + Object.entries(s.encode_fields).map(([k, v]) => `<code>${esc(k)}</code>⇦${esc(String(v))}`).join("、"));
    if (s.extract_cookie) bits.push(`取回会话 Cookie → <code>${esc(String(s.extract_cookie))}</code>`);
    else if (s.extract && typeof s.extract === "object") bits.push("提取 "
      + Object.entries(s.extract).map(([k, v]) => `<code>${esc(k)}</code>⇦${esc(extractSrcLabel(v))}`).join("、"));
    if (s.success_field) bits.push(`校验 <code>${esc(String(s.success_field))}=${esc(String(s.success_value == null ? "0" : s.success_value))}</code>`);
    if (s.captcha === "manual") bits.push("验证码人工输入");
    return `<div>${bits.join(" · ")}</div>`;
  }).join("");
}

async function editFlow(id, presetProj) {
  await loadProjects();
  const creds = (await api("/api/credentials")).credentials || [];
  state.creds = creds;
  let f = {};
  if (id) {
    const flows = await api("/api/login-flows");
    f = (flows.loginFlows || []).find(x => x.id === id) || {};
  }
  const pretty = (raw, fallback) => {
    if (raw == null || raw === "") return fallback;
    let v = raw;
    try { if (typeof v === "string" && v.trim().startsWith("\"")) v = JSON.parse(v); } catch (e) {}
    try { return JSON.stringify(typeof v === "string" ? JSON.parse(v) : v, null, 2); } catch (e) { return String(raw); }
  };
  // 第四十七轮删掉了写死的假模板（POST /api/login + tester/123456 + $.data.accessToken，四个值全是编的）。
  // 第四十八轮补另一半：盘上真学到的东西由【学到的登录线索】整条/逐步铺进来，学不到的照空串摆并逐格点名为什么空。
  const m = modal(`<h3>${id ? "编辑" : "添加"}登录流</h3>
    <div class="form-item"><label>项目</label><select id="fProj" ${id ? "disabled" : ""} onchange="flwHints(this.value, null, flwMode())">${state.projects.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select></div>
    <div class="form-item"><label>名称</label><input id="fName" value="${esc(f.name || "")}" placeholder="自己起一个认得出的名字，如 生产环境登录"></div>
    <div class="form-item"><label>登录凭据（步骤里用 {{cred.username}} / {{cred.password}} 引用）</label>
      <select id="fCred" style="width:100%" onchange="credHint(this.closest('.modal-mask')); flwStepNote()"><option value="" data-purpose="none">（不绑定：步骤里写死账号密码时选这个）</option>
        ${creds.map(c => `<option value="${c.id}" data-purpose="${esc(c.purpose || "other")}">${esc(c.name)}｜${esc(credPurpose(c))}</option>`).join("")}</select>
      <div id="fCredHint" class="muted" style="font-size:12px">凭据在 [凭据保险库] 里以「名称 + user:password」添加，AES-GCM 加密存储，界面上不回显明文</div></div>
    <div id="fHints" class="muted" style="font-size:12px">正在读这个项目学到的登录线索…</div>
    <div class="form-item"><label>步骤 JSON（每步可用：method · path · body 或 raw_body · content_type · extract · success_field/success_value · extract_cookie · captcha="manual"；
      extract 的取值来源三种：<code>$.a.b</code>＝响应体 JSON、<code>header:名字</code>＝响应头、<code>cookie:名字</code>＝Cookie；
      <code>encode</code> 支持 <code>kjdp-login</code>（金证线路编码）与 <code>sm4-ecb</code>（整块请求体国密加密，密钥写在 <code>encode_key</code>，可引用上一步 extract 出来的变量）；
      <code>encode_fields</code> 按字段混淆（如 <code>{"password":"ka"}</code>，可与 <code>sm4-ecb</code> 叠加：密码先 ka、整包再加密））</label>
      <textarea id="fSteps" class="code" oninput="flwStepNote()">${esc(pretty(f.steps, "[]"))}</textarea>
      <div id="fStepNote" class="muted" style="font-size:12px;margin-top:3px"></div>
      <div id="fAutoNote" class="muted" style="font-size:12px;margin-top:3px"></div></div>
    <div class="form-item" style="margin-top:2px"><button class="small ghost" onclick="flwClearSteps()">✕ 清空步骤（铺错了就重来，手写的也一并清）</button></div>
    <div class="form-item"><label>会话注入 JSON（apply.header，值里可引用上面 extract 出来的变量）</label>
      <textarea id="fApply" class="code">${esc(pretty(f.apply, "{}"))}</textarea></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="fSave">保存</button></div>`);
  if (f.project_id) m.querySelector("#fProj").value = f.project_id;
  else if (presetProj) m.querySelector("#fProj").value = presetProj;
  if (f.cred_id) m.querySelector("#fCred").value = f.cred_id;
  credHint(m);
  flwStepNote();
  // 第五十一轮：新建＝打开就把这个项目学到的那条链铺进步骤那一格（用户原话"我还是不知道要把哪些内容填到里面去"）。
  // 编辑已有那条不动它——自动铺的内容只进弹屏文本框，一行登录流都不替你 INSERT。
  state.flwDialog = { mode: id ? "edit" : "new", autoSteps: null, autoApply: null, autoChain: "", autoProj: "", creds: creds };
  // 恒显不等线索读完才说话：编辑＝这一格是库里存过的（或本来就空的），新建＝正在读这个项目学到的链
  const hadSteps = ((m.querySelector("#fSteps").value || "").trim()) && m.querySelector("#fSteps").value.trim() !== "[]";
  flwAutoNote(id ? (hadSteps ? "keep" : "idle") : "loading", null, null);
  flwHints(m.querySelector("#fProj").value, undefined, id ? "edit" : "new");
  m.querySelector("#fSave").onclick = async () => {
    let steps, apply;
    try {
      steps = JSON.parse(m.querySelector("#fSteps").value);
      apply = JSON.parse(m.querySelector("#fApply").value);
    } catch (e) { return toast("JSON 格式错误", true); }
    const nm = m.querySelector("#fName").value.trim();
    if (!nm) return toast("名称要填：这条流是给哪个环境/哪套账号登录的，写个自己认得出的名字", true);
    if (!Array.isArray(steps)) return toast("步骤 JSON 的最外层要是一个数组 []", true);
    if (apply === null || typeof apply !== "object" || Array.isArray(apply)) return toast("会话注入 JSON 要是一个对象 {}", true);
    const credVal = m.querySelector("#fCred").value;
    const r = await api("/api/login-flows", { id: id || null, projectId: Number(m.querySelector("#fProj").value), name: nm,
      steps: steps, apply: apply, credId: credVal ? Number(credVal) : null });
    if (r.ok) { m.remove(); toast("已保存"); route(); } else toast(r.error, true);
  };
}
/** 第四十七轮：登录线索。一处判定（后端 /login-flows/hints），弹屏与设置页都摆这一份
 *  mode="edit" 摆在建流弹屏里（可点【＋ 填成一步】/【＋ 铺这条链】）；mode="page" 摆在本页做归因，只读
 *  第四十八轮：草稿（role / 整条链 / 留空点名）也出自同一个端点，界面只负责复述服务端算好的数 */
async function flwHints(projId, box, mode) {
  const target = box || document.querySelector("#fHints");
  if (!target) return;
  if (!projId) {   // 没项目可选就说没项目可选，不许停在「正在读」装成还在忙
    target.innerHTML = '<span style="color:var(--red)">项目列表是空的：先到 [项目与仓库] 建一个，再回来看这个项目的登录线索。</span>';
    return;
  }
  const page = mode === "page";
  target.innerHTML = "正在读这个项目学到的登录线索…";
  // 换项目后这一格还写着上一个项目「已自动填好」＝说了句当下不成立的话（冷读要 6 秒以上，看得见）；
  // 读的过程本身也要恒显，所以这一行由「读」这一处写，不由打开弹屏那一处写
  if (!page && mode === "new") flwAutoNote("loading", null);
  const r = await api("/api/login-flows/hints?projectId=" + projId);
  if (!r.ok) { target.innerHTML = `<span style="color:var(--red)">${esc(r.error || "线索读取失败")}</span>`; return; }
  state.loginHints = r.cards || [];
  state.loginChains = r.flow_drafts || [];
  state.loginNoChain = r.chains_without_login || [];
  const rows = state.loginHints.map((c, i) => {
    const d = c.draft || {};
    const n = (d.key_count || 0);
    return `<div>· <span class="badge ${ROLE_BADGE[d.role] || "b-gray"}">${esc(ROLE_LABEL[d.role] || "相关")}</span>`
      + ` <span class="badge ${SRC_BADGE[c.src] || "b-gray"}">${esc(SRC_LABEL[c.src] || "来源没自述")}</span>`
      + ` <code>${esc(c.method)} ${esc(d.step && d.step.path ? d.step.path : c.path)}</code>`
      + `${c.name ? ` <span class="muted">学到的是 ${esc(c.name)}（命中「${esc(c.matched)}」）</span>` : ""}`
      + `${c.evidence ? ` <span class="muted">${esc(c.evidence)}</span>` : ""}`
      + `<span class="muted"> · ${flwKeyBits(n, d.filled, d.blank_count)}</span>`
      + (page ? "" : ` <button class="small ghost" onclick="flwAddStep(${i})">＋ 填成一步</button>`) + "</div>"
      + flwBlankLines(d.blanks, "")
      + flwDraftNotes(d);
  }).join("");
  const chains = state.loginChains.map((f, j) =>
    `<div>· <code>${esc(f.chain)}</code>`
    + (f.chain === r.auto ? ` <span class="badge b-green">默认铺这条</span>` : "")
    + ` <span class="muted">整条链 ${f.step_count} 步 · ${flwKeyBits(f.key_count, f.filled, f.blank_count)}</span>`
    + (page ? "" : ` <button class="small" onclick="flwAddChain(${j})">＋ 铺这条链 ${f.step_count} 步</button>`) + "</div>"
    + flwBlankLines(f.blanks, "　")
    + (f.notes || []).map(x => `<div class="muted" style="margin-left:1em">※ ${esc(x)}</div>`).join("")
    + (f.steps || []).map((s, k) => `<div class="muted" style="margin-left:1em">${k + 1}. ${esc(s.method)} ${esc(s.path)}`
      + `${s.body ? ` <span>· 报文 ${Object.keys(s.body).length} 个顶层键</span>` : ""}`
      + `${s.extract_cookie ? ` <span>· 会话 Cookie → <code>${esc(String(s.extract_cookie))}</code></span>` : ""}`
      + `${s.extract ? ` <span>· 留下 <code>${esc(Object.keys(s.extract).join("、"))}</code></span>` : ""}</div>`
      + (f.bases && f.bases[k] ? `<div class="muted" style="margin-left:2.5em">依据：${esc(f.bases[k])}</div>` : "")).join("")
  ).join("");
  const noChain = state.loginNoChain.map(x =>
    `<div>· <code>${esc(x.chain)}</code> <span class="muted">学到 ${x.cards} 张卡 —— ${esc(x.reason)}</span></div>`).join("");
  const capNote = r.capped ? `（命中 ${r.matched} 张，只摆前 ${r.cap} 张）` : "";
  // 默认铺哪一条、为什么是它、地址按哪一份铺——三条事实都由服务端那一处算，界面只复述
  const autoLine = (r.auto_why ? `<div class="muted" style="margin-top:4px">※ ${esc(r.auto_why)}</div>` : "")
    + (r.route_note ? `<div class="muted" style="margin-top:2px">※ ${esc(r.route_note)}</div>` : "");
  // 「几张卡」与「几处前端调用点」是两条通路的两个数，混成一个「张」报出来就是假数——服务端算好，界面只复述
  const srcNote = `接口卡 ${r.learned_cards} 张 ＋ 前端调用点 ${r.front_sites} 处（共 ${state.loginHints.length} 条）`;
  target.innerHTML = (page
      ? `<div>这个项目已有登录流 <b>${r.flows}</b> 条 · 学到的登录线索 <b>${srcNote}</b>${capNote}`
        + ` · 分发框架：${esc((r.frameworks || []).join("、") || "未识别到")}</div>`
      : `<div style="margin-top:6px">这个项目<b>学到的登录线索</b>：${srcNote}${capNote}，`
        + `其中能整条铺出来的链 <b>${state.loginChains.length}</b> 条</div>`)
    + (r.front_note ? `<div class="muted" style="margin-top:4px">${esc(r.front_note)}`
        + (r.front_basis ? ` <span>· ${esc(r.front_basis)}</span>` : "") + "</div>" : "")
    + (chains ? `<div class="mt" style="margin-top:6px"><b>照盘上学到的东西能直接铺出来的链</b>（${
        page ? "新建登录流时默认铺的那一条标在下面"
          : mode === "new" ? "带「默认铺这条」的那一条已经填进上面的步骤了，其余点一下就换上"
          : "新建登录流时会自动铺默认那一条；这条是你已经存过的，系统没动它"}）</div>${chains}${autoLine}` : autoLine)
    + (noChain ? `<div style="margin-top:6px"><b>铺不出一条流的链</b></div>${noChain}` : "")
    + `<div style="margin-top:6px"><b>逐张卡</b>（只想补其中一步时用）</div>`
    + (rows || `<div>· 一张都没有：学习阶段没认出登录语义的接口，到 [知识库 → 接口] 用 login / captcha 搜一遍再手工建</div>`)
    + `<div class="muted" style="margin-top:4px">${esc(r.why)}</div>`
    + `<div class="muted">已给值的键来自盘上学到的默认值或跨项目规则值（不是示例值）；留空的格子一律是空串并逐条说为什么空，系统不编示例值。`
    + `账号密码不要写进步骤，在 [凭据保险库] 存好后${page ? "回来" : "到上面"}绑定，步骤里用 {{cred.username}} / {{cred.password}} 引用。</div>`
    + (page ? `<div class="mt"><button class="small ghost" onclick="editFlow(0, ${projId})">＋ 照这些线索给这个项目建一条登录流</button></div>` : "");
  if (mode === "new") flwAutoFill(r, projId);
  // 编辑已存的那条：自动铺不动这条记录，但这一格是空的还是有内容得按事实说（空的那一条不能谎称"已经有内容"）
  else if (mode === "edit") {
    const cur = ((document.querySelector("#fSteps") || {}).value || "").trim();
    flwAutoNote(cur && cur !== "[]" ? "keep" : "idle", r, null);
  }
}

/** 留空点名：每学到一半、缺一半的键都要说清缺的是哪一半 */
function flwBlankLines(blanks, indent) {
  return (blanks || []).map(b => `<div class="muted" style="margin-left:1em">${indent ? "　" : ""}☐ <code>${esc(b.key)}</code>：${esc(b.why)}${b.path ? ` <span>（步骤 ${esc(b.path)}）</span>` : ""}</div>`).join("");
}
/** 键级之外的那几条事实（会话出路 / 整包加密 / 加密因子 / 动词是推的 / 地址按前端改写 / 这一步的说明 /
 *  被凭据引用换掉的盘上初始值）——服务端算好，界面照摆 */
function flwDraftNotes(d) {
  return [d.session_note, d.codec_note, d.factor_note, d.factor_conflict, d.verb_note,
    d.path_rewritten, d.step_note, d.cred_note].filter(Boolean)
    .map(x => `<div class="muted" style="margin-left:1em">※ ${esc(x)}</div>`).join("");
}
/** 入参那几个数怎么念：一个键都没学时不说「0 个键：已给值 0、留空 0」——那是把空当数字摆给人看 */
function flwKeyBits(n, filled, blank) {
  return !n ? "没有要填的入参键" : `入参 ${n} 个键：已给值 ${filled || 0}、留空 ${blank || 0}`;
}
const ROLE_LABEL = { login: "登录", logout: "登出", captcha: "验证码", token: "取会话", other: "相关" };
const ROLE_BADGE = { login: "b-green", logout: "b-red", captcha: "b-amber", token: "b-blue", other: "b-gray" };
/** 第四十九轮：一条登录线索有两条证据通路——注解学到的接口卡、前端实际发请求那一行读回来的调用点。
 *  这一枚徽章说的是"这一步的底气从哪来"，界面上每条都要自述，不许混着摆 */
const SRC_LABEL = { card: "学到的接口卡", "card+front": "卡＋前端调用点", front: "前端调用点" };
const SRC_BADGE = { card: "b-blue", "card+front": "b-green", front: "b-amber" };

/** 本页的归因行：跟着下拉选中的项目现读，和弹屏用的是同一个出口 */
function curFlwWhyProj() {
  const sel = document.querySelector("#flwWhyProj");
  return sel ? sel.value : 0;
}
function loadFlwWhy() {
  const sel = document.querySelector("#flwWhyProj");
  if (sel) flwHints(sel.value, document.querySelector("#flwWhy"), "page");
}

/** 点一下把「哪一步打哪个地址、报文长什么样、会话怎么带」都铺进去；铺完仍可手改——系统不替你编值 */
function flwAddStep(i) {
  const c = (state.loginHints || [])[i];
  const got = flwReadSteps();
  if (!c || !got) return;
  const d = c.draft || {};
  const step = d.step ? JSON.parse(JSON.stringify(d.step)) : { method: c.method, path: c.path };
  got.arr.push(step);
  flwWriteSteps(got);
  const blank = d.blank_count || 0;
  toast(`已加一步 ${step.method} ${step.path}：学到 ${d.key_count || 0} 个入参键，`
    + (blank ? `${blank} 个键值没有可推导来源、照空串摆（原因见上面点名）` : `值全部来自盘上学到的东西`)
    + "；系统不编示例值" + flwApplyFill(d.apply));
}

/** 第四十八轮：整条链一次铺进去（登录那一步在最后，登出语义不铺） */
function flwAddChain(j) {
  const f = (state.loginChains || [])[j];
  const got = flwReadSteps();
  if (!f || !got) return;
  const steps = (f.steps || []).map(s => JSON.parse(JSON.stringify(s)));
  const had = got.arr.length;
  got.arr = got.arr.concat(steps);
  flwWriteSteps(got);
  toast(`已铺 ${f.chain} 的 ${steps.length} 步${had ? `（接着你已有的 ${had} 步后面，要重来先点【✕ 清空步骤】）` : ""}`
    + `：学到 ${f.key_count} 个入参键，${f.blank_count} 个键照空串摆，原因在上面逐条点名`
    + `；这一步的底气来自：${(f.bases || []).filter(Boolean).length || steps.length} 条依据（逐步写在上面）`
    + flwApplyFill(f.apply));
}

/** 「会话注入」那一格：这条链学到的那份要不要铺进去
 *  那一格空着就直接填；已经有内容先问过再覆盖——自动铺的东西不许把用户手配的那一份顶掉。
 *  唯一例外是 `d.autoApply`：那一格本来就是<b>系统上一次写进去的</b>（换项目/换链时），跟着这一条链走，
 *  否则会留下上一个项目那条链的 {@code Bearer {{authorization}}}——第五十一轮自动铺把这条路变成了主路。 */
function flwApplyFill(apply) {
  const ta = document.querySelector("#fApply");
  if (!ta) return "";
  const d = state.flwDialog || {};
  const json = apply ? JSON.stringify(apply, null, 2) : "{}";
  const cur = (ta.value || "").trim();
  const mine = d.autoApply != null && d.autoApply === cur;     // 这一格还是系统上次写的那一份
  if (cur === json) { if (apply) d.autoApply = cur; return "；会话注入那一格本来就是这一份，没动"; }
  if (!cur || cur === "{}") {
    if (!apply) return "";                                     // 空着、这条链也没学到：不写
    ta.value = json; d.autoApply = json;
    return "；会话注入也跟着铺好了";
  }
  if (mine) {
    ta.value = json; d.autoApply = json;
    return apply ? "；会话注入跟着换成这一条链学到的那份"
      : "；这一条链没学到会话注入，那一格跟着回到空（{}）";
  }
  if (!apply) return "；这一条链没学到会话注入，那一格是你自己写的那一份，没动";
  if (confirm("「会话注入」那一格你已经写过东西了。这条链学到的是：\n" + json
      + "\n\n【确定】＝换成这条链学到的这份，【取消】＝保留你自己写的那一份")) {
    ta.value = json; d.autoApply = json;
    return "；会话注入已换成这条链学到的那份";
  }
  return "；会话注入保留你原来那一格（这条链学到的那份在上面※那行写着）";
}

/** 这一格现在填的是系统铺的还是用户手写的：自动铺的内容只进弹屏文本框，一行登录流都不替你 INSERT */
function flwMode() { return state.flwDialog ? state.flwDialog.mode : "page"; }

/**
 * 第五十一轮：点【＋ 添加登录流】打开就把这个项目学到的那条链铺进步骤那一格（用户原话
 * "我还是不知道要把哪些内容填到里面去"、"我选的项目不同这里自动填充登录流内容"）。
 *
 * <p>三条硬约束：① 铺的内容全部来自盘上证据（{@code /login-flows/hints} 那一份，铺哪一条由服务端
 * {@code LoginFlowDraftService.compareAuto} 定，界面不再算一遍）；② <b>只进文本框，不落库</b>——
 * 绝不替用户 INSERT 一条登录流；③ <b>手写的那一份永不顶掉</b>，换项目也只重铺"还是系统上次铺的那一份"。
 * 铺不出链的项目照空摆并点名为什么空，不编示例值。
 */
function flwAutoFill(r, projId) {
  const d = state.flwDialog;
  const ta = document.querySelector("#fSteps");
  if (!d || !ta) return;
  const pick = (r.flow_drafts || []).find(f => f.chain === r.auto);
  const cur = (ta.value || "").trim();
  const mine = d.autoSteps !== null && d.autoSteps === cur;   // 这一格还是系统上一次铺的那一份
  if (!pick) {
    flwAutoNote("none", r);
    return;
  }
  if (cur && cur !== "[]" && !mine) {                          // 用户手写过的内容：不动，只说清
    flwAutoNote("keep", r, pick);
    return;
  }
  const json = JSON.stringify(pick.steps, null, 2);
  ta.value = json;
  d.autoSteps = json; d.autoChain = pick.chain; d.autoProj = String(projId);
  const bits = [`步骤 ${pick.step_count} 步`];
  const nm = document.querySelector("#fName");
  if (nm && !nm.value.trim()) {
    nm.value = `${pick.chain} 登录 · 学到 ${pick.step_count} 步`;
    bits.push("名称也起了");
  }
  const ap = flwApplyFill(pick.apply);
  if (ap) {                                                  // 说的是"这一格发生了什么"，不要在前面再冠一次名字
    const t = ap.replace(/^；/, "");
    if (/铺好|换成|回到空/.test(t)) bits.push(t);
  }
  bits.push(flwCredFill(pick));
  flwStepNote();
  flwAutoNote("filled", r, pick, bits.filter(Boolean));
  toast(`已按这个项目盘上学到的东西填好：${bits.filter(Boolean).join("、")}。${r.auto_why || ""}`
    + `这一份只在弹屏里，点【保存】才落库，每一格都能手改`);
}

/** 步骤里引用了凭据：库里只有一条能用的就顺手选上，多条绝不替人选（选错等于把别人的密码发到这台地址） */
function flwCredFill(pick) {
  const sel = document.querySelector("#fCred");
  if (!sel || !(pick.steps || []).some(s => JSON.stringify(s).includes("{{cred."))) return "";
  if (sel.value) return "凭据沿用你已选的这一条";
  const d = state.flwDialog || {};
  const usable = (d.creds || []).filter(c => !credNonLoginReason(c));
  if (usable.length === 1) {
    sel.value = String(usable[0].id);
    credHint(sel.closest(".modal-mask"));
    return `凭据替你选了库里唯一那条「${usable[0].name}」`;
  }
  return usable.length ? `账号密码那两格要你自己选（库里 ${usable.length} 条可用凭据，选哪一条我替不了）`
    : "账号密码那两格要你自己选（库里还没有登录用途的凭据，先到 [凭据保险库] 存一条 user:password）";
}

/** 自动铺这件事恒显在步骤那一格下面：铺了什么、还差什么、为什么没铺，当场看得见（不是一句 toast 就过去） */
function flwAutoNote(kind, r, pick, bits) {
  const box = document.querySelector("#fAutoNote");
  if (!box) return;
  if (kind === "loading") {
    box.innerHTML = `<span class="muted">正在按这个项目读盘上学到的登录链…（读得到就把整条链铺进「步骤」那一格，读不到就照空摆并说为什么空）</span>`;
    return;
  }
  if (kind === "idle") {
    box.innerHTML = `<span style="color:#b45309">这条已存的登录流步骤本来就是空的</span>：自动铺不往已存的记录里塞步骤，`
      + `要照盘上学到的补就点上面【＋ 铺这条链】接进来，或自己手写（每一格都能改）。`;
    return;
  }
  if (kind === "none") {
    box.innerHTML = `<span style="color:#b45309">这个项目盘上铺不出一条整链，所以步骤那一格是空的</span>`
      + `——系统不替你编值。${esc(r.auto_why || "")} 想一步步来就点上面每张卡的【＋ 填成一步】，或自己手写。`;
    return;
  }
  if (kind === "keep") {
    box.innerHTML = `<span style="color:#b45309">步骤这一格已经有内容了（你写的或已存的那一份），自动铺没动它</span>`
      + `；要按盘上学到的重铺，先点【✕ 清空步骤】，或直接点上面那条链的【＋ 铺这条链】`
      + `（那会接在你已有的步骤后面）。`;
    return;
  }
  const need = [];
  if (pick.blank_count) need.push(`${pick.blank_count} 个键照空串摆（逐条原因在上面「留空点名」）`);
  if (pick.placeholder_count) need.push(`${pick.placeholder_count} 格是<b>占位串</b>：验证码位数盘上写着、码值没人学得动，`
    + `测试环境按不校验放行才发得通`);
  const cn = (bits || []).filter(x => x.startsWith("账号密码") || x.startsWith("凭据"));
  box.innerHTML = `<span style="color:#047857">已按这个项目盘上学到的东西自动填好</span>：${esc((bits || []).join("、"))}`
    + `<br>铺的是「${esc(pick.chain)}」那一条：${esc(r.auto_why || "")}`
    + (need.length ? `<br>还要你看的：${need.join("；")}` : `<br>这一条链上没有留空的键`)
    + (cn.length ? `<br>${esc(cn.join("；"))}` : "")
    + `<br>这一份只在弹屏里，没落库；每一格都能手改，改完【保存】才存。要换一条点上面的【＋ 铺这条链】，要重来点【✕ 清空步骤】。`;
}

/** 铺错了要能一键回到空手：手写的也一并清，所以按钮上写明 */
function flwClearSteps() {
  const ta = document.querySelector("#fSteps");
  if (!ta) return;
  if (ta.value && ta.value.trim() !== "[]" && !confirm("步骤 JSON 现在不是空的：清空会连手写的部分一起清掉，继续？")) return;
  ta.value = "[]";
  flwStepNote();
  toast("步骤已清空，回到空手");
}

function flwReadSteps() {
  const ta = document.querySelector("#fSteps");
  if (!ta) return null;
  let arr;
  try { arr = JSON.parse(ta.value || "[]"); } catch (e) { toast("步骤 JSON 现在格式不对，先修正再加", true); return null; }
  if (!Array.isArray(arr)) { toast("步骤 JSON 的最外层要是一个数组 []", true); return null; }
  return { ta: ta, arr: arr };
}
function flwWriteSteps(got) {
  got.ta.value = JSON.stringify(got.arr, null, 2);
  flwStepNote();
}

/** 步骤那一格的恒显状态：铺了几步、引用了凭据却没人绑、空数组，都要当场说出来（只说，不拦保存） */
function flwStepNote() {
  const box = document.querySelector("#fStepNote");
  if (!box) return;
  const ta = document.querySelector("#fSteps"), sel = document.querySelector("#fCred");
  let arr;
  try { arr = JSON.parse(ta ? ta.value : "[]"); } catch (e) {
    box.innerHTML = '<span style="color:var(--red)">步骤 JSON 解析不了：保存会被挡住，先修正格式</span>'; return;
  }
  if (!Array.isArray(arr)) { box.innerHTML = '<span style="color:var(--red)">步骤 JSON 的最外层要是一个数组 []</span>'; return; }
  const bits = [`现在 <b>${arr.length}</b> 步`];
  if (!arr.length) bits.push("空数组：这条流登录不了任何东西");
  const noPath = arr.filter(s => !s || !s.path).length;
  if (noPath) bits.push(`<span style="color:var(--red)">${noPath} 步没有 path</span>`);
  if ((ta.value || "").includes("{{cred.")) {
    bits.push(sel && sel.value ? "账号密码走已绑定的凭据"
      : `<span style="color:var(--red)">步骤里用了 {{cred.username}} / {{cred.password}}，上面却还没绑登录凭据：`
        + "到「登录凭据」选一条（明文只在保险库里），或把这一格改成你自己手工填的值</span>");
  }
  box.innerHTML = "· " + bits.join(" · ");
}

async function trialFlow(id) {
  const envs = (await api("/api/environments")).environments || [];
  if (!envs.length) return toast("还没有任何环境，先到「测试环境」添加", true);
  const m = modal(`<h3>试跑登录流</h3>
    <div class="form-item"><label>用哪个环境真实执行一遍（值会打码显示）</label>
      <select id="tfEnv" style="width:100%">${envs.map(e => `<option value="${e.id}">${esc(e.name)} · ${esc(e.base_url || "无 base url")}</option>`).join("")}</select></div>
    <div id="tfOut" class="muted">将真实调用登录接口一次，不落任何数据</div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button><button id="tfGo">▶ 试跑</button></div>`);
  m.querySelector("#tfGo").onclick = async () => {
    m.querySelector("#tfOut").textContent = "执行中…";
    const r = await api(`/api/login-flows/${id}/trial`, { envId: Number(m.querySelector("#tfEnv").value) }, null, { progress: true });
    m.querySelector("#tfOut").innerHTML = r.ok
      ? `<span style="color:var(--green)">✔ 登录流跑通，提取到会话变量：</span><br>` +
        (Object.keys(r.extracted || {}).length ? Object.entries(r.extracted).map(([k, v]) => `<code>${esc(k)}</code> = ${esc(v)}`).join("<br>") : "（steps 里没配 extract，仅登录成功）")
      : `<span style="color:var(--red)">✘ 失败：${esc(r.error)}</span>`;
  };
}
async function delFlow(id) { if (!confirm("删除该登录流？")) return; await api(`/api/login-flows/${id}`, null, "DELETE"); route(); }
async function editCred() {
  const m = modal(`<h3>添加凭据</h3>
    <div class="form-item"><label>名称（SVN 凭据命名约定：&lt;仓库名&gt;-svn；被测系统的登录账号请另起名字，不带 -svn）</label><input id="cName"></div>
    <div class="form-item"><label>类型</label>
      <select id="cKind"><option value="password">登录账号 / SVN（内容格式 user:password）</option>
        <option value="yapi_login">YApi 账号密码（内容格式 邮箱:密码，系统自己登录换 token，过期自动重登）</option>
        <option value="yapi">YApi token（内容直接粘 _yapi_token 那一整串 JWT，约 7 天过期，过期要自己换）</option></select></div>
    <div class="form-item"><label>内容</label><input id="cSecret" type="password"></div>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">取消</button><button id="cSave">保存</button></div>`);
  m.querySelector("#cSave").onclick = async () => {
    const r = await api("/api/credentials", { name: m.querySelector("#cName").value,
      kind: m.querySelector("#cKind").value, secret: m.querySelector("#cSecret").value });
    if (r.ok) { m.remove(); toast("已加密保存"); route(); } else toast(r.error, true);
  };
}
async function delCred(id) { if (!confirm("删除该凭据？")) return; await api(`/api/credentials/${id}`, null, "DELETE"); route(); }
async function saveWebhook() { await api("/api/settings", { key: "notify_webhook_url", value: $("#webhookUrl").value }); toast("已保存"); }
/** 第 26 轮：业务错误码列名。留空＝用户主动关掉判定，所以保存话术里不说"会恢复默认" */
async function saveBizCols() {
  const v = $("#bizColsInput").value.trim();
  const r = await api("/api/settings", { key: "biz_error_columns", value: v });
  if (!r.ok) return toast("保存失败：" + (r.error || "服务无响应"), true);
  toast(v ? `已保存：命中 ${v.split(/[,，;；\s]+/).filter(Boolean).length} 个列名（只影响之后的执行，旧记录保留当时的判定）`
          : "已保存为空＝关掉业务错误码判定（只影响之后的执行，旧记录不变）");
}
function fillDefaultBizCols() {
  $("#bizColsInput").value = window.__bizColsDefault || "";
  toast("已把默认列名填进输入框，点【保存】才生效");
}
/** 第 18 轮：界面提示开关 + 默认每页条数，一次保存三项（三个 POST 会互相打红闪，宁可一次点一次反馈） */
async function saveUiPrefs() {
  const items = [["ui_tip_menu", $("#uiTipMenu").checked ? "1" : "0"],
    ["ui_tip_buttons", $("#uiTipBtns").checked ? "1" : "0"],
    ["ui_page_size", $("#uiPageSize").value]];
  for (const [k, v] of items) {
    const r = await api("/api/settings", { key: k, value: v });
    if (!r.ok) return toast(`保存 ${k} 失败：${r.error || "服务无响应"}`, true);
  }
  state.uiPrefs = Object.assign({}, state.uiPrefs, Object.fromEntries(items));
  toast("已保存，立即生效");
  route();     // 重渲染：新默认条数当场体现在没单独改过的列表上
}
function resetLocalSizes() {
  const keys = Object.keys(localStorage).filter(k => k.startsWith("pf.size."));
  keys.forEach(k => localStorage.removeItem(k));
  toast(keys.length ? `已清掉 ${keys.length} 个列表的单独设置，全部回到系统默认` : "本浏览器没有单独改过任何列表的每页条数", !keys.length);
  route();
}
function showTipMiss() {
  const miss = [...window.__pfTipMiss];
  modal(`<h3>还没配悬停说明的控件（${miss.length} 项）</h3>
    <div class="muted mb">这里记的是你这次打开页面后悬停过、但系统没写说明的按钮/菜单——没配的不猜，宁可空着。
      把常用的那几项报出来，补进 app.js 的 NAV_TIPS / BTN_TIPS 就会生效。</div>
    ${miss.length ? `<div class="wrap">${miss.map(x => `<span class="badge b-gray" style="margin:2px">${esc(x)}</span>`).join("")}</div>`
      : `<div class="empty">你悬停过的控件都已经配上说明了</div>`}
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
}
async function testNotify() { const r = await api("/api/notify/test", { text: "这是一条 PreFlight 测试通知" }); toast(r.ok ? "已发送" : r.error, !r.ok); }
async function addUser() {
  const r = await api("/api/auth/users", { username: $("#newUserName").value, password: $("#newUserPass").value, role: $("#newUserRole").value });
  toast(r.ok ? "已添加" : r.error, !r.ok); route();
}
async function delUser(id) { if (!confirm("删除该用户？")) return; await api(`/api/auth/users/${id}`, null, "DELETE"); route(); }
async function runBenchmark() {
  toast("评测运行中…");
  const r = await api("/api/llm/benchmark/run", {}, null, { progress: true });
  if (r.ok) {
    const m = modal(`<h3>🧬 评测报告</h3><div class="row"><div class="stat"><div class="num">${r.report.avgPrecision}%</div><div class="lbl">平均精确率</div></div>
      <div class="stat"><div class="num">${r.report.avgRecall}%</div><div class="lbl">平均召回率</div></div></div>
      <div class="mt"><b>${esc(r.report.recommendation)}</b></div>
      <div class="actions"><button onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
  } else toast(r.error, true);
}

/* ============ 认证（RBAC：admin/tester/viewer） ============ */
let currentUser = null;
let authOk = false;   // F1：未完成登录/初始化时不渲染业务页面，避免弹窗与"未登录"看板同屏

async function authCheck() {
  try {
    const s = await api("/api/auth/status");
    if (s.needsSetup) { authOk = false; showSetup(); return; }
    if (!s.loggedIn) { authOk = false; showLogin(); return; }
    authOk = true;
    currentUser = { username: s.username, role: s.role };
    const foot = $(".sidebar-foot");
    if (foot) foot.innerHTML = `<div class="llm-status ok" id="llmStatus">…</div>
      <div>👤 ${esc(s.username)} · ${esc(s.role)} · <a href="#" onclick="logout()" style="color:#93c5fd">退出</a></div>`;
    refreshLlmStatus();
  } catch (e) { /* 静默 */ }
}
function showSetup() {
  const m = modal(`<h3>🛫 初始化 PreFlight</h3><div class="muted mb">首次使用：创建管理员账号（此后在系统设置中添加组员）</div>
    <div class="form-item"><label>管理员用户名</label><input id="suName" value="admin"></div>
    <div class="form-item"><label>密码（至少 6 位）</label><input id="suPass" type="password"></div>
    <div class="actions"><button id="suGo">创建并登录</button></div>`);
  m.querySelector("#suGo").onclick = async () => {
    const r = await api("/api/auth/setup", { username: m.querySelector("#suName").value, password: m.querySelector("#suPass").value });
    if (r.ok) { m.remove(); toast("初始化完成，欢迎 " + r.username); await authCheck(); await loadUiPrefs(); route(); }
    else toast(r.error, true);
  };
}
function showLogin() {
  if (document.querySelector(".login-mask")) return;
  const mask = document.createElement("div");
  mask.className = "modal-mask login-mask";
  mask.innerHTML = `<div class="modal" style="width:380px"><h3>🛫 PreFlight 登录</h3>
    <div class="form-item"><label>用户名</label><input id="liName"></div>
    <div class="form-item"><label>密码</label><input id="liPass" type="password"></div>
    <div class="actions"><button id="liGo" style="width:100%">登 录</button></div></div>`;
  mask.querySelector("#liGo").onclick = async () => {
    const r = await api("/api/auth/login", { username: mask.querySelector("#liName").value, password: mask.querySelector("#liPass").value });
    if (r.ok) { mask.remove(); toast("欢迎，" + r.username); await authCheck(); await loadUiPrefs(); route(); } else toast(r.error, true);
  };
  document.getElementById("modalRoot").appendChild(mask);
}
async function logout() { await api("/api/auth/logout", {}); currentUser = null; location.reload(); }
function refreshLlmStatus() {
  api("/api/llm/status").then(s => {
    const el = $("#llmStatus");
    if (el) { el.className = "llm-status " + (s.available ? "ok" : "off");
      el.textContent = s.available ? `LLM 在线 · ${s.enabledProviders} 个供应商 · 缓存 ${s.cacheRows} 条` : "LLM 离线 · 降级模式运行"; }
  }).catch(() => {});
}

/* ============ 路由 ============ */
const titles = { dashboard: "看板", projects: "项目与仓库", changes: "改动流", lab: "接口测试台", sql: "SQL 直测", knowledge: "知识库", releases: "起飞检查单", reports: "提测结论单", yapi: "YApi 文档同步", settings: "系统设置" };
let routeSeq = 0;   // 同屏只认最后一次渲染：连点两个过滤（先项目后仓库）时，前一次可能在后一次之后才回来
async function route() {
  const seq = ++routeSeq;
  const hash = location.hash.replace("#/", "") || "dashboard";
  const view = hash.split("?")[0];
  const fn = views[view] || views.dashboard;
  document.querySelectorAll("nav a").forEach(a => a.classList.toggle("active", a.dataset.view === view));
  $("#pageTitle").textContent = titles[view] || view;
  if (!authOk) { $("#view").innerHTML = ""; return; }   // F1：登录/初始化完成前只显示弹窗，不渲染业务数据
  $("#view").innerHTML = `<div class="empty">加载中…</div>`;
  let html = null, failed = null;
  try { html = await fn(); } catch (e) { failed = e; }
  if (seq !== routeSeq) return;      // 期间又发起过渲染：这一份内容已经过时，整次收尾（含后面的段加载）都不做
  if (failed) { $("#view").innerHTML = `<div class="card result-fail">加载失败：${esc(failed.message)}</div>`; }
  else $("#view").innerHTML = html;
  fitControls();
  bindProjectSelect();
  if (view === "lab" && state.labIfcId) loadLabIfc();   // 第八轮：弹屏选卡后跨刷新恢复
  if (view === "sql") {                                  // 第 15 轮：用例目录深链到 SQL 直测时自动选卡
    renderSqlCaseBar();                                  // 第 20 轮：执行前就要看清"这次算谁的"
    if (window.__sqlObj) loadSqlObj();
  }
  if (view === "reports" && location.hash.includes("id=")) loadRpRows();   // 明细行服务端分页：卡片壳先出，行随后填
  if (view === "knowledge") {   // 第 16 轮：三段并发各自加载，谁先回来谁先出（用例段不再被两份全量卡 JSON 挡在后面）
    const fail = id => e => {
      const box = document.getElementById(id);
      if (box) box.innerHTML = `<div class="result-fail">加载失败：${esc(e.message)}</div>`;
    };
    loadKbSection("ifc").catch(fail("kbIfc"));
    loadKbSection("dob").catch(fail("kbDob"));
    loadCaseCatalog().catch(fail("kbCases"));
  }
  if (view === "projects") loadTestability(false);   // 第 11 轮：体检卡常驻
  if (view === "settings") loadFlwWhy();             // 第 47 轮：登录线索归因行（与建流弹屏同一个出口）
}
window.addEventListener("hashchange", route);

/* 全局搜索 */
$("#globalSearch").addEventListener("keydown", async e => {
  if (e.key !== "Enter") return;
  const q = e.target.value.trim();
  if (!q) return;
  const r = await api("/api/search?q=" + encodeURIComponent(q));
  const m = modal(`<h3>搜索「${esc(q)}」— 命中 ${r.results.length} 条</h3>
    <table><tr><th>类型</th><th>名称</th><th>详情</th></tr>
    ${r.results.map(x => `<tr><td>${badge(x.kind, "indigo")}</td><td>${esc(x.name)}</td>
      <td class="wrap muted">${esc(x.path || x.obj_type || x.scenario || "")}</td></tr>`).join("")}</table>
    <div class="actions"><button class="ghost" onclick="this.closest('.modal-mask').remove()">关闭</button></div>`);
});

/* LLM 状态灯 */
async function initAuthAndRoute() { await authCheck(); await loadUiPrefs(); route(); }
initAuthAndRoute();
