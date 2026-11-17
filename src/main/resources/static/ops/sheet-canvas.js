/**
 * 中文备注：协作工作台 Canvas 表格引擎（类多维表格 / Excel 网格）。
 * 依赖页面注入的 hooks：getRows / getDraft / isDirty / columnDefs / options / labels 等。
 */
(function (global) {
  const OPS_SHEET_CANVAS_REV = "20261115a";
  // 中文备注：主流多维表格实测行高 30px(紧凑档)/表头 30px。
  const DEFAULT_ROW_H = 30;
  const HEADER_H = 30;
  // 中文备注：行号列要能完整显示四位数（上千行）；过窄会被 drawText 默认 pad 裁成省略号。
  const ROW_INDEX_W = 56;
  const HEADER_ICON_W = 16;
  const HEADER_MENU_W = 16;
  const HEADER_PAD_X = 12;
  const CELL_PAD_X = 12;
  const COLORS = {
    grid: "#e1e3e6",
    gridStrong: "#cfd3d7",
    headerBg: "#f7f8fa",
    headerHot: "#eef0f2",
    headerText: "#1f2329",
    headerSelectedBg: "rgba(20, 86, 240, 0.08)",
    headerSelectedText: "#1456f0",
    cornerBg: "#f7f8fa",
    rowIndexBg: "#ffffff",
    text: "#1f2329",
    subtle: "#51565d",
    muted: "#8f959e",
    white: "#ffffff",
    hover: "#f5f6f7",
    dirty: "#fff8ec",
    focus: "#1456f0",
    select: "#1456f0",
    rangeFill: "rgba(20,86,240,0.14)",
    rangeBorder: "#1456f0",
    rangeHandle: "#1456f0",
    find: "rgba(20,86,240,0.14)",
    findActive: "rgba(20,86,240,0.22)",
    findStroke: "#1456f0",
    freezeShadow: "rgba(31,35,41,0.06)",
    headerIcon: "#a2a8b0",
    headerMenu: "#8f959e",
    resizeLine: "rgba(20,86,240,0.55)",
    groupBg: "#f5f6f7",
    groupBgHover: "#eef0f3",
    groupBgL1: "#f5f6f7",
    groupBgL1Hover: "#eef0f3",
    groupBgL2: "#f5f6f7",
    groupBgL2Hover: "#eef0f3",
    groupText: "#1f2329",
    groupMuted: "#8f959e",
    groupLine: "#dee0e3",
    groupLineSoft: "#ebebeb",
    groupCardBorder: "#d0d3d6",
    groupInnerBorder: "#e1e3e6",
    // 中文备注：仅一级分组间距与页边露此色，卡片内全白。
    groupSpacer: "#fcfcfc",
    groupPage: "#fcfcfc"
  };
  // 中文备注：一级分组白卡片左右边距。
  const GROUP_CARD_PAD = 10;

  function createGroupBlockIndex() {
    let indexedRows = null;
    let blocksByLevel = new Map();

    function get(list, level = 0) {
      const rowList = Array.isArray(list) ? list : [];
      if (rowList !== indexedRows) {
        indexedRows = rowList;
        blocksByLevel = new Map();
      }
      const depth = Math.max(0, Number(level) || 0);
      if (blocksByLevel.has(depth)) return blocksByLevel.get(depth);

      const blocks = [];
      for (let i = 0; i < rowList.length; i++) {
        const row = rowList[i];
        if (!row || row.__group !== true || Number(row.level) !== depth) continue;
        let end = i;
        for (let j = i + 1; j < rowList.length; j++) {
          const next = rowList[j];
          if (depth === 0 && next?.__groupSpacer === true) break;
          if (next?.__group === true && Number(next.level) <= depth) break;
          end = j;
        }
        blocks.push({ start: i, end, level: depth });
        i = end;
      }
      blocksByLevel.set(depth, blocks);
      return blocks;
    }

    function visible(list, rowStart, rowEnd, level = 0) {
      const blocks = get(list, level);
      let low = 0;
      let high = blocks.length;
      while (low < high) {
        const mid = (low + high) >>> 1;
        if (blocks[mid].end < rowStart) low = mid + 1;
        else high = mid;
      }
      const result = [];
      for (let i = low; i < blocks.length && blocks[i].start <= rowEnd; i++) {
        result.push(blocks[i]);
      }
      return result;
    }

    return { get, visible };
  }

  function createSheet(opts) {
    const viewport = opts.viewport;
    const canvas = opts.canvas;
    const sizer = opts.sizer;
    const editor = opts.editor;
    const input = opts.input;
    const select = opts.select;
    const optionMenu = opts.optionMenu || (() => {
      const el = document.createElement("div");
      el.className = "sheet-option-menu";
      document.body.appendChild(el);
      return el;
    })();
    const tooltip = opts.tooltip;
    const selectionStatEl = opts.selectionStat || null;
    // 中文备注：先画到离屏缓冲，成功后再整帧贴到可见画布；
    // 这样数据/代码更新导致绘制抛错时保留上一帧，表格不会瞬间变白。
    const vctx = canvas.getContext("2d");
    const buffer = document.createElement("canvas");
    const ctx = buffer.getContext("2d");

    const sheet = {
      hoverRow: -1,
      active: null, // { rowIndex, colKey } 焦点格
      activeCol: "", // 中文备注：点击表头选中整列时记录列 key
      headerSelection: null, // { c0, c1 } 中文备注：从表头长按拖动形成连续多列选区。
      // 中文备注：主选区（最近一次框选）；ranges 为 Cmd/Ctrl 追加的其它矩形选区。
      selection: null, // { r0, r1, c0, c1 }
      ranges: [], // [{ r0, r1, c0, c1 }, ...]
      selecting: null, // { add, startRow, startCol, moved }
      editing: null,
      findHits: [], // [{rowIndex, colKey}]
      findHitKeys: new Set(),
      findIndex: -1,
      headerHotKey: "",
      raf: 0,
      dpr: 1,
      scrollLeft: 0,
      scrollTop: 0,
      bound: false,
      resizing: null, // { key, startX, startW }
      widthOverrides: {}, // drag 中的临时列宽
      edgeKey: "",
      suppressClick: false,
      // 中文备注：已选列表头长按后进入拖拽改列序（多维表格：先点选整列，再长按拖）。
      colLongPress: null, // { key, timer, x, y }
      colDragging: null, // { key, insertKey, place, lineX }
      // 中文备注：填充柄拖拽快照；源选区固定，预览区随指针向下扩展；边拖边提交共用 pasteBatchId。
      fillDragging: null, // { sourceRange, targetRow, lastCommittedTargetRow, pasteBatchId, moved, _commitTail }
      selectingEdgeRaf: 0,
      colDragRaf: 0,
      colDragX: 0
    };
    const COL_LONG_PRESS_MS = 400;
    // 中文备注：对齐 Excel——框选拖到视口边缘持续自动滚，选区可超出当前可见范围。
    const SELECT_EDGE_ZONE = 40;
    /** 中文备注：填充柄向下每覆盖 N 行触发一次增量提交，避免松手时几百行一次性粘贴。 */
    const FILL_LIVE_COMMIT_ROWS = 20;
    const SELECT_EDGE_MAX_SPEED = 28;
    const SELECT_EDGE_OUTSIDE_BOOST = 1.6;

    function hooks() { return opts.hooks || {}; }
    function rowH() {
      try {
        const n = Number(hooks().rowHeightPx?.());
        if (Number.isFinite(n) && n >= 24 && n <= 200) return Math.round(n);
      } catch (_) {}
      return DEFAULT_ROW_H;
    }
    function rows() { return hooks().getRows() || []; }
    function isGroupRow(row) {
      return !!(row && row.__group === true);
    }
    function isGroupSpacer(row) {
      return !!(row && row.__groupSpacer === true);
    }
    function isDataRow(row) {
      return !!(row && !isGroupRow(row) && !isGroupSpacer(row));
    }
    function focusGridCanvas() {
      try {
        canvas.focus({ preventScroll: true });
      } catch (_) {
        try { canvas.focus(); } catch (__) {}
      }
    }
    /** 中文备注：#sheetEditor 常驻 DOM，关编辑后焦点常仍落在隐藏 input，不能据此拦截表格复制粘贴。 */
    function isExternalTextInputFocused() {
      if (sheet.editing) return false;
      const el = document.activeElement;
      if (!el || el === document.body || el === document.documentElement) return false;
      const tag = String(el.tagName || "").toUpperCase();
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
        const sheetEd = el.closest?.("#sheetEditor, .sheet-editor");
        if (sheetEd) return !!(sheetEd.classList.contains("show") || sheet.editing);
        return true;
      }
      if (el.isContentEditable) return true;
      if (el.closest?.("[contenteditable='true'], .cell-editor, .fx-surface")) return true;
      return false;
    }
    /** 中文备注：查找引用列按引用字段类型渲染/编辑（如主表单选→彩色 tag）。 */
    function effectiveCellType(col) {
      if (!col) return "text";
      if (col.type === "lookup") return col.editType || col.cellType || "text";
      return col.type || "text";
    }
    function asCellColumn(col) {
      if (!col) return col;
      const fresh = liveColumn(col) || col;
      const cellType = effectiveCellType(fresh);
      if (fresh.type === "lookup" && cellType !== "lookup") {
        return { ...fresh, type: cellType, _lookupCol: true };
      }
      return fresh;
    }
    /** 中文备注：批量粘贴构建行时，严格向下找下一条数据行；越界返回 -1，避免重复落在最后一行。 */
    function nextPasteBuildRowIndex(current) {
      if (current < 0) return findDataRowIndex(0, 1);
      const list = rows();
      for (let i = current + 1; i < list.length; i++) {
        if (isDataRow(list[i])) return i;
      }
      return -1;
    }

    async function ensureRowsForBulkMatrix(matrix, pasteStartRow) {
      if (typeof hooks().ensurePasteRows !== "function" || !matrix?.length) return;
      const anchor = findDataRowIndex(pasteStartRow, 1);
      const startForEnsure = anchor >= 0 ? anchor : Math.max(0, pasteStartRow | 0);
      await hooks().ensurePasteRows(matrix.length, startForEnsure);
    }

    /** 中文备注：从 from 起找下一条数据行；dir=1 向下，dir=-1 向上。找不到返回 -1。 */
    function findDataRowIndex(from, dir = 1) {
      const list = rows();
      if (!list.length) return -1;
      const step = dir < 0 ? -1 : 1;
      let i = Math.max(0, Math.min(list.length - 1, from | 0));
      // 若 from 越界，从端点开始。
      if (from < 0) i = 0;
      if (from >= list.length) i = list.length - 1;
      for (; i >= 0 && i < list.length; i += step) {
        if (isDataRow(list[i])) return i;
      }
      return -1;
    }
    /** 中文备注：相对当前行按方向跳到下一条数据行；没有则停在当前（若当前是数据行）。 */
    function stepDataRowIndex(from, deltaRow) {
      if (!deltaRow) return from;
      const list = rows();
      if (!list.length) return from;
      let cur = from;
      if (!isDataRow(list[cur])) {
        const landed = findDataRowIndex(cur, deltaRow > 0 ? 1 : -1);
        return landed < 0 ? from : landed;
      }
      const next = findDataRowIndex(cur + (deltaRow > 0 ? 1 : -1), deltaRow > 0 ? 1 : -1);
      return next < 0 ? cur : next;
    }
    function hasGroupRows(list) {
      const rowList = list || rows();
      for (let i = 0; i < rowList.length; i++) {
        if (isGroupRow(rowList[i]) || isGroupSpacer(rowList[i])) return true;
      }
      return false;
    }

    function scrollMetaVisibleRange(scrollMeta, sl, clipLeft, W) {
      const n = scrollMeta.length;
      if (!n) return { start: 0, end: -1 };
      const leftBound = sl + clipLeft;
      const rightBound = sl + W;
      let lo = 0;
      let hi = n - 1;
      let start = n;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (scrollMeta[mid].x + scrollMeta[mid].w > leftBound) {
          start = mid;
          hi = mid - 1;
        } else lo = mid + 1;
      }
      if (start >= n) return { start: 0, end: -1 };
      let end = start;
      for (let i = start; i < n; i++) {
        if (scrollMeta[i].x > rightBound) break;
        end = i;
      }
      return { start, end };
    }

    let dataSeqPrefixSig = "";
    let dataSeqPrefixArr = [];
    function resetDataSeqPrefix() {
      dataSeqPrefixSig = "";
      dataSeqPrefixArr = [];
    }
    function dataRowSeqBefore(list, rowIndex) {
      const rowList = list || rows();
      const sig = String(rowList.length);
      if (dataSeqPrefixSig !== sig || dataSeqPrefixArr.length !== rowList.length + 1) {
        const pref = new Array(rowList.length + 1);
        pref[0] = 0;
        for (let i = 0; i < rowList.length; i++) {
          const g = isGroupRow(rowList[i]) || isGroupSpacer(rowList[i]);
          pref[i + 1] = pref[i] + (g ? 0 : 1);
        }
        dataSeqPrefixArr = pref;
        dataSeqPrefixSig = sig;
      }
      const ri = Math.max(0, Math.min(rowIndex, rowList.length));
      return dataSeqPrefixArr[ri] || 0;
    }
    // 中文备注：分组时用卡片左边距代替序号列，内容落在白卡片内。
    function indexW(list) {
      const grouped = typeof hooks().displayHasGroupRows === "function"
        ? !!hooks().displayHasGroupRows()
        : hasGroupRows(list);
      return grouped ? GROUP_CARD_PAD : ROW_INDEX_W;
    }
    const groupBlockIndex = createGroupBlockIndex();
    function drawGroupCardFrames(blocks, st, viewportW, viewportH) {
      const bodyTop = HEADER_H;
      const bodyH = Math.max(0, viewportH - HEADER_H);
      if (bodyH <= 0) return;
      const pad = GROUP_CARD_PAD;
      const cardW = Math.max(0, viewportW - pad * 2);

      // 中文备注：只画一级（顶层）白底大卡片；内层分组不另铺灰底，全白。
      for (const b of blocks) {
        const y0 = bodyTop + b.start * rowH() - st;
        const y1 = bodyTop + (b.end + 1) * rowH() - st;
        const h = y1 - y0;
        if (h <= 0) continue;
        drawRoundRect(pad, y0, cardW, h, 8, COLORS.white, "");
      }
    }
    function strokeGroupCardFrames(blocks, st, viewportW) {
      const bodyTop = HEADER_H;
      const pad = GROUP_CARD_PAD;
      const cardW = Math.max(0, viewportW - pad * 2);
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, bodyTop, viewportW, Math.max(0, viewport.clientHeight - bodyTop));
      ctx.clip();

      // 中文备注：大框描边；内层用缩进 + 格子线分小框，不再套第二层灰边卡片。
      for (const b of blocks) {
        const y0 = bodyTop + b.start * rowH() - st;
        const y1 = bodyTop + (b.end + 1) * rowH() - st;
        const h = y1 - y0;
        if (h <= 0) continue;
        drawRoundRect(pad + 0.5, y0 + 0.5, cardW - 1, h - 1, 8, "", COLORS.groupCardBorder);
      }
      ctx.restore();
    }
    const COL_W_MIN = 88;
    const COL_W_MAX = 560;
    const COL_W_MAX_MANUAL = 900;
    let autoWidthCache = { sig: "", map: Object.create(null) };
    let visibleColsMemo = { sig: "", cols: [] };
    let columnLabelCache = { sig: "", map: Object.create(null) };
    let layoutMemo = { sig: "", value: null };

    function invalidateLayoutCache() {
      layoutMemo = { sig: "", value: null };
      visibleColsMemo = { sig: "", cols: [] };
      autoWidthCache = { sig: "", map: Object.create(null) };
      columnLabelCache = { sig: "", map: Object.create(null) };
    }

    function visibleCols() {
      const all = hooks().getColumns?.() || [];
      const hiddenList = hooks().getHiddenFields?.() || [];
      const sig = `${all.length}|${hiddenList.slice().sort().join(",")}|${all.map((c) => c.key).join(",")}`;
      if (visibleColsMemo.sig === sig) return visibleColsMemo.cols;
      const hidden = new Set(hiddenList);
      const cols = all.filter((c) => c.key !== "actions" ? !hidden.has(c.key) : true);
      visibleColsMemo = { sig, cols };
      return cols;
    }
    function clampColW(n, { manual = false } = {}) {
      const max = manual ? COL_W_MAX_MANUAL : COL_W_MAX;
      return Math.max(COL_W_MIN, Math.min(max, Math.ceil(Number(n) || 100)));
    }
    function autoWidthSignature() {
      const cols = visibleCols();
      return cols.map((c) => `${c.key}:${labelOf(c)}`).join("|");
    }
    function measureAutoColWidth(col) {
      if (!col) return 120;
      // 中文备注：默认只按表头标题撑开，不看单元格内容；要看全内容需手动拉宽。
      const fontBold = '400 14px "PingFang SC","Microsoft YaHei","Segoe UI",Calibri,Arial,sans-serif';
      ctx.font = fontBold;
      const label = String(labelOf(col) || col.key || "");
      const headerChrome = HEADER_PAD_X + HEADER_ICON_W + 4 + HEADER_MENU_W + 12;
      let maxW = ctx.measureText(label).width + headerChrome;
      if (isActionColumn(col)) {
        // 中文备注：操作列要放下「操作历史」按钮，不能只按两字表头「操作」估宽。
        ctx.font = '11px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
        const btnNeed = ctx.measureText("操作历史").width + CELL_PAD_X * 2 + 28;
        maxW = Math.max(maxW, btnNeed);
      }
      if (col.type === "checkbox") maxW = Math.max(maxW, 88);
      return clampColW(maxW);
    }
    function ensureAutoWidths() {
      const sig = autoWidthSignature();
      if (autoWidthCache.sig === sig) return autoWidthCache.map;
      const map = Object.create(null);
      visibleCols().forEach((col) => {
        map[col.key] = measureAutoColWidth(col);
      });
      autoWidthCache = { sig, map };
      return map;
    }
    function headerMinWidth(col) {
      const fontBold = '400 14px "PingFang SC","Microsoft YaHei","Segoe UI",Calibri,Arial,sans-serif';
      ctx.font = fontBold;
      const label = String(labelOf(col) || col.key || "");
      const headerChrome = HEADER_PAD_X + HEADER_ICON_W + 4 + HEADER_MENU_W + 12;
      let minW = ctx.measureText(label).width + headerChrome;
      if (isActionColumn(col)) {
        ctx.font = '11px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
        minW = Math.max(minW, ctx.measureText("操作历史").width + CELL_PAD_X * 2 + 28);
      }
      return clampColW(minW);
    }
    function colWidth(col) {
      if (!col) return 120;
      if (sheet.widthOverrides[col.key] != null) {
        return clampColW(Math.max(sheet.widthOverrides[col.key], headerMinWidth(col)), { manual: true });
      }
      const autoMap = ensureAutoWidths();
      const autoW = autoMap[col.key] != null ? autoMap[col.key] : clampColW(col.width || 120);
      const custom = hooks().getColumnWidth?.(col.key);
      if (custom != null && custom !== "") {
        const n = Number(custom);
        if (!Number.isFinite(n) || n < COL_W_MIN) return autoW;
        return clampColW(Math.max(n, headerMinWidth(col)), { manual: true });
      }
      // 中文备注：未手动设宽时按表头+内容自适应撑开。
      return autoW;
    }
    function labelOf(col) {
      if (!col) return "";
      const cols = visibleCols();
      const sig = visibleColsMemo.sig;
      if (columnLabelCache.sig !== sig) {
        const map = Object.create(null);
        for (const visible of cols) {
          map[visible.key] = hooks().columnLabel?.(visible.key) || visible.label || visible.key;
        }
        columnLabelCache = { sig, map };
      }
      if (Object.prototype.hasOwnProperty.call(columnLabelCache.map, col.key)) {
        return columnLabelCache.map[col.key];
      }
      return hooks().columnLabel?.(col.key) || col.label || col.key;
    }
    function isActionColumn(col) {
      return !!col && (col.key === "actions" || col.type === "action");
    }

    function colIndexOf(key) {
      return visibleCols().findIndex((c) => c.key === key);
    }
    function clampRange(rg) {
      if (!rg) return null;
      const cols = visibleCols();
      const nRows = rows().length;
      if (!cols.length || nRows <= 0) return null;
      const r0 = Math.max(0, Math.min(nRows - 1, Math.min(rg.r0, rg.r1)));
      const r1 = Math.max(0, Math.min(nRows - 1, Math.max(rg.r0, rg.r1)));
      const c0 = Math.max(0, Math.min(cols.length - 1, Math.min(rg.c0, rg.c1)));
      const c1 = Math.max(0, Math.min(cols.length - 1, Math.max(rg.c0, rg.c1)));
      return { r0, r1, c0, c1 };
    }
    function makeRange(rA, cA, rB, cB) {
      return clampRange({
        r0: Math.min(rA, rB),
        r1: Math.max(rA, rB),
        c0: Math.min(cA, cB),
        c1: Math.max(cA, cB)
      });
    }
    function allRanges() {
      const list = [];
      const primary = clampRange(sheet.selection);
      if (primary) list.push(primary);
      for (const rg of sheet.ranges || []) {
        const n = clampRange(rg);
        if (n) list.push(n);
      }
      return list;
    }
    function isCellSelected(rowIndex, colKey) {
      const list = rows();
      const row = list[rowIndex];
      // 中文备注：分组头/间距不参与选区高亮，避免点表头整列时像把分组「折没」。
      if (isGroupRow(row) || isGroupSpacer(row)) return false;
      const ci = colIndexOf(colKey);
      if (ci < 0) return false;
      return allRanges().some((rg) => (
        rowIndex >= rg.r0 && rowIndex <= rg.r1 && ci >= rg.c0 && ci <= rg.c1
      ));
    }
    function selectionCellCount(rg) {
      if (!rg) return 0;
      return (rg.r1 - rg.r0 + 1) * (rg.c1 - rg.c0 + 1);
    }
    /** 中文备注：Ctrl 追加的多块单格选区也要计入「多选粘贴」；不能只看单块 ≥2 格。 */
    function totalSelectionCellCount(ranges = allRanges()) {
      return (ranges || []).reduce((sum, rg) => sum + selectionCellCount(rg), 0);
    }
    function clearSelection() {
      sheet.selection = null;
      sheet.ranges = [];
      sheet.selecting = null;
      sheet._selAnchor = null;
    }
    function setSingleCellSelection(rowIndex, colKey, { keepExtras = false } = {}) {
      const ci = colIndexOf(colKey);
      if (ci < 0) {
        sheet.active = { rowIndex, colKey };
        if (!keepExtras) clearSelection();
        if (!sheet.editing) focusGridCanvas();
        return;
      }
      sheet.active = { rowIndex, colKey };
      sheet.activeCol = "";
      sheet.headerSelection = null;
      sheet.selection = makeRange(rowIndex, ci, rowIndex, ci);
      sheet._selAnchor = { rowIndex, colIndex: ci };
      if (!keepExtras) sheet.ranges = [];
      if (!sheet.editing) focusGridCanvas();
    }
    function cellScreenRect(rowIndex, colKey) {
      const L = layout();
      const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === colKey);
      if (!meta) return null;
      const rect = cellEditorScreenRect(meta, rowIndex);
      if (!rect) return null;
      return { x: rect.screenX, y: rect.screenY, w: rect.w, h: rect.h, freeze: !!meta.col.freeze };
    }
    /** 中文备注：滚动列被冻结区遮住时，编辑框必须贴可见区域，不能沿用 meta.x-scrollLeft。 */
    function cellEditorScreenRect(meta, rowIndex) {
      if (!meta) return null;
      const L = layout();
      const ix = L.indexW ?? indexW();
      const clipLeft = ix + L.freezeW;
      const rh = rowH();
      const screenY = HEADER_H + rowIndex * rh - sheet.scrollTop;
      if (meta.col.freeze) {
        return { screenX: meta.x, screenY, w: meta.w, h: rh };
      }
      const rawX = meta.x - sheet.scrollLeft;
      if (rawX + meta.w <= clipLeft) return null;
      if (rawX >= clipLeft) {
        return { screenX: rawX, screenY, w: meta.w, h: rh };
      }
      return {
        screenX: clipLeft,
        screenY,
        w: Math.max(48, rawX + meta.w - clipLeft),
        h: rh
      };
    }
    function rangeScreenRect(rg) {
      const cols = visibleCols();
      if (!rg || !cols.length) return null;
      const leftCol = cols[rg.c0];
      const rightCol = cols[rg.c1];
      const left = cellScreenRect(rg.r0, leftCol.key);
      const right = cellScreenRect(rg.r1, rightCol.key);
      if (!left || !right) return null;
      const w = (right.x + right.w) - left.x;
      const h = (right.y + right.h) - left.y;
      if (!(w > 0 && h > 0) || !Number.isFinite(w) || !Number.isFinite(h)) return null;
      return { x: left.x, y: left.y, w, h };
    }
    /** 中文备注：大行数框选时 rect 可远超视口；与 clip 求交后再画，避免 canvas 异常。 */
    function intersectRect(a, b) {
      if (!a || !b) return null;
      const x = Math.max(a.x, b.x);
      const y = Math.max(a.y, b.y);
      const w = Math.min(a.x + a.w, b.x + b.w) - x;
      const h = Math.min(a.y + a.h, b.y + b.h) - y;
      if (!(w > 0 && h > 0) || !Number.isFinite(w) || !Number.isFinite(h)) return null;
      return { x, y, w, h };
    }
    const SELECTION_STATS_CELL_CAP = 2500;
    /** 中文备注：框选 ≥2 格时统计非空单元格数量。 */
    function computeSelectionStats(rg) {
      if (!rg || selectionCellCount(rg) < 2) return null;
      const cells = selectionCellCount(rg);
      if (cells > SELECTION_STATS_CELL_CAP) {
        const list = rows();
        let dataRows = 0;
        for (let r = rg.r0; r <= rg.r1; r++) {
          if (isDataRow(list[r])) dataRows += 1;
        }
        const colSpan = rg.c1 - rg.c0 + 1;
        return { count: null, approx: true, dataRows, colSpan, cells };
      }
      const cols = visibleCols();
      const list = rows();
      let count = 0;
      for (let r = rg.r0; r <= rg.r1; r++) {
        const row = list[r];
        if (!isDataRow(row)) continue;
        for (let c = rg.c0; c <= rg.c1; c++) {
          const col = cols[c];
          if (!col) continue;
          if (cellCopyValue(row, col).trim()) count += 1;
        }
      }
      return { count };
    }
    function hideSelectionStatPopup() {
      if (!selectionStatEl) return;
      selectionStatEl.classList.remove("show");
      selectionStatEl.setAttribute("aria-hidden", "true");
      selectionStatEl.textContent = "";
      selectionStatEl.style.left = "";
      selectionStatEl.style.top = "";
      selectionStatEl.style.visibility = "";
    }
    function updateSelectionStatPopup() {
      if (!selectionStatEl) return;
      if (sheet.editing || sheet.selecting || sheet.fillDragging || sheet.headerSelecting) {
        hideSelectionStatPopup();
        return;
      }
      const rg = clampRange(sheet.selection);
      const stats = computeSelectionStats(rg);
      if (!stats) {
        hideSelectionStatPopup();
        return;
      }
      selectionStatEl.textContent = stats.approx
        ? `已选 ${stats.dataRows} 行 × ${stats.colSpan} 列`
        : `计数: ${stats.count}`;
      selectionStatEl.classList.add("show");
      selectionStatEl.setAttribute("aria-hidden", "false");
      selectionStatEl.style.visibility = "hidden";
      const pw = selectionStatEl.offsetWidth;
      const ph = selectionStatEl.offsetHeight;
      selectionStatEl.style.visibility = "";
      const vr = viewport.getBoundingClientRect();
      const pad = 10;
      const scrollbarW = Math.max(0, viewport.offsetWidth - viewport.clientWidth);
      // 中文备注：钉在表格视口右下角（多维表格风格），不跟随选区矩形移动。
      const left = vr.right - pw - pad - scrollbarW;
      const top = vr.bottom - ph - pad;
      selectionStatEl.style.left = `${Math.round(left)}px`;
      selectionStatEl.style.top = `${Math.round(top)}px`;
    }
    function dataRowSegmentsInRange(rg) {
      // 中文备注：选区按连续数据行切段，跳过分组头/间距，避免整列框罩住分组。
      const list = rows();
      const segs = [];
      let start = -1;
      for (let r = rg.r0; r <= rg.r1; r++) {
        const row = list[r];
        const isData = row && !isGroupRow(row) && !isGroupSpacer(row);
        if (isData) {
          if (start < 0) start = r;
        } else if (start >= 0) {
          segs.push({ r0: start, r1: r - 1, c0: rg.c0, c1: rg.c1 });
          start = -1;
        }
      }
      if (start >= 0) segs.push({ r0: start, r1: rg.r1, c0: rg.c0, c1: rg.c1 });
      return segs;
    }
    function drawSelectionOverlays(W, H, L) {
      // 中文备注：单列表头点击只使用列底色；表头连续多选需要把数据区整列绘制为同一选区。
      if (sheet.activeCol && !sheet.headerSelection) return;
      const ranges = sheet.headerSelection
        ? [{
            r0: 0,
            r1: Math.max(0, rows().length - 1),
            c0: sheet.headerSelection.c0,
            c1: sheet.headerSelection.c1,
          }]
        : allRanges();
      if (!ranges.length) return;
      const ix = indexW();
      const clipParts = [
        { x: ix, y: HEADER_H, w: L.freezeW, h: Math.max(0, H - HEADER_H) },
        {
          x: ix + L.freezeW,
          y: HEADER_H,
          w: Math.max(0, W - ix - L.freezeW),
          h: Math.max(0, H - HEADER_H)
        }
      ];
      const primary = clampRange(sheet.selection);
      ranges.forEach((rg, idx) => {
        const segs = dataRowSegmentsInRange(rg);
        if (!segs.length) return;
        const isPrimary = primary
          && rg.r0 === primary.r0 && rg.r1 === primary.r1
          && rg.c0 === primary.c0 && rg.c1 === primary.c1
          && idx === 0;
        segs.forEach((seg) => {
          const rect = rangeScreenRect(seg);
          if (!rect) return;
          clipParts.forEach((clip) => {
            if (clip.w <= 0 || clip.h <= 0) return;
            const vis = intersectRect(rect, clip);
            if (!vis) return;
            ctx.save();
            ctx.beginPath();
            ctx.rect(clip.x, clip.y, clip.w, clip.h);
            ctx.clip();
            // 中文备注：浅色底 + 描边，对齐 Excel 框选观感（不盖死条件填色）。
            if (selectionCellCount(seg) >= 2) {
              ctx.fillStyle = COLORS.rangeFill;
              ctx.fillRect(vis.x, vis.y, vis.w, vis.h);
            }
            ctx.strokeStyle = COLORS.rangeBorder;
            ctx.lineWidth = isPrimary ? 2 : 1.5;
            ctx.strokeRect(vis.x + 0.5, vis.y + 0.5, Math.max(0, vis.w - 1), Math.max(0, vis.h - 1));
            ctx.restore();
          });
        });
      });
      // 中文备注：整列选中时不画填充柄，避免像「只选了某一格」；单元格选区仍保留。
      if (primary && !sheet.activeCol && !sheet.fillDragging) {
        const segs = dataRowSegmentsInRange(primary);
        const last = segs[segs.length - 1];
        const rect = last ? rangeScreenRect(last) : null;
        if (rect) {
          const hs = 7;
          const hx = rect.x + rect.w - hs / 2;
          const hy = rect.y + rect.h - hs / 2;
          if (hx > indexW() - 2 && hy > HEADER_H - 2 && hx < W && hy < H) {
            fillRect(hx, hy, hs, hs, COLORS.rangeHandle);
            ctx.strokeStyle = COLORS.white;
            ctx.lineWidth = 1;
            ctx.strokeRect(hx + 0.5, hy + 0.5, hs - 1, hs - 1);
          }
        }
      }
    }

    function primaryFillHandleRect() {
      if (sheet.activeCol || sheet.headerSelection || sheet.editing) return null;
      const primary = clampRange(sheet.selection);
      if (!primary) return null;
      const segs = dataRowSegmentsInRange(primary);
      const last = segs[segs.length - 1];
      const rect = last ? rangeScreenRect(last) : null;
      if (!rect) return null;
      const hs = 7;
      return { x: rect.x + rect.w - hs / 2, y: rect.y + rect.h - hs / 2, w: hs, h: hs, rect, range: primary };
    }

    function fillHandleHitTest(clientX, clientY) {
      const hit = primaryFillHandleRect();
      if (!hit) return null;
      const canvasRect = canvas.getBoundingClientRect();
      const x = clientX - canvasRect.left;
      const y = clientY - canvasRect.top;
      // 中文备注：命中区略放大，视觉角标仍保持 7px，降低精确拖拽难度。
      const pad = 5;
      if (x < hit.x - pad || x > hit.x + hit.w + pad || y < hit.y - pad || y > hit.y + hit.h + pad) return null;
      return hit;
    }

    function fillTargetRowFromPointer(clientX, clientY) {
      const source = sheet.fillDragging?.sourceRange;
      if (!source) return -1;
      const list = rows();
      if (!list.length) return source.r1;
      const axes = selectEdgeAxes(clientX, clientY);
      const { rowStart, rowEnd } = visibleBodyRowBounds();
      let raw;
      if (axes.vyDir > 0) raw = rowEnd;
      else if (axes.vyDir < 0) raw = rowStart;
      else raw = rowIndexFromClientY(clientY);
      let target = Math.max(source.r1, Math.min(list.length - 1, raw));
      if (!isDataRow(list[target])) {
        const next = findDataRowIndex(target, target >= source.r1 ? 1 : -1);
        if (next >= 0) target = next;
      }
      return target;
    }

    function fillPreviewRange() {
      const drag = sheet.fillDragging;
      if (!drag?.sourceRange || drag.targetRow == null) return null;
      return makeRange(drag.sourceRange.r0, drag.sourceRange.c0, drag.targetRow, drag.sourceRange.c1);
    }

    function fillPreviewSelection() {
      const drag = sheet.fillDragging;
      if (!drag || !drag.moved) return null;
      const preview = fillPreviewRange();
      if (!preview || preview.r1 <= drag.sourceRange.r1) return null;
      return preview;
    }

    function drawFillPreview() {
      const preview = fillPreviewSelection();
      if (!preview) return;
      const W = viewport.clientWidth;
      const H = viewport.clientHeight;
      const bodyClip = { x: 0, y: HEADER_H, w: W, h: Math.max(0, H - HEADER_H) };
      for (const seg of dataRowSegmentsInRange(preview)) {
        const rect = rangeScreenRect(seg);
        if (!rect) continue;
        const vis = intersectRect(rect, bodyClip);
        if (!vis) continue;
        ctx.save();
        ctx.fillStyle = "rgba(36, 104, 242, 0.08)";
        ctx.fillRect(vis.x, vis.y, vis.w, vis.h);
        ctx.strokeStyle = COLORS.rangeBorder;
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 3]);
        ctx.strokeRect(vis.x + 0.5, vis.y + 0.5, Math.max(0, vis.w - 1), Math.max(0, vis.h - 1));
        ctx.restore();
      }
    }
    function copySelectionPayload() {
      const ranges = sheet.headerSelection
        ? [{ r0: 0, r1: Math.max(0, rows().length - 1), c0: sheet.headerSelection.c0, c1: sheet.headerSelection.c1 }]
        : allRanges();
      if (!ranges.length) return { text: "", cellCount: 0, fields: [] };
      const cols = visibleCols();
      const rowList = rows();
      // 中文备注：Ctrl 多块单格复制 → 纵向 N 行，粘贴时可与目标 N 格 1:1 对齐。
      if (!sheet.headerSelection && ranges.length > 1) {
        const values = flattenSelectionValues(ranges, cols, rowList);
        return { text: values.join("\n"), cellCount: values.length, fields: [] };
      }
      let cellCount = 0;
      const parts = ranges.map((rg) => {
        const lines = [];
        for (let r = rg.r0; r <= rg.r1; r++) {
          const row = rows()[r];
          if (isGroupRow(row) || isGroupSpacer(row)) continue;
          const cells = [];
          for (let c = rg.c0; c <= rg.c1; c++) {
            const col = cols[c];
            // 中文备注：框选复制与单击复制统一走 cellCopyValue（含版权等 select 的 draft 值）。
            cells.push(row && col ? cellCopyValue(row, col) : "");
            if (row && col) cellCount += 1;
          }
          lines.push(cells.join("\t"));
        }
        return lines.join("\n");
      });
      const hr = sheet.headerSelection;
      const fields = hr ? cols.slice(hr.c0, hr.c1 + 1).map((col) => ({
        key: col.key,
        title: col.label || col.key,
        fieldType: col.type || "text",
        options: (hooks().getOptionObjects?.(col.key) || hooks().getOptions?.(col.optionsKey) || [])
      })) : [];
      return { text: parts.join("\n\n"), cellCount, fields };
    }

    function copySelectionText() {
      return copySelectionPayload().text;
    }

    function writeClipboardText(text, meta = {}) {
      let value = String(text ?? "");
      if (meta.fields?.length) {
        // 中文备注：使用 ASCII 标记，避免部分系统剪贴板清理不可见 Unicode 导致字段配置丢失。
        value += `\n[OPS_FIELDS]${encodeURIComponent(JSON.stringify(meta.fields))}[/OPS_FIELDS]`;
      }
      if (!value && !(Number(meta.cellCount) > 0)) return false;
      // 中文备注：优先走页面 onCopy（含 execCommand 兜底）；clipboard API 失败时也不至于静默丢内容。
      try {
        hooks().onCopy?.(value, { ...meta, clipboardText: String(text ?? ""), clipboardValue: value });
        return true;
      } catch (_) {
        /* fall through */
      }
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(value).catch(() => {});
        return true;
      }
      return false;
    }

    function copyCurrentSelection() {
      const payload = copySelectionPayload();
      if (!payload.text && payload.cellCount <= 0) return false;
      return writeClipboardText(payload.text, { cellCount: payload.cellCount });
    }

    function parseClipboardMatrix(text) {
      const raw = String(text ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").trimEnd();
      if (!raw) return [];
      return raw.split("\n").map((line) => line.split("\t").map((cell) => String(cell ?? "").trim()));
    }

    function parseClipboardFields(text) {
      const raw = String(text ?? "");
      const match = raw.match(/\n\[OPS_FIELDS\]([^\n]+)\[\/OPS_FIELDS\]\s*$/);
      if (!match) return { text: raw, fields: [] };
      try {
        const fields = JSON.parse(decodeURIComponent(match[1]));
        return { text: raw.slice(0, match.index), fields: Array.isArray(fields) ? fields : [] };
      } catch (_) {
        return { text: raw, fields: [] };
      }
    }

    /** 中文备注：仅空白表字段导入时把首行当字段名；已有表/纯数据粘贴不剥首行，避免「表头粘贴吃掉一行/一列」。 */
    function preparePasteMatrixFromClipboard(clipboard, matrix) {
      let nextMatrix = matrix;
      let fields = clipboard.fields.slice();
      if (fields.length || !sheet.headerSelection || matrix.length <= 1) {
        return { matrix: nextMatrix, fields };
      }
      // 非空白协作表：表头粘贴只写数据；若首行就是列标题（如 lxl全域）则自动剥掉。
      if (!hooks().showBottomAddRow?.()) {
        if (!fields.length && isColumnTargetPaste() && nextMatrix.length > 1) {
          nextMatrix = stripMatchingHeaderTitleRow(nextMatrix);
        }
        return { matrix: nextMatrix, fields };
      }
      const cols = visibleCols();
      const startCol = sheet.headerSelection?.c0 ?? 0;
      const headerLine = matrix[0] || [];
      let titleHits = 0;
      let checked = 0;
      for (let j = 0; j < headerLine.length; j++) {
        const col = cols[startCol + j];
        if (!col) break;
        checked++;
        const title = String(labelOf(col) || "").trim();
        const cell = String(headerLine[j] ?? "").trim();
        if (title && cell && title.toLowerCase() === cell.toLowerCase()) titleHits++;
      }
      if (checked > 0 && titleHits / checked >= 0.5) {
        return { matrix: nextMatrix, fields };
      }
      const nonEmpty = headerLine.map((cell) => String(cell ?? "").trim()).filter(Boolean);
      const numericLike = nonEmpty.filter((cell) => /^\d{8,}$/.test(cell)).length;
      if (nonEmpty.length && numericLike / nonEmpty.length >= 0.34) {
        return { matrix: nextMatrix, fields };
      }
      const inferred = headerLine.map((title, index) => {
        const rawValues = matrix.slice(1).map((line) => String(line?.[index] ?? "").trim()).filter(Boolean);
        const hasMulti = rawValues.some((value) => /[、,，;；|｜\/]/.test(value));
        const values = [...new Set(rawValues.flatMap((value) => value.split(/\s*[、,，;；|｜\/]+\s*/).map((part) => part.trim()).filter(Boolean)))];
        const hasRepeatedValue = rawValues.length > values.length || new Set(rawValues).size < rawValues.length;
        const inferredType = hasMulti ? "multi" : (hasRepeatedValue && values.length > 0 && values.length <= 20 ? "select" : "text");
        return {
          key: `inferred_${index}`,
          title: String(title || "").trim(),
          fieldType: inferredType,
          options: values
        };
      });
      const usable = inferred.filter((item) => item.title);
      if (!usable.length) return { matrix: nextMatrix, fields };
      fields = usable;
      nextMatrix = matrix.slice(1);
      return { matrix: nextMatrix, fields };
    }

    // 中文备注：≥24 行走 paste-batch 快路径；原 120 导致 100 行仍逐格+逐行保存卡顿。
    const BULK_PASTE_ROW_THRESHOLD = 24;

    function buildBulkPasteRows(matrix, startRow, startCol) {
      const cols = visibleCols();
      const rowList = rows();
      const out = [];
      const seenRowIds = new Set();
      let rowIndex = findDataRowIndex(startRow, 1);
      for (let i = 0; i < matrix.length; i++) {
        const line = matrix[i] || [];
        const cells = {};
        for (let j = 0; j < line.length; j++) {
          const col = asCellColumn(cols[startCol + j]);
          if (!col || !isPasteEditable(col)) continue;
          if (col.key === "bookId" && !hooks().showBottomAddRow?.()) continue;
          const matched = matchPasteValue(col, line[j]);
          if (matched == null) continue;
          if (matched === "" && !String(line[j] ?? "").trim()) continue;
          cells[col.key] = matched;
        }
        if (!Object.keys(cells).length) {
          rowIndex = nextPasteBuildRowIndex(rowIndex);
          continue;
        }
        const existing = rowIndex >= 0 && rowIndex < rowList.length ? rowList[rowIndex] : null;
        const tmpId = `tmp_${Date.now()}_${i}_${Math.random().toString(36).slice(2, 6)}`;
        let rowId = existing?.opsKey || "";
        if (rowId && seenRowIds.has(rowId)) rowId = "";
        const finalRowId = rowId || tmpId;
        if (rowId) seenRowIds.add(rowId);
        out.push({
          rowIndex,
          row: existing,
          rowId: finalRowId,
          opsKey: finalRowId,
          version: existing?.version || 1,
          cells,
          isNew: !rowId || !isDataRow(existing)
        });
        rowIndex = nextPasteBuildRowIndex(rowIndex);
      }
      return out;
    }

    /** 中文备注：单值 + 多块/大块选区 → 直接拼 paste-batch 行，避免 100+ 格逐格 map。 */
    function buildBulkPasteRowsFromSelection(ranges, rawValue) {
      const cols = visibleCols();
      const rowList = rows();
      const out = [];
      for (const rg of ranges) {
        for (let r = rg.r0; r <= rg.r1; r++) {
          const row = rowList[r];
          if (!row || !isDataRow(row)) continue;
          const cells = {};
          for (let c = rg.c0; c <= rg.c1; c++) {
            const col = asCellColumn(cols[c]);
            if (!col || !isPasteEditable(col)) continue;
            if (col.key === "bookId" && !hooks().showBottomAddRow?.()) continue;
            const matched = matchPasteValue(col, rawValue);
            if (matched == null) continue;
            if (matched === "" && !String(rawValue ?? "").trim()) continue;
            if (col.key === "screeningRating" && cellCopyValue(row, col).trim() && String(matched).trim()) continue;
            if (String(cellText(row, col) ?? "") === String(matched)) continue;
            cells[col.key] = matched;
          }
          if (!Object.keys(cells).length) continue;
          const tmpId = `tmp_${Date.now()}_${r}_${Math.random().toString(36).slice(2, 6)}`;
          out.push({
            rowIndex: r,
            row,
            rowId: row.opsKey || tmpId,
            opsKey: row.opsKey || tmpId,
            version: row.version || 1,
            cells,
            isNew: !isDataRow(row)
          });
        }
      }
      return out;
    }

    function countSelectionDataRows(ranges = allRanges()) {
      const rowList = rows();
      let count = 0;
      for (const rg of ranges) {
        for (let r = rg.r0; r <= rg.r1; r++) {
          if (isDataRow(rowList[r])) count++;
        }
      }
      return count;
    }

    /** 中文备注：按选区顺序（行优先）展开坐标，供多格 1:1 复制/粘贴。 */
    function flattenSelectionCellCoords(ranges, rowList) {
      const coords = [];
      for (const rg of ranges || []) {
        const r0 = Math.min(rg.r0, rg.r1);
        const r1 = Math.max(rg.r0, rg.r1);
        const c0 = Math.min(rg.c0, rg.c1);
        const c1 = Math.max(rg.c0, rg.c1);
        for (let r = r0; r <= r1; r++) {
          const row = rowList[r];
          if (!isDataRow(row)) continue;
          for (let c = c0; c <= c1; c++) coords.push({ r, c });
        }
      }
      return coords;
    }

    function flattenSelectionValues(ranges, cols, rowList) {
      return flattenSelectionCellCoords(ranges, rowList).map(({ r, c }) => {
        const col = cols[c];
        const row = rowList[r];
        return row && col ? cellCopyValue(row, col) : "";
      });
    }

    function flattenMatrixValues(matrix) {
      const out = [];
      for (const line of matrix || []) {
        for (const cell of line || []) out.push(String(cell ?? "").trim());
      }
      return out;
    }

    /** 中文备注：Excel/多维表格复制单格常带尾部空行；仍视为单值以便填满多格选区。 */
    function extractSinglePasteValue(matrix) {
      if (!matrix?.length) return null;
      const flat = flattenMatrixValues(matrix);
      const nonEmpty = flat.map((v) => String(v).trim()).filter(Boolean);
      if (!nonEmpty.length) return null;
      const distinct = [...new Set(nonEmpty)];
      if (distinct.length > 1) return null;
      if (matrix.length === 1 && matrix[0].length === 1) return distinct[0];
      const sameOrBlank = flat.every((v) => !String(v).trim() || String(v).trim() === distinct[0]);
      return sameOrBlank ? distinct[0] : null;
    }

    /** 中文备注：N 个值贴 N 个格（含 1×N→N×1 多对一列），按顺序赋值，不横向扩列。 */
    function shouldSequentialCellPaste(ranges, matrix, rowList, headerPaste) {
      if (headerPaste) return false;
      const targets = flattenSelectionCellCoords(ranges, rowList);
      const values = flattenMatrixValues(matrix);
      return targets.length >= 2 && targets.length === values.length;
    }

    /** 中文备注：选区行数 > 剪贴板行数 → 按剪贴板行循环填满选区（复制一行粘贴到百行）。 */
    function isMatrixRepeatIntoSelection(ranges, matrix, totalSelectedCells, headerPaste) {
      if (headerPaste || totalSelectedCells <= 1 || !matrix?.length) return false;
      if (matrix.length === 1 && matrix[0].length === 1) return false;
      return countSelectionDataRows(ranges) > matrix.length;
    }

    function buildBulkPasteRowsFromMatrixRepeat(ranges, matrix) {
      const cols = visibleCols();
      const rowList = rows();
      const out = [];
      let offset = 0;
      for (const rg of ranges) {
        for (let r = rg.r0; r <= rg.r1; r++) {
          const row = rowList[r];
          if (!row || !isDataRow(row)) continue;
          const line = matrix[offset % matrix.length] || [];
          offset += 1;
          const cells = {};
          for (let j = 0; j < line.length; j++) {
            const colIndex = rg.c0 + j;
            if (colIndex > rg.c1 || colIndex >= cols.length) break;
            const col = asCellColumn(cols[colIndex]);
            if (!col || !isPasteEditable(col)) continue;
            if (col.key === "bookId" && !hooks().showBottomAddRow?.()) continue;
            const matched = matchPasteValue(col, line[j]);
            if (matched == null) continue;
            if (matched === "" && !String(line[j] ?? "").trim()) continue;
            if (col.key === "screeningRating" && cellCopyValue(row, col).trim() && String(matched).trim()) continue;
            if (String(cellText(row, col) ?? "") === String(matched)) continue;
            cells[col.key] = matched;
          }
          if (!Object.keys(cells).length) continue;
          const tmpId = `tmp_${Date.now()}_${r}_${Math.random().toString(36).slice(2, 6)}`;
          out.push({
            rowIndex: r,
            row,
            rowId: row.opsKey || tmpId,
            opsKey: row.opsKey || tmpId,
            version: row.version || 1,
            cells,
            isNew: !isDataRow(row)
          });
        }
      }
      return out;
    }

    function isPasteEditable(col) {
      if (!col) return false;
      return isColumnClearable(col);
    }

    function isColumnClearable(col) {
      if (!col || isActionColumn(col)) return false;
      const t = effectiveCellType(col);
      return t === "text" || t === "number" || t === "select" || t === "multi"
        || t === "date" || t === "checkbox";
    }

    function columnClearRanges() {
      if (sheet.headerSelection) {
        return [{
          r0: 0,
          r1: Math.max(0, rows().length - 1),
          c0: sheet.headerSelection.c0,
          c1: sheet.headerSelection.c1
        }];
      }
      if (sheet.activeCol) {
        const ci = colIndexOf(sheet.activeCol);
        if (ci >= 0) {
          return [{ r0: 0, r1: Math.max(0, rows().length - 1), c0: ci, c1: ci }];
        }
      }
      return allRanges();
    }

    // 中文备注：评级/负责人等下拉，粘贴时按选项精确或忽略大小写匹配，避免脏值入库。
    function matchPasteValue(col, raw) {
      const text = String(raw ?? "").trim();
      if (!col) return null;
      const t = effectiveCellType(col);
      if (t === "checkbox") {
        return normalizeCheckboxPasteValue(raw);
      }
      if (t === "select") {
        if (!text) return "";
        const opts = (hooks().getOptions?.(col.optionsKey) || []).map((o) => String(o?.value ?? o?.label ?? o ?? "").trim()).filter(Boolean);
        const exact = opts.find((o) => o === text);
        if (exact != null) return exact;
        const lower = text.toLowerCase();
        const ci = opts.find((o) => o.toLowerCase() === lower);
        if (ci != null) return ci;
        // 中文备注：外部表格可能携带本表尚不存在的值，保留原文交给粘贴保存流程新增选项。
        return text;
      }
      if (t === "multi") {
        if (!text) return "";
        const opts = (hooks().getOptions?.(col.optionsKey) || []).map((o) => String(o?.value ?? o?.label ?? o ?? "").trim()).filter(Boolean);
        const parts = text.split(/\s*[、,，;；|｜\/]+\s*/).map((p) => p.trim()).filter(Boolean);
        const matched = [];
        for (const part of parts) {
          const exact = opts.find((o) => o === part);
          const hit = exact != null ? exact : opts.find((o) => o.toLowerCase() === part.toLowerCase());
          if (hit != null && !matched.includes(hit)) matched.push(hit);
        }
        // 中文备注：只要有任一值未匹配，就把整串视为一个新选项，避免“ 小米、华为、oppo ”被错误拆散。
        return matched.length === parts.length && matched.length ? matched.join("、") : text;
      }
      if (t === "date") {
        if (!text) return "";
        // 中文备注：日期列统一存到时分秒。
        const m = text.match(/^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2}(?::\d{2})?))?/);
        if (!m) return null;
        let time = m[2] || "00:00:00";
        if (time.length === 5) time += ":00";
        return `${m[1]} ${time}`;
      }
      return text;
    }

    async function pasteIntoCells(text) {
      if (typeof hooks().onPasteBegin === "function") hooks().onPasteBegin();
      try {
      const clipboard = parseClipboardFields(text);
      let matrix = parseClipboardMatrix(clipboard.text);
      if (!matrix.length) return 0;
      const preparedClip = preparePasteMatrixFromClipboard(clipboard, matrix);
      matrix = preparedClip.matrix;
      clipboard.fields = preparedClip.fields;
      if (matrix.length >= BULK_PASTE_ROW_THRESHOLD) {
        await new Promise((resolve) => {
          if (typeof requestAnimationFrame === "function") requestAnimationFrame(resolve);
          else setTimeout(resolve, 0);
        });
      }
      const cols = visibleCols();
      // 中文备注：字段元数据粘贴必须先确认，再扩充空白表行；取消时不得产生新行。
      if (clipboard.fields.length && typeof hooks().onPastePrepare === "function") {
        const prepared = await hooks().onPastePrepare(clipboard.fields, { matrixRows: matrix.length });
        if (prepared === false || prepared === "cancelled") return -1;
      }
      const headerPaste = isColumnTargetPaste();
      const ranges = allRanges();
      const totalSelectedCells = totalSelectionCellCount(ranges);
      const pasteValueEarly = extractSinglePasteValue(matrix);
      const singleValue = pasteValueEarly != null
        && flattenMatrixValues(matrix).filter((v) => String(v).trim()).length === 1;
      const matrixRepeat = isMatrixRepeatIntoSelection(ranges, matrix, totalSelectedCells, headerPaste);
      const selectionDataRows = matrixRepeat ? countSelectionDataRows(ranges) : 0;
      // 中文备注：表头粘贴永远以当前表头左侧列、首个数据行作为锚点；不能沿用上一次单元格 active，
      // 否则连续点击不同表头时可能落到旧列，且会按旧行号错误扩充空行。
      const pasteStartRow = headerPaste ? 0 : (sheet.active?.rowIndex ?? ranges[0]?.r0 ?? 0);
      const pasteStartCol = headerPaste
        ? columnPasteStartCol()
        : (sheet.active ? colIndexOf(sheet.active.colKey) : (ranges[0]?.c0 ?? 0));
      if (singleValue && totalSelectedCells > 1
        && totalSelectedCells >= BULK_PASTE_ROW_THRESHOLD
        && hooks().showBottomAddRow?.()
        && typeof hooks().onPasteBulkCommit === "function") {
        const bulkRows = buildBulkPasteRowsFromSelection(ranges, pasteValueEarly);
        if (bulkRows.length) {
          const result = await hooks().onPasteBulkCommit(bulkRows, clipboard.fields, {
            targetColKey: sheet.activeCol || cols[pasteStartCol]?.key || "",
            startColIndex: pasteStartCol,
            matrixRows: bulkRows.length,
            singleValueFill: true
          });
          if (result === false || result === "cancelled") return -1;
          return Number(result) || bulkRows.length;
        }
      }
      if (matrixRepeat
        && selectionDataRows >= BULK_PASTE_ROW_THRESHOLD
        && hooks().showBottomAddRow?.()
        && typeof hooks().onPasteBulkCommit === "function") {
        const bulkRows = buildBulkPasteRowsFromMatrixRepeat(ranges, matrix);
        if (bulkRows.length) {
          const result = await hooks().onPasteBulkCommit(bulkRows, clipboard.fields, {
            targetColKey: sheet.activeCol || cols[pasteStartCol]?.key || "",
            startColIndex: pasteStartCol,
            matrixRows: bulkRows.length,
            matrixRepeat: true
          });
          if (result === false || result === "cancelled") return -1;
          return Number(result) || bulkRows.length;
        }
      }
      if (matrix.length >= BULK_PASTE_ROW_THRESHOLD
        && !matrixRepeat
        && hooks().showBottomAddRow?.()
        && typeof hooks().onPasteBulkCommit === "function") {
        await ensureRowsForBulkMatrix(matrix, pasteStartRow);
        const bulkRows = buildBulkPasteRows(matrix, pasteStartRow, pasteStartCol);
        if (!bulkRows.length) return 0;
        const result = await hooks().onPasteBulkCommit(bulkRows, clipboard.fields, {
          targetColKey: sheet.activeCol || cols[pasteStartCol]?.key || "",
          startColIndex: pasteStartCol,
          matrixRows: matrix.length
        });
        if (result === false || result === "cancelled") return -1;
        return Number(result) || bulkRows.length;
      }
      if (typeof hooks().ensurePasteRows === "function") {
        await hooks().ensurePasteRows(matrix.length, pasteStartRow);
      }
      const rowList = rows();
      if (!cols.length || !rowList.length) return 0;

      const pasteValue = pasteValueEarly;
      const changesByCell = new Map();

      const tryWrite = (rowIndex, colIndex, raw) => {
        const col = asCellColumn(cols[colIndex]);
        const row = rowList[rowIndex];
        if (!row || isGroupRow(row) || isGroupSpacer(row) || !col || !isPasteEditable(col)) return;
        // 中文备注：系统表 book_id 是采集主键，只读且不能被外部粘贴覆盖；否则重复 id 会破坏整表关联。
        if (col.key === "bookId" && !hooks().showBottomAddRow?.()) return;
        const matched = matchPasteValue(col, raw);
        if (matched == null) return;
        // 中文备注：空串粘贴到空格无意义，跳过。
        if (matched === "" && !String(raw ?? "").trim()) return;
        // 中文备注：评级是内部只补空的字段；已有评级不允许被粘贴或填充覆盖。
        if (col.key === "screeningRating" && cellCopyValue(row, col).trim() && String(matched).trim()) return;
        // 中文备注：允许覆盖已有内容；值未变化时不生成保存请求，多选区重叠时同一格只提交一次。
        if (String(cellText(row, col) ?? "") === String(matched)) return;
        changesByCell.set(`${rowIndex}\u0000${col.key}`, { row, field: col.key, value: matched });
      };

      const commitChanges = async () => {
        const changes = [...changesByCell.values()];
        if (typeof hooks().onPasteCommit === "function") {
          const result = await hooks().onPasteCommit(changes, clipboard.fields, {
            targetColKey: sheet.activeCol || changes[0]?.field || "",
            startColIndex: pasteStartCol,
            matrixRows: matrix.length
          });
          if (result === false || result === "cancelled") return -1;
        } else {
          changes.forEach(({ row, field, value }) => hooks().onCommit?.(row, field, value));
        }
        return changes.length;
      };

      // 中文备注：剪贴板单值 + 多选区 → 填满选区内所有可编辑格（对齐 Excel 填充）。
      if (pasteValueEarly != null && totalSelectedCells > 1) {
        for (const rg of ranges) {
          for (let r = rg.r0; r <= rg.r1; r++) {
            for (let c = rg.c0; c <= rg.c1; c++) {
              tryWrite(r, c, pasteValueEarly);
            }
          }
        }
        return await commitChanges();
      }

      // 中文备注：N 值 → N 格按选区顺序 1:1；1×N 横复制贴到 N×1 纵列时不扩新列。
      if (shouldSequentialCellPaste(ranges, matrix, rowList, headerPaste)) {
        const targets = flattenSelectionCellCoords(ranges, rowList);
        const values = flattenMatrixValues(matrix);
        for (let i = 0; i < targets.length; i++) {
          tryWrite(targets[i].r, targets[i].c, values[i]);
        }
        return await commitChanges();
      }

      // 中文备注：复制一行（或多行）→ 选中更多行 → 按剪贴板行循环填满选区（对齐 Excel）。
      if (matrixRepeat) {
        let offset = 0;
        for (const rg of ranges) {
          for (let r = rg.r0; r <= rg.r1; r++) {
            const row = rowList[r];
            if (!isDataRow(row)) continue;
            const line = matrix[offset % matrix.length] || [];
            offset += 1;
            for (let j = 0; j < line.length; j++) {
              const colIndex = rg.c0 + j;
              if (colIndex > rg.c1 || colIndex >= cols.length) break;
              tryWrite(r, colIndex, line[j]);
            }
          }
        }
        return await commitChanges();
      }

      // 中文备注：多格选择的起点必须是选区左上角，不能取最后点击留下的 active（否则会从第三格开始粘贴）。
      const hasMultiSelection = ranges.some((rg) => selectionCellCount(rg) > 1);
      const startRow = headerPaste ? 0 : (hasMultiSelection ? (ranges[0]?.r0 ?? 0) : pasteStartRow);
      const startCol = headerPaste ? pasteStartCol : (hasMultiSelection ? (ranges[0]?.c0 ?? 0) : pasteStartCol);
      if (startCol < 0) return 0;

      // 中文备注：多行粘贴只落到数据行，跳过组头/间距，与 copySelectionText 对称，避免错行。
      let rowIndex = findDataRowIndex(startRow, 1);
      for (let i = 0; i < matrix.length; i++) {
        if (rowIndex < 0 || rowIndex >= rowList.length) break;
        const line = matrix[i] || [];
        for (let j = 0; j < line.length; j++) {
          const colIndex = startCol + j;
          if (colIndex >= cols.length) break;
          tryWrite(rowIndex, colIndex, line[j]);
        }
        rowIndex = findDataRowIndex(rowIndex + 1, 1);
      }
      return await commitChanges();
      } finally {
        if (typeof hooks().onPasteEnd === "function") hooks().onPasteEnd();
      }
    }

    function collectFillChanges(sourceInput, lastCommittedTargetRow, targetRow) {
      const source = clampRange(sourceInput);
      const toRow = Number(targetRow);
      const fromRow = Number(lastCommittedTargetRow);
      const list = rows();
      const cols = visibleCols();
      if (!source || !list.length || !cols.length || !Number.isInteger(toRow) || toRow <= source.r1) {
        return { changes: [], targetRows: [] };
      }
      if (!Number.isInteger(fromRow) || toRow <= fromRow) return { changes: [], targetRows: [] };

      const sourceRows = [];
      for (let r = source.r0; r <= source.r1; r++) {
        if (isDataRow(list[r])) sourceRows.push(r);
      }
      if (!sourceRows.length) return { changes: [], targetRows: [] };

      const allTargetRows = [];
      for (let r = source.r1 + 1; r <= toRow; r++) {
        if (isDataRow(list[r])) allTargetRows.push(r);
      }
      const targetRows = allTargetRows.filter((r) => r > fromRow);
      if (!targetRows.length) return { changes: [], targetRows: [] };

      const changesByCell = new Map();
      for (const targetIndex of targetRows) {
        const offset = allTargetRows.indexOf(targetIndex);
        const sourceIndex = sourceRows[offset % sourceRows.length];
        const sourceRow = list[sourceIndex];
        const target = list[targetIndex];
        for (let c = source.c0; c <= source.c1; c++) {
          const col = asCellColumn(cols[c]);
          if (!col || !isPasteEditable(col)) continue;
          if (col.key === "bookId" && !hooks().showBottomAddRow?.()) continue;
          const raw = cellCopyValue(sourceRow, col);
          const matched = matchPasteValue(col, raw);
          if (matched == null || (matched === "" && !raw.trim())) continue;
          if (col.key === "screeningRating" && cellCopyValue(target, col).trim() && String(matched).trim()) continue;
          if (String(cellText(target, col) ?? "") === String(matched)) continue;
          changesByCell.set(`${targetIndex}\u0000${col.key}`, { row: target, field: col.key, value: matched });
        }
      }
      return { changes: [...changesByCell.values()], targetRows, source };
    }

    async function commitFillDrag(drag, { finalize = false } = {}) {
      if (!drag?.sourceRange || !drag.moved) return 0;
      const targetRow = Number(drag.targetRow);
      const lastCommitted = Number.isInteger(drag.lastCommittedTargetRow)
        ? drag.lastCommittedTargetRow
        : drag.sourceRange.r1;
      const { changes, targetRows, source } = collectFillChanges(drag.sourceRange, lastCommitted, targetRow);
      const cols = visibleCols();
      // 中文备注：边拖边写后，末批(<20 行)可能已在 accum 里且 changes 为空；松手仍须 finalize 落库。
      const shouldCommit = changes.length > 0 || (finalize && typeof hooks().onPasteCommit === "function");
      if (!shouldCommit) {
        if (targetRow > lastCommitted) drag.lastCommittedTargetRow = targetRow;
        return 0;
      }
      if (typeof hooks().onPasteCommit === "function") {
        const targetColKey = cols[source?.c0 ?? drag.sourceRange.c0]?.key || "";
        const result = await hooks().onPasteCommit(changes, [], {
          pasteBatchId: drag.pasteBatchId,
          targetColKey,
          matrixRows: targetRows.length,
          fillHandle: true,
          fillHandleIncremental: !finalize,
          fillHandleFinalize: finalize,
          sourceRange: { ...(source || drag.sourceRange) },
          targetRow
        });
        if (result === false || result === "cancelled" || Number(result) < 0) return -1;
        drag.lastCommittedTargetRow = targetRow;
        if (changes.length) {
          drag.totalWritten = Number(drag.totalWritten || 0) + changes.length;
        } else if (finalize && Number(result) > 0) {
          drag.totalWritten = Number(drag.totalWritten || 0) + Number(result);
        }
        return Number(result) || changes.length;
      }
      changes.forEach(({ row, field, value }) => hooks().onCommit?.(row, field, value));
      drag.lastCommittedTargetRow = targetRow;
      drag.totalWritten = Number(drag.totalWritten || 0) + changes.length;
      return changes.length;
    }

    function enqueueFillCommit(drag, { finalize = false } = {}) {
      drag._commitTail = (drag._commitTail || Promise.resolve()).then(() => commitFillDrag(drag, { finalize }));
      return drag._commitTail;
    }

    function maybeLiveFillCommit(drag) {
      if (!drag?.moved || drag.targetRow <= drag.lastCommittedTargetRow) return;
      const pendingRows = drag.targetRow - drag.lastCommittedTargetRow;
      if (pendingRows >= FILL_LIVE_COMMIT_ROWS) enqueueFillCommit(drag, { finalize: false });
    }

    /** @deprecated 保留别名，测试/脚本仍可能引用。 */
    async function fillIntoCells(dragInput = sheet.fillDragging) {
      return commitFillDrag(dragInput, { finalize: true });
    }

    function layout(list) {
      const rowList = list || rows();
      const woSig = Object.keys(sheet.widthOverrides).sort().map((k) => `${k}:${sheet.widthOverrides[k]}`).join("|");
      // 中文备注：scroll 不参与 layout 签名，否则每帧滚动都重算列宽/冻结区，3k 行明显掉帧。
      const sig = `${rowList.length}|${woSig}|${visibleColsMemo.sig || ""}`;
      if (layoutMemo.sig === sig && layoutMemo.value) return layoutMemo.value;
      const loadedCount = rowList.length;
      const ix = indexW(rowList);
      const cols = visibleCols();
      const freezeCols = cols.filter((c) => c.freeze);
      const scrollCols = cols.filter((c) => !c.freeze);
      let freezeW = 0;
      const freezeMeta = freezeCols.map((c) => {
        const w = colWidth(c);
        const meta = { col: c, x: ix + freezeW, w };
        freezeW += w;
        return meta;
      });
      let scrollW = 0;
      const scrollMeta = scrollCols.map((c) => {
        const w = colWidth(c);
        const meta = { col: c, x: ix + freezeW + scrollW, w };
        scrollW += w;
        return meta;
      });
      const rowCount = rowList.length;
      const contentW = ix + freezeW + scrollW;
      const hasBottomAddRow = !!hooks().showBottomAddRow?.();
      const contentH = HEADER_H + (rowCount + (hasBottomAddRow ? 1 : 0)) * rowH();
      const grouped = typeof hooks().displayHasGroupRows === "function"
        ? !!hooks().displayHasGroupRows()
        : hasGroupRows(rowList);
      const result = {
        cols, freezeCols, scrollCols, freezeMeta, scrollMeta, freezeW, scrollW, contentW, contentH,
        rowCount, loadedCount, indexW: ix, hasBottomAddRow, hasGroupRows: grouped
      };
      layoutMemo = { sig, value: result };
      return result;
    }

    function scheduleDraw() {
      if (sheet.raf) return;
      sheet.raf = requestAnimationFrame(() => {
        sheet.raf = 0;
        // 中文备注：拖宽时每帧只同步一次内容宽度（滚动条范围随新列宽变化），其余事件不再触发布局/回流。
        if (sheet.resizing) syncSizerAndClampScroll();
        draw();
      });
    }

    /**
     * 中文备注：筛选增删 / 静默刷新会瞬间缩短展示行数；若只改数据不立刻钳 scroll，
     * 下一次 scroll 帧会拿旧 scrollTop 去画（rowStart > 末行）→ 表体全白。
     * 滚动路径也必须按当前行数同步 sizer 并钳位，不能只依赖 resize()。
     */
    let lastSizerW = 0;
    let lastSizerH = 0;
    function syncSizerAndClampScroll(list) {
      const rowList = list || rows();
      const L = layout(rowList);
      const w = Math.max(1, viewport.clientWidth);
      const h = Math.max(1, viewport.clientHeight);
      const nextW = Math.max(w, L.contentW);
      const nextH = Math.max(h, L.contentH);
      if (nextW !== lastSizerW) {
        sizer.style.width = nextW + "px";
        lastSizerW = nextW;
      }
      if (nextH !== lastSizerH) {
        sizer.style.height = nextH + "px";
        lastSizerH = nextH;
      }
      sizer.style.marginTop = "";
      const maxTop = Math.max(0, nextH - h);
      const maxLeft = Math.max(0, nextW - w);
      if (viewport.scrollTop > maxTop) viewport.scrollTop = maxTop;
      if (viewport.scrollLeft > maxLeft) viewport.scrollLeft = maxLeft;
      if (viewport.scrollTop < 0) viewport.scrollTop = 0;
      if (viewport.scrollLeft < 0) viewport.scrollLeft = 0;
      sheet.scrollTop = viewport.scrollTop;
      sheet.scrollLeft = viewport.scrollLeft;
      return L;
    }

    let syncScrollGuard = false;
    let scrollPaintRaf = 0;
    let scrollGestureEndTimer = 0;
    function markScrollGesture() {
      sheet._scrollUntil = performance.now() + 120;
      clearTimeout(scrollGestureEndTimer);
      scrollGestureEndTimer = setTimeout(() => {
        scrollGestureEndTimer = 0;
        sheet._scrollUntil = 0;
        scheduleDraw();
      }, 130);
    }
    function flushScrollPaint() {
      scrollPaintRaf = 0;
      syncSizerAndClampScroll();
      if (sheet.editing) positionEditor();
      if (sheet.raf) {
        cancelAnimationFrame(sheet.raf);
        sheet.raf = 0;
      }
      markScrollGesture();
      draw();
    }
    function scheduleScrollPaint() {
      if (sheet.colDragging || sheet.resizing) {
        if (scrollPaintRaf) {
          cancelAnimationFrame(scrollPaintRaf);
          scrollPaintRaf = 0;
        }
        syncSizerAndClampScroll();
        if (sheet.editing) positionEditor();
        if (sheet.raf) {
          cancelAnimationFrame(sheet.raf);
          sheet.raf = 0;
        }
        draw();
        return;
      }
      if (scrollPaintRaf) return;
      scrollPaintRaf = requestAnimationFrame(flushScrollPaint);
    }
    function syncScroll() {
      if (syncScrollGuard) {
        sheet.scrollLeft = viewport.scrollLeft;
        sheet.scrollTop = viewport.scrollTop;
        return;
      }
      syncScrollGuard = true;
      try {
        sheet.scrollLeft = viewport.scrollLeft;
        sheet.scrollTop = viewport.scrollTop;
        scheduleScrollPaint();
      } finally {
        syncScrollGuard = false;
      }
    }

    function resize() {
      invalidateLayoutCache();
      const dpr = Math.max(1, Math.min(2, window.devicePixelRatio || 1));
      sheet.dpr = dpr;
      const w = Math.max(1, viewport.clientWidth);
      const h = Math.max(1, viewport.clientHeight);
      canvas.style.width = w + "px";
      canvas.style.height = h + "px";
      // 中文备注：只改缓冲尺寸；可见 canvas 等 draw 成功后再对齐，避免 resize/刷新瞬间白屏。
      const bw = Math.floor(w * dpr);
      const bh = Math.floor(h * dpr);
      if (buffer.width !== bw || buffer.height !== bh) {
        buffer.width = bw;
        buffer.height = bh;
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      syncSizerAndClampScroll();
      scheduleDraw();
    }

    /** 中文备注：补页只变行数/滚动高度，勿整段 resize canvas（与 drain 叠加会卡滚动）。 */
    function syncContentSize() {
      // 中文备注：layout() 签名含行数/列宽/列集，无需全量失效缓存——
      // 流式补行时每批 invalidate 会连带清 autoWidthCache，65 列表头每帧重测，滚动直接掉帧。
      syncSizerAndClampScroll();
      scheduleDraw();
    }

    let contentSyncRaf = 0;
    /** 中文备注：流式加载每 128 行一批且一秒可达 70 批；批内只挂数据，内容高度/重绘 RAF 合并成一帧一次。 */
    function scheduleContentSizeSync() {
      if (contentSyncRaf) return;
      contentSyncRaf = requestAnimationFrame(() => {
        contentSyncRaf = 0;
        syncContentSize();
      });
    }

    function ellipsize(text, maxW, font) {
      const t = String(text ?? "");
      if (!t) return "";
      ctx.font = font;
      if (ctx.measureText(t).width <= maxW) return t;
      const dots = "…";
      let lo = 0;
      let hi = t.length;
      while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (ctx.measureText(t.slice(0, mid) + dots).width <= maxW) lo = mid;
        else hi = mid - 1;
      }
      return t.slice(0, lo) + dots;
    }

    function cellText(row, col) {
      if (!row || !col || isGroupRow(row) || isGroupSpacer(row)) return "";
      if (isActionColumn(col)) return "操作历史";
      if (col.type === "formula") {
        // 中文备注：公式列只读展示，由页面 hooks 按当前行字段求值。
        try {
          return String(hooks().getFormulaValue?.(row, col) ?? "");
        } catch (_) {
          return "#ERR!";
        }
      }
      if (col.key === "seriesName") {
        const draft = hooks().getDraft?.(row) || {};
        if (Object.prototype.hasOwnProperty.call(draft, "seriesName")) {
          return String(draft.seriesName ?? "");
        }
        return String(row.seriesName || "");
      }
      if (col.key === "bookId") {
        // 中文备注：ADX 热榜运营键是 name-名称，表头 book_id 只展示匹配到的真实 id。
        if (row.dramaType === "adx") {
          return String(row.bookId || "").trim();
        }
        return String(row.bookId || row.opsKey || "");
      }
      if (col.key === "playCountAdd") {
        return String(row.playCountAddText || row.playCountAdd || "").trim();
      }
      if (col.key === "playCount") {
        return String(row.playCountText || row.playCount || "").trim();
      }
      // 中文备注：可编辑字段（版权/负责人/评级等）优先 draft，保证框选复制与标签展示一致。
      const draft = hooks().getDraft?.(row) || {};
      if (Object.prototype.hasOwnProperty.call(draft, col.key)) {
        const dv = draft[col.key];
        return dv == null || String(dv).trim() === "" ? "" : String(dv);
      }
      if (["workId", "account", "publishAwemeUser", "isFocus", "categoryText",
           "estimatePublishTime", "publishTime", "createTime", "timeDiffMinutes",
           "onlineTime", "crawlUpdateTime", "episodeAmount"].includes(col.key)) {
        // 中文备注：空值一律留白，不再用 “-” 占位（四表通用）。
        const v = row[col.key];
        return v == null || String(v).trim() === "" ? "" : String(v);
      }
      const v = row[col.key];
      return v == null || String(v).trim() === "" ? "" : String(v);
    }

    function cellCopyValue(row, col) {
      if (!row || !col) return "";
      if (col.key === "seriesName") {
        const draft = hooks().getDraft?.(row) || {};
        if (Object.prototype.hasOwnProperty.call(draft, "seriesName")) {
          return String(draft.seriesName ?? "").trim();
        }
        return String(row.seriesName || "").trim();
      }
      if (col.key === "bookId") {
        if (row.dramaType === "adx") return String(row.bookId || "").trim();
        return String(row.bookId || row.opsKey || "").trim();
      }
      if (col.type === "copy") {
        const v = col.key === "bookId" ? (row.bookId || row.opsKey) : row[col.key];
        return String(v || "").trim();
      }
      return cellText(row, col).trim();
    }

    function fillRect(x, y, w, h, color) {
      if (!(w > 0 && h > 0) || !Number.isFinite(x + y + w + h)) return;
      ctx.fillStyle = color;
      ctx.fillRect(x, y, w, h);
    }

    // 中文备注：圆角矩形（可选描边），用于操作按钮/徽标等细节，画面更柔和。
    function drawRoundRect(x, y, w, h, r, fill, stroke) {
      const radius = Math.min(r, w / 2, h / 2);
      ctx.beginPath();
      ctx.moveTo(x + radius, y);
      ctx.arcTo(x + w, y, x + w, y + h, radius);
      ctx.arcTo(x + w, y + h, x, y + h, radius);
      ctx.arcTo(x, y + h, x, y, radius);
      ctx.arcTo(x, y, x + w, y, radius);
      ctx.closePath();
      if (fill) { ctx.fillStyle = fill; ctx.fill(); }
      if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 1; ctx.stroke(); }
    }

    function strokeGrid(x, y, w, h, color) {
      // 中文备注：多维表格式——每格只画上边+左边,相邻格共享边只画一次;逐格四边矩形会让
      // 共享边出现 1px 错位(重边/缝隙),视觉粗细不均。网格最外圈右/下边由网格级补线负责。
      ctx.strokeStyle = color || COLORS.grid;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, y + 0.5);
      ctx.lineTo(x + w, y + 0.5);
      ctx.moveTo(x + 0.5, y);
      ctx.lineTo(x + 0.5, y + h);
      ctx.stroke();
    }

    function drawText(text, x, y, w, h, { align = "left", color = COLORS.text, bold = false, size = 14, pad = CELL_PAD_X } = {}) {
      const font = `${bold ? "600 " : ""}${size}px "PingFang SC","Microsoft YaHei","Segoe UI",Calibri,Arial,sans-serif`;
      const shown = ellipsize(text, Math.max(8, w - pad * 2), font);
      ctx.fillStyle = color;
      ctx.font = font;
      ctx.textBaseline = "middle";
      if (align === "center") {
        ctx.textAlign = "center";
        ctx.fillText(shown, x + w / 2, y + h / 2 + 0.5);
      } else if (align === "right") {
        ctx.textAlign = "right";
        ctx.fillText(shown, x + w - pad, y + h / 2 + 0.5);
      } else {
        ctx.textAlign = "left";
        ctx.fillText(shown, x + pad, y + h / 2 + 0.5);
      }
      ctx.textAlign = "left";
    }

    function headerIconKind(col) {
      if (!col) return "text";
      if (isActionColumn(col)) return "action";
      if (col.type === "lookup") {
        const et = effectiveCellType(col);
        if (et === "select" || et === "multi") return "select";
        if (et === "date") return "date";
        if (et === "checkbox") return "bool";
        return "text";
      }
      if (col.type === "select" || col.type === "multi") return "select";
      if (col.type === "date" || col.valueType === "date") return "date";
      if (col.type === "checkbox") return "bool";
      if (col.valueType === "number") return "number";
      if (col.key === "isFocus") return "bool";
      // 中文备注：字段配置已显式改成 text 时，不再按字段名硬编码日期/数字图标（与筛选值类型同源）。
      if (col.type === "text") {
        if (/Url|Link|url|link|云盘|网盘|IAA|IAP/.test(col.key + (col.label || ""))) return "link";
        return "text";
      }
      if (col.key === "copyrightOwner") return "tag";
      if (col.key === "categoryText") return "text";
      if (col.key === "estimatePublishTime" || col.key === "publishTime"
          || col.key === "createTime" || col.key === "onlineTime" || col.key === "crawlUpdateTime") {
        return "date";
      }
      if (col.key === "episodeAmount" || col.key === "materialCount" || col.key === "priorityLevel"
          || col.key === "playCountAdd" || col.key === "playCount" || col.key === "timeDiffMinutes") {
        return "number";
      }
      if (col.type === "copy" || col.copyBtn || /Url|Link|url|link|云盘|网盘/.test(col.key + (col.label || ""))) {
        if (/Url|Link|url|link|云盘|网盘|IAA|IAP/.test(col.key + (col.label || ""))) return "link";
      }
      return "text";
    }

    function drawHeaderTypeIcon(kind, cx, cy, color) {
      ctx.save();
      ctx.strokeStyle = color;
      ctx.fillStyle = color;
      ctx.lineWidth = 1.4;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      const x = cx - 6;
      const y = cy - 6;
      if (kind === "select") {
        ctx.strokeRect(x + 1, y + 1, 10, 10);
        ctx.beginPath();
        ctx.moveTo(x + 3, y + 4.5);
        ctx.lineTo(x + 6, y + 7.5);
        ctx.lineTo(x + 9, y + 4.5);
        ctx.stroke();
      } else if (kind === "date") {
        ctx.strokeRect(x + 1, y + 2, 10, 9);
        ctx.beginPath();
        ctx.moveTo(x + 1, y + 5);
        ctx.lineTo(x + 11, y + 5);
        ctx.moveTo(x + 4, y + 1);
        ctx.lineTo(x + 4, y + 3.5);
        ctx.moveTo(x + 8, y + 1);
        ctx.lineTo(x + 8, y + 3.5);
        ctx.stroke();
      } else if (kind === "number") {
        ctx.font = '700 10px "Segoe UI",Arial,sans-serif';
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText("#", cx, cy + 0.5);
      } else if (kind === "bool") {
        ctx.strokeRect(x + 1.5, y + 1.5, 9, 9);
        ctx.beginPath();
        ctx.moveTo(x + 3.5, y + 6);
        ctx.lineTo(x + 5.5, y + 8);
        ctx.lineTo(x + 9.5, y + 3.5);
        ctx.stroke();
      } else if (kind === "tag") {
        ctx.beginPath();
        ctx.moveTo(x + 1, y + 3);
        ctx.lineTo(x + 7, y + 1);
        ctx.lineTo(x + 11, y + 6);
        ctx.lineTo(x + 5, y + 11);
        ctx.closePath();
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(x + 4.2, y + 4.2, 1, 0, Math.PI * 2);
        ctx.fill();
      } else if (kind === "link") {
        ctx.beginPath();
        ctx.arc(x + 4, y + 4, 2.4, 0, Math.PI * 2);
        ctx.arc(x + 8, y + 8, 2.4, 0, Math.PI * 2);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(x + 5.2, y + 5.2);
        ctx.lineTo(x + 6.8, y + 6.8);
        ctx.stroke();
      } else if (kind === "action") {
        ctx.beginPath();
        ctx.moveTo(x + 3, y + 2);
        ctx.lineTo(x + 10, y + 6);
        ctx.lineTo(x + 3, y + 10);
        ctx.closePath();
        ctx.stroke();
      } else {
        // text / Ab
        ctx.font = '700 9px "Segoe UI",Arial,sans-serif';
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText("Ab", cx, cy + 0.5);
      }
      ctx.restore();
    }

    function drawHeaderSyncSpinner(cx, cy) {
      const start = (performance.now() / 1000) * 6;
      ctx.save();
      ctx.strokeStyle = "#409eff";
      ctx.lineWidth = 1.8;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.arc(cx, cy, 5, start, start + Math.PI * 1.35);
      ctx.stroke();
      ctx.restore();
    }

    function drawHeaderMenuIcon(x, y, w, h, active) {
      // 中文备注：多维表格行为——菜单箭头仅 hover/选中时出现,常驻会显得嘈杂。
      if (!active && !sheet.activeCol) return;
      const cx = x + w / 2;
      const cy = y + h / 2;
      ctx.save();
      ctx.strokeStyle = active ? COLORS.select : COLORS.headerMenu;
      ctx.globalAlpha = active ? 1 : 0.55;
      ctx.lineWidth = 1.5;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.beginPath();
      ctx.moveTo(cx - 3.5, cy - 1);
      ctx.lineTo(cx, cy + 2.5);
      ctx.lineTo(cx + 3.5, cy - 1);
      ctx.stroke();
      ctx.restore();
    }

    function cellAlign(col) {
      if (!col) return "left";
      if (col.key === "episodeAmount" || col.key === "materialCount" || col.key === "isFocus") return "center";
      return "left";
    }

    function isFindHit(rowIndex, colKey) {
      return sheet.findHitKeys.has(`${rowIndex}\u0000${colKey}`);
    }
    function isFindActive(rowIndex, colKey) {
      const hit = sheet.findHits[sheet.findIndex];
      return !!(hit && hit.rowIndex === rowIndex && hit.colKey === colKey);
    }

    function drawHeaderCell(meta, screenX) {
      const { col, w } = meta;
      const hot = sheet.headerHotKey === col.key;
      const selected = !!((sheet.headerSelection && colIndexOf(col.key) >= sheet.headerSelection.c0 && colIndexOf(col.key) <= sheet.headerSelection.c1) || sheet.activeCol === col.key);
      const fill = hooks().getColumnFill?.(col.key) || "";
      let bg = hot ? COLORS.headerHot : COLORS.headerBg;
      if (fill) bg = fill;
      else if (selected && !sheet.colDragging) bg = COLORS.headerSelectedBg;
      fillRect(screenX, 0, w, HEADER_H, bg);
      strokeGrid(screenX, 0, w, HEADER_H, COLORS.gridStrong);

      const iconX = screenX + HEADER_PAD_X + HEADER_ICON_W / 2;
      const iconY = HEADER_H / 2;
      drawHeaderTypeIcon(headerIconKind(col), iconX, iconY, selected ? COLORS.headerSelectedText : COLORS.headerIcon);

      // 中文备注：右侧菜单区常驻占位，hover 只加深颜色，不挤压标题。
      const menuX = screenX + w - HEADER_MENU_W - 2;
      const syncing = col.type === "lookup" && !!hooks().isLookupColumnSyncing?.(col.key);
      if (syncing) {
        drawHeaderSyncSpinner(menuX - 9, HEADER_H / 2);
      }
      // 中文备注：拖宽/拖列进行中隐藏下拉箭头(对齐主流多维表格——拖拽态不出现菜单入口,也不误提示可点)。
      if (!sheet.resizing && !sheet.colDragging) {
        drawHeaderMenuIcon(menuX, 0, HEADER_MENU_W, HEADER_H, hot);
      }

      let label = labelOf(col);
      if (hooks().sortField?.() === col.key) {
        const dir = hooks().sortDir?.() || "asc";
        label = `${label}${dir === "desc" ? " ↓" : " ↑"}`;
      }
      const textX = screenX + HEADER_PAD_X + HEADER_ICON_W + 4;
      const textW = Math.max(12, menuX - textX - 2);
      // 中文备注：主流多维表格实测表头文字——14px 常规字重深色 #1F2329,选中整列转蓝。
      drawText(label, textX, 0, textW, HEADER_H, {
        align: "left", bold: false, color: selected ? COLORS.headerSelectedText : COLORS.headerText, size: 14, pad: 0
      });
    }

    function isCheckboxOn(value) {
      const text = String(value ?? "").trim();
      if (!text) return false;
      const v = text.toLowerCase();
      return v === "是" || v === "true" || v === "1" || v === "yes" || v === "y"
        || v === "✓" || v === "√" || v === "☑" || v === "✔"
        || v === "勾选" || v === "已勾选" || v === "选中" || v === "对";
    }

    /** 中文备注：表头/整列粘贴复选框时，兼容 Excel/多维表格/WPS 常见勾选对号写法。 */
    function normalizeCheckboxPasteValue(raw) {
      const text = String(raw ?? "").trim();
      if (!text) return "";
      if (isCheckboxOn(text)) return "是";
      const lower = text.toLowerCase();
      if (["否", "false", "0", "no", "n", "off", "unchecked", "未勾选", "未选中", "未选", "无"].includes(lower)) {
        return "";
      }
      if (["×", "✗", "✘", "✕"].includes(text)) return "";
      return null;
    }

    function columnPasteStartCol() {
      if (sheet.headerSelection) return sheet.headerSelection.c0 ?? 0;
      if (sheet.activeCol) {
        const ci = colIndexOf(sheet.activeCol);
        if (ci >= 0) return ci;
      }
      return 0;
    }

    /** 中文备注：表头选中整列（含仅 activeCol）时，粘贴从第 1 条数据行起纵向填充。 */
    function isColumnTargetPaste() {
      if (sheet.headerSelection) return true;
      if (!sheet.activeCol || sheet.active) return false;
      const ranges = sheet.ranges || [];
      if (ranges.some((rg) => selectionCellCount(rg) > 1)) return false;
      return true;
    }

    function stripMatchingHeaderTitleRow(matrix) {
      if (!Array.isArray(matrix) || matrix.length <= 1) return matrix;
      const cols = visibleCols();
      const startCol = columnPasteStartCol();
      const headerLine = matrix[0] || [];
      let titleHits = 0;
      let checked = 0;
      for (let j = 0; j < headerLine.length; j++) {
        const col = cols[startCol + j];
        if (!col) break;
        checked++;
        const title = String(labelOf(col) || "").trim();
        const cell = String(headerLine[j] ?? "").trim();
        if (title && cell && title.toLowerCase() === cell.toLowerCase()) titleHits++;
      }
      if (checked > 0 && titleHits / checked >= 0.5) return matrix.slice(1);
      return matrix;
    }

    // 中文备注：重点剧场标记「是」的行整行铺淡紫底，便于扫表。
    function isFocusRow(row) {
      if (!row || isGroupRow(row) || isGroupSpacer(row)) return false;
      const draft = hooks().getDraft?.(row) || {};
      const raw = draft.isFocus != null && String(draft.isFocus).trim() !== ""
        ? draft.isFocus
        : row.isFocus;
      return isCheckboxOn(raw);
    }

    function splitMultiValues(raw) {
      const text = String(raw || "").trim();
      if (!text) return [];
      return text.split(/\s*[、,，;；|｜\/]+\s*/).map((s) => s.trim()).filter(Boolean);
    }

    function joinMultiValues(list) {
      return (list || []).map((s) => String(s || "").trim()).filter(Boolean).join("、");
    }
    function toDateTimeLocalValue(raw) {
      const text = String(raw || "").trim().replace("T", " ");
      if (!text) return "";
      if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return `${text}T00:00:00`;
      if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(text)) return `${text.replace(" ", "T")}:00`;
      if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(text)) return text.slice(0, 19).replace(" ", "T");
      return "";
    }
    function fromDateTimeLocalValue(raw) {
      const text = String(raw || "").trim();
      if (!text) return "";
      let s = text.replace("T", " ");
      if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return `${s} 00:00:00`;
      if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(s)) return `${s}:00`;
      if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(s)) return s.slice(0, 19);
      return s;
    }

    function isActiveCell(rowIndex, colKey) {
      return !!(sheet.active
        && sheet.active.rowIndex === rowIndex
        && sheet.active.colKey === colKey);
    }

    function drawSelectCaret(screenX, rowY, w, h) {
      const cx = screenX + w - 12;
      const cy = rowY + h / 2;
      ctx.strokeStyle = COLORS.select;
      ctx.lineWidth = 1.5;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.beginPath();
      ctx.moveTo(cx - 4, cy - 1.5);
      ctx.lineTo(cx, cy + 2.5);
      ctx.lineTo(cx + 4, cy - 1.5);
      ctx.stroke();
      ctx.lineWidth = 1;
    }

    function drawSelectClearIcon(screenX, rowY, w, h) {
      const size = 14;
      const bx = screenX + w - 34;
      const by = rowY + (h - size) / 2;
      ctx.fillStyle = "#ffffff";
      ctx.strokeStyle = "#f2c28b";
      ctx.lineWidth = 1;
      ctx.beginPath();
      const r = 7;
      ctx.moveTo(bx + r, by);
      ctx.arcTo(bx + size, by, bx + size, by + size, r);
      ctx.arcTo(bx + size, by + size, bx, by + size, r);
      ctx.arcTo(bx, by + size, bx, by, r);
      ctx.arcTo(bx, by, bx + size, by, r);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.strokeStyle = "#946200";
      ctx.lineWidth = 1.6;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(bx + 4.2, by + 4.2);
      ctx.lineTo(bx + size - 4.2, by + size - 4.2);
      ctx.moveTo(bx + size - 4.2, by + 4.2);
      ctx.lineTo(bx + 4.2, by + size - 4.2);
      ctx.stroke();
      ctx.lineWidth = 1;
    }

    function drawTagRemoveMark(rx, cy, ink) {
      // 中文备注：多选 tag 右侧小叉，附着在每个标签上（非整格统一删除）。
      ctx.strokeStyle = ink || "#646a73";
      ctx.lineWidth = 1.5;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(rx - 3.2, cy - 3.2);
      ctx.lineTo(rx + 3.2, cy + 3.2);
      ctx.moveTo(rx + 3.2, cy - 3.2);
      ctx.lineTo(rx - 3.2, cy + 3.2);
      ctx.stroke();
      ctx.lineWidth = 1;
    }

    function drawOverflowBadge(x, cy, label) {
      ctx.font = '11px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
      const tw = ctx.measureText(label).width;
      const bw = Math.max(22, tw + 10);
      const bh = 20;
      const bx = x;
      const by = cy - bh / 2;
      ctx.fillStyle = "#eef0f2";
      ctx.beginPath();
      const r = bh / 2;
      ctx.moveTo(bx + r, by);
      ctx.arcTo(bx + bw, by, bx + bw, by + bh, r);
      ctx.arcTo(bx + bw, by + bh, bx, by + bh, r);
      ctx.arcTo(bx, by + bh, bx, by, r);
      ctx.arcTo(bx, by, bx + bw, by, r);
      ctx.closePath();
      ctx.fill();
      drawText(label, bx, by, bw, bh, { align: "center", size: 11, color: COLORS.text, pad: 0 });
      return bw;
    }

    function tagRemoveExpandProgress(rowIndex, colKey) {
      // 中文备注：选中后删除叉从 0→完整宽度丝滑撑开。
      if (!sheet.tagExpand) sheet.tagExpand = { key: "", t0: 0 };
      const key = `${rowIndex}::${colKey}`;
      const focused = isActiveCell(rowIndex, colKey);
      if (!focused) {
        if (sheet.tagExpand.key === key) sheet.tagExpand = { key: "", t0: 0 };
        return 0;
      }
      if (sheet.tagExpand.key !== key) {
        sheet.tagExpand = { key, t0: performance.now() };
      }
      const dur = 220;
      const p = Math.min(1, (performance.now() - sheet.tagExpand.t0) / dur);
      if (p < 1) scheduleDraw();
      return 1 - Math.pow(1 - p, 3);
    }

    function layoutSelectTags(screenX, rowY, w, h, col, raw, opts = {}) {
      const cellType = effectiveCellType(col);
      const values = cellType === "multi" ? splitMultiValues(raw) : [String(raw || "").trim()].filter(Boolean);
      const isMulti = cellType === "multi";
      const showCaret = !!opts.showCaret;
      const removeExpand = Math.max(0, Math.min(1, Number(opts.removeExpand) || 0));
      // 中文备注：单选/多选统一：选中后每个 tag 自带删除叉（非整格统一清空）。
      const showClear = false;
      const showTagRemove = !!((isMulti || cellType === "select") && values.length && removeExpand > 0.02);
      const caretPad = showCaret ? 18 : 0;
      const clearPad = 0;
      const overflowReserve = (isMulti || cellType === "select") ? 28 : 0;
      const maxRight = screenX + w - CELL_PAD_X - caretPad - clearPad;
      const tagH = 22;
      const ty = rowY + (h - tagH) / 2;
      const removeHit = 14;
      const removePadFull = 16;
      const tags = [];
      let x = screenX + CELL_PAD_X;
      ctx.font = '12px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
      for (let i = 0; i < values.length; i += 1) {
        const value = values[i];
        const textW = ctx.measureText(value).width;
        const removePad = showTagRemove ? removePadFull * removeExpand : 0;
        const natural = textW + 16 + removePad;
        const remain = values.length - i;
        // 中文备注：预留 +N 徽章宽度，避免挤掉溢出提示。
        const needOverflow = remain > 1 || (remain === 1 && x + natural > maxRight);
        const limit = needOverflow
          ? Math.min(maxRight - overflowReserve, maxRight)
          : maxRight;
        const tw = Math.min(natural, Math.max(24 + removePad, limit - x));
        if (x + Math.min(natural, 40) > limit && tags.length) break;
        if (x + tw > limit && tags.length) break;
        const removeCx = showTagRemove ? (x + tw - 4 - 6 * removeExpand) : 0;
        tags.push({
          value,
          x,
          y: ty,
          w: tw,
          h: tagH,
          textW: Math.max(0, tw - removePad),
          showRemove: showTagRemove && removeExpand > 0.55,
          removeOpacity: removeExpand,
          removeCx,
          removeCy: ty + tagH / 2,
          removeHit
        });
        x += tw + 5;
        if (x >= maxRight - 4) break;
      }
      const hidden = Math.max(0, values.length - tags.length);
      return {
        values,
        tags,
        hidden,
        overflowX: x,
        overflowCy: rowY + h / 2,
        showClear,
        showCaret: !!showCaret,
        clearRect: showClear
          ? { x: screenX + w - 34, y: rowY + (h - 14) / 2, w: 14, h: 14 }
          : null
      };
    }

    function drawSelectTags(screenX, rowY, w, h, col, raw, opts = {}) {
      const layout = layoutSelectTags(screenX, rowY, w, h, col, raw, opts);
      for (const tag of layout.tags) {
        const color = hooks().getOptionColor?.(col.key, tag.value) || "#eef1f5";
        const ink = tagTextColor(color);
        const r = tag.h / 2;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(tag.x + r, tag.y);
        ctx.arcTo(tag.x + tag.w, tag.y, tag.x + tag.w, tag.y + tag.h, r);
        ctx.arcTo(tag.x + tag.w, tag.y + tag.h, tag.x, tag.y + tag.h, r);
        ctx.arcTo(tag.x, tag.y + tag.h, tag.x, tag.y, r);
        ctx.arcTo(tag.x, tag.y, tag.x + tag.w, tag.y, r);
        ctx.closePath();
        ctx.fill();
        drawText(tag.value, tag.x, tag.y, tag.textW, tag.h, {
          align: "center",
          size: 11,
          color: ink,
          pad: 0
        });
        if (tag.showRemove) {
          ctx.save();
          ctx.globalAlpha = Math.max(0.15, tag.removeOpacity || 1);
          drawTagRemoveMark(tag.removeCx, tag.removeCy, ink);
          ctx.restore();
        }
      }
      if (layout.hidden > 0) {
        drawOverflowBadge(layout.overflowX, layout.overflowCy, `+${layout.hidden}`);
      }
      if (layout.showClear) drawSelectClearIcon(screenX, rowY, w, h);
      if (layout.showCaret) drawSelectCaret(screenX, rowY, w, h);
    }

    function hitSelectTagRemove(screenX, rowY, w, h, col, raw, localX, localY, opts = {}) {
      if (col?.type !== "multi" && col?.type !== "select") return null;
      const layout = layoutSelectTags(screenX, rowY, w, h, col, raw, opts);
      for (const tag of layout.tags) {
        if (!tag.showRemove) continue;
        // 中文备注：仅命中 × 圆点，禁止把整颗 tag 当成删除（否则点选项像被删）。
        const r = Math.max(8, (tag.removeHit || 14) / 2 + 2);
        const dx = localX - tag.removeCx;
        const dy = localY - tag.removeCy;
        if (dx * dx + dy * dy <= r * r) return tag.value;
      }
      return null;
    }

    // 中文备注：按背景色亮度自动选深/浅字，保证彩色标签文字清晰。
    function tagTextColor(bg) {
      const hex = String(bg || "").trim();
      const m = /^#?([0-9a-f]{6})$/i.exec(hex);
      if (!m) return COLORS.text;
      const n = parseInt(m[1], 16);
      const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
      const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
      return lum > 0.7 ? COLORS.text : (lum > 0.55 ? "#2b3038" : "#ffffff");
    }

    function fallbackGroupTagColor(value) {
      const palette = ["#d6e4ff", "#ffd6e7", "#d9f7be", "#fff1b8", "#efdbff", "#b5f5ec", "#ffd8bf", "#f5f5f5"];
      const s = String(value || "");
      let h = 0;
      for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
      return palette[h % palette.length];
    }

    function groupTagValues(row, col) {
      const raw = row.raw != null ? row.raw : row.label;
      const text = String(raw ?? "").trim();
      if (!text || text === "(空)" || text === "(为空)") return [];
      if (col?.type === "multi") return splitMultiValues(text);
      // 中文备注：单选也可能被存成「A、B」组合键（多人同组），按多值拆开画标签。
      if (col?.type === "select" && /[、,|｜]/.test(text)) return splitMultiValues(text);
      return [text];
    }

    function shouldDrawGroupAsTags(row) {
      const field = String(row?.field || "").trim();
      if (!field) return false;
      const col = hooks().getColumnDef?.(field) || hooks().getColumns?.()?.find((c) => c.key === field);
      if (!col) return false;
      if (col.type === "select" || col.type === "multi") return true;
      // 中文备注：字段配置里带选项色的也按标签展示。
      const sample = groupTagValues(row, col)[0];
      return !!(sample && hooks().getOptionColor?.(field, sample));
    }

    function drawGroupTags(screenX, rowY, maxW, h, field, values) {
      let x = screenX;
      const maxRight = screenX + maxW;
      for (const value of values) {
        const configured = hooks().getOptionColor?.(field, value) || "";
        const color = configured || fallbackGroupTagColor(value);
        ctx.font = '12px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
        const tw = Math.min(ctx.measureText(value).width + 16, Math.max(28, maxRight - x));
        if (x + tw > maxRight && x > screenX) break;
        const tagH = 20;
        const ty = rowY + (h - tagH) / 2;
        const r = tagH / 2;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(x + r, ty);
        ctx.arcTo(x + tw, ty, x + tw, ty + tagH, r);
        ctx.arcTo(x + tw, ty + tagH, x, ty + tagH, r);
        ctx.arcTo(x, ty + tagH, x, ty, r);
        ctx.arcTo(x, ty, x + tw, ty, r);
        ctx.closePath();
        ctx.fill();
        drawText(value, x, ty, tw, tagH, { align: "center", size: 11, color: tagTextColor(color), pad: 0 });
        x += tw + 6;
        if (x >= maxRight) break;
      }
      return x;
    }

    function groupHeaderLayout(row) {
      const level = Math.max(0, Math.min(3, Number(row?.level) || 0));
      const pad = GROUP_CARD_PAD;
      const padLeft = pad + 12 + level * 24;
      return {
        level,
        pad,
        padLeft,
        chevronX: padLeft,
        chevronHitX0: padLeft - 6,
        chevronHitX1: padLeft + 18,
        textX: padLeft + 18,
        countW: 108,
        rightPad: pad + 14
      };
    }

    function drawGroupHeader(rowIndex, rowY, list, viewportW) {
      const row = list[rowIndex];
      if (isGroupSpacer(row)) {
        fillRect(0, rowY, viewportW, rowH(), COLORS.groupSpacer);
        return;
      }
      if (!isGroupRow(row)) return;
      const collapsed = !!row.collapsed;
      const hover = sheet.hoverRow === rowIndex;
      const layout = groupHeaderLayout(row);
      const { pad, padLeft, textX, countW, rightPad, level } = layout;
      const bg = hover
        ? (level === 0 ? COLORS.groupBgHover : COLORS.groupBgL1Hover)
        : COLORS.groupBg;
      // 中文备注：多维表格分组头浅灰底，落在白卡片宽度内。
      fillRect(pad, rowY, Math.max(0, viewportW - pad * 2), rowH(), bg);
      ctx.strokeStyle = COLORS.groupLineSoft;
      ctx.beginPath();
      ctx.moveTo(pad, rowY + rowH() - 0.5);
      ctx.lineTo(viewportW - pad, rowY + rowH() - 0.5);
      ctx.stroke();

      const chevronX = layout.chevronX;
      const chevronY = rowY + rowH() / 2;
      ctx.strokeStyle = COLORS.groupMuted;
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      if (collapsed) {
        ctx.moveTo(chevronX, chevronY - 4);
        ctx.lineTo(chevronX + 5, chevronY);
        ctx.lineTo(chevronX, chevronY + 4);
      } else {
        ctx.moveTo(chevronX - 1, chevronY - 2);
        ctx.lineTo(chevronX + 4, chevronY + 3);
        ctx.lineTo(chevronX + 9, chevronY - 2);
      }
      ctx.stroke();
      ctx.lineWidth = 1;

      const label = String(row.label || "(为空)");
      const countText = `${Number(row.count) || 0} 条记录`;
      const maxLabelW = Math.max(80, viewportW - textX - countW - rightPad);
      const field = String(row.field || "").trim();
      const col = hooks().getColumnDef?.(field) || hooks().getColumns?.()?.find((c) => c.key === field);
      const tagValues = groupTagValues(row, col);
      if (shouldDrawGroupAsTags(row) && tagValues.length) {
        drawGroupTags(textX, rowY, maxLabelW, rowH(), field, tagValues);
      } else {
        drawText(label, textX, rowY, maxLabelW, rowH(), {
          bold: false,
          size: 13,
          color: COLORS.groupText,
          pad: 0
        });
      }
      drawText(countText, viewportW - rightPad - countW, rowY, countW, rowH(), {
        align: "right",
        size: 12,
        color: COLORS.groupMuted,
        pad: 0
      });
    }

    function drawPlaceholderRow(rowIndex, rowY, L, W, sl, clipLeft) {
      const bg = rowIndex % 2 ? "#fafbfc" : "#f5f6f7";
      for (const meta of L.scrollMeta) {
        const screenX = meta.x - sl;
        if (screenX + meta.w < clipLeft || screenX > W) continue;
        fillRect(screenX, rowY, meta.w, rowH(), bg);
        strokeGrid(screenX, rowY, meta.w, rowH());
      }
      ctx.save();
      ctx.beginPath();
      ctx.rect(L.indexW ?? indexW(), rowY, L.freezeW, rowH());
      ctx.clip();
      for (const meta of L.freezeMeta) {
        fillRect(meta.x, rowY, meta.w, rowH(), bg);
        strokeGrid(meta.x, rowY, meta.w, rowH());
      }
      ctx.restore();
    }

    function drawBodyCell(meta, screenX, rowIndex, rowY, list) {
      const row = (list || rows())[rowIndex];
      if (!row || isGroupRow(row) || isGroupSpacer(row)) return;
      const { col, w } = meta;
      const lite = !!(sheet._scrollUntil && performance.now() < sheet._scrollUntil);
      const dirty = hooks().isDirty?.(row);
      const fill = lite ? "" : (hooks().getColumnFill?.(col.key) || "");
      const cond = lite ? null : (hooks().getCellStyle?.(row, col.key) || null);
      const focus = isFocusRow(row);
      let bg = COLORS.white;
      if (focus) bg = COLORS.focus;
      if (fill) bg = fill;
      if (cond?.bg) bg = cond.bg;
      if (dirty) bg = COLORS.dirty;
      else if (!lite && sheet.hoverRow === rowIndex) bg = (cond?.bg || fill) || (focus ? COLORS.focus : COLORS.hover);
      const columnSelected = !!(!sheet.colDragging && sheet.activeCol && sheet.activeCol === col.key);
      if (columnSelected) bg = "#edf3ff";
      // 中文备注：选中只靠 drawSelectionOverlays 画边框，不盖蒙版，避免冲掉整列/条件填色。
      fillRect(screenX, rowY, w, rowH(), bg);
      if (!lite && isFindHit(rowIndex, col.key)) {
        fillRect(screenX, rowY, w, rowH(), isFindActive(rowIndex, col.key) ? COLORS.findActive : COLORS.find);
      }
      strokeGrid(screenX, rowY, w, rowH());

      const cellType = effectiveCellType(col);
      if (isActionColumn(col)) {
        // 中文备注：按钮随列宽变宽，保证「操作历史」四字完整可见。
        const btnH = 22;
        const label = "操作历史";
        ctx.font = '11px "PingFang SC","Microsoft YaHei","Segoe UI",sans-serif';
        const textW = ctx.measureText(label).width + 16;
        const btnW = Math.max(textW, Math.min(Math.max(0, w - CELL_PAD_X * 2), 120));
        const bx = screenX + CELL_PAD_X;
        const by = rowY + (rowH() - btnH) / 2;
        if (btnW >= 36) {
          drawRoundRect(bx, by, btnW, btnH, 6, "#f4f5f7", COLORS.gridStrong);
          drawText(label, bx, by, btnW, btnH, { align: "center", size: 11, color: COLORS.subtle, pad: 2 });
        }
      } else if (col.key === "seriesName") {
        // 中文备注：名称列只展示名称（含草稿）；类目/描述类型走独立列。
        const name = cellText(row, col) || "";
        if (name) {
          drawText(name, screenX, rowY, w - (dirty ? 48 : 0), rowH(), {
            bold: true,
            size: 12,
            color: cond?.fg || COLORS.text
          });
        }
        if (dirty) {
          drawRoundRect(screenX + w - 48, rowY + (rowH() - 16) / 2, 42, 16, 8, "#fff3e0", "");
          drawText("未保存", screenX + w - 48, rowY + (rowH() - 16) / 2, 42, 16, { align: "center", color: "#e08a00", size: 10, pad: 0 });
        }
      } else if (cellType === "checkbox") {
        const checked = isCheckboxOn(cellText(row, col));
        const size = 14;
        const bx = screenX + CELL_PAD_X;
        const by = rowY + (rowH() - size) / 2;
        ctx.strokeStyle = checked ? "#3370ff" : COLORS.gridStrong;
        ctx.lineWidth = 1.5;
        ctx.strokeRect(bx + 0.5, by + 0.5, size - 1, size - 1);
        if (checked) {
          fillRect(bx, by, size, size, "#3370ff");
          ctx.strokeStyle = "#fff";
          ctx.beginPath();
          ctx.moveTo(bx + 3, by + 7);
          ctx.lineTo(bx + 6, by + 10);
          ctx.lineTo(bx + 11, by + 4);
          ctx.stroke();
          ctx.lineWidth = 1;
        }
      } else if (cellType === "select" || cellType === "multi") {
        // 中文备注：编辑中由 chip 浮层展示全部 tag（可换行撑开）；canvas 只画非编辑态。
        const editingHere = !!(sheet.editing
          && sheet.editing.rowIndex === rowIndex
          && sheet.editing.col?.key === col.key);
        if (editingHere) {
          // skip canvas tags
        } else {
          // 中文备注：默认单行；放不下用 +N。选中未编辑时也可撑开叉，但仍单行。
          const focused = isActiveCell(rowIndex, col.key);
          const showCaret = focused;
          const removeExpand = (cellType === "multi" || cellType === "select")
            ? tagRemoveExpandProgress(rowIndex, col.key)
            : 0;
          const raw = cellText(row, col);
          if (raw) {
            drawSelectTags(screenX, rowY, w, rowH(), col, raw, {
              showCaret,
              removeExpand
            });
          } else if (showCaret) {
            drawSelectCaret(screenX, rowY, w, rowH());
          }
        }
      } else if (cellType === "date") {
        const editingHere = !!(sheet.editing
          && sheet.editing.rowIndex === rowIndex
          && sheet.editing.col?.key === col.key);
        if (!editingHere) {
          const text = cellText(row, col);
          drawText(text === "-" ? "-" : text, screenX, rowY, w, rowH(), {
            align: cellAlign(col),
            color: text === "-" ? COLORS.muted : (cond?.fg || COLORS.text),
            bold: !!cond?.bold
          });
        }
      } else {
        const text = cellText(row, col);
        drawText(text === "-" ? "-" : text, screenX, rowY, w, rowH(), {
          align: cellAlign(col),
          color: text === "-" ? COLORS.muted : (cond?.fg || COLORS.text),
          bold: !!cond?.bold
        });
      }

      const active = sheet.active;
      if (active && active.rowIndex === rowIndex && active.colKey === col.key) {
        ctx.strokeStyle = COLORS.rangeBorder;
        ctx.lineWidth = 2;
        ctx.strokeRect(screenX + 1, rowY + 1, w - 2, rowH() - 2);
        ctx.lineWidth = 1;
      }
      if (!lite) drawPeerFocusOnCell(row, col, screenX, rowY, w);
      if (!lite && isFindActive(rowIndex, col.key)) {
        ctx.strokeStyle = COLORS.findStroke;
        ctx.lineWidth = 2;
        ctx.strokeRect(screenX + 1, rowY + 1, w - 2, rowH() - 2);
        ctx.lineWidth = 1;
      }
    }

    function drawPeerFocusOnCell(row, col, screenX, rowY, w) {
      const peers = sheet._drawPeerFocuses || [];
      if (!peers.length || !row || !col) return;
      const rowOps = String(row.opsKey || "");
      const rowType = String(row.dramaType || "");
      // W2 选区广播——他人框选的行键×列键命中时,先用极淡底色铺满选区格子(多维表格式协作选区)。
      for (const peer of peers) {
        if (!peer?.sel) continue;
        if (!peer.sel.rowSet.has(rowOps) || !peer.sel.colSet.has(col.key)) continue;
        ctx.fillStyle = "rgba(20,86,240,0.10)";
        ctx.fillRect(screenX, rowY, w, rowH());
      }
      const hits = peers.filter((p) => {
        if (!p || String(p.colKey || "") !== col.key) return false;
        if (String(p.opsKey || "") !== rowOps) return false;
        if (p.dramaType && rowType && String(p.dramaType) !== rowType) return false;
        return true;
      });
      if (!hits.length) return;
      hits.slice(0, 2).forEach((peer, idx) => {
        const color = peer.color || "#3370ff";
        ctx.strokeStyle = color;
        ctx.lineWidth = peer.mode === "edit" ? 2.5 : 2;
        ctx.strokeRect(screenX + 1.5 + idx, rowY + 1.5 + idx, w - 3 - idx * 2, rowH() - 3 - idx * 2);
        ctx.lineWidth = 1;
        const label = String(peer.shortName || peer.displayName || "?").slice(0, 4);
        ctx.font = '700 10px "PingFang SC","Segoe UI",sans-serif';
        const tw = Math.max(18, ctx.measureText(label).width + 8);
        const th = 14;
        const bx = screenX + w - tw - 2;
        const by = rowY + 1 + idx * (th + 1);
        fillRect(bx, by, tw, th, color);
        drawText(label, bx, by, tw, th, { align: "center", size: 10, color: "#fff", pad: 0 });
      });
    }

    function emitFocusChange(mode) {
      const active = sheet.active;
      if (!active) {
        hooks().onFocusChange?.({ mode: "idle" });
        return;
      }
      const row = rows()[active.rowIndex];
      // W2 presence 选区——主选区转行键/列键(行键用 opsKey:跨用户行序不同,不能传索引;超大框选截断)。
      let selection = null;
      const sel = sheet.selection;
      if (sel && (mode === "select" || mode === "edit")) {
        const list = rows();
        const cols = visibleCols();
        const rowKeys = [];
        const seenRows = new Set();
        for (let r = sel.r0; r <= sel.r1 && r < list.length && rowKeys.length < 200; r++) {
          const rw = list[r];
          if (!rw || isGroupRow(rw) || isGroupSpacer(rw)) continue;
          const k = String(rw.opsKey || rw.rowId || rw.id || "");
          if (k && !seenRows.has(k)) { seenRows.add(k); rowKeys.push(k); }
        }
        const colKeys = [];
        for (let c = sel.c0; c <= sel.c1 && c < cols.length && colKeys.length < 80; c++) {
          const k = cols[c]?.key;
          if (k) colKeys.push(k);
        }
        if (rowKeys.length && colKeys.length) selection = { rowKeys, colKeys };
      }
      hooks().onFocusChange?.({
        mode: mode || (sheet.editing ? "edit" : "select"),
        row,
        colKey: active.colKey,
        selection
      });
    }

    let lastDrawErrorAt = 0;
    let drawPaused = false;
    function draw() {
      if (drawPaused) return;
      const drawT0 = performance.now();
      const lite = !!(sheet._scrollUntil && performance.now() < sheet._scrollUntil);
      sheet._drawPeerFocuses = lite ? [] : (hooks().getPeerFocuses?.() || []);
      try {
        drawFrame();
      } catch (e) {
        // 中文备注：大行数框选/填充预览时偶发绘制异常；拖拽中不暂停、不弹「新版本」条。
        console.error("sheet draw failed", e);
        if (sheet.selecting || sheet.fillDragging || sheet.headerSelecting) return;
        drawPaused = true;
        const now = Date.now();
        if (now - lastDrawErrorAt > 1000) {
          lastDrawErrorAt = now;
          try { hooks().onDrawError?.(e); } catch (_) {}
        }
        return;
      } finally {
        sheet._drawPeerFocuses = null;
      }
      vctx.setTransform(1, 0, 0, 1, 0, 0);
      if (canvas.width !== buffer.width || canvas.height !== buffer.height) {
        canvas.width = buffer.width;
        canvas.height = buffer.height;
      }
      vctx.clearRect(0, 0, canvas.width, canvas.height);
      vctx.drawImage(buffer, 0, 0);
      if (!lite) updateSelectionStatPopup();
      try { hooks().onDrawSample?.(performance.now() - drawT0, { lite }); } catch (_) {}
    }

    function drawFrame() {
      const list = rows();
      const W = viewport.clientWidth;
      const H = viewport.clientHeight;
      const L = layout(list);
      const ix = L.indexW ?? indexW(list);
      const grouped = !!L.hasGroupRows;
      // 中文备注：不在 draw 里改 DOM scroll（会重入 scroll 事件）；仅把绘制窗口钳在有效行。
      const sl = sheet.scrollLeft;
      const maxSt = Math.max(0, L.contentH - H);
      const st = Math.max(0, Math.min(sheet.scrollTop, maxSt));
      if (st !== sheet.scrollTop) sheet.scrollTop = st;
      fillRect(0, 0, W, H, COLORS.white);
      const last = Math.max(0, L.rowCount - 1);
      let rowStart = Math.max(0, Math.floor(st / rowH()));
      let rowEnd = Math.min(last, Math.ceil((st + H - HEADER_H) / rowH()));
      if (last < 0) {
        rowStart = 0;
        rowEnd = -1;
      } else if (rowStart > last) {
        rowStart = Math.max(0, last - Math.max(0, Math.ceil((H - HEADER_H) / rowH())));
        rowEnd = last;
      }
      const visibleOuterGroups = grouped ? groupBlockIndex.visible(list, rowStart, rowEnd, 0) : [];
      // 中文备注：分组页边/#FCFCFC；卡片内白底；间距行露出同色。
      if (grouped) {
        fillRect(0, HEADER_H, W, Math.max(0, H - HEADER_H), COLORS.groupPage);
        drawGroupCardFrames(visibleOuterGroups, st, W, H);
      }
      const clipLeft = ix + L.freezeW;
      const visScroll = scrollMetaVisibleRange(L.scrollMeta, sl, clipLeft, W);

      // scrollable body
      ctx.save();
      ctx.beginPath();
      ctx.rect(clipLeft, HEADER_H, Math.max(0, W - clipLeft), Math.max(0, H - HEADER_H));
      ctx.clip();
      for (let r = rowStart; r <= rowEnd; r++) {
        const rowY = HEADER_H + r * rowH() - st;
        if (r >= list.length) {
          drawPlaceholderRow(r, rowY, L, W, sl, clipLeft);
          continue;
        }
        if (isGroupRow(list[r]) || isGroupSpacer(list[r])) continue;
        for (let ci = visScroll.start; ci <= visScroll.end; ci++) {
          const meta = L.scrollMeta[ci];
          drawBodyCell(meta, meta.x - sl, r, rowY, list);
        }
      }
      // 中文备注：网格级补线——最右列右缘+最后一行下缘(逐格只画上/左后这两条没人画)。
      ctx.lineWidth = 1;
      ctx.strokeStyle = COLORS.grid;
      ctx.beginPath();
      const lastCol = visScroll.end >= 0 && visScroll.end < L.scrollMeta.length ? L.scrollMeta[visScroll.end] : null;
      if (lastCol) {
        const rightX = lastCol.x - sl + lastCol.w;
        if (rightX >= clipLeft && rightX <= W) {
          ctx.moveTo(Math.round(rightX) + 0.5, HEADER_H);
          ctx.lineTo(Math.round(rightX) + 0.5, Math.min(H, HEADER_H + (rowEnd - rowStart + 1) * rowH()));
        }
      }
      const bottomY = HEADER_H + (rowEnd + 1) * rowH() - st;
      if (bottomY >= HEADER_H && bottomY <= H) {
        ctx.moveTo(clipLeft, Math.round(bottomY) + 0.5);
        ctx.lineTo(W, Math.round(bottomY) + 0.5);
      }
      ctx.stroke();
      ctx.restore();

      // freeze body
      ctx.save();
      ctx.beginPath();
      ctx.rect(ix, HEADER_H, L.freezeW, Math.max(0, H - HEADER_H));
      ctx.clip();
      for (let r = rowStart; r <= rowEnd; r++) {
        const rowY = HEADER_H + r * rowH() - st;
        if (r >= list.length) {
          drawPlaceholderRow(r, rowY, L, W, sl, ix);
          continue;
        }
        if (isGroupRow(list[r]) || isGroupSpacer(list[r])) continue;
        for (const meta of L.freezeMeta) {
          drawBodyCell(meta, meta.x, r, rowY, list);
        }
      }
      ctx.restore();

      // scrollable header
      ctx.save();
      ctx.beginPath();
      ctx.rect(clipLeft, 0, Math.max(0, W - clipLeft), HEADER_H);
      ctx.clip();
      for (let ci = visScroll.start; ci <= visScroll.end; ci++) {
        const meta = L.scrollMeta[ci];
        drawHeaderCell(meta, meta.x - sl);
      }
      ctx.restore();

      // freeze header
      for (const meta of L.freezeMeta) drawHeaderCell(meta, meta.x);

      // freeze shadow
      if (L.freezeW > 0 && sl > 0) {
        const grad = ctx.createLinearGradient(clipLeft, 0, clipLeft + 8, 0);
        grad.addColorStop(0, COLORS.freezeShadow);
        grad.addColorStop(1, "rgba(0,0,0,0)");
        fillRect(clipLeft, 0, 8, H, grad);
      }

      // row index（分组卡片模式不画序号）
      if (ix > 0 && !grouped) {
        fillRect(0, HEADER_H, ix, Math.max(0, H - HEADER_H), COLORS.rowIndexBg);
        let dataSeq = dataRowSeqBefore(list, rowStart);
        for (let r = rowStart; r <= rowEnd; r++) {
          const rowY = HEADER_H + r * rowH() - st;
          if (r >= list.length) {
            const bg = r % 2 ? COLORS.rowIndexBg : "#f3f4f6";
            fillRect(0, rowY, ix, rowH(), bg);
            strokeGrid(0, rowY, ix, rowH(), COLORS.gridStrong);
            drawText(String(r + 1), 0, rowY, ix, rowH(), { align: "center", color: COLORS.muted, size: 11, pad: 2 });
            continue;
          }
          const row = list[r];
          if (isGroupRow(row) || isGroupSpacer(row)) continue;
          dataSeq += 1;
          const no = dataSeq;
          let bg = COLORS.rowIndexBg;
          if (isFocusRow(row)) bg = COLORS.focus;
          if (sheet.hoverRow === r) bg = isFocusRow(row) ? COLORS.focus : COLORS.headerHot;
          fillRect(0, rowY, ix, rowH(), bg);
          strokeGrid(0, rowY, ix, rowH(), COLORS.gridStrong);
          // 中文备注：多维表格式——行号 hover 显示展开箭头(替代数字),点击打开记录详情抽屉。
          if (sheet.hoverRow === r) {
            const cx = ix / 2;
            const cy = rowY + rowH() / 2;
            ctx.fillStyle = "rgba(255,255,255,0.92)";
            ctx.beginPath();
            ctx.arc(cx, cy, 11, 0, Math.PI * 2);
            ctx.fill();
            ctx.strokeStyle = COLORS.headerSelectedText || "#1456f0";
            ctx.lineWidth = 1.4;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";
            ctx.beginPath();
            ctx.moveTo(cx - 3.5, cy - 4);
            ctx.lineTo(cx + 0.5, cy);
            ctx.lineTo(cx - 3.5, cy + 4);
            ctx.stroke();
            ctx.lineWidth = 1;
          } else {
            drawText(String(no), 0, rowY, ix, rowH(), { align: "center", color: COLORS.muted, size: 11, pad: 2 });
          }
        }
      }

      // 中文备注：分组行最后全宽绘制，盖住序号列底色与单元格，对齐主流多维表格分组条。
      ctx.save();
      ctx.beginPath();
      ctx.rect(0, HEADER_H, W, Math.max(0, H - HEADER_H));
      ctx.clip();
      for (let r = rowStart; r <= rowEnd; r++) {
        if (!isGroupRow(list[r]) && !isGroupSpacer(list[r])) continue;
        const rowY = HEADER_H + r * rowH() - st;
        drawGroupHeader(r, rowY, list, W);
      }
      ctx.restore();

      // 中文备注：空白表固定在末行下方显示多维表格式「+」新增行入口。
      if (L.hasBottomAddRow && !grouped) {
        const addY = HEADER_H + L.rowCount * rowH() - st;
        if (addY < H && addY + rowH() > HEADER_H) {
          fillRect(0, addY, W, rowH(), COLORS.white);
          strokeGrid(0, addY, W, rowH(), COLORS.gridStrong);
          drawText("+", Math.max(0, ix), addY, Math.max(44, W - ix), rowH(), {
            align: "left", color: COLORS.subtle, size: 24, pad: 14
          });
        }
      }

      // 中文备注：卡片描边盖在内容之上，大框/小框轮廓更清晰。
      if (grouped) {
        strokeGroupCardFrames(visibleOuterGroups, st, W);
      }

      // corner
      if (ix > 0 && !grouped) {
        fillRect(0, 0, ix, HEADER_H, COLORS.cornerBg);
        strokeGrid(0, 0, ix, HEADER_H, COLORS.gridStrong);
      }

      // top/left separators — softer than cell grid for a cleaner spreadsheet feel
      ctx.strokeStyle = COLORS.gridStrong;
      ctx.beginPath();
      ctx.moveTo(0, HEADER_H - 0.5);
      ctx.lineTo(W, HEADER_H - 0.5);
      if (ix > 0 && !grouped) {
        ctx.moveTo(ix - 0.5, 0);
        ctx.lineTo(ix - 0.5, H);
      }
      ctx.stroke();

      drawFillPreview();
      drawSelectionOverlays(W, H, L);

      // 中文备注：拖列时绘制跟随指针的整列浅色影子；真实列顺序仅在松开后提交。
      if (sheet.colDragging) {
        const dragMeta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === sheet.colDragging.key);
        if (dragMeta) {
          const originX = dragMeta.col.freeze ? dragMeta.x : (dragMeta.x - sl);
          const dx = Number(sheet.colDragging.currentX || 0) - Number(sheet.colDragging.startX || 0);
          const ghostX = Math.max(Number(L.indexW ?? 0), Math.min(W - dragMeta.w, originX + dx));
          ctx.save();
          ctx.shadowColor = "rgba(31,35,41,0.18)";
          ctx.shadowBlur = 8;
          ctx.shadowOffsetX = 2;
          ctx.shadowOffsetY = 2;
          ctx.fillStyle = "rgba(232,238,251,0.88)";
          ctx.fillRect(ghostX, 0, dragMeta.w, H);
          ctx.restore();
          ctx.strokeStyle = "rgba(190,201,224,0.9)";
          ctx.lineWidth = 1;
          ctx.strokeRect(ghostX + 0.5, 0.5, Math.max(1, dragMeta.w - 1), Math.max(1, H - 1));
        }
      }

      // 中文备注：拖拽调列宽时画辅助竖线。
      if (sheet.resizing) {
        const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === sheet.resizing.key);
        if (meta) {
          const screenX = meta.col.freeze ? meta.x : (meta.x - sl);
          const lineX = screenX + meta.w;
          ctx.strokeStyle = COLORS.resizeLine;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(lineX + 0.5, 0);
          ctx.lineTo(lineX + 0.5, H);
          ctx.stroke();
          ctx.lineWidth = 1;
        }
      }
      // 中文备注：长按拖列时画插入指示线。
      if (sheet.colDragging && Number.isFinite(sheet.colDragging.lineX)) {
        const lineX = sheet.colDragging.lineX;
        // 中文备注：拖列时只强调表头插入位置，使用多维表格式低饱和蓝色指示线。
        ctx.strokeStyle = "#3370ff";
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(lineX + 0.5, 0);
        ctx.lineTo(lineX + 0.5, H);
        ctx.stroke();
        ctx.lineWidth = 1;
      }
    }

    function clearColLongPress() {
      if (sheet.colLongPress?.timer) {
        try { clearTimeout(sheet.colLongPress.timer); } catch (_) {}
      }
      sheet.colLongPress = null;
    }

    /** 中文备注：已选中列按下后进入拖列（移动阈值或长按均可触发）。 */
    function beginColDragAfterSelect(key, clientX) {
      if (!key || sheet.colDragging) return false;
      if (sheet.activeCol !== key || key === "actions") return false;
      clearColLongPress();
      closeEditor({ commit: true });
      hideTooltip();
      sheet.colDragging = {
        key,
        startX: clientX,
        currentX: clientX,
        targetKey: key,
        insertKey: key,
        place: "before",
        lineX: NaN
      };
      sheet.suppressClick = true;
      canvas.style.cursor = "grabbing";
      scheduleDraw();
      return true;
    }

    function endColDrag({ commit = false } = {}) {
      clearColLongPress();
      if (sheet.colDragRaf) {
        cancelAnimationFrame(sheet.colDragRaf);
        sheet.colDragRaf = 0;
      }
      const drag = sheet.colDragging;
      if (!drag) return;
      sheet.colDragging = null;
      sheet.suppressClick = true;
      canvas.style.cursor = "default";
      if (commit && drag.key && drag.insertKey && drag.key !== drag.insertKey) {
        try {
          hooks().onColumnReorder?.(drag.key, drag.insertKey, drag.place || "before");
        } catch (_) {}
      }
      // 中文备注：拖列完成后退出整列选中态，下一次拖拽必须重新点击表头。
      sheet.activeCol = "";
      sheet.headerSelection = null;
      sheet.selection = null;
      sheet.ranges = [];
      scheduleDraw();
    }

    /** 中文备注：按指针 x 解析插入目标列（before/after）与指示线位置；排除正在拖的列。 */
    function resolveColInsertAt(clientX, dragKey = "") {
      const rect = canvas.getBoundingClientRect();
      const x = clientX - rect.left;
      const L = layout();
      const sl = sheet.scrollLeft;
      const skip = String(dragKey || "");
      const metas = [...L.freezeMeta, ...L.scrollMeta].filter((m) => (
        m?.col?.key && m.col.key !== "actions" && m.col.key !== skip
      ));
      if (!metas.length) return null;
      let last = null;
      for (const meta of metas) {
        const screenX = meta.col.freeze ? meta.x : (meta.x - sl);
        const mid = screenX + meta.w / 2;
        last = { key: meta.col.key, screenX, w: meta.w };
        if (x < mid) {
          return { insertKey: meta.col.key, place: "before", lineX: screenX };
        }
      }
      if (!last) return null;
      return { insertKey: last.key, place: "after", lineX: last.screenX + last.w };
    }

    function updateColDraggingTarget(clientX) {
      if (!sheet.colDragging) return;
      sheet.colDragging.currentX = clientX;
      const target = resolveColInsertAt(clientX, sheet.colDragging.key);
      if (!target) return;
      sheet.colDragging.insertKey = target.insertKey;
      sheet.colDragging.place = target.place;
      sheet.colDragging.targetKey = target.insertKey;
      sheet.colDragging.lineX = target.insertKey === sheet.colDragging.key ? NaN : target.lineX;
      scheduleDraw();
    }

    function hitResizeEdge(clientX, clientY) {
      const rect = canvas.getBoundingClientRect();
      const x = clientX - rect.left;
      const y = clientY - rect.top;
      const ix = indexW();
      // 中文备注：表头列缝可拖；命中加宽到 10px，避免细缝难抓。
      if (y < 0 || y > HEADER_H + 4 || x < ix) return null;
      const L = layout();
      const sl = sheet.scrollLeft;
      // 中文备注：拖宽热区=边界线 ±5px——10px 会盖住下拉箭头(中心在右缘 -10px)的点击区,
      // 点箭头变成拖宽、菜单永远打不开(多维表格:边界线拖宽、箭头开菜单,两个热区不重叠)。
      const edge = 5;
      const check = (meta, screenX) => {
        const right = screenX + meta.w;
        if (Math.abs(x - right) <= edge) return meta.col;
        return null;
      };
      for (const meta of L.freezeMeta) {
        const hit = check(meta, meta.x);
        if (hit) return hit;
      }
      for (const meta of L.scrollMeta) {
        const screenX = meta.x - sl;
        if (screenX + meta.w < ix + L.freezeW - edge) continue;
        const hit = check(meta, screenX);
        if (hit) return hit;
      }
      return null;
    }

    /** Mac overlay 滚动条区域：勿启动 canvas 框选，交给原生滚动。 */
    function viewportScrollbarInset() {
      const gx = Math.max(0, viewport.offsetWidth - viewport.clientWidth);
      const gy = Math.max(0, viewport.offsetHeight - viewport.clientHeight);
      return { x: Math.max(gx, 14), y: Math.max(gy, 14) };
    }
    function isNativeScrollbarPointer(clientX, clientY) {
      const r = viewport.getBoundingClientRect();
      const inset = viewportScrollbarInset();
      if (clientX >= r.right - inset.x - 1) return true;
      if (clientY >= r.bottom - inset.y - 1) return true;
      return false;
    }

    function hitTest(clientX, clientY) {
      const rect = canvas.getBoundingClientRect();
      const x = clientX - rect.left;
      const y = clientY - rect.top;
      if (x < 0 || y < 0 || x > rect.width || y > rect.height) return null;
      const L = layout();
      const ix = L.indexW ?? indexW();
      const sl = sheet.scrollLeft;
      const st = sheet.scrollTop;

      if (y < HEADER_H) {
        if (ix > 0 && x < ix) return { kind: "corner" };
        for (const meta of L.freezeMeta) {
          if (x >= meta.x && x < meta.x + meta.w) {
            return { kind: "header", col: meta.col, menu: x > meta.x + meta.w - (HEADER_MENU_W + 4), screenX: meta.x, screenY: 0, w: meta.w };
          }
        }
        for (const meta of L.scrollMeta) {
          const screenX = meta.x - sl;
          if (x >= screenX && x < screenX + meta.w) {
            return { kind: "header", col: meta.col, menu: x > screenX + meta.w - (HEADER_MENU_W + 4), screenX, screenY: 0, w: meta.w };
          }
        }
        return { kind: "headerEmpty" };
      }

      const addRowY = HEADER_H + rows().length * rowH() - st;
      if (L.hasBottomAddRow && y >= addRowY && y < addRowY + rowH()) {
        return { kind: "bottomAddRow", screenY: addRowY };
      }

      const rowIndex = Math.floor((y - HEADER_H + st) / rowH());
      if (rowIndex < 0 || rowIndex >= rows().length) return { kind: "empty" };
      const rowY = HEADER_H + rowIndex * rowH() - st;
      const row = rows()[rowIndex];
      if (isGroupSpacer(row)) {
        return { kind: "groupSpacer", rowIndex, row, screenY: rowY };
      }
      if (isGroupRow(row)) {
        const layout = groupHeaderLayout(row);
        const onChevron = x >= layout.chevronHitX0 && x <= layout.chevronHitX1;
        return { kind: "group", rowIndex, row, screenY: rowY, onChevron };
      }

      if (ix > 0 && x < ix) return { kind: "rowIndex", rowIndex, screenY: rowY, expand: x >= ix / 2 - 10 && x <= ix / 2 + 10 };

      for (const meta of L.freezeMeta) {
        if (x >= meta.x && x < meta.x + meta.w) {
          let raw = cellText(row, meta.col);
          const cellType = effectiveCellType(meta.col);
          if (cellType === "multi"
            && sheet.editing
            && sheet.editing.rowIndex === rowIndex
            && sheet.editing.col?.key === meta.col.key) {
            raw = joinMultiValues(sheet.editing.draftMulti || []);
          }
          const active = isActiveCell(rowIndex, meta.col.key);
          const tagRemoveValue = active && (cellType === "multi" || cellType === "select")
            ? hitSelectTagRemove(meta.x, rowY, meta.w, rowH(), meta.col, raw, x, y, {
                showCaret: active && !sheet.editing,
                removeExpand: tagRemoveExpandProgress(rowIndex, meta.col.key)
              })
            : null;
          return {
            kind: "cell", rowIndex, col: meta.col,
            screenX: meta.x, screenY: rowY, w: meta.w, h: rowH(),
            actionBtn: isActionColumn(meta.col),
            tagRemoveValue
          };
        }
      }
      for (const meta of L.scrollMeta) {
        const edRect = cellEditorScreenRect(meta, rowIndex);
        if (!edRect) continue;
        const { screenX, w: visW } = edRect;
        if (x >= screenX && x < screenX + visW) {
          let raw = cellText(row, meta.col);
          const cellType = effectiveCellType(meta.col);
          if (cellType === "multi"
            && sheet.editing
            && sheet.editing.rowIndex === rowIndex
            && sheet.editing.col?.key === meta.col.key) {
            raw = joinMultiValues(sheet.editing.draftMulti || []);
          }
          const active = isActiveCell(rowIndex, meta.col.key);
          const tagRemoveValue = active && (cellType === "multi" || cellType === "select")
            ? hitSelectTagRemove(screenX, rowY, visW, rowH(), meta.col, raw, x, y, {
                showCaret: active && !sheet.editing,
                removeExpand: tagRemoveExpandProgress(rowIndex, meta.col.key)
              })
            : null;
          return {
            kind: "cell", rowIndex, col: meta.col,
            screenX, screenY: rowY, w: visW, h: rowH(),
            actionBtn: isActionColumn(meta.col),
            tagRemoveValue
          };
        }
      }
      return { kind: "row", rowIndex };
    }

    function hideTooltip() {
      if (!tooltip) return;
      tooltip.classList.remove("show");
    }
    function showTooltip(text, clientX, clientY) {
      if (!tooltip) return;
      const t = String(text || "").trim();
      if (!t || t === "-") {
        hideTooltip();
        return;
      }
      tooltip.textContent = t;
      tooltip.classList.add("show");
      const tw = tooltip.offsetWidth || 120;
      const th = tooltip.offsetHeight || 28;
      let left = clientX + 12;
      let top = clientY + 14;
      if (left + tw > window.innerWidth - 8) left = clientX - tw - 8;
      if (top + th > window.innerHeight - 8) top = clientY - th - 8;
      tooltip.style.left = Math.max(8, left) + "px";
      tooltip.style.top = Math.max(8, top) + "px";
    }

    /** 中文备注：多维表格式——被截断的单元格内容气泡悬于单元格上方;上方放不下翻到下方。 */
    function showTooltipAbove(text, clientX, cellTopClientY) {
      if (!tooltip) return;
      const t = String(text ?? "").trim();
      if (!t || t === "-") {
        hideTooltip();
        return;
      }
      tooltip.textContent = t;
      tooltip.classList.add("show");
      const tw = tooltip.offsetWidth || 120;
      const th = tooltip.offsetHeight || 28;
      let left = Math.round(clientX + 10);
      let top = Math.round(cellTopClientY - th - 8);
      if (left + tw > window.innerWidth - 8) left = window.innerWidth - 8 - tw;
      if (left < 8) left = 8;
      if (top < 8) top = Math.round(cellTopClientY + 30 + 8);
      tooltip.style.left = `${left}px`;
      tooltip.style.top = `${top}px`;
    }

    // ===== 公式单元格单击展开(多维表格式:白底蓝框,约 3 行高滚动查看;点别处/滚动关闭) =====
    function ensureFormulaPeek() {
      let el = document.getElementById("formulaPeek");
      if (!el) {
        el = document.createElement("div");
        el.id = "formulaPeek";
        el.addEventListener("mousedown", (e) => e.stopPropagation());
        el.addEventListener("click", (e) => e.stopPropagation());
        document.body.appendChild(el);
      }
      return el;
    }
    function closeFormulaPeek() {
      const el = document.getElementById("formulaPeek");
      if (el) el.remove();
    }
    function openFormulaPeek(hit) {
      const row = rows()[hit.rowIndex];
      const col = asCellColumn(hit.col);
      if (!row || !col) return;
      const text = String(cellText(row, col) ?? "");
      const el = ensureFormulaPeek();
      el.textContent = text || "(空)";
      const L = layout();
      const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === col.key);
      const w = Math.max(140, meta ? meta.w : (hit.w || 160));
      const rh = rowH();
      const vr = viewport.getBoundingClientRect();
      const left = vr.left + hit.screenX;
      const top = vr.top + hit.screenY;
      el.style.cssText = `position:fixed;z-index:310;background:#fff;border:2px solid #1456f0;border-radius:6px;
        padding:8px 10px;font-size:13px;line-height:1.55;color:#1f2329;word-break:break-all;white-space:pre-wrap;
        overflow:auto;box-sizing:border-box;left:${left}px;top:${top}px;width:${w}px;height:${rh * 3}px;box-shadow:0 4px 14px rgba(31,35,41,0.12);`;
      // 底部越界翻到单元格上方
      requestAnimationFrame(() => {
        const h = el.offsetHeight || rh * 3;
        if (top + h > window.innerHeight - 8) {
          el.style.top = `${Math.max(8, top - h + rh)}px`;
        }
        const pw = el.offsetWidth || w;
        if (left + pw > window.innerWidth - 8) {
          el.style.width = `${Math.max(140, window.innerWidth - 8 - left)}px`;
        }
      });
    }

    function closeOptionMenu() {
      if (!optionMenu) return;
      optionMenu.classList.remove("show");
      optionMenu.innerHTML = "";
      sheet.optionMenuOpen = false;
    }

    function placeOptionMenu(anchorRect) {
      if (!optionMenu || !anchorRect) return;
      const mw = Math.min(280, Math.max(148, anchorRect.w || 148));
      optionMenu.style.minWidth = `${mw}px`;
      optionMenu.style.width = `${mw}px`;
      // 先显示以量高度
      optionMenu.classList.add("show");
      const mh = Math.min(optionMenu.offsetHeight || 200, Math.floor(window.innerHeight * 0.5));
      let left = anchorRect.left;
      let top = anchorRect.bottom + 4;
      if (left + mw > window.innerWidth - 8) left = Math.max(8, window.innerWidth - mw - 8);
      if (left < 8) left = 8;
      if (top + mh > window.innerHeight - 8 && anchorRect.top > mh + 8) {
        top = Math.max(8, anchorRect.top - mh - 4);
      }
      optionMenu.style.left = `${Math.round(left)}px`;
      optionMenu.style.top = `${Math.round(top)}px`;
    }

    function openOptionMenu(col, value) {
      if (!optionMenu || !sheet.editing) return;
      col = liveColumn(col) || col;
      if (sheet.editing?.col?.key === col.key) sheet.editing.col = col;
      const options = hooks().getOptions?.(col.optionsKey) || [];
      const selected = col.type === "multi" ? splitMultiValues(value) : [String(value || "").trim()].filter(Boolean);
      const list = [...options];
      selected.forEach((v) => { if (v && !list.includes(v)) list.unshift(v); });
      const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
      if (!list.length) {
        optionMenu.innerHTML = `<div class="so-empty">暂无选项</div>`;
      } else if (col.type === "multi") {
        optionMenu.innerHTML = list.map((opt) => {
          const color = hooks().getOptionColor?.(col.key, opt) || "#eef1f5";
          const on = selected.includes(opt);
          return `<button type="button" class="so-item ${on ? "is-active" : ""}" data-opt="${esc(opt)}">
            <input class="so-check" type="checkbox" ${on ? "checked" : ""} tabindex="-1" />
            <span class="so-tag" style="background:${esc(color)}">${esc(opt)}</span>
          </button>`;
        }).join("") + `<button type="button" class="so-clear" data-done="1">完成</button>`;
      } else {
        optionMenu.innerHTML = list.map((opt) => {
          const color = hooks().getOptionColor?.(col.key, opt) || "#eef1f5";
          const on = selected[0] === opt;
          return `<button type="button" class="so-item ${on ? "is-active" : ""}" data-opt="${esc(opt)}">
            <span class="so-tag" style="background:${esc(color)}">${esc(opt)}</span>
          </button>`;
        }).join("") + `<button type="button" class="so-clear" data-clear="1">清空</button>`;
      }
      const vr = viewport.getBoundingClientRect();
      const ed = sheet.editing;
      placeOptionMenu({
        left: vr.left + ed.screenX,
        top: vr.top + ed.screenY,
        bottom: vr.top + ed.screenY + ed.h,
        w: ed.w
      });
      sheet.optionMenuOpen = true;
      sheet._blockMenuClose = true;
      setTimeout(() => { sheet._blockMenuClose = false; }, 0);
    }

    function closeEditor({ commit = true } = {}) {
      closeOptionMenu();
      input.readOnly = false;
      hideChipEditor();
      if (!sheet.editing) {
        global.FrDateTimePicker?.destroySheet?.();
        editor.classList.remove("show", "is-chip", "is-date", "is-multiline");
        const ta0 = editor.querySelector("textarea.sheet-textarea");
        if (ta0) ta0.style.display = "none";
        input.style.display = "none";
        select.style.display = "none";
        select.multiple = false;
        input.type = "text";
        input.step = "";
        input.style.opacity = "1";
        input.style.pointerEvents = "auto";
        input.style.caretColor = "";
        input.style.position = "";
        input.style.width = "";
        input.style.height = "";
        return;
      }
      const { rowIndex, col: rawCol, value, draftMulti, opsKey, dramaType } = sheet.editing;
      const col = liveColumn(rawCol) || rawCol;
      const row = rows()[rowIndex];
      let next = input.value;
      if (col.type === "select") {
        next = sheet.editing.picked != null ? sheet.editing.picked : value;
      } else if (col.type === "multi") {
        next = joinMultiValues(draftMulti || splitMultiValues(value));
      } else if (col.type === "date") {
        next = global.FrDateTimePicker?.getSheetValue?.()
          || fromDateTimeLocalValue(input.value);
      }
      global.FrDateTimePicker?.destroySheet?.();
      editor.classList.remove("is-date");
      sheet.editing = null;
      editor.classList.remove("show", "is-chip", "is-multiline");
      const taMain = editor.querySelector("textarea.sheet-textarea");
      if (taMain) taMain.style.display = "none";
      input.style.display = "none";
      select.style.display = "none";
      select.multiple = false;
      input.type = "text";
      input.step = "";
      input.style.opacity = "1";
      input.style.pointerEvents = "auto";
      input.style.caretColor = "";
      input.style.position = "";
      input.style.width = "";
      input.style.height = "";
      const sameRow = row && (!opsKey || String(row.opsKey || "") === String(opsKey || ""))
        && (!dramaType || !row.dramaType || String(row.dramaType || "") === String(dramaType || ""));
      if (commit && sameRow && col) {
        hooks().onCommit?.(row, col.key, next);
      }
      input.blur();
      select.blur();
      if (!sheet.editing) focusGridCanvas();
      scheduleDraw();
      emitFocusChange(sheet.active ? "select" : "idle");
    }

    function ensureChipEditor() {
      let chip = editor.querySelector("#sheetChipEditor") || editor.querySelector(".sheet-chip-editor");
      if (!chip) {
        chip = document.createElement("div");
        chip.className = "sheet-chip-editor";
        chip.id = "sheetChipEditor";
        chip.innerHTML = `<div class="sheet-chip-tags" id="sheetChipTags"></div>
          <button type="button" class="sheet-chip-caret" id="sheetChipCaret" tabindex="-1" aria-label="展开选项"></button>`;
        editor.appendChild(chip);
      }
      if (!chip.dataset.bound) {
        chip.dataset.bound = "1";
        chip.addEventListener("mousedown", (e) => {
          // 中文备注：阻止失焦关编辑；阻止冒泡到 window。
          e.preventDefault();
          e.stopPropagation();
        });
        chip.addEventListener("click", (e) => {
          if (!sheet.editing) return;
          const col = liveColumn(sheet.editing.col) || sheet.editing.col;
          const xBtn = e.target.closest?.("[data-chip-remove]");
          if (xBtn) {
            e.preventDefault();
            e.stopPropagation();
            const val = xBtn.getAttribute("data-chip-remove") || "";
            if (col.type === "select") {
              sheet.editing.picked = "";
              sheet.editing.value = "";
              input.value = "";
            } else {
              const nextList = (sheet.editing.draftMulti || []).filter((v) => v !== val);
              sheet.editing.draftMulti = nextList;
              sheet.editing.value = joinMultiValues(nextList);
              input.value = sheet.editing.value;
            }
            renderChipEditor();
            positionEditor();
            // 中文备注：点叉只删 tag；菜单已开才同步刷新，首次编辑态不因此展开下拉。
            if (sheet.optionMenuOpen) openOptionMenu(col, input.value);
            scheduleDraw();
            return;
          }
          // 中文备注：第二次点空白/箭头 → 展开选项列表（已选 tag+叉保持）。
          openOptionMenu(col, input.value);
        });
      }
      return chip;
    }

    function hideChipEditor() {
      const chip = editor.querySelector("#sheetChipEditor") || editor.querySelector(".sheet-chip-editor");
      if (chip) chip.style.display = "none";
      editor.classList.remove("is-chip");
    }

    function chipEditorValues(col) {
      if (!sheet.editing) return [];
      if (col.type === "multi") return [...(sheet.editing.draftMulti || [])];
      const v = String(sheet.editing.picked != null ? sheet.editing.picked : sheet.editing.value || "").trim();
      return v ? [v] : [];
    }

    function renderChipEditor() {
      if (!sheet.editing) return;
      const col = liveColumn(sheet.editing.col) || sheet.editing.col;
      if (col.type !== "select" && col.type !== "multi") return;
      const chip = ensureChipEditor();
      const tagsEl = chip.querySelector(".sheet-chip-tags") || chip.querySelector("#sheetChipTags");
      if (!tagsEl) return;
      const esc = (s) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
      const values = chipEditorValues(col);
      // 中文备注：编辑态必须渲染全部选项 tag（带叉），禁止 +N 截断。
      tagsEl.innerHTML = values.map((v) => {
        const color = hooks().getOptionColor?.(col.key, v) || "#eef1f5";
        const ink = tagTextColor(color);
        return `<span class="sheet-chip-tag" style="background:${esc(color)};color:${esc(ink)}">
          <span>${esc(v)}</span>
          <button type="button" class="sheet-chip-x" data-chip-remove="${esc(v)}" aria-label="移除 ${esc(v)}">×</button>
        </span>`;
      }).join("");
      chip.style.display = "block";
      chip.style.width = "100%";
      editor.classList.add("is-chip");
    }

    function positionEditor() {
      if (!sheet.editing) return;
      const L = layout();
      const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === sheet.editing.col?.key);
      const edRect = meta
        ? cellEditorScreenRect(meta, sheet.editing.rowIndex)
        : { screenX: sheet.editing.screenX, screenY: sheet.editing.screenY, w: sheet.editing.w, h: sheet.editing.h };
      if (!edRect) return;
      sheet.editing.screenX = edRect.screenX;
      sheet.editing.screenY = edRect.screenY;
      sheet.editing.w = edRect.w;
      const { screenX, screenY, w } = sheet.editing;
      const vr = viewport.getBoundingClientRect();
      const isDate = editor.classList.contains("is-date");
      const rh = rowH();
      const baseH = Math.max(rh, sheet.editing.cellH || rh);
      editor.style.position = "fixed";
      editor.style.left = `${vr.left + screenX}px`;
      editor.style.top = `${vr.top + screenY}px`;
      editor.style.boxSizing = "border-box";
      if (isDate) {
        const exactW = Math.max(48, w);
        editor.style.width = `${exactW}px`;
        editor.style.minWidth = "0";
        editor.style.maxWidth = `${exactW}px`;
        editor.style.height = `${baseH}px`;
        editor.style.minHeight = `${baseH}px`;
        editor.style.maxHeight = `${baseH}px`;
        editor.style.overflow = "hidden";
        editor.style.borderRadius = "0";
        sheet.editing.h = baseH;
        return;
      }
      const baseW = Math.max(120, w);
      const isMultiline = editor.classList.contains("is-multiline");
      const multilineW = isMultiline ? Math.max(baseW, 240) : baseW;
      editor.style.width = `${multilineW}px`;
      editor.style.minWidth = `${multilineW}px`;
      editor.style.maxWidth = `${Math.max(multilineW, Math.min(420, window.innerWidth - (vr.left + screenX) - 8))}px`;
      const col = liveColumn(sheet.editing.col) || sheet.editing.col;
      if (col && (col.type === "select" || col.type === "multi") && editor.classList.contains("is-chip")) {
        // 中文备注：宽度不够就换行，高度按全部 tag 撑开，编辑时可逐个删除。
        const chip = ensureChipEditor();
        const tagsEl = chip.querySelector(".sheet-chip-tags");
        editor.style.height = "auto";
        editor.style.minHeight = `${baseH}px`;
        chip.style.minHeight = `${baseH - 4}px`;
        void editor.offsetHeight;
        const contentH = Math.max(
          tagsEl?.scrollHeight || 0,
          chip.scrollHeight || 0,
          chip.offsetHeight || 0
        );
        const measured = Math.max(baseH, contentH + 4);
        editor.style.height = `${measured}px`;
        sheet.editing.h = measured;
        // 中文备注：下一帧再量一次，避免 flex 换行高度尚未结算。
        requestAnimationFrame(() => {
          if (!sheet.editing || !editor.classList.contains("is-chip")) return;
          const h2 = Math.max(
            baseH,
            (tagsEl?.scrollHeight || 0) + 10,
            chip.scrollHeight || 0
          );
          if (h2 > measured + 1) {
            editor.style.height = `${h2}px`;
            sheet.editing.h = h2;
            if (sheet.optionMenuOpen) {
              const c = liveColumn(sheet.editing.col) || sheet.editing.col;
              openOptionMenu(c, input.value);
            }
          }
        });
      } else if (isMultiline) {
        // 中文备注：多行编辑高度(单击 96px / 双击 220px);底部越出视口则上移钳位。
        const mh = sheet.editing.multilineH || 96;
        let topNum = parseFloat(editor.style.top) || 0;
        const maxTop = window.innerHeight - mh - 8;
        if (topNum > maxTop) {
          topNum = Math.max(8, maxTop);
          editor.style.top = `${topNum}px`;
        }
        editor.style.height = `${mh}px`;
        editor.style.maxHeight = `${mh}px`;
        editor.style.overflow = "hidden";
        sheet.editing.h = mh;
      } else {
        editor.style.height = `${baseH}px`;
        sheet.editing.h = baseH;
      }
    }

    function liveColumn(col) {
      // 中文备注：layout 里可能冻着改类型前的 col；编辑/菜单一律再取一次最新 type（尤其 multi）。
      if (!col?.key) return col;
      const fresh = hooks().getColumnDef?.(col.key);
      if (!fresh) return col;
      return { ...col, ...fresh, key: col.key };
    }

    /** 中文备注：多维表格式长文本多行编辑——textarea 与 input 同语义(Enter 提交/Esc 取消/blur 提交),
     *  值实时镜像进 input.value,closeEditor 无需感知差异。单击 ~4 行,双击 ~10 行。 */
    function ensureEditorTextarea() {
      let ta = editor.querySelector("textarea.sheet-textarea");
      if (ta) return ta;
      ta = document.createElement("textarea");
      ta.className = "sheet-textarea";
      ta.rows = 2;
      ta.spellcheck = false;
      ta.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          closeEditor({ commit: true });
        } else if (e.key === "Escape") {
          e.preventDefault();
          closeEditor({ commit: false });
        }
      });
      ta.addEventListener("input", () => {
        if (sheet.editing) {
          sheet.editing.value = ta.value;
          input.value = ta.value;
        }
      });
      ta.addEventListener("blur", () => {
        setTimeout(() => {
          if (!sheet.editing) return;
          if (document.activeElement === ta) return;
          if (document.activeElement?.closest?.(".sheet-option-menu")) return;
          if (document.activeElement?.closest?.("#sheetEditor, .sheet-chip-editor, .el-picker-panel")) return;
          closeEditor({ commit: true });
        }, 0);
      });
      editor.appendChild(ta);
      return ta;
    }
    function openTextMultiline(value, tall) {
      const ta = ensureEditorTextarea();
      editor.classList.add("show", "is-multiline");
      sheet.editing.multiline = true;
      sheet.editing.multilineH = tall ? 220 : 96;
      ta.value = value || "";
      // 中文备注：closeEditor 读 input.value 提交——初始值必须镜像,否则再次按下提交空串会清掉原值!
      input.value = value || "";
      ta.style.display = "block";
      input.style.display = "none";
      positionEditor();
      ta.focus({ preventScroll: true });
      ta.select();
    }
    function openEditor(hit, opts = {}) {
      if (!hit || hit.kind !== "cell") return;
      const col = asCellColumn(hit.col);
      const row = rows()[hit.rowIndex];
      if (!row || !col || !isDataRow(row)) return;
      if (hooks().isTableReadOnly?.()) return;
      if (isActionColumn(col) || col.type === "readonly" || col.type === "copy" || col.type === "formula") return;
      if (col.type === "checkbox") {
        const cur = cellText(row, col);
        const next = isCheckboxOn(cur) ? "" : "是";
        hooks().onCommit?.(row, col.key, next);
        setSingleCellSelection(hit.rowIndex, col.key);
        scheduleDraw();
        emitFocusChange("select");
        return;
      }
      // 中文备注：同一格已是 chip 编辑态时，再点一次才展开选项下拉（图2/图4）。
      if (sheet.editing
        && sheet.editing.rowIndex === hit.rowIndex
        && sheet.editing.col?.key === col.key
        && (col.type === "select" || col.type === "multi")) {
        if (!sheet.optionMenuOpen) openOptionMenu(col, input.value);
        return;
      }
      closeEditor({ commit: true });
      const value = cellText(row, col);
      setSingleCellSelection(hit.rowIndex, col.key);
      sheet.editing = {
        rowIndex: hit.rowIndex,
        opsKey: row.opsKey || "",
        dramaType: row.dramaType || "",
        col,
        value,
        picked: col.type === "select" ? value : null,
        draftMulti: col.type === "multi" ? splitMultiValues(value) : null,
        screenX: hit.screenX,
        screenY: hit.screenY,
        w: hit.w,
        h: hit.h,
        cellH: hit.h
      };
      select.multiple = false;
      select.style.display = "none";
      input.type = "text";
      input.readOnly = false;
      hideChipEditor();
      if (col.type === "select" || col.type === "multi") {
        // 中文备注：chip 展示全部已选+叉。单选二次进入即带下拉；多选首次只撑开，二次再开菜单。
        input.style.display = "block";
        input.readOnly = true;
        input.value = col.type === "multi" ? joinMultiValues(splitMultiValues(value)) : (value || "");
        input.style.opacity = "0";
        input.style.pointerEvents = "none";
        input.style.caretColor = "transparent";
        input.style.position = "absolute";
        input.style.width = "1px";
        input.style.height = "1px";
        editor.classList.add("show");
        renderChipEditor();
        positionEditor();
        input.focus({ preventScroll: true });
        if (col.type === "select" || opts.openMenu === true) {
          openOptionMenu(col, value);
        } else {
          closeOptionMenu();
        }
      } else if (col.type === "date") {
        // 中文备注：协作表日期列用 Element datetime，与筛选/工具栏一致。
        input.style.display = "none";
        input.type = "text";
        let mount = editor.querySelector(".sheet-date-mount");
        if (!mount) {
          mount = document.createElement("div");
          mount.className = "sheet-date-mount";
          editor.appendChild(mount);
        }
        const apiVal = fromDateTimeLocalValue(toDateTimeLocalValue(value));
        input.value = apiVal;
        global.FrDateTimePicker?.mountSheet?.(mount, {
          value: apiVal,
          onChange: (val) => {
            input.value = val || "";
            if (!sheet.editing) return;
            sheet.editing.value = val || "";
            scheduleDraw();
          }
        });
        editor.classList.add("show", "is-date");
        positionEditor();
        global.FrDateTimePicker?.focusSheet?.();
      } else {
        // 中文备注：多维表格式——长文本(超长 URL 等)进入多行编辑:单击适量高度滚动查看,双击更高。
        if (String(value || "").length > 36) {
          openTextMultiline(value, !!opts.tall);
          scheduleDraw();
          emitFocusChange("edit");
          return;
        }
        editor.classList.remove("is-multiline");
        input.style.display = "block";
        input.value = value || "";
        editor.classList.add("show");
        positionEditor();
        // 中文备注：必须在同一次 click 手势里同步聚焦，延迟会导致还要再点一次。
        input.focus({ preventScroll: true });
        input.select();
      }
      scheduleDraw();
      emitFocusChange("edit");
    }

    function ensureCellVisible(rowIndex, colKey) {
      const L = layout();
      const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === colKey);
      if (!meta) return;
      const isFreeze = !!meta.col.freeze;
      if (!isFreeze) {
        const left = meta.x;
        const right = meta.x + meta.w;
        const viewLeft = sheet.scrollLeft + (L.indexW ?? indexW()) + L.freezeW;
        const viewRight = sheet.scrollLeft + viewport.clientWidth;
        if (left < viewLeft) viewport.scrollLeft = Math.max(0, left - (L.indexW ?? indexW()) - L.freezeW);
        else if (right > viewRight) viewport.scrollLeft = right - viewport.clientWidth;
      }
      const top = HEADER_H + rowIndex * rowH();
      const bottom = top + rowH();
      const viewTop = sheet.scrollTop + HEADER_H;
      const viewBottom = sheet.scrollTop + viewport.clientHeight;
      if (top < viewTop) viewport.scrollTop = Math.max(0, top - HEADER_H);
      else if (bottom > viewBottom) viewport.scrollTop = bottom - viewport.clientHeight;
      syncScroll();
    }

    function clearFocus(opts = {}) {
      const commitEditing = opts.commitEditing !== false;
      if (sheet.editing) closeEditor({ commit: commitEditing });
      clearColLongPress();
      if (sheet.colDragging) endColDrag({ commit: false });
      const had = !!(sheet.active || sheet.selection || sheet.activeCol || sheet.headerSelection || sheet.ranges.length);
      sheet.active = null;
      sheet.activeCol = "";
      sheet.headerSelection = null;
      clearSelection();
      hideTooltip();
      hideSelectionStatPopup();
      closeOptionMenu();
      if (had) {
        emitFocusChange("idle");
        scheduleDraw();
      }
    }

    function clampScrollTop(next) {
      const maxTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
      return Math.max(0, Math.min(maxTop, next));
    }
    function clampScrollLeft(next) {
      const maxLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth);
      return Math.max(0, Math.min(maxLeft, next));
    }
    function visibleBodyRowBounds() {
      const st = sheet.scrollTop;
      const h = viewport.clientHeight;
      const rh = rowH();
      const last = Math.max(0, rows().length - 1);
      const rowStart = Math.max(0, Math.floor(st / rh));
      const rowEnd = Math.min(last, Math.max(rowStart, Math.ceil((st + Math.max(0, h - HEADER_H)) / rh) - 1));
      return { rowStart, rowEnd, last };
    }
    function selectEdgeAxes(clientX, clientY) {
      const vr = viewport.getBoundingClientRect();
      const L = layout();
      const bodyLeft = (L.indexW ?? indexW()) + L.freezeW;
      const zone = SELECT_EDGE_ZONE;
      const relY = clientY - vr.top;
      const relX = clientX - vr.left;
      let vyDir = 0;
      let vxDir = 0;
      let yDist = 0;
      let xDist = 0;
      if (clientY > vr.bottom) {
        vyDir = 1;
        yDist = clientY - vr.bottom + zone;
      } else if (relY > vr.height - zone) {
        vyDir = 1;
        yDist = relY - (vr.height - zone);
      } else if (clientY < vr.top + HEADER_H) {
        vyDir = -1;
        yDist = (vr.top + HEADER_H) - clientY + zone;
      } else if (relY < HEADER_H + zone) {
        vyDir = -1;
        yDist = (HEADER_H + zone) - relY;
      }
      if (clientX > vr.right) {
        vxDir = 1;
        xDist = clientX - vr.right + zone;
      } else if (relX > vr.width - zone) {
        vxDir = 1;
        xDist = relX - (vr.width - zone);
      } else if (clientX < vr.left + bodyLeft) {
        vxDir = -1;
        xDist = (vr.left + bodyLeft) - clientX + zone;
      } else if (relX < bodyLeft + zone) {
        vxDir = -1;
        xDist = (bodyLeft + zone) - relX;
      }
      return { vyDir, vxDir, yDist, xDist, outside: clientX < vr.left || clientX > vr.right || clientY < vr.top || clientY > vr.bottom };
    }
    function isNearSelectEdge(clientX, clientY) {
      const axes = selectEdgeAxes(clientX, clientY);
      return !!(axes.vyDir || axes.vxDir);
    }
    function colIndexFromClientX(clientX) {
      const canvasRect = canvas.getBoundingClientRect();
      const x = clientX - canvasRect.left;
      const L = layout();
      const ix = L.indexW ?? indexW();
      const sl = sheet.scrollLeft;
      const cols = visibleCols();
      if (!cols.length) return -1;
      const startCol = sheet.selecting?.startCol ?? sheet.headerSelecting?.startCol ?? 0;
      const { rowStart, rowEnd } = visibleBodyRowBounds();
      void rowStart;
      void rowEnd;
      if (x < ix) return Math.min(startCol, cols.length - 1);
      let lastHit = startCol;
      for (const meta of [...L.freezeMeta, ...L.scrollMeta]) {
        const ci = colIndexOf(meta.col.key);
        if (ci < 0) continue;
        const screenX = meta.col.freeze ? meta.x : (meta.x - sl);
        if (x >= screenX) lastHit = ci;
        if (x >= screenX && x < screenX + meta.w) return ci;
      }
      return Math.max(0, Math.min(cols.length - 1, lastHit));
    }
    function rowIndexFromClientY(clientY) {
      const vr = viewport.getBoundingClientRect();
      const canvasRect = canvas.getBoundingClientRect();
      const rh = rowH();
      const st = sheet.scrollTop;
      const { rowStart, rowEnd, last } = visibleBodyRowBounds();
      if (clientY >= vr.bottom - 1) return rowEnd;
      if (clientY <= vr.top + HEADER_H + 1) return rowStart;
      const y = clientY - canvasRect.top;
      if (y < HEADER_H) return rowStart;
      if (y > canvasRect.height - 1) return rowEnd;
      return Math.max(0, Math.min(last, Math.floor((y - HEADER_H + st) / rh)));
    }
    function visibleColIndexBounds() {
      const cols = visibleCols();
      if (!cols.length) return { cStart: 0, cEnd: -1 };
      const L = layout();
      const W = viewport.clientWidth;
      const sl = sheet.scrollLeft;
      const ix = L.indexW ?? indexW();
      const bodyLeft = ix + L.freezeW;
      let cStart = cols.length - 1;
      let cEnd = 0;
      for (const meta of [...L.freezeMeta, ...L.scrollMeta]) {
        const ci = colIndexOf(meta.col.key);
        if (ci < 0) continue;
        const screenX = meta.col.freeze ? meta.x : (meta.x - sl);
        const screenRight = screenX + meta.w;
        const visible = meta.col.freeze
          ? (screenRight > ix && screenX < bodyLeft + 1)
          : (screenRight > bodyLeft && screenX < W);
        if (!visible) continue;
        if (ci < cStart) cStart = ci;
        if (ci > cEnd) cEnd = ci;
      }
      if (cEnd < cStart) {
        cStart = 0;
        cEnd = cols.length - 1;
      }
      return { cStart, cEnd };
    }
    /** 中文备注：Excel 式——边缘外拖时锚到当前可见边缘格，滚动后再锚到新边缘。 */
    function selectionTargetFromPointer(clientX, clientY) {
      const cols = visibleCols();
      if (!cols.length) return null;
      const startCol = sheet.selecting?.startCol ?? 0;
      const axes = selectEdgeAxes(clientX, clientY);
      const { rowStart, rowEnd } = visibleBodyRowBounds();
      const { cStart, cEnd } = visibleColIndexBounds();
      let rowIndex;
      let ci;
      if (axes.vyDir > 0) rowIndex = rowEnd;
      else if (axes.vyDir < 0) rowIndex = rowStart;
      else rowIndex = rowIndexFromClientY(clientY);
      if (axes.vxDir > 0) ci = cEnd;
      else if (axes.vxDir < 0) ci = cStart;
      else ci = colIndexFromClientX(clientX);
      // 中文备注：非边缘方向仍优先用 hitTest 精确到指针下的格。
      if (!axes.vyDir && !axes.vxDir) {
        const hit = hitTest(clientX, clientY);
        if (hit?.kind === "cell" && hit.col?.key) {
          const hitCi = colIndexOf(hit.col.key);
          if (hitCi >= 0 && hit.rowIndex != null) {
            return { rowIndex: hit.rowIndex, colKey: hit.col.key, ci: hitCi };
          }
        }
        if (hit?.kind === "rowIndex") {
          return { rowIndex: hit.rowIndex, colKey: cols[startCol]?.key, ci: startCol };
        }
      }
      ci = Math.max(0, Math.min(cols.length - 1, ci));
      rowIndex = Math.max(0, Math.min(Math.max(0, rows().length - 1), rowIndex));
      const colKey = cols[ci]?.key;
      if (!colKey) return null;
      return { rowIndex, colKey, ci };
    }
    function applySelectEdgeScroll(clientX, clientY) {
      const axes = selectEdgeAxes(clientX, clientY);
      if (!axes.vyDir && !axes.vxDir) return false;
      const zone = SELECT_EDGE_ZONE;
      const maxSpd = SELECT_EDGE_MAX_SPEED * (axes.outside ? SELECT_EDGE_OUTSIDE_BOOST : 1);
      const speed = (dist) => {
        const t = Math.min(1.5, Math.max(0, dist) / zone);
        return maxSpd * Math.min(1, t);
      };
      const vy = axes.vyDir ? axes.vyDir * speed(axes.yDist) : 0;
      const vx = axes.vxDir ? axes.vxDir * speed(axes.xDist) : 0;
      if (!vx && !vy) return false;
      const beforeT = viewport.scrollTop;
      const beforeL = viewport.scrollLeft;
      viewport.scrollTop = clampScrollTop(beforeT + vy);
      viewport.scrollLeft = clampScrollLeft(beforeL + vx);
      if (viewport.scrollTop !== beforeT || viewport.scrollLeft !== beforeL) {
        syncScroll();
        return true;
      }
      return false;
    }
    function extendSelectionFromPointer(clientX, clientY) {
      if (!sheet.selecting) return;
      const target = selectionTargetFromPointer(clientX, clientY);
      if (!target) return;
      const cols = visibleCols();
      const c0 = sheet.selecting.rowSelect ? 0 : sheet.selecting.startCol;
      const c1 = sheet.selecting.rowSelect ? Math.max(0, cols.length - 1) : target.ci;
      sheet.selection = makeRange(
        sheet.selecting.startRow,
        c0,
        target.rowIndex,
        c1
      );
      sheet.active = { rowIndex: target.rowIndex, colKey: target.colKey };
      canvas.style.cursor = "cell";
      scheduleDraw();
    }
    function isNearHeaderSelectEdge(clientX, clientY) {
      return selectEdgeAxes(clientX, clientY).vxDir !== 0;
    }
    function applyHeaderSelectEdgeScroll(clientX, clientY) {
      const axes = selectEdgeAxes(clientX, clientY);
      if (!axes.vxDir) return false;
      const zone = SELECT_EDGE_ZONE;
      const maxSpd = SELECT_EDGE_MAX_SPEED * (axes.outside ? SELECT_EDGE_OUTSIDE_BOOST : 1);
      const speed = (dist) => {
        const t = Math.min(1.5, Math.max(0, dist) / zone);
        return maxSpd * Math.min(1, t);
      };
      const vx = axes.vxDir * speed(axes.xDist);
      const beforeL = viewport.scrollLeft;
      viewport.scrollLeft = clampScrollLeft(beforeL + vx);
      if (viewport.scrollLeft !== beforeL) {
        syncScroll();
        return true;
      }
      return false;
    }
    function headerTargetColFromPointer(clientX, clientY) {
      const cols = visibleCols();
      if (!cols.length) return -1;
      const axes = selectEdgeAxes(clientX, clientY);
      const { cStart, cEnd } = visibleColIndexBounds();
      let ci;
      if (axes.vxDir > 0) ci = cEnd;
      else if (axes.vxDir < 0) ci = cStart;
      else {
        const hit = hitTest(clientX, clientY);
        if (hit?.kind === "header" && hit.col?.key) ci = colIndexOf(hit.col.key);
        else ci = colIndexFromClientX(clientX);
      }
      return Math.max(0, Math.min(cols.length - 1, ci));
    }
    function extendHeaderSelectionFromPointer(clientX, clientY) {
      if (!sheet.headerSelecting) return;
      const ci = headerTargetColFromPointer(clientX, clientY);
      if (ci < 0) return;
      sheet.headerSelecting.moved = sheet.headerSelecting.moved || ci !== sheet.headerSelecting.startCol;
      sheet.headerSelection = makeRange(0, sheet.headerSelecting.startCol, 0, ci);
      sheet.activeCol = visibleCols()[sheet.headerSelection.c0]?.key || "";
      scheduleDraw();
    }
    function stopSelectEdgeScrollLoop() {
      if (!sheet.selectingEdgeRaf) return;
      cancelAnimationFrame(sheet.selectingEdgeRaf);
      sheet.selectingEdgeRaf = 0;
    }
    function updateFillDraggingPointer(clientX, clientY) {
      if (!sheet.fillDragging) return;
      sheet.fillDragging.lastClientX = clientX;
      sheet.fillDragging.lastClientY = clientY;
      applySelectEdgeScroll(clientX, clientY);
      const nextRow = fillTargetRowFromPointer(clientX, clientY);
      if (nextRow >= 0) {
        sheet.fillDragging.targetRow = nextRow;
        sheet.fillDragging.moved = nextRow > sheet.fillDragging.sourceRange.r1;
        canvas.style.cursor = "crosshair";
        maybeLiveFillCommit(sheet.fillDragging);
        scheduleDraw();
      }
    }
    function tickSelectEdgeScroll() {
      sheet.selectingEdgeRaf = 0;
      if (sheet.fillDragging) {
        const x = sheet.fillDragging.lastClientX;
        const y = sheet.fillDragging.lastClientY;
        if (x == null || y == null) return;
        const near = isNearSelectEdge(x, y);
        updateFillDraggingPointer(x, y);
        if (sheet.fillDragging && near) {
          sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
        }
        return;
      }
      if (sheet.headerSelecting) {
        const x = sheet.headerSelecting.lastClientX;
        const y = sheet.headerSelecting.lastClientY;
        if (x == null || y == null) return;
        const near = isNearHeaderSelectEdge(x, y);
        if (near) applyHeaderSelectEdgeScroll(x, y);
        extendHeaderSelectionFromPointer(x, y);
        if (sheet.headerSelecting && near) {
          sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
        }
        return;
      }
      if (!sheet.selecting) return;
      const x = sheet.selecting.lastClientX;
      const y = sheet.selecting.lastClientY;
      if (x == null || y == null) return;
      const near = isNearSelectEdge(x, y);
      if (near) applySelectEdgeScroll(x, y);
      extendSelectionFromPointer(x, y);
      // 中文备注：停在边缘不动时也持续滚+扩选，对齐 Excel。
      if (sheet.selecting && near) {
        sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
      }
    }
    function updateHeaderSelectingPointer(clientX, clientY) {
      if (!sheet.headerSelecting) return;
      sheet.headerSelecting.lastClientX = clientX;
      sheet.headerSelecting.lastClientY = clientY;
      applyHeaderSelectEdgeScroll(clientX, clientY);
      extendHeaderSelectionFromPointer(clientX, clientY);
      if (isNearHeaderSelectEdge(clientX, clientY) && !sheet.selectingEdgeRaf) {
        sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
      }
    }
    function updateSelectingPointer(clientX, clientY) {
      if (!sheet.selecting) return;
      sheet.selecting.lastClientX = clientX;
      sheet.selecting.lastClientY = clientY;
      const dx = clientX - sheet.selecting.startX;
      const dy = clientY - sheet.selecting.startY;
      if (!sheet.selecting.moved && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
        sheet.selecting.moved = true;
      }
      applySelectEdgeScroll(clientX, clientY);
      extendSelectionFromPointer(clientX, clientY);
      if (isNearSelectEdge(clientX, clientY) && !sheet.selectingEdgeRaf) {
        sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
      }
    }

    function bind() {
      if (sheet.bound) return;
      sheet.bound = true;

      viewport.addEventListener("scroll", syncScroll, { passive: true });
      // 中文备注：canvas sticky 钉视口时，部分浏览器滚轮不传给外层 scroll 容器，手动转发。
      canvas.addEventListener("wheel", (e) => {
        if (sheet.editing || sheet.selecting) return;
        const maxTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
        const maxLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth);
        if (maxTop <= 0 && maxLeft <= 0) return;
        const beforeT = viewport.scrollTop;
        const beforeL = viewport.scrollLeft;
        viewport.scrollTop = Math.max(0, Math.min(maxTop, beforeT + e.deltaY));
        viewport.scrollLeft = Math.max(0, Math.min(maxLeft, beforeL + e.deltaX));
        if (viewport.scrollTop !== beforeT || viewport.scrollLeft !== beforeL) {
          e.preventDefault();
          syncScroll();
        }
      }, { passive: false });
      window.addEventListener("resize", resize);
      // 中文备注：点 canvas 外（工具条/侧栏等）取消选区与焦点，对齐表格失焦习惯。
      document.addEventListener("mousedown", (e) => {
        const t = e.target;
        if (!t) return;
        if (viewport.contains(t)) return;
        if (editor && editor.contains(t)) return;
        if (optionMenu && optionMenu.contains(t)) return;
        if (t.closest?.(".sheet-option-menu,.sheet-editor,.col-menu-popup,.fr-multi-menu,.el-picker-panel")) return;
        clearFocus();
      }, true);

      canvas.addEventListener("mousemove", (e) => {
        if (sheet.resizing) {
          const dx = e.clientX - sheet.resizing.startX;
          const nextW = clampColW(sheet.resizing.startW + dx, { manual: true });
          // 中文备注：拖宽事件只更新宽度 + RAF 合并重绘；布局重建由 layout() 的 woSig 自动失效，
          // 一帧只重建一次。严禁每事件 invalidateLayoutCache/resize()——高频 mousemove 下
          // 每帧重复 6-10 次布局重建+回流，真实数据（5 万行 65 列）拖表头 p95 单帧 109ms。
          if (sheet.widthOverrides[sheet.resizing.key] !== nextW) {
            sheet.widthOverrides[sheet.resizing.key] = nextW;
            canvas.style.cursor = "col-resize";
            hideTooltip();
            scheduleDraw();
          }
          return;
        }
        if (sheet.colDragging) {
          hideTooltip();
          canvas.style.cursor = "grabbing";
          updateColDraggingTarget(e.clientX);
          return;
        }
        if (sheet.colLongPress) {
          const dx = e.clientX - sheet.colLongPress.x;
          const dy = e.clientY - sheet.colLongPress.y;
          if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
            const key = sheet.colLongPress.key;
            beginColDragAfterSelect(key, e.clientX);
            return;
          }
        }
        if (sheet.fillDragging) {
          hideTooltip();
          updateFillDraggingPointer(e.clientX, e.clientY);
          if (isNearSelectEdge(e.clientX, e.clientY) && !sheet.selectingEdgeRaf) {
            sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
          }
          return;
        }
        if (sheet.headerSelecting) {
          hideTooltip();
          updateHeaderSelectingPointer(e.clientX, e.clientY);
          return;
        }
        if (sheet.selecting) {
          hideTooltip();
          return;
        }
        const edgeCol = hitResizeEdge(e.clientX, e.clientY);
        sheet.edgeKey = edgeCol ? edgeCol.key : "";
        if (edgeCol) {
          canvas.style.cursor = "col-resize";
          hideTooltip();
          const hot = "";
          let need = false;
          if (sheet.hoverRow !== -1) { sheet.hoverRow = -1; need = true; }
          if (sheet.headerHotKey !== hot) { sheet.headerHotKey = hot; need = true; }
          if (need) scheduleDraw();
          return;
        }

        if (fillHandleHitTest(e.clientX, e.clientY)) {
          canvas.style.cursor = "crosshair";
          hideTooltip();
          return;
        }

        const hit = hitTest(e.clientX, e.clientY);
        const nextHover = hit && (hit.rowIndex ?? -1);
        const hot = hit?.kind === "header" ? hit.col.key : "";
        let need = false;
        if (nextHover !== sheet.hoverRow) { sheet.hoverRow = nextHover; need = true; }
        if (hot !== sheet.headerHotKey) { sheet.headerHotKey = hot; need = true; }
        if (need) scheduleDraw();

        if (hit?.kind === "cell") {
          const row = rows()[hit.rowIndex];
          const text = cellText(row, hit.col);
          // 中文备注：表格内默认用正常箭头；仅可点击操作列保留手型。
          canvas.style.cursor = (isActionColumn(hit.col) || hit.col.type === "copy" || hit.col.type === "checkbox")
            ? "pointer"
            : "default";
          // 中文备注：多维表格式——内容被截断(省略号)才弹完整气泡,且悬于单元格上方;未截断不弹。
          const usableW = Math.max(20, (hit.w || colWidth(hit.col)) - CELL_PAD_X * 2);
          ctx.font = '13px "PingFang SC","Microsoft YaHei","Segoe UI",Calibri,Arial,sans-serif';
          const truncated = String(text ?? "").length > 0 && ctx.measureText(String(text)).width > usableW;
          if (truncated) {
            const cellTopY = viewport.getBoundingClientRect().top + HEADER_H + hit.rowIndex * rowH() - sheet.scrollTop;
            showTooltipAbove(text, e.clientX, cellTopY);
          } else {
            hideTooltip();
          }
        } else if (hit?.kind === "group") {
          canvas.style.cursor = hit.onChevron ? "pointer" : "default";
          hideTooltip();
        } else if (hit?.kind === "header") {
          // 中文备注：已选中的列可拖序（按下移动/长按）；其余 hover 展示完整标题。
          const ci = colIndexOf(hit.col.key);
          const canDrag = !!(sheet.headerSelection && sheet.headerSelection.c0 === sheet.headerSelection.c1
            && ci === sheet.headerSelection.c0 && hit.col.key !== "actions" && !hit.menu);
          canvas.style.cursor = hit.menu ? "pointer" : (canDrag ? "grab" : "default");
          const full = labelOf(hit.col);
          showTooltip(full, e.clientX, e.clientY);
        } else {
          canvas.style.cursor = "default";
          hideTooltip();
        }
      });
      canvas.addEventListener("mouseleave", () => {
        if (sheet.resizing || sheet.colDragging) return;
        clearColLongPress();
        sheet.hoverRow = -1;
        sheet.headerHotKey = "";
        sheet.edgeKey = "";
        hideTooltip();
        scheduleDraw();
      });

      canvas.addEventListener("mousedown", (e) => {
        if (e.button !== 0) return;
        if (isNativeScrollbarPointer(e.clientX, e.clientY)) return;
        // 中文备注：任何画布按下都收起公式展开层(下一次单击重新按需展开)。
        closeFormulaPeek();
        const edgeCol = hitResizeEdge(e.clientX, e.clientY);
        if (edgeCol) {
          e.preventDefault();
          e.stopPropagation();
          clearColLongPress();
          closeEditor({ commit: true });
          sheet.resizing = {
            key: edgeCol.key,
            startX: e.clientX,
            startW: colWidth(edgeCol)
          };
          sheet.widthOverrides[edgeCol.key] = sheet.resizing.startW;
          canvas.style.cursor = "col-resize";
          scheduleDraw();
          return;
        }

        const fillHandle = fillHandleHitTest(e.clientX, e.clientY);
        if (fillHandle) {
          e.preventDefault();
          e.stopPropagation();
          clearColLongPress();
          closeEditor({ commit: true });
          sheet.fillDragging = {
            sourceRange: { ...fillHandle.range },
            targetRow: fillHandle.range.r1,
            lastCommittedTargetRow: fillHandle.range.r1,
            pasteBatchId: `fill_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
            startX: e.clientX,
            startY: e.clientY,
            lastClientX: e.clientX,
            lastClientY: e.clientY,
            moved: false,
            totalWritten: 0,
            _commitTail: null
          };
          if (typeof hooks().onPasteBegin === "function") hooks().onPasteBegin();
          sheet.suppressClick = true;
          canvas.style.cursor = "crosshair";
          hideTooltip();
          scheduleDraw();
          return;
        }

        const hit = hitTest(e.clientX, e.clientY);
        // 中文备注：已选中的同一列表头 → 按下移动或长按进入拖列；首次点选仍走 click。
        const headerCi = hit?.kind === "header" ? colIndexOf(hit.col?.key) : -1;
        if (hit?.kind === "header" && !hit.menu && hit.col?.key && hit.col.key !== "actions"
          && sheet.headerSelection?.c0 === headerCi && sheet.headerSelection?.c1 === headerCi) {
          // 中文备注：选中后第二次按下，必须横向移动超过阈值才进入拖拽；静止按住不得自动换列。
          clearColLongPress();
          const key = hit.col.key;
          sheet.colLongPress = {
            key,
            x: e.clientX,
            y: e.clientY,
            timer: null
          };
        } else {
          clearColLongPress();
          if (hit?.kind === "header" && !hit.menu && headerCi >= 0) {
            e.preventDefault();
            sheet.headerSelecting = { startCol: headerCi, moved: false };
            sheet.headerSelection = { c0: headerCi, c1: headerCi };
            sheet.activeCol = hit.col.key;
            sheet.active = null;
            clearSelection();
            scheduleDraw();
          }
        }
        if (hit?.kind === "groupSpacer") {
          e.preventDefault();
          closeEditor({ commit: true });
          hideTooltip();
          return;
        }
        if (hit?.kind === "group") {
          e.preventDefault();
          closeEditor({ commit: true });
          hideTooltip();
          sheet.active = null;
          sheet.activeCol = "";
          clearSelection();
          // 中文备注：仅点展开箭头才折叠，避免点标签误收起（对齐主流多维表格）。
          if (hit.onChevron) hooks().onGroupToggle?.(hit.row, hit.rowIndex);
          scheduleDraw();
          emitFocusChange("idle");
          return;
        }
        if (hit?.kind === "cell") {
          e.preventDefault();
          const ciAction = colIndexOf(hit.col.key);
          // 中文备注：操作列不参与框选；否则轻微拖动会 suppressClick，导致「操作历史」点不开。
          if (isActionColumn(hit.col)) {
            hideTooltip();
            closeEditor({ commit: true });
            sheet.activeCol = "";
            if (ciAction >= 0) {
              sheet.active = { rowIndex: hit.rowIndex, colKey: hit.col.key };
              sheet.selection = makeRange(hit.rowIndex, ciAction, hit.rowIndex, ciAction);
              sheet._selAnchor = { rowIndex: hit.rowIndex, colIndex: ciAction };
            }
            scheduleDraw();
            emitFocusChange("select");
            return;
          }
          // 中文备注：记录「按下前是否已是同一格」，用于区分首次聚焦 vs 再点展开/编辑。
          const sameCell = isActiveCell(hit.rowIndex, hit.col.key)
            && !(e.metaKey || e.ctrlKey)
            && !e.shiftKey;
          const editingSame = !!(sheet.editing
            && sheet.editing.rowIndex === hit.rowIndex
            && sheet.editing.col?.key === hit.col.key);
          const colLive = asCellColumn(hit.col);
          const keepChip = editingSame
            && (effectiveCellType(colLive) === "select" || effectiveCellType(colLive) === "multi")
            && !hit.tagRemoveValue;
          sheet._clickSameCell = sameCell || editingSame;
          // 中文备注：同格 chip 编辑中再点，勿先关编辑器，否则二次展开会丢状态。
          if (!keepChip) closeEditor({ commit: true });
          hideTooltip();
          const ci = colIndexOf(hit.col.key);
          if (ci < 0) return;
          const add = !!(e.metaKey || e.ctrlKey);
          // 中文备注：Shift 在现有锚点上扩展；Cmd/Ctrl 追加一块新矩形选区。
          if (e.shiftKey && sheet.active && !add) {
            const anchor = sheet._selAnchor || {
              rowIndex: sheet.active.rowIndex,
              colIndex: colIndexOf(sheet.active.colKey)
            };
            if (anchor.colIndex >= 0) {
              sheet.selection = makeRange(anchor.rowIndex, anchor.colIndex, hit.rowIndex, ci);
              sheet.active = { rowIndex: hit.rowIndex, colKey: hit.col.key };
              sheet.activeCol = "";
              sheet.suppressClick = true;
              sheet._clickSameCell = false;
              scheduleDraw();
              emitFocusChange("select");
              return;
            }
          }
          if (add && sheet.selection) {
            sheet.ranges = [...(sheet.ranges || []), sheet.selection];
          } else if (!add) {
            sheet.ranges = [];
          }
          sheet.activeCol = "";
          sheet.active = { rowIndex: hit.rowIndex, colKey: hit.col.key };
          sheet.selection = makeRange(hit.rowIndex, ci, hit.rowIndex, ci);
          sheet._selAnchor = { rowIndex: hit.rowIndex, colIndex: ci };
          sheet.selecting = {
            add,
            startRow: hit.rowIndex,
            startCol: ci,
            startX: e.clientX,
            startY: e.clientY,
            moved: false
          };
          // 中文备注：框选/多选时抑制 click；普通单击交给 click 决定是否展开选项。
          sheet.suppressClick = add;
          scheduleDraw();
          emitFocusChange(keepChip && sheet.editing ? "edit" : "select");
          return;
        }
        // 中文备注：多维表格式——hover 行号出现的展开箭头点击打开记录详情抽屉(替代数字的中央区域)。
        if (hit?.kind === "rowIndex" && hit.expand) {
          e.preventDefault();
          e.stopPropagation();
          closeEditor({ commit: true });
          hideTooltip();
          const row = rows()[hit.rowIndex];
          if (row && !isGroupRow(row) && !isGroupSpacer(row)) {
            hooks().onRecordDetail?.(row, hit.rowIndex);
          }
          return;
        }
        if (hit?.kind === "rowIndex") {
          e.preventDefault();
          closeEditor({ commit: true });
          const cols = visibleCols();
          if (!cols.length) return;
          const add = !!(e.metaKey || e.ctrlKey);
          const lastCol = cols.length - 1;
          const rg = makeRange(hit.rowIndex, 0, hit.rowIndex, lastCol);
          if (add && sheet.selection) sheet.ranges = [...(sheet.ranges || []), sheet.selection];
          else sheet.ranges = [];
          sheet.selection = rg;
          sheet.active = { rowIndex: hit.rowIndex, colKey: cols[0].key };
          sheet.activeCol = "";
          sheet._selAnchor = { rowIndex: hit.rowIndex, colIndex: 0 };
          // 中文备注：行号按下拖动可跨屏多选整行，对齐 Excel/多维表格。
          sheet.selecting = {
            add,
            startRow: hit.rowIndex,
            startCol: 0,
            startX: e.clientX,
            startY: e.clientY,
            moved: false,
            rowSelect: true
          };
          sheet.suppressClick = true;
          scheduleDraw();
          emitFocusChange("select");
        }
      });

      const endResize = () => {
        if (!sheet.resizing) return;
        const key = sheet.resizing.key;
        const w = clampColW(Number(sheet.widthOverrides[key]) || sheet.resizing.startW, { manual: true });
        // 中文备注：拖宽结束后鼠标必落在列缝附近(hit.menu 区)——必须吞掉随行的 click,
        // 否则 mouseup 先清 resizing、click 再命中菜单区,每次拖完都弹字段菜单(多维表格无此行为)。
        sheet.suppressClick = true;
        sheet.resizing = null;
        sheet.edgeKey = "";
        delete sheet.widthOverrides[key];
        // 中文备注：手动拉宽后清掉自适应缓存，以手调宽度为准（可拉得很宽看全内容）。
        autoWidthCache = { sig: "", map: Object.create(null) };
        hooks().onColumnResize?.(key, w);
        canvas.style.cursor = "default";
        resize();
      };
      const endSelecting = () => {
        if (!sheet.selecting) return;
        const sel = sheet.selecting;
        stopSelectEdgeScrollLoop();
        sheet.selecting = null;
        drawPaused = false;
        // 中文备注：拖动过（或 Cmd 追加选区）就只保留框选，交给 click 抑制逻辑；
        // 单击不在这里开编辑器，避免随后的 click 事件把输入框焦点抢走。
        if (sel.moved || sel.add) sheet.suppressClick = true;
        canvas.style.cursor = "default";
        scheduleDraw();
      };
      const endFillDragging = () => {
        if (!sheet.fillDragging) return;
        stopSelectEdgeScrollLoop();
        const drag = sheet.fillDragging;
        sheet.fillDragging = null;
        canvas.style.cursor = "default";
        sheet.suppressClick = true;
        scheduleDraw();
        const finishFill = (written) => {
          if (typeof hooks().onPasteEnd === "function") hooks().onPasteEnd();
          const total = Math.max(Number(drag.totalWritten || 0), Number(written) || 0);
          if (total > 0) {
            sheet.selection = makeRange(
              drag.sourceRange.r0,
              drag.sourceRange.c0,
              drag.targetRow,
              drag.sourceRange.c1
            );
            sheet.active = { rowIndex: drag.targetRow, colKey: visibleCols()[drag.sourceRange.c1]?.key || "" };
          }
          hooks().onPaste?.(total, "");
          scheduleDraw();
        };
        if (!drag.moved) {
          finishFill(0);
          return;
        }
        Promise.resolve(enqueueFillCommit(drag, { finalize: true }))
          .then((written) => finishFill(written))
          .catch((error) => {
            console.error("fillIntoCells failed", error);
            if (typeof hooks().onPasteEnd === "function") hooks().onPasteEnd();
            if (typeof hooks().onFillBatchReset === "function") hooks().onFillBatchReset();
            hooks().onPaste?.(-1, "");
            scheduleDraw();
          });
      };
      window.addEventListener("mouseup", () => {
        if (sheet.colDragging) {
          endColDrag({ commit: true });
        } else {
          clearColLongPress();
        }
        endResize();
        endFillDragging();
        endSelecting();
        if (sheet.headerSelecting) {
          if (sheet.headerSelecting.moved) sheet.suppressClick = true;
          stopSelectEdgeScrollLoop();
          sheet.headerSelecting = null;
          drawPaused = false;
        }
      });
      window.addEventListener("mousemove", (e) => {
        if (sheet.resizing) {
          const dx = e.clientX - sheet.resizing.startX;
          const nextW = clampColW(sheet.resizing.startW + dx, { manual: true });
          // 中文备注：与 canvas 级 handler 同理——只改宽度 + RAF 合并，绝不每事件 resize()。
          if (sheet.widthOverrides[sheet.resizing.key] !== nextW) {
            sheet.widthOverrides[sheet.resizing.key] = nextW;
            canvas.style.cursor = "col-resize";
            scheduleDraw();
          }
          return;
        }
        if (sheet.colDragging) {
          canvas.style.cursor = "grabbing";
          // 中文备注：拖列目标按帧更新，避免高频 mousemove 触发重复布局/绘制造成卡顿。
          sheet.colDragX = e.clientX;
          if (!sheet.colDragRaf) {
            sheet.colDragRaf = requestAnimationFrame(() => {
              sheet.colDragRaf = 0;
              if (sheet.colDragging) updateColDraggingTarget(sheet.colDragX);
            });
          }
          return;
        }
        if (sheet.colLongPress) {
          const dx = e.clientX - sheet.colLongPress.x;
          const dy = e.clientY - sheet.colLongPress.y;
          if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) {
            const startX = sheet.colLongPress.x;
            const key = sheet.colLongPress.key;
            beginColDragAfterSelect(key, startX);
            sheet.colDragX = e.clientX;
            return;
          }
        }
        if (sheet.fillDragging) {
          updateFillDraggingPointer(e.clientX, e.clientY);
          if (isNearSelectEdge(e.clientX, e.clientY) && !sheet.selectingEdgeRaf) {
            sheet.selectingEdgeRaf = requestAnimationFrame(tickSelectEdgeScroll);
          }
          return;
        }
        if (sheet.headerSelecting) {
          updateHeaderSelectingPointer(e.clientX, e.clientY);
          return;
        }
        if (!sheet.selecting) return;
        updateSelectingPointer(e.clientX, e.clientY);
      });

      canvas.addEventListener("click", (e) => {
        if (sheet.edgeKey || sheet.resizing || sheet.colDragging || sheet.suppressClick) {
          sheet.suppressClick = false;
          return;
        }
        const hit = hitTest(e.clientX, e.clientY);
        if (!hit) return;
        if (hit.kind === "bottomAddRow") {
          e.stopPropagation();
          if (sheet.addingBottomRow) return;
          sheet.addingBottomRow = true;
          Promise.resolve(hooks().onBottomAddRow?.()).finally(() => {
            sheet.addingBottomRow = false;
            scheduleDraw();
          });
          return;
        }
        if (hit.kind === "header") {
          // 中文备注：阻止冒泡到 document，否则刚打开的表头菜单会被全局 click 立刻关掉。
          e.stopPropagation();
          if (hit.menu) {
            const rect = canvas.getBoundingClientRect();
            // 中文备注：主流多维表格实测——字段菜单展开锚点=整列左下(菜单左缘贴列左缘,悬于表头下方),不是右侧箭头处。
            hooks().onHeaderMenu?.(hit.col.key, {
              getBoundingClientRect: () => ({
                left: rect.left + hit.screenX + 2,
                right: rect.left + hit.screenX + hit.w,
                top: rect.top,
                bottom: rect.top + HEADER_H,
                width: hit.w - 2,
                height: HEADER_H
              }),
              closest: () => null
            });
            return;
          }
          // 中文备注：点击表头选整列；不设首格 active，避免像「只选中了第一格」。
          closeEditor({ commit: true });
          sheet.activeCol = hit.col.key;
          sheet.active = null;
          clearSelection();
          const ci = colIndexOf(hit.col.key);
          sheet.headerSelection = ci >= 0 ? { c0: ci, c1: ci } : null;
          const list = rows();
          let first = -1;
          let last = -1;
          for (let i = 0; i < list.length; i++) {
            if (isGroupRow(list[i]) || isGroupSpacer(list[i])) continue;
            if (first < 0) first = i;
            last = i;
          }
          if (ci >= 0 && first >= 0) {
            sheet.selection = makeRange(first, ci, last, ci);
          }
          scheduleDraw();
          emitFocusChange("idle");
          return;
        }
        if (hit.kind === "cell") {
          e.stopPropagation();
          if (isActionColumn(hit.col)) {
            hooks().onAction?.(rows()[hit.rowIndex], "logs");
            return;
          }
          sheet.activeCol = "";
          const reopenSelect = !!sheet._clickSameCell;
          sheet._clickSameCell = false;
          setSingleCellSelection(hit.rowIndex, hit.col.key);
          draw();
          emitFocusChange("select");
          const hitCellType = effectiveCellType(hit.col);
          if (hit.tagRemoveValue != null && (hitCellType === "multi" || hitCellType === "select")) {
            const row = rows()[hit.rowIndex];
            if (hitCellType === "select") {
              if (sheet.editing
                && sheet.editing.rowIndex === hit.rowIndex
                && sheet.editing.col?.key === hit.col.key) {
                sheet.editing.picked = "";
                sheet.editing.value = "";
                input.value = "";
                renderChipEditor();
                positionEditor();
                if (sheet.optionMenuOpen) openOptionMenu(asCellColumn(hit.col), "");
                scheduleDraw();
                return;
              }
              hooks().onCommit?.(row, hit.col.key, "");
              return;
            }
            const cur = splitMultiValues(cellText(row, hit.col));
            const nextList = cur.filter((v) => v !== hit.tagRemoveValue);
            const next = joinMultiValues(nextList);
            // 中文备注：编辑中点 tag 叉只改草稿并刷新菜单，不整格清空。
            if (sheet.editing
              && sheet.editing.rowIndex === hit.rowIndex
              && sheet.editing.col?.key === hit.col.key) {
              sheet.editing.draftMulti = nextList;
              sheet.editing.value = next;
              input.value = next;
              renderChipEditor();
              positionEditor();
              if (sheet.optionMenuOpen) openOptionMenu(asCellColumn(hit.col), next);
              scheduleDraw();
              return;
            }
            hooks().onCommit?.(row, hit.col.key, next);
            return;
          }
          if (hit.col.type === "copy") {
            const text = cellCopyValue(rows()[hit.rowIndex], hit.col);
            hooks().onCopy?.(text);
            return;
          }
          const colType = effectiveCellType(asCellColumn(hit.col));
          // 中文备注：单选 图1→选中+叉；图2→再点打开 chip+下拉。
          if (colType === "select") {
            if (reopenSelect) openEditor(hit, { openMenu: true });
            return;
          }
          // 中文备注：多选与单选一致：首次单击只选中，第二次单击才进入编辑并展开下拉。
          if (colType === "multi") {
            if (reopenSelect) openEditor(hit, { openMenu: true });
            return;
          }
          // 中文备注：文本/数字 图5→选中；图6→再点进入编辑。日期仅双击（图7）。
          if (colType === "text" || colType === "number") {
            if (reopenSelect) openEditor(hit);
            return;
          }
          // 中文备注：勾选列同样「先聚焦，再点切换」，避免误触。
          if (colType === "checkbox") {
            if (reopenSelect) openEditor(hit);
            return;
          }
          // 中文备注：公式列单击=展开查看(白底蓝框约 3 行,多维表格式;公式只读不可编辑)。
          if (colType === "formula") {
            openFormulaPeek(hit);
            return;
          }
          // 中文备注：日期单击只聚焦，双击才进入编辑（见 dblclick）。
        }
      });

      // 中文备注：表头右键=列菜单；行/单元格右键=行菜单（插入/历史/删除）。
      canvas.addEventListener("contextmenu", (e) => {
        const hit = hitTest(e.clientX, e.clientY);
        if (!hit) return;
        const anchor = {
          getBoundingClientRect: () => ({
            left: e.clientX,
            right: e.clientX,
            top: e.clientY,
            bottom: e.clientY,
            width: 0,
            height: 0
          }),
          closest: () => null
        };
        if (hit.kind === "header" && hit.col?.key) {
          e.preventDefault();
          e.stopPropagation();
          hooks().onHeaderMenu?.(hit.col.key, anchor);
          return;
        }
        const rowKinds = hit.kind === "cell" || hit.kind === "row" || hit.kind === "rowIndex";
        if (rowKinds && hit.rowIndex != null) {
          const row = rows()[hit.rowIndex];
          if (!row || row.__group || row.__groupSpacer) return;
          e.preventDefault();
          e.stopPropagation();
          if (hit.kind === "cell" && hit.col?.key) {
            setSingleCellSelection(hit.rowIndex, hit.col.key);
            scheduleDraw();
          }
          hooks().onRowMenu?.(row, hit.rowIndex, anchor);
        }
      });

      canvas.addEventListener("dblclick", (e) => {
        const hit = hitTest(e.clientX, e.clientY);
        if (!hit || hit.kind !== "cell") return;
        const colType = effectiveCellType(asCellColumn(hit.col));
        // 中文备注：文本/数字/日期双击编辑；单选双击=二次（chip+下拉）；多选双击=二次（展开下拉）。
        if (colType === "text" || colType === "number" || colType === "date"
          || colType === "select" || colType === "multi") {
          e.stopPropagation();
          closeFormulaPeek();
          if (colType === "multi") openEditor(hit, { openMenu: true });
          else if (colType === "select") openEditor(hit, { openMenu: true });
          else openEditor(hit, { tall: true });
          return;
        }
        // 中文备注：公式列双击=打开该字段的公式编辑面板(多维表格式;查看/修改定义)。
        if (colType === "formula" && hit.col?.key) {
          e.stopPropagation();
          closeFormulaPeek();
          hooks().onFormulaCellEdit?.(hit.col.key, hit);
        }
      });

      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          closeEditor({ commit: true });
        } else if (e.key === "Escape") {
          e.preventDefault();
          closeEditor({ commit: false });
        }
      });
      input.addEventListener("blur", () => {
        setTimeout(() => {
          if (!sheet.editing) return;
          if (sheet.optionMenuOpen) return;
          if (document.activeElement === select) return;
          if (document.activeElement?.closest?.(".sheet-option-menu")) return;
          if (document.activeElement?.closest?.("#sheetEditor, .sheet-chip-editor, .el-picker-panel")) return;
          closeEditor({ commit: true });
        }, 0);
      });
      select.addEventListener("change", () => closeEditor({ commit: true }));
      select.addEventListener("blur", () => {
        setTimeout(() => {
          if (!sheet.editing) return;
          if (sheet.optionMenuOpen) return;
          if (document.activeElement === input) return;
          closeEditor({ commit: true });
        }, 0);
      });
      select.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          closeEditor({ commit: false });
        }
      });

      optionMenu.addEventListener("mousedown", (e) => {
        e.preventDefault();
        e.stopPropagation();
      });
      optionMenu.addEventListener("click", (e) => {
        e.stopPropagation();
        if (!sheet.editing) return;
        const col = liveColumn(sheet.editing.col) || sheet.editing.col;
        sheet.editing.col = col;
        if (e.target.closest("[data-done]")) {
          closeEditor({ commit: true });
          return;
        }
        if (e.target.closest("[data-clear]")) {
          sheet.editing.picked = "";
          input.value = "";
          closeEditor({ commit: true });
          return;
        }
        const item = e.target.closest(".so-item[data-opt]");
        if (!item) return;
        const opt = item.getAttribute("data-opt") || "";
        if (col.type === "multi") {
          const cur = new Set(sheet.editing.draftMulti || []);
          if (cur.has(opt)) cur.delete(opt);
          else cur.add(opt);
          sheet.editing.draftMulti = [...cur];
          input.value = joinMultiValues(sheet.editing.draftMulti);
          renderChipEditor();
          positionEditor();
          openOptionMenu(col, joinMultiValues(sheet.editing.draftMulti));
          scheduleDraw();
          return;
        }
        // 中文备注：单选点选项 = 选中并提交，绝不能当成删除。
        sheet.editing.picked = opt;
        sheet.editing.value = opt;
        input.value = opt;
        closeEditor({ commit: true });
      });
      window.addEventListener("mousedown", (e) => {
        if (!sheet.optionMenuOpen || sheet._blockMenuClose) return;
        if (e.target.closest(".sheet-option-menu") || e.target.closest("#sheetEditor")) return;
        // 中文备注：多选编辑时点 canvas 上的 tag 叉，交给 click 处理，不先关编辑器。
        if (e.target === canvas || canvas.contains(e.target)) {
          try {
            const hit = hitTest(e.clientX, e.clientY);
            if (hit?.tagRemoveValue != null) return;
          } catch (_) {}
        }
        closeEditor({ commit: true });
      }, true);

      window.addEventListener("keydown", async (e) => {
        if (sheet.editing) {
          if (e.key === "Escape") {
            e.preventDefault();
            closeEditor({ commit: false });
          }
          return;
        }
        if (sheet.colDragging || sheet.colLongPress) {
          if (e.key === "Escape") {
            e.preventDefault();
            endColDrag({ commit: false });
          }
          return;
        }
        if (isExternalTextInputFocused()) return;
        // 中文备注：数据加载中（阻塞遮罩）时禁用画布键盘操作（方向键滚动/删除等），加载结束才可操作。
        if (hooks().isListLoading?.()) return;
        if (!sheet.active && !sheet.selection && !sheet.activeCol) return;
        const cols = visibleCols();
        if (!cols.length || !rows().length) return;

        if (e.key === "Delete" || e.key === "Backspace") {
          const ranges = columnClearRanges();
          const clear = [];
          for (const rg of ranges) {
            for (let r = rg.r0; r <= rg.r1; r++) {
              for (let c = rg.c0; c <= rg.c1; c++) {
                const row = rows()[r];
                const col = cols[c];
                if (!row || isGroupRow(row) || isGroupSpacer(row)) continue;
                if (!col || !isColumnClearable(col)) continue;
                if (String(cellText(row, col) ?? "") === "") continue;
                clear.push({ row, field: col.key, value: "" });
              }
            }
          }
          if (clear.length) {
            void hooks().onPasteCommit?.(clear, [], { targetColKey: clear[0].field, matrixRows: 0 });
          }
          e.preventDefault();
          return;
        }

        const isCopy = (e.key === "c" || e.key === "C") && (e.metaKey || e.ctrlKey);
        if (isCopy) {
          const payload = copySelectionPayload();
          if (!payload.text && payload.cellCount <= 0) return;
          e.preventDefault();
          writeClipboardText(payload.text, { cellCount: payload.cellCount, fields: payload.fields });
          return;
        }

        // 中文备注：Enter / F2 进入编辑或展开选项，与「单击聚焦、再操作编辑」一致。
        if ((e.key === "Enter" || e.key === "F2") && sheet.active) {
          const col = asCellColumn(cols.find((c) => c.key === sheet.active.colKey));
          const rowIndex = sheet.active.rowIndex;
          const row = rows()[rowIndex];
          if (!isDataRow(row)) return;
          const colType = effectiveCellType(col);
          if (col && (colType === "text" || colType === "number" || colType === "date" || colType === "select" || colType === "multi" || colType === "checkbox")) {
            e.preventDefault();
            const L = layout();
            const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === col.key);
            if (!meta) return;
            const edRect = cellEditorScreenRect(meta, rowIndex);
            if (!edRect) return;
            openEditor({
              kind: "cell",
              rowIndex,
              col: cols.find((c) => c.key === col.key) || col,
              screenX: edRect.screenX,
              screenY: edRect.screenY,
              w: edRect.w,
              h: edRect.h
            }, { openMenu: colType === "select" || colType === "multi" });
            return;
          }
        }

        const map = { ArrowUp: [ -1, 0 ], ArrowDown: [ 1, 0 ], ArrowLeft: [ 0, -1 ], ArrowRight: [ 0, 1 ] };
        const delta = map[e.key];
        if (!delta) return;
        e.preventDefault();
        let curRow = sheet.active?.rowIndex ?? sheet.selection?.r0 ?? 0;
        const curCol = sheet.active ? colIndexOf(sheet.active.colKey) : (sheet.selection?.c0 ?? 0);
        if (curCol < 0) return;
        if (delta[0] !== 0) {
          curRow = stepDataRowIndex(curRow, delta[0]);
        } else if (!isDataRow(rows()[curRow])) {
          const landed = findDataRowIndex(curRow, 1);
          if (landed < 0) return;
          curRow = landed;
        }
        const nextRow = curRow;
        const nextCol = Math.max(0, Math.min(cols.length - 1, curCol + delta[1]));
        const nextKey = cols[nextCol].key;
        if (e.shiftKey) {
          if (!sheet._selAnchor) {
            sheet._selAnchor = { rowIndex: nextRow, colIndex: curCol };
          }
          sheet.selection = makeRange(sheet._selAnchor.rowIndex, sheet._selAnchor.colIndex, nextRow, nextCol);
          sheet.active = { rowIndex: nextRow, colKey: nextKey };
          sheet.activeCol = "";
        } else {
          sheet._selAnchor = null;
          setSingleCellSelection(nextRow, nextKey);
        }
        ensureCellVisible(nextRow, nextKey);
        scheduleDraw();
        emitFocusChange("select");
      });

      window.addEventListener("copy", (e) => {
        if (sheet.editing || isExternalTextInputFocused()) return;
        if (!sheet.active && !sheet.selection && !sheet.activeCol && !sheet.headerSelection) return;
        const payload = copySelectionPayload();
        if (!payload.text && payload.cellCount <= 0) return;
        let value = String(payload.text ?? "");
        if (payload.fields?.length) {
          value += `\n[OPS_FIELDS]${encodeURIComponent(JSON.stringify(payload.fields))}[/OPS_FIELDS]`;
        }
        e.clipboardData?.setData("text/plain", value);
        e.preventDefault();
        hooks().onCopy?.(value, {
          cellCount: payload.cellCount,
          fields: payload.fields,
          clipboardText: payload.text,
          clipboardValue: value,
          clipboardHandled: true
        });
      });

      // 中文备注：Ctrl/Cmd+V 可覆盖已有内容；评级/负责人等 select 仍按选项匹配。
      window.addEventListener("paste", async (e) => {
        // 中文备注：多选/单选编辑器内粘贴仍走表格批量解析；普通文本编辑器保留原生粘贴。
        const editingType = sheet.editing ? (liveColumn(sheet.editing.col)?.type || sheet.editing.col?.type || "") : "";
        const chipEditing = editingType === "select" || editingType === "multi";
        const multiCellPaste = totalSelectionCellCount() > 1;
        if (sheet.editing && !chipEditing) {
          if (!multiCellPaste) return;
          closeEditor({ commit: true });
        }
        if (!chipEditing && isExternalTextInputFocused()) return;
        if (!sheet.active && !sheet.selection && !(sheet.ranges || []).length && !sheet.activeCol && !sheet.headerSelection) return;
        const text = e.clipboardData?.getData("text/plain");
        if (text == null || !String(text).trim()) return;
        e.preventDefault();
        const written = await pasteIntoCells(text);
        scheduleDraw();
        hooks().onPaste?.(written, text);
      });
    }

    function setFindHits(hits, index) {
      sheet.findHits = Array.isArray(hits) ? hits : [];
      sheet.findHitKeys = new Set(sheet.findHits.map((hit) => `${hit.rowIndex}\u0000${hit.colKey}`));
      sheet.findIndex = index;
      scheduleDraw();
    }

    function focusFind(index) {
      const total = sheet.findHits.length;
      if (!total) {
        sheet.findIndex = -1;
        scheduleDraw();
        return -1;
      }
      const next = ((index % total) + total) % total;
      sheet.findIndex = next;
      const hit = sheet.findHits[next];
      if (hit) {
        setSingleCellSelection(hit.rowIndex, hit.colKey);
        ensureCellVisible(hit.rowIndex, hit.colKey);
      }
      scheduleDraw();
      return next;
    }

    function isEditing() {
      return !!(sheet.editing || sheet.optionMenuOpen);
    }

    /** 中文备注：列表重排后按 opsKey 找回正在编辑的行，避免写到错行。 */
    function rematchEditingRow() {
      if (!sheet.editing) return true;
      const key = String(sheet.editing.opsKey || "").trim();
      if (!key) {
        closeEditor({ commit: false });
        return false;
      }
      const list = rows();
      const dtype = String(sheet.editing.dramaType || "").trim();
      let idx = list.findIndex((r) => r && !r.__group && !r.__groupSpacer
        && String(r.opsKey || "") === key
        && (!dtype || String(r.dramaType || "") === dtype));
      if (idx < 0) {
        idx = list.findIndex((r) => r && !r.__group && !r.__groupSpacer
          && String(r.opsKey || "") === key);
      }
      if (idx < 0) {
        closeEditor({ commit: false });
        return false;
      }
      sheet.editing.rowIndex = idx;
      if (sheet.editing.col?.key) {
        setSingleCellSelection(idx, sheet.editing.col.key);
      }
      const L = layout();
      const meta = [...L.freezeMeta, ...L.scrollMeta].find((m) => m.col.key === sheet.editing.col?.key);
      const edRect = meta ? cellEditorScreenRect(meta, idx) : null;
      if (edRect) {
        sheet.editing.screenX = edRect.screenX;
        sheet.editing.screenY = edRect.screenY;
        sheet.editing.w = edRect.w;
        sheet.editing.h = edRect.h;
      }
      return true;
    }

    function refresh() {
      // 中文备注：恢复绘制；曾因异常 pause 时，筛选/滚动刷新应能自愈，避免一直白屏。
      drawPaused = false;
      invalidateLayoutCache();
      // 中文备注：编辑/选项菜单打开时勿强行关编辑器，否则后台 drain/增量 render 会把下拉「弹走」且未提交。
      if (sheet.editing) {
        if (!rematchEditingRow()) {
          resize();
          scheduleDraw();
          return;
        }
        resize();
        positionEditor();
        if (sheet.optionMenuOpen && sheet.editing) {
          const ed = sheet.editing;
          const vr = viewport.getBoundingClientRect();
          placeOptionMenu({
            left: vr.left + ed.screenX,
            top: vr.top + ed.screenY,
            bottom: vr.top + ed.screenY + ed.h,
            w: ed.w
          });
        }
        scheduleDraw();
        return;
      }
      closeEditor({ commit: true });
      resize();
      scheduleDraw();
    }

    function resetScroll() {
      closeEditor({ commit: true });
      viewport.scrollLeft = 0;
      viewport.scrollTop = 0;
      sheet.scrollLeft = 0;
      sheet.scrollTop = 0;
      scheduleDraw();
    }

    /** 中文备注：滚到当前已加载数据的表顶/表底（代投快捷触顶·触底）。 */
    function scrollToTop() {
      closeEditor({ commit: true });
      syncSizerAndClampScroll();
      viewport.scrollTop = 0;
      sheet.scrollTop = 0;
      syncScroll();
    }

    function getVerticalScrollMetrics() {
      syncSizerAndClampScroll();
      const maxTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
      const scrollTop = Math.max(0, viewport.scrollTop || 0);
      return { scrollTop, maxTop };
    }

    function applyVerticalScrollTop(targetTop) {
      const maxTop = getVerticalScrollMetrics().maxTop;
      const top = Math.max(0, Math.min(maxTop, targetTop));
      viewport.scrollTop = top;
      sheet.scrollTop = top;
      syncScroll();
    }

    function scrollToBottom() {
      closeEditor({ commit: true });
      invalidateLayoutCache();
      applyVerticalScrollTop(Number.MAX_SAFE_INTEGER);
      requestAnimationFrame(() => applyVerticalScrollTop(Number.MAX_SAFE_INTEGER));
    }

    bind();
    resize();

    return {
      refresh,
      resetScroll,
      scrollToTop,
      scrollToBottom,
      getVerticalScrollMetrics,
      scheduleDraw,
      draw,
      resize,
      syncContentSize,
      scheduleContentSizeSync,
      invalidateLayoutCache,
      resetRowIndexCache: resetDataSeqPrefix,
      closeEditor,
      isEditing,
      clearFocus,
      copyCurrentSelection,
      copySelectionPayload,
      pasteText: pasteIntoCells,
      setFindHits,
      focusFind,
      ensureCellVisible,
      layout,
      cellText,
      rowH,
      HEADER_H,
      ROW_INDEX_W
    };
  }

  global.OpsSheet = { createSheet, ROW_H: DEFAULT_ROW_H, HEADER_H, ROW_INDEX_W, CANVAS_REV: OPS_SHEET_CANVAS_REV };
})(window);
