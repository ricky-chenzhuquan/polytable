/**
 * 协作表多维表格式 records 解码
 * encoding=0：稠密列式 JSON + gzip + Base64
 * encoding=1：稀疏列式 v2 + gzip + Base64
 * encoding=2：稀疏 v2 + gzip 二进制（/records/payload?nonce=）
 */
(function (global) {
  const ENCODING_GZIP_COLUMNAR_JSON = 0;
  const ENCODING_GZIP_SPARSE_COLUMNAR_JSON = 1;
  const ENCODING_GZIP_SPARSE_BINARY = 2;

  function base64ToBytes(b64) {
    const raw = String(b64 || "").trim();
    if (!raw) return new Uint8Array(0);
    const bin = atob(raw);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  async function gunzipBytes(bytes) {
    if (typeof DecompressionStream === "function") {
      const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
      const text = await new Response(stream).text();
      return text;
    }
    throw new Error("DecompressionStream unavailable");
  }

  async function parseGzipPayloadBytes(bytes) {
    const json = await gunzipBytes(bytes);
    return JSON.parse(json);
  }

  function rowsFromDense(keys, matrix) {
    return matrix.map((vec) => {
      const row = {};
      for (let i = 0; i < keys.length; i++) {
        const k = keys[i];
        if (!k) continue;
        row[k] = vec[i] == null ? "" : vec[i];
      }
      if (!row.dramaType) row.dramaType = "blank";
      return row;
    });
  }

  function rowsFromSparse(keys, sparseRows) {
    return sparseRows.map((pairs) => {
      const row = {};
      const list = Array.isArray(pairs) ? pairs : [];
      for (let i = 0; i < list.length; i++) {
        const pair = list[i];
        if (!Array.isArray(pair) || pair.length < 2) continue;
        const idx = Number(pair[0]);
        const k = keys[idx];
        if (!k) continue;
        row[k] = pair[1] == null ? "" : pair[1];
      }
      if (!row.dramaType) row.dramaType = "blank";
      if (!row.bookId && row.opsKey) row.bookId = row.opsKey;
      return row;
    });
  }

  async function rowsFromGzipPayload(bytes) {
    const payload = await parseGzipPayloadBytes(bytes);
    const keys = Array.isArray(payload.keys) ? payload.keys : [];
    const v = Number(payload.v);
    if (v === 2) {
      return rowsFromSparse(keys, Array.isArray(payload.rows) ? payload.rows : []);
    }
    return rowsFromDense(keys, Array.isArray(payload.rows) ? payload.rows : []);
  }

  async function decodeSheetRecordsPayload(encoding, recordsB64) {
    const enc = Number(encoding);
    const bytes = base64ToBytes(recordsB64);
    const payload = await parseGzipPayloadBytes(bytes);
    const keys = Array.isArray(payload.keys) ? payload.keys : [];
    const v = Number(payload.v);
    if (enc === ENCODING_GZIP_SPARSE_COLUMNAR_JSON || v === 2) {
      return rowsFromSparse(keys, Array.isArray(payload.rows) ? payload.rows : []);
    }
    if (enc === ENCODING_GZIP_COLUMNAR_JSON || v === 1) {
      return rowsFromDense(keys, Array.isArray(payload.rows) ? payload.rows : []);
    }
    throw new Error(`unsupported records encoding: ${encoding}`);
  }

  /** 将 /records 响应归一化为与 /rows 相同的 data 形状（rows 已解码）。 */
  async function normalizeSheetRecordsApiData(data, gzipArrayBuffer) {
    if (!data || data.encoding == null) {
      return data;
    }
    const enc = Number(data.encoding);
    if (enc === ENCODING_GZIP_SPARSE_BINARY) {
      if (!gzipArrayBuffer || !gzipArrayBuffer.byteLength) {
        throw new Error("records binary payload missing");
      }
      const rows = await rowsFromGzipPayload(gzipArrayBuffer);
      const next = { ...data, rows };
      delete next.records;
      delete next.payloadNonce;
      delete next.recordByteLength;
      delete next.recordKeys;
      delete next.recordCount;
      return next;
    }
    if (data.records == null || data.records === "") {
      return data;
    }
    const rows = await decodeSheetRecordsPayload(data.encoding, data.records);
    const next = { ...data, rows };
    delete next.records;
    delete next.recordKeys;
    delete next.recordCount;
    return next;
  }

  /** Consume a single NDJSON (newline-delimited JSON，逐行 JSON) HTTP body without buffering the full response. */
  async function consumeNdjsonResponse(response, options = {}) {
    if (!response || !response.ok || !response.body || typeof response.body.getReader !== "function") {
      throw new Error("streaming response body unavailable");
    }
    const signal = options.signal;
    if (signal && signal.aborted) throw new Error("stream aborted");
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let carry = "";
    let meta = null;
    let end = null;
    let count = 0;
    const rowBatch = [];
    const flushRows = async () => {
      if (!rowBatch.length) return;
      const batch = rowBatch.splice(0, rowBatch.length);
      if (typeof options.onRows === "function") await options.onRows(batch, meta);
    };
    const abort = () => { try { reader.cancel(); } catch (_) {} };
    if (signal) signal.addEventListener("abort", abort, { once: true });

    const consumeLine = async (line) => {
      const source = String(line || "").replace(/\r$/, "");
      if (!source.trim()) return;
      let frame;
      try { frame = JSON.parse(source); }
      catch (error) { throw new Error(`invalid NDJSON frame JSON: ${error.message}`); }
      if (!frame || typeof frame !== "object" || typeof frame.type !== "string") {
        throw new Error("invalid NDJSON frame type");
      }
      if (end) throw new Error("frame received after end frame");
      if (frame.type === "meta") {
        if (meta || count !== 0) throw new Error("duplicate or misplaced meta frame");
        if (!Array.isArray(frame.keys) || !Number.isFinite(Number(frame.total))) throw new Error("invalid meta frame");
        meta = frame;
        if (typeof options.onMeta === "function") await options.onMeta(meta);
        return;
      }
      if (!meta) throw new Error("meta frame must be first");
      if (frame.type === "row") {
        if (!Array.isArray(frame.values) || frame.values.length !== meta.keys.length) throw new Error("row values do not match meta keys");
        const row = {};
        for (let index = 0; index < meta.keys.length; index++) row[meta.keys[index]] = frame.values[index] == null ? "" : frame.values[index];
        count++;
        rowBatch.push(row);
        if (rowBatch.length >= Math.max(1, Number(options.batchSize) || 128)) await flushRows();
        return;
      }
      if (frame.type === "end") {
        if (Number(frame.count) !== count) throw new Error(`end count ${frame.count} does not match received row count ${count}`);
        if (meta.complete === true && Number(meta.total) !== count) throw new Error(`meta total ${meta.total} does not match received row count ${count}`);
        await flushRows();
        end = frame;
        if (typeof options.onEnd === "function") await options.onEnd(end, meta);
        return;
      }
      throw new Error(`unknown NDJSON frame type: ${frame.type}`);
    };

    try {
      while (true) {
        if (signal && signal.aborted) throw new Error("stream aborted");
        const { value, done } = await reader.read();
        if (done) break;
        carry += decoder.decode(value, { stream: true });
        let newline;
        while ((newline = carry.indexOf("\n")) >= 0) {
          const line = carry.slice(0, newline);
          carry = carry.slice(newline + 1);
          await consumeLine(line);
        }
      }
      carry += decoder.decode();
      if (carry.trim()) await consumeLine(carry);
      if (!meta) throw new Error("missing meta frame");
      if (!end) throw new Error("missing end frame");
      return { meta, end, count };
    } finally {
      if (signal) signal.removeEventListener("abort", abort);
      try { reader.releaseLock(); } catch (_) {}
    }
  }

  function attachProgressiveRows(state, rows, batch, keyOf, resetKeys = false) {
    if (!state || !Array.isArray(rows) || !Array.isArray(batch) || typeof keyOf !== "function") {
      throw new TypeError("progressive row state, rows, batch, and keyOf are required");
    }
    state.rows = rows;
    if (resetKeys || !(state._rowKeySet instanceof Set)) state._rowKeySet = new Set();
    for (const row of batch) state._rowKeySet.add(keyOf(row));
    return state;
  }

  global.OpsSheetRecords = {
    ENCODING_GZIP_COLUMNAR_JSON,
    ENCODING_GZIP_SPARSE_COLUMNAR_JSON,
    ENCODING_GZIP_SPARSE_BINARY,
    decodeSheetRecordsPayload,
    normalizeSheetRecordsApiData,
    rowsFromGzipPayload,
    consumeNdjsonResponse,
    attachProgressiveRows
  };
})(window);
