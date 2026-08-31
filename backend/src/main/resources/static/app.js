"use strict";

const SCREENING_CONSENT_VERSION = "voice-screening-consent-v1";
const IMAGE_OPTIONS = [
  { label: "图片一", value: "test.jpg", hint: "请描述画面中的人物、物品和正在发生的事情。" },
  { label: "图片二", value: "test1.jpg", hint: "请尽量按空间顺序讲述你看到的内容。" },
  { label: "图片三", value: "test2.jpg", hint: "请补充人物关系、动作和可能的场景背景。" }
];
const MODEL_PROVIDERS = {
  DEEPSEEK: { label: "DeepSeek", defaultModel: "deepseek-chat" },
  KIMI: { label: "Kimi", defaultModel: "kimi-k2.6" },
  GLM: { label: "智谱 GLM", defaultModel: "glm-5.2" },
  QWEN: { label: "通义千问", defaultModel: "qwen-plus" }
};

function assistantClientId() {
  let value = localStorage.getItem("alzAssistantClientId");
  if (!value) {
    value = crypto.randomUUID ? crypto.randomUUID() : "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
      const random = Math.floor(Math.random() * 16);
      return (char === "x" ? random : (random & 3) | 8).toString(16);
    });
    localStorage.setItem("alzAssistantClientId", value);
  }
  return value;
}

const state = {
  token: sessionStorage.getItem("alzToken") || "",
  username: sessionStorage.getItem("alzUsername") || "",
  role: sessionStorage.getItem("alzRole") || "",
  stream: null,
  audioContext: null,
  sourceNode: null,
  processorNode: null,
  chunks: [],
  recordingStartedAt: 0,
  timer: null,
  audioBlob: null,
  audioFile: null,
  durationSeconds: 0,
  previewUrl: "",
  protectedAudioUrls: {},
  audioRecords: [],
  currentDiagnosis: null,
  screeningPollTimer: null,
  adminFullUsers: [],
  assistantClientId: assistantClientId(),
  assistantConversations: [],
  currentConversationId: "",
  chatStreaming: false,
  assistantLoadVersion: 0,
  assistantModelProfile: null
};

const $ = (id) => document.getElementById(id);

function safeJsonParse(value) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function parseJwtPayload(token) {
  const payload = token?.split(".")[1];
  if (!payload) return {};
  const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  return safeJsonParse(decodeURIComponent(escape(atob(padded)))) || {};
}

function switchTab(target) {
  const panels = {
    chat: ["chatTab", "chatPanel"],
    screening: ["screeningTab", "screeningPanel"],
    workspace: ["workspaceTab", "workspacePanel"]
  };
  const sectionCopy = {
    chat: ["知识普及", "科普助手"],
    screening: ["语音采集", "看图说话"],
    workspace: ["个人空间", "功能中心"]
  };
  if (!panels[target]) return;
  Object.entries(panels).forEach(([key, [tabId, panelId]]) => {
    const active = key === target;
    $(panelId).hidden = !active;
    $(tabId).classList.toggle("active", active);
    $(tabId).setAttribute("aria-selected", String(active));
    $(tabId).tabIndex = active ? 0 : -1;
  });
  document.body.dataset.activeSection = target;
  if ($("currentSectionKicker")) $("currentSectionKicker").textContent = sectionCopy[target][0];
  if ($("currentSectionName")) $("currentSectionName").textContent = sectionCopy[target][1];
}

function setStatus(element, message, type = "") {
  if (!element) return;
  element.textContent = message || "";
  element.className = `status-text ${type}`.trim();
}

async function readResponse(response) {
  const type = response.headers.get("content-type") || "";
  const body = type.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    const message = typeof body === "object" ? body.message || body.error : body;
    throw new Error(message || `请求失败（${response.status}）`);
  }
  return body;
}

async function api(path, options = {}, requireAuth = false) {
  const headers = new Headers(options.headers || {});
  if (requireAuth) {
    if (!state.token) throw new Error("请先登录");
    headers.set("Authorization", `Bearer ${state.token}`);
  }
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401 && requireAuth) {
    logout(false);
    setStatus($("authStatus"), "登录会话已过期，请重新登录。", "error");
  }
  return readResponse(response);
}

function releaseProtectedAudioUrl(playerId) {
  const objectUrl = state.protectedAudioUrls[playerId];
  if (!objectUrl) return;
  URL.revokeObjectURL(objectUrl);
  delete state.protectedAudioUrls[playerId];
}

async function playProtectedAudio(playerId, path, statusId) {
  const player = $(playerId);
  const status = $(statusId);
  try {
    if (!state.token) throw new Error("请先登录");
    setStatus(status, "正在加载音频...");

    const response = await fetch(path, {
      headers: { Authorization: `Bearer ${state.token}` }
    });
    if (response.status === 401) {
      logout(false);
      setStatus($("authStatus"), "登录会话已过期，请重新登录后播放。", "error");
      return;
    }
    if (!response.ok) await readResponse(response);

    const blob = await response.blob();
    if (!blob.size) throw new Error("服务器返回了空音频文件");

    player.pause();
    releaseProtectedAudioUrl(playerId);
    const objectUrl = URL.createObjectURL(blob);
    state.protectedAudioUrls[playerId] = objectUrl;
    player.src = objectUrl;
    player.hidden = false;
    player.load();
    await player.play();
    setStatus(status, "音频正在播放。", "success");
  } catch (error) {
    setStatus(status, `音频播放失败：${error.message}`, "error");
  }
}

function createButton(text, className, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.textContent = text;
  button.addEventListener("click", onClick);
  return button;
}

function renderEmpty(container, text) {
  container.replaceChildren();
  const empty = document.createElement("p");
  empty.className = "hint-text";
  empty.textContent = text;
  container.append(empty);
}

function appendUserMessage(text) {
  const article = document.createElement("article");
  article.className = "message user-message";
  const paragraph = document.createElement("p");
  paragraph.textContent = text;
  article.append(paragraph);
  $("chatMessages").append(article);
}

function appendAssistantMessage(data, target = null) {
  const article = target || document.createElement("article");
  article.replaceChildren();
  article.className = "message assistant-message";
  if (data.urgent) {
    article.classList.add("urgent-message");
    article.setAttribute("role", "alert");
  }
  const title = document.createElement("h3");
  title.textContent = data.title || "忆声助手";
  const answer = document.createElement("p");
  answer.textContent = data.answer ?? data.content ?? "暂时无法回答。";
  article.append(title, answer);

  if (Array.isArray(data.actionSuggestions) && data.actionSuggestions.length) {
    const list = document.createElement("ul");
    data.actionSuggestions.forEach((item) => {
      const li = document.createElement("li");
      li.textContent = item;
      list.append(li);
    });
    article.append(list);
  }

  if (data.medicalDisclaimer) {
    const boundary = document.createElement("p");
    boundary.textContent = `医疗边界：${data.medicalDisclaimer}`;
    boundary.style.marginTop = "12px";
    article.append(boundary);
  }

  if (Array.isArray(data.sources) && data.sources.length) {
    const sources = document.createElement("ul");
    sources.className = "source-list";
    data.sources.forEach((source) => {
      const li = document.createElement("li");
      const link = document.createElement("a");
      link.href = source.url;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = source.title;
      li.append(link);
      sources.append(li);
    });
    article.append(sources);
  }
  if (!target) $("chatMessages").append(article);
  $("chatMessages").scrollTop = $("chatMessages").scrollHeight;
  return article;
}

function assistantHeaders(extra = {}) {
  const headers = new Headers(extra);
  headers.set("X-Assistant-Client-Id", state.assistantClientId);
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  return headers;
}

function modelSettingsStorageKey() {
  const owner = state.username ? `user:${state.username}` : `anonymous:${state.assistantClientId}`;
  return `alzAssistantModelSettings:${encodeURIComponent(owner)}`;
}

function emptyModelProfile() {
  const providers = {};
  Object.entries(MODEL_PROVIDERS).forEach(([provider, meta]) => {
    providers[provider] = { model: meta.defaultModel, apiKey: "" };
  });
  return { activeProvider: "SYSTEM", providers };
}

function loadAssistantModelSettings() {
  const profile = emptyModelProfile();
  const saved = safeJsonParse(localStorage.getItem(modelSettingsStorageKey()) || "");
  if (saved && (saved.activeProvider === "SYSTEM" || MODEL_PROVIDERS[saved.activeProvider])) {
    profile.activeProvider = saved.activeProvider;
    Object.keys(MODEL_PROVIDERS).forEach((provider) => {
      const value = saved.providers?.[provider];
      if (!value) return;
      if (typeof value.model === "string" && value.model.trim()) profile.providers[provider].model = value.model.trim();
      if (typeof value.apiKey === "string") profile.providers[provider].apiKey = value.apiKey;
    });
  }
  state.assistantModelProfile = profile;
  renderModelSettingsEditor(profile.activeProvider);
  updateActiveModelBadge();
}

function renderModelSettingsEditor(provider) {
  const selected = provider === "SYSTEM" || MODEL_PROVIDERS[provider] ? provider : "SYSTEM";
  $("modelProvider").value = selected;
  const system = selected === "SYSTEM";
  const config = system ? { model: "", apiKey: "" } : state.assistantModelProfile.providers[selected];
  $("modelName").value = config.model;
  $("modelName").placeholder = system ? "由 start.ps1 决定" : MODEL_PROVIDERS[selected].defaultModel;
  $("modelApiKey").value = config.apiKey;
  $("modelName").disabled = system;
  $("modelApiKey").disabled = system;
  $("clearModelApiKey").disabled = system;
}

function updateActiveModelBadge() {
  const provider = state.assistantModelProfile?.activeProvider || "SYSTEM";
  if (provider === "SYSTEM") {
    $("activeModelBadge").textContent = "系统默认";
    return;
  }
  const config = state.assistantModelProfile.providers[provider];
  $("activeModelBadge").textContent = `${MODEL_PROVIDERS[provider].label} · ${config.model}`;
}

function persistAssistantModelSettings() {
  localStorage.setItem(modelSettingsStorageKey(), JSON.stringify(state.assistantModelProfile));
}

function saveAssistantModelSettings(event) {
  event.preventDefault();
  const provider = $("modelProvider").value;
  try {
    if (provider === "SYSTEM") {
      state.assistantModelProfile.activeProvider = "SYSTEM";
    } else {
      const model = $("modelName").value.trim() || MODEL_PROVIDERS[provider].defaultModel;
      const apiKey = $("modelApiKey").value.trim();
      if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$/.test(model)) throw new Error("模型名称格式无效");
      if (!apiKey) throw new Error("请输入所选服务商的 API Key");
      state.assistantModelProfile.providers[provider] = { model, apiKey };
      state.assistantModelProfile.activeProvider = provider;
    }
    persistAssistantModelSettings();
    updateActiveModelBadge();
    setStatus($("modelSettingsStatus"), "模型设置已保存，下一次提问立即生效。", "success");
  } catch (error) {
    setStatus($("modelSettingsStatus"), `保存失败：${error.message}`, "error");
  }
}

function clearSelectedModelApiKey() {
  const provider = $("modelProvider").value;
  if (provider === "SYSTEM") return;
  state.assistantModelProfile.providers[provider].apiKey = "";
  if (state.assistantModelProfile.activeProvider === provider) {
    state.assistantModelProfile.activeProvider = "SYSTEM";
  }
  $("modelApiKey").value = "";
  persistAssistantModelSettings();
  updateActiveModelBadge();
  setStatus($("modelSettingsStatus"), "该服务商的 API Key 已从此浏览器清除，并切回系统默认。", "success");
}

function activeAssistantModelSettings() {
  const profile = state.assistantModelProfile || emptyModelProfile();
  const provider = profile.activeProvider;
  if (provider === "SYSTEM") return null;
  const config = profile.providers[provider];
  if (!config?.apiKey) throw new Error("当前模型缺少 API Key，请先打开“回答模型”保存设置");
  return { provider, model: config.model, apiKey: config.apiKey };
}

async function assistantApi(path, options = {}) {
  const response = await fetch(path, { ...options, headers: assistantHeaders(options.headers) });
  return readResponse(response);
}

function renderAssistantWelcome() {
  $("chatMessages").replaceChildren();
  appendAssistantMessage({ title: "你好，我是忆声助手", answer: "我可以解释常见表现、语音筛查、就医评估、风险管理、治疗常识和家庭照护。" });
}

function updateConversationHeader(conversation) {
  $("currentConversationTitle").textContent = conversation?.title || "新对话";
  $("conversationTurnCount").textContent = `${conversation?.turnCount || 0} / ${conversation?.maxTurns || 100} 轮`;
  $("chatSend").disabled = state.chatStreaming || !conversation || conversation.turnCount >= conversation.maxTurns;
}

function renderConversationList() {
  const container = $("conversationList");
  container.replaceChildren();
  state.assistantConversations.forEach((conversation) => {
    const row = document.createElement("div"); row.className = "conversation-row";
    const select = document.createElement("button"); select.type = "button";
    select.className = `conversation-select${conversation.id === state.currentConversationId ? " active" : ""}`;
    select.textContent = conversation.title; select.title = `${conversation.title}（${conversation.turnCount}/${conversation.maxTurns}轮）`;
    select.addEventListener("click", () => selectConversation(conversation.id));
    const remove = document.createElement("button"); remove.type = "button"; remove.className = "conversation-delete"; remove.textContent = "×";
    remove.setAttribute("aria-label", `删除对话 ${conversation.title}`); remove.addEventListener("click", () => deleteConversation(conversation.id));
    row.append(select, remove); container.append(row);
  });
}

async function initializeAssistantConversations() {
  const version = ++state.assistantLoadVersion;
  state.currentConversationId = ""; renderAssistantWelcome(); updateConversationHeader(null);
  try {
    let conversations = await assistantApi("/assistant/conversations");
    if (version !== state.assistantLoadVersion) return;
    if (!conversations.length) {
      const created = await assistantApi("/assistant/conversations", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
      conversations = [created];
    }
    state.assistantConversations = conversations; renderConversationList(); await selectConversation(conversations[0].id, version);
  } catch (error) {
    if (version !== state.assistantLoadVersion) return;
    appendAssistantMessage({ title: "对话加载失败", answer: error.message });
  }
}

async function createConversation() {
  if (state.chatStreaming) return;
  try {
    const conversation = await assistantApi("/assistant/conversations", { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" });
    state.assistantConversations.unshift(conversation); renderConversationList(); await selectConversation(conversation.id);
  } catch (error) { appendAssistantMessage({ title: "无法新建对话", answer: error.message }); }
}

async function deleteConversation(id) {
  if (state.chatStreaming || !window.confirm("确定删除这个对话及其全部消息吗？")) return;
  try {
    await assistantApi(`/assistant/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
    state.assistantConversations = state.assistantConversations.filter((item) => item.id !== id);
    if (!state.assistantConversations.length) await createConversation();
    else if (state.currentConversationId === id) await selectConversation(state.assistantConversations[0].id);
    else renderConversationList();
  } catch (error) { appendAssistantMessage({ title: "无法删除对话", answer: error.message }); }
}

async function selectConversation(id, expectedVersion = state.assistantLoadVersion) {
  if (state.chatStreaming || !id) return;
  try {
    const detail = await assistantApi(`/assistant/conversations/${encodeURIComponent(id)}`);
    if (expectedVersion !== state.assistantLoadVersion) return;
    state.currentConversationId = id;
    const index = state.assistantConversations.findIndex((item) => item.id === id);
    if (index >= 0) state.assistantConversations[index] = detail.conversation;
    renderConversationList(); updateConversationHeader(detail.conversation); $("chatMessages").replaceChildren();
    if (!detail.messages.length) { renderAssistantWelcome(); return; }
    detail.messages.forEach((message) => message.role === "user" ? appendUserMessage(message.content) : appendAssistantMessage({ ...message, answer: message.content }));
  } catch (error) { appendAssistantMessage({ title: "对话加载失败", answer: error.message }); }
}

function parseSseBlock(block) {
  let event = "message"; const data = [];
  block.split(/\r?\n/).forEach((line) => { if (line.startsWith("event:")) event = line.slice(6).trim(); if (line.startsWith("data:")) data.push(line.slice(5).trim()); });
  return { event, data: data.length ? JSON.parse(data.join("\n")) : {} };
}

async function askQuestion(question) {
  const value = String(question || "").trim();
  if (!value || state.chatStreaming || !state.currentConversationId) return;
  let modelSettings;
  try { modelSettings = activeAssistantModelSettings(); }
  catch (error) { $("modelSettingsPanel").open = true; setStatus($("modelSettingsStatus"), error.message, "error"); return; }
  const active = state.assistantConversations.find((item) => item.id === state.currentConversationId);
  if (active && active.turnCount >= active.maxTurns) { appendAssistantMessage({ title: "本对话已满", answer: "每个对话最多 100 轮，请点击“新对话”继续。" }); return; }
  appendUserMessage(value); $("chatInput").value = ""; state.chatStreaming = true; $("chatSend").disabled = true;
  const placeholder = document.createElement("article"); placeholder.className = "message assistant-message";
  const title = document.createElement("h3"); title.textContent = "正在准备回答…";
  const progress = document.createElement("section"); progress.className = "stream-progress"; progress.setAttribute("aria-live", "polite");
  const progressLabel = document.createElement("strong"); progressLabel.className = "stream-progress-label"; progressLabel.textContent = "检索状态";
  const statusText = document.createElement("p"); statusText.className = "stream-status"; statusText.textContent = "正在建立流式连接…";
  const analysis = document.createElement("div"); analysis.className = "stream-analysis"; analysis.hidden = true;
  progress.append(progressLabel, statusText, analysis);
  const sourcePanel = document.createElement("section"); sourcePanel.className = "stream-sources"; sourcePanel.hidden = true;
  const sourceLabel = document.createElement("strong"); sourceLabel.textContent = "实时检索来源";
  const sourceList = document.createElement("ul"); sourceList.className = "stream-source-list"; sourcePanel.append(sourceLabel, sourceList);
  const answer = document.createElement("p"); answer.className = "stream-answer streaming-cursor";
  placeholder.append(title, progress, sourcePanel, answer); $("chatMessages").append(placeholder);
  const streamedSourceUrls = new Set(); let answerStarted = false;
  try {
    const response = await fetch(`/assistant/conversations/${encodeURIComponent(state.currentConversationId)}/messages/stream`, {
      method: "POST", headers: assistantHeaders({ "Content-Type": "application/json", "Accept": "text/event-stream" }), body: JSON.stringify({ message: value, modelSettings })
    });
    if (!response.ok) throw new Error((await readResponse(response)) || `请求失败（${response.status}）`);
    if (!response.body) throw new Error("当前浏览器不支持流式响应");
    const reader = response.body.getReader(); const decoder = new TextDecoder("utf-8"); let buffer = ""; let completed = false;
    while (true) {
      const { value: chunk, done } = await reader.read(); buffer += decoder.decode(chunk || new Uint8Array(), { stream: !done });
      const blocks = buffer.split(/\r?\n\r?\n/); buffer = blocks.pop() || "";
      // A valid final SSE event may arrive without a trailing blank line when
      // an intermediary closes the response immediately after the payload.
      if (done && buffer.trim()) { blocks.push(buffer); buffer = ""; }
      for (const block of blocks) {
        if (!block.trim()) continue; const message = parseSseBlock(block);
        if (message.event === "status") {
          statusText.textContent = message.data.message || "正在处理…";
        } else if (message.event === "analysis") {
          const content = String(message.data.content || "").trim();
          if (content) { analysis.hidden = false; const item = document.createElement("p"); item.textContent = content; analysis.append(item); }
        } else if (message.event === "source") {
          const url = String(message.data.url || "").trim();
          try {
            const parsed = new URL(url);
            if (["http:", "https:"].includes(parsed.protocol) && !streamedSourceUrls.has(parsed.href)) {
              streamedSourceUrls.add(parsed.href); sourcePanel.hidden = false;
              const item = document.createElement("li"); const link = document.createElement("a");
              link.href = parsed.href; link.target = "_blank"; link.rel = "noopener noreferrer";
              link.textContent = message.data.title || parsed.hostname; item.append(link); sourceList.append(item);
            }
          } catch (_) { /* Ignore malformed source URLs from an upstream service. */ }
        } else if (message.event === "delta") {
          if (!answerStarted) { answerStarted = true; title.textContent = "正在生成回答…"; }
          answer.textContent += message.data.content || ""; $("chatMessages").scrollTop = $("chatMessages").scrollHeight;
        }
        else if (message.event === "complete") {
          completed = true;
          const processSummaries = Array.from(analysis.querySelectorAll("p"), (item) => item.textContent).filter(Boolean);
          appendAssistantMessage(message.data.response, placeholder);
          if (processSummaries.length) {
            const details = document.createElement("details"); details.className = "stream-process-summary";
            const summary = document.createElement("summary"); summary.textContent = "查看检索与分析摘要"; details.append(summary);
            processSummaries.forEach((content) => { const item = document.createElement("p"); item.textContent = content; details.append(item); });
            placeholder.insertBefore(details, placeholder.children[1] || null);
          }
          const conversation = message.data.conversation;
          const index = state.assistantConversations.findIndex((item) => item.id === conversation.id); if (index >= 0) state.assistantConversations[index] = conversation;
          state.assistantConversations.sort((a, b) => a.id === conversation.id ? -1 : b.id === conversation.id ? 1 : 0); updateConversationHeader(conversation); renderConversationList();
        } else if (message.event === "error") throw new Error(message.data.message || "回答生成失败");
      }
      if (done) break;
    }
    if (!completed) throw new Error("流式连接提前结束，请稍后重试");
  } catch (error) { appendAssistantMessage({ title: "回答中断", answer: error.message }, placeholder); }
  finally { state.chatStreaming = false; const current = state.assistantConversations.find((item) => item.id === state.currentConversationId); updateConversationHeader(current); }
}

function updateAuthUi() {
  loadAssistantModelSettings();
  const loggedIn = Boolean(state.token);
  $("authCard").hidden = loggedIn;
  $("screeningWorkspace").hidden = !loggedIn;
  $("workspaceLoginNotice").hidden = loggedIn;
  $("workspaceContent").hidden = !loggedIn;
  $("loginIdentity").textContent = loggedIn
    ? `当前账号：${state.username}${state.role ? `（${state.role}）` : ""}`
    : "未登录";
  document.querySelectorAll(".admin-only").forEach((item) => {
    item.hidden = state.role !== "ADMIN";
  });
  if (loggedIn) {
    loadHistory();
    loadProfile();
    refreshAudioSelectors();
    loadPdfList();
    loadScreeningTasks().then((tasks) => {
      if (tasks.some((task) => !["COMPLETED", "FAILED", "CANCELLED"].includes(task.status))) {
        startScreeningPolling();
      }
    });
    if (state.role === "ADMIN") {
      loadAdminUsers();
      loadAdminStats();
    }
  }
  initializeAssistantConversations();
}

async function authenticate(register = false) {
  const username = $("username").value.trim();
  const password = $("password").value;
  if (!username || !password) {
    setStatus($("authStatus"), "请输入用户名和密码。", "error");
    return;
  }
  setStatus($("authStatus"), register ? "正在注册..." : "正在登录...");
  try {
    if (register) {
      const message = await api("/user/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });
      if (!String(message).includes("成功")) throw new Error(String(message));
    }
    const token = await api("/user/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    });
    if (typeof token !== "string" || token.split(".").length !== 3) {
      throw new Error(String(token || "登录失败"));
    }
    const payload = parseJwtPayload(token);
    state.token = token;
    state.username = username;
    state.role = payload.role || payload.authority || "";
    sessionStorage.setItem("alzToken", token);
    sessionStorage.setItem("alzUsername", username);
    sessionStorage.setItem("alzRole", state.role);
    setStatus($("authStatus"), "登录成功。", "success");
    updateAuthUi();
  } catch (error) {
    setStatus($("authStatus"), error.message, "error");
  }
}

function logout(showMessage = true) {
  if (state.screeningPollTimer) {
    window.clearInterval(state.screeningPollTimer);
    state.screeningPollTimer = null;
  }
  Object.keys(state.protectedAudioUrls).forEach((playerId) => {
    const player = $(playerId);
    if (player) {
      player.pause();
      player.removeAttribute("src");
      player.load();
    }
    releaseProtectedAudioUrl(playerId);
  });
  state.token = "";
  state.username = "";
  state.role = "";
  sessionStorage.removeItem("alzToken");
  sessionStorage.removeItem("alzUsername");
  sessionStorage.removeItem("alzRole");
  updateAuthUi();
  if (showMessage) setStatus($("authStatus"), "已退出登录。", "success");
}

function initializeImageTasks() {
  const select = $("imageSelect");
  select.replaceChildren();
  IMAGE_OPTIONS.forEach((item) => {
    const option = document.createElement("option");
    option.value = item.value;
    option.textContent = item.label;
    select.append(option);
  });
  select.addEventListener("change", updateSelectedImage);
  updateSelectedImage();
}

function selectedImageOption() {
  return IMAGE_OPTIONS.find((item) => item.value === $("imageSelect").value) || IMAGE_OPTIONS[0];
}

function updateSelectedImage() {
  const selected = selectedImageOption();
  $("taskImage").src = `/${selected.value}`;
  $("taskImage").alt = selected.label;
  $("imageHint").textContent = selected.hint;
}

async function startRecording() {
  if (!$("consentCheckbox").checked) {
    setStatus($("screeningStatus"), "请先阅读并勾选知情同意。", "error");
    return;
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    setStatus($("screeningStatus"), "当前浏览器不支持录音，请上传 PCM WAV 文件。", "error");
    return;
  }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1 } });
    state.audioContext = new AudioContext();
    state.sourceNode = state.audioContext.createMediaStreamSource(state.stream);
    state.processorNode = state.audioContext.createScriptProcessor(4096, 1, 1);
    state.chunks = [];
    state.processorNode.onaudioprocess = (event) => {
      state.chunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
    };
    state.sourceNode.connect(state.processorNode);
    state.processorNode.connect(state.audioContext.destination);
    state.recordingStartedAt = Date.now();
    state.timer = window.setInterval(updateRecordingTime, 500);
    $("startRecording").disabled = true;
    $("stopRecording").disabled = false;
    $("recordingIndicator").classList.add("recording");
    $("recordingText").textContent = "正在录音，请根据图片自然讲述";
    setStatus($("screeningStatus"), "录音已开始。请保持正常语速，无需追求完美。", "success");
  } catch (error) {
    setStatus($("screeningStatus"), `无法使用麦克风：${error.message}`, "error");
  }
}

function updateRecordingTime() {
  const elapsed = Math.floor((Date.now() - state.recordingStartedAt) / 1000);
  $("recordingTime").textContent = `${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}`;
}

async function stopRecording() {
  if (!state.audioContext) return;
  window.clearInterval(state.timer);
  state.durationSeconds = Math.max(1, Math.round((Date.now() - state.recordingStartedAt) / 1000));
  state.processorNode.disconnect();
  state.sourceNode.disconnect();
  state.stream.getTracks().forEach((track) => track.stop());
  const sampleRate = state.audioContext.sampleRate;
  await state.audioContext.close();
  const samples = mergeAudioChunks(state.chunks);
  state.audioBlob = encodeWav(samples, sampleRate);
  state.audioFile = null;
  showPreview(state.audioBlob);
  $("startRecording").disabled = false;
  $("stopRecording").disabled = true;
  $("uploadAudio").disabled = false;
  $("recordingIndicator").classList.remove("recording");
  $("recordingText").textContent = "录音完成，可以试听和上传";
  setStatus($("screeningStatus"), `录音完成，共 ${state.durationSeconds} 秒。`, "success");
  state.audioContext = null;
}

function mergeAudioChunks(chunks) {
  const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const merged = new Float32Array(length);
  let offset = 0;
  chunks.forEach((chunk) => {
    merged.set(chunk, offset);
    offset += chunk.length;
  });
  return merged;
}

function encodeWav(samples, sampleRate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const writeText = (offset, text) => [...text].forEach((char, index) => view.setUint8(offset + index, char.charCodeAt(0)));
  writeText(0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  writeText(8, "WAVE");
  writeText(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeText(36, "data");
  view.setUint32(40, samples.length * 2, true);
  let offset = 44;
  samples.forEach((sample) => {
    const clipped = Math.max(-1, Math.min(1, sample));
    view.setInt16(offset, clipped < 0 ? clipped * 32768 : clipped * 32767, true);
    offset += 2;
  });
  return new Blob([view], { type: "audio/wav" });
}

function showPreview(blob) {
  if (state.previewUrl) URL.revokeObjectURL(state.previewUrl);
  state.previewUrl = URL.createObjectURL(blob);
  $("audioPreview").src = state.previewUrl;
  $("audioPreview").hidden = false;
}

async function readFileDuration(file) {
  return new Promise((resolve) => {
    const audio = document.createElement("audio");
    const url = URL.createObjectURL(file);
    audio.addEventListener("loadedmetadata", () => {
      const duration = Number.isFinite(audio.duration) ? Math.round(audio.duration) : 0;
      URL.revokeObjectURL(url);
      resolve(duration);
    });
    audio.addEventListener("error", () => {
      URL.revokeObjectURL(url);
      resolve(0);
    });
    audio.src = url;
  });
}

async function selectAudioFile() {
  const file = $("audioFile").files[0];
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".wav")) {
    setStatus($("screeningStatus"), "请选择 PCM WAV 文件。", "error");
    $("uploadAudio").disabled = true;
    return;
  }
  state.audioFile = file;
  state.audioBlob = null;
  state.durationSeconds = await readFileDuration(file);
  showPreview(file);
  $("uploadAudio").disabled = false;
  setStatus($("screeningStatus"), `已选择 ${file.name}。`, "success");
}

async function uploadAudio() {
  if (!$("consentCheckbox").checked) {
    setStatus($("screeningStatus"), "请先阅读并勾选知情同意。", "error");
    return;
  }
  const audio = state.audioFile || state.audioBlob;
  if (!audio) {
    setStatus($("screeningStatus"), "请先录音或选择 WAV 文件。", "error");
    return;
  }
  $("uploadAudio").disabled = true;
  setStatus($("screeningStatus"), "正在上传录音...");
  const image = selectedImageOption();
  const form = new FormData();
  form.append("file", audio, state.audioFile?.name || `picture-task-${Date.now()}.wav`);
  form.append("duration", String(Math.max(0, state.durationSeconds)));
  form.append("imageName", image.value);
  form.append("consentAccepted", "true");
  form.append("consentVersion", SCREENING_CONSENT_VERSION);
  form.append("taskType", "NATURAL_SPEECH");
  try {
    await api("/audio/upload", { method: "POST", body: form }, true);
    setStatus($("screeningStatus"), "上传成功。请在历史录音中开始风险分析。", "success");
    await loadHistory();
    await refreshAudioSelectors();
  } catch (error) {
    setStatus($("screeningStatus"), error.message, "error");
  } finally {
    $("uploadAudio").disabled = false;
  }
}

function imageLabel(name) {
  return IMAGE_OPTIONS.find((item) => item.value === name)?.label || name || "未记录";
}

async function loadHistory() {
  if (!state.token) return;
  const container = $("audioHistory");
  container.replaceChildren();
  try {
    const records = await api("/audio/my", {}, true);
    state.audioRecords = Array.isArray(records) ? records : [];
    if (state.audioRecords.length === 0) {
      renderEmpty(container, "还没有上传过录音。");
      return;
    }
    state.audioRecords.forEach((record) => {
      const row = document.createElement("div");
      row.className = "audio-row";
      const info = document.createElement("div");
      const name = document.createElement("strong");
      name.textContent = record.filePath;
      const meta = document.createElement("small");
      meta.textContent = `${record.duration ?? 0} 秒 · ${record.uploadTime || "时间未知"} · ${imageLabel(record.imageName)}`;
      info.append(name, meta);
      const actions = document.createElement("div");
      actions.className = "button-row";
      actions.append(
        createButton("播放", "secondary-button", () => playUserAudio(record.filePath)),
        createButton("风险分析", "secondary-button", (event) => runScreening(record.filePath, event.currentTarget)),
        createButton("删除录音", "danger-button", (event) => deleteAudio(record.filePath, event.currentTarget))
      );
      row.append(info, actions);
      container.append(row);
    });
    renderAudioTable();
  } catch (error) {
    setStatus($("screeningStatus"), error.message, "error");
  }
}

function playUserAudio(fileName) {
  return playProtectedAudio(
    "historyAudioPlayer",
    `/audio/file/${encodeURIComponent(fileName)}`,
    "screeningStatus"
  );
}

async function downloadUserAudio(fileName) {
  const response = await fetch(`/audio/file/${encodeURIComponent(fileName)}`, {
    headers: { Authorization: `Bearer ${state.token}` }
  });
  if (!response.ok) throw new Error("下载失败");
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

async function deleteAudio(audioName, button) {
  if (!window.confirm("确定删除这段录音及其筛查结果和 PDF 报告吗？此操作无法撤销。")) return;
  button.disabled = true;
  setStatus($("screeningStatus"), "正在删除敏感数据...");
  try {
    await api(`/audio/${encodeURIComponent(audioName)}`, { method: "DELETE" }, true);
    $("resultCard").hidden = true;
    setStatus($("screeningStatus"), "录音及关联筛查数据已删除。", "success");
    await loadHistory();
    await refreshAudioSelectors();
    await loadPdfList();
  } catch (error) {
    setStatus($("screeningStatus"), error.message, "error");
    button.disabled = false;
  }
}

async function runScreening(audioName, button) {
  button.disabled = true;
  setStatus($("screeningStatus"), "正在提交后台筛查任务...");
  try {
    const task = await submitScreeningTask(audioName);
    setStatus($("screeningStatus"), task.message || "任务已进入后台，可以安全退出页面。", "success");
    await loadScreeningTasks();
    startScreeningPolling();
  } catch (error) {
    setStatus($("screeningStatus"), error.message, "error");
  } finally {
    button.disabled = false;
  }
}

function renderList(id, items, emptyText) {
  const list = $(id);
  list.replaceChildren();
  const values = Array.isArray(items) && items.length ? items : [emptyText];
  values.forEach((value) => {
    const li = document.createElement("li");
    li.textContent = value;
    list.append(li);
  });
}

function renderResult(result) {
  const levelLabels = { LOW: "较低", ELEVATED: "升高", HIGH: "较高", INCONCLUSIVE: "无法判定" };
  const level = result.riskLevel || "INCONCLUSIVE";
  const completed = result.screeningStatus === "COMPLETED";
  $("resultCard").hidden = false;
  $("resultTitle").textContent = completed ? "风险筛查已完成" : "需要重新采集或人工复核";
  $("resultBadge").textContent = levelLabels[level] || "无法判定";
  $("resultBadge").className = `result-badge ${String(level).toLowerCase()}`;
  $("resultBoundary").textContent = result.medicalDisclaimer || "该结果不能替代临床诊断。";
  $("riskLevel").textContent = levelLabels[level] || level;
  $("riskScore").textContent = typeof result.riskScore === "number" ? result.riskScore.toFixed(2) : "未提供";
  $("qualityPassed").textContent = result.qualityPassed === true ? "通过" : result.qualityPassed === false ? "未通过" : "未知";
  $("modelVersion").textContent = result.modelVersion || "未提供";
  $("reportText").textContent = result.report || "未提供结果解释。";
  $("transcriptionText").textContent = result.transcription || "未提供转写文本。";
  renderList("qualityIssues", result.qualityIssues, "未报告质量问题");
  renderList("featureHighlights", result.featureHighlights, "未提供可解释特征");
  renderList("recommendedActions", result.recommendedActions, "如仍有担心，请咨询正规医疗机构");
}

function fillSelect(select, records, placeholder = "请选择") {
  select.replaceChildren();
  const empty = document.createElement("option");
  empty.value = "";
  empty.textContent = placeholder;
  select.append(empty);
  records.forEach((record) => {
    const option = document.createElement("option");
    option.value = record.filePath || record;
    option.textContent = record.filePath || record;
    select.append(option);
  });
}

async function refreshAudioSelectors() {
  if (!state.token) return;
  if (!state.audioRecords.length) {
    const records = await api("/audio/my", {}, true);
    state.audioRecords = Array.isArray(records) ? records : [];
  }
  fillSelect($("detectAudioSelect"), state.audioRecords);
  fillSelect($("diagnosisAudioSelect"), state.audioRecords);
}

async function loadProfile() {
  if (!state.token) return;
  try {
    const profile = await api("/user/profile/get", {}, true);
    if (typeof profile === "object" && profile) {
      const form = $("profileForm");
      ["name", "gender", "age", "phone", "medicalHistory", "mmse", "moca", "hkbc"].forEach((field) => {
        if (form.elements[field]) form.elements[field].value = profile[field] ?? "";
      });
    }
  } catch (error) {
    setStatus($("profileStatus"), error.message, "error");
  }
}

async function saveProfile() {
  const form = $("profileForm");
  const data = {
    name: form.elements.name.value.trim(),
    gender: form.elements.gender.value.trim(),
    age: Number(form.elements.age.value || 0),
    phone: form.elements.phone.value.trim(),
    medicalHistory: form.elements.medicalHistory.value.trim()
  };
  try {
    await api("/user/profile/update", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    }, true);
    setStatus($("profileStatus"), "个人信息已保存。", "success");
  } catch (error) {
    setStatus($("profileStatus"), error.message, "error");
  }
}

async function changePassword() {
  const form = $("passwordForm");
  const oldPassword = form.elements.oldPassword.value;
  const newPassword = form.elements.newPassword.value;
  if (!oldPassword || !newPassword) {
    setStatus($("profileStatus"), "请输入旧密码和新密码。", "error");
    return;
  }
  try {
    const result = await api("/user/change-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ oldPassword, newPassword })
    }, true);
    setStatus($("profileStatus"), result.message || "密码已更新。", result.success === false ? "error" : "success");
    form.reset();
  } catch (error) {
    setStatus($("profileStatus"), error.message, "error");
  }
}

function renderAudioTable() {
  const container = $("audioTable");
  if (!container) return;
  if (!state.audioRecords.length) {
    renderEmpty(container, "暂无历史录音。");
    return;
  }
  container.replaceChildren();
  const table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>文件名</th><th>时长</th><th>上传时间</th><th>图片任务</th><th>操作</th></tr></thead>";
  const tbody = document.createElement("tbody");
  state.audioRecords.forEach((record) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${record.filePath || ""}</td>
      <td>${record.duration ?? 0} 秒</td>
      <td>${record.uploadTime || ""}</td>
      <td>${imageLabel(record.imageName)}</td>
      <td></td>`;
    const actions = document.createElement("div");
    actions.className = "table-actions";
    actions.append(
      createButton("播放", "secondary-button small-button", () => playUserAudio(record.filePath)),
      createButton("下载", "secondary-button small-button", async () => {
        try {
          await downloadUserAudio(record.filePath);
          setStatus($("audioListStatus"), "下载已开始。", "success");
        } catch (error) {
          setStatus($("audioListStatus"), error.message, "error");
        }
      }),
      createButton("删除", "danger-button small-button", (event) => deleteAudio(record.filePath, event.currentTarget))
    );
    tr.lastElementChild.append(actions);
    tbody.append(tr);
  });
  table.append(tbody);
  container.append(table);
}

async function loadAudioList() {
  await loadHistory();
  await refreshAudioSelectors();
  setStatus($("audioListStatus"), "历史录音已刷新。", "success");
}

function renderDetectResult(container, result) {
  container.hidden = false;
  const ad = Number(result.ad_probability);
  const normal = Number(result.normal_probability);
  container.innerHTML = `
    <div><dt>标签</dt><dd>${result.label ?? "未提供"}</dd></div>
    <div><dt>AD 概率</dt><dd>${Number.isFinite(ad) ? ad.toFixed(4) : "未提供"}</dd></div>
    <div><dt>正常概率</dt><dd>${Number.isFinite(normal) ? normal.toFixed(4) : "未提供"}</dd></div>`;
}

async function runDetect() {
  const fileName = $("detectAudioSelect").value;
  if (!fileName) {
    setStatus($("detectStatus"), "请选择录音。", "error");
    return;
  }
  $("runDetect").disabled = true;
  setStatus($("detectStatus"), "正在调用自我检测接口...");
  try {
    const result = await api("/user/detect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fileName })
    }, true);
    renderDetectResult($("detectResult"), result);
    setStatus($("detectStatus"), "检测完成。", "success");
  } catch (error) {
    setStatus($("detectStatus"), error.message, "error");
  } finally {
    $("runDetect").disabled = false;
  }
}

async function runDiagnosis() {
  const fileName = $("diagnosisAudioSelect").value;
  if (!fileName) {
    setStatus($("diagnosisStatus"), "请选择录音。", "error");
    return;
  }
  $("runDiagnosis").disabled = true;
  setStatus($("diagnosisStatus"), "正在提交后台筛查任务...");
  try {
    const task = await submitScreeningTask(fileName);
    setStatus($("diagnosisStatus"), task.message || "任务已进入后台，可以退出页面。", "success");
    await loadScreeningTasks();
    startScreeningPolling();
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  } finally {
    $("runDiagnosis").disabled = false;
  }
}

function newIdempotencyKey() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  return `screening-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function submitScreeningTask(audioName) {
  return api(`/audio/screening/${encodeURIComponent(audioName)}`, {
    method: "POST",
    headers: { "Idempotency-Key": newIdempotencyKey() }
  }, true);
}

const TASK_STATUS_LABELS = {
  QUEUED: "已进入队列",
  TRANSCRIBING: "正在识别语音",
  FEATURE_EXTRACTING: "正在提取特征",
  LLM_ANALYZING: "正在生成筛查分析",
  RESULT_PERSISTING: "正在保存结果",
  PDF_QUEUED: "等待生成 PDF",
  PDF_GENERATING: "正在生成 PDF",
  COMPLETED: "报告已完成",
  RETRY_WAIT: "系统正在重试",
  FAILED: "处理失败",
  CANCEL_REQUESTED: "正在取消",
  CANCELLED: "已取消"
};

async function loadScreeningTasks() {
  if (!state.token || !$("screeningTaskTable")) return [];
  const container = $("screeningTaskTable");
  try {
    const tasks = await api("/api/v1/screenings?page=0&size=20", {}, true);
    if (!Array.isArray(tasks) || tasks.length === 0) {
      renderEmpty(container, "暂无后台筛查任务。");
      return [];
    }
    container.replaceChildren();
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>提交时间</th><th>录音</th><th>状态</th><th>进度</th><th>操作</th></tr></thead>";
    const tbody = document.createElement("tbody");
    tasks.forEach((task) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `<td>${task.requestedAt || ""}</td><td>${task.audioName || ""}</td>`
        + `<td>${TASK_STATUS_LABELS[task.status] || task.status}</td><td>${task.progress ?? 0}%</td><td></td>`;
      const actions = document.createElement("div");
      actions.className = "table-actions";
      if (task.status === "COMPLETED" && task.result) {
        actions.append(createButton("查看结果", "secondary-button small-button", () => showCompletedTask(task)));
      }
      if (task.links?.pdf) {
        const pdfName = decodeURIComponent(task.links.pdf.split("/").pop());
        actions.append(createButton("下载 PDF", "secondary-button small-button", () => downloadPdf(pdfName)));
      }
      if (["QUEUED", "TRANSCRIBING", "FEATURE_EXTRACTING", "LLM_ANALYZING", "RETRY_WAIT"].includes(task.status)) {
        actions.append(createButton("取消", "danger-button small-button", () => cancelScreeningTask(task.taskId)));
      }
      if (["FAILED", "CANCELLED"].includes(task.status)) {
        const label = task.status === "CANCELLED" ? "重新筛查" : "重试";
        actions.append(createButton(label, "secondary-button small-button", (event) => {
          retryScreeningTask(task.taskId, event.currentTarget);
        }));
      }
      tr.lastElementChild.append(actions);
      tbody.append(tr);
    });
    table.append(tbody);
    container.append(table);
    return tasks;
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
    return [];
  }
}

function showCompletedTask(task) {
  state.currentDiagnosis = { audioName: task.audioName, ...task.result };
  renderDiagnosisPreview(state.currentDiagnosis);
  renderResult(task.result);
  $("resultCard").scrollIntoView({ behavior: "smooth", block: "start" });
}

async function cancelScreeningTask(taskId) {
  try {
    const task = await api(`/api/v1/screenings/${encodeURIComponent(taskId)}`, { method: "DELETE" }, true);
    setStatus($("diagnosisStatus"), task.message || "已请求取消。", "success");
    await loadScreeningTasks();
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  }
}

async function retryScreeningTask(taskId, button) {
  button.disabled = true;
  setStatus($("diagnosisStatus"), "正在重新提交筛查任务...");
  try {
    const task = await api(`/api/v1/screenings/${encodeURIComponent(taskId)}/retry`, {
      method: "POST",
      headers: { "Idempotency-Key": newIdempotencyKey() }
    }, true);
    setStatus($("diagnosisStatus"), task.message || "任务已重新进入筛查队列。", "success");
    await loadScreeningTasks();
    startScreeningPolling();
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  } finally {
    button.disabled = false;
  }
}

function startScreeningPolling() {
  if (state.screeningPollTimer) return;
  state.screeningPollTimer = window.setInterval(async () => {
    const tasks = await loadScreeningTasks();
    const active = tasks.some((task) => !["COMPLETED", "FAILED", "CANCELLED"].includes(task.status));
    if (!active) {
      window.clearInterval(state.screeningPollTimer);
      state.screeningPollTimer = null;
      await loadPdfList();
    }
  }, 5000);
}

function renderDiagnosisPreview(data) {
  const container = $("diagnosisPreview");
  container.hidden = false;
  container.replaceChildren();
  const title = document.createElement("h4");
  title.textContent = data.audioName || "当前报告";
  const transcription = document.createElement("pre");
  transcription.textContent = data.transcription || "未提供转写文本。";
  const report = document.createElement("pre");
  report.textContent = data.report || "未提供报告。";
  container.append(title, labeledBlock("转写文本", transcription), labeledBlock("报告内容", report));
}

function labeledBlock(label, node) {
  const section = document.createElement("section");
  const heading = document.createElement("strong");
  heading.textContent = label;
  section.append(heading, node);
  return section;
}

async function savePdf() {
  if (!state.currentDiagnosis?.report) {
    setStatus($("diagnosisStatus"), "没有可保存的报告。", "error");
    return;
  }
  try {
    const pdfName = await api("/audio/pdf/save", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        audioName: state.currentDiagnosis.audioName,
        transcription: state.currentDiagnosis.transcription,
        report: state.currentDiagnosis.report
      })
    }, true);
    setStatus($("diagnosisStatus"), `PDF 已保存：${pdfName}`, "success");
    await loadPdfList();
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  }
}

async function loadPdfList() {
  if (!state.token) return;
  const container = $("pdfTable");
  try {
    const rows = await api("/audio/pdf/list", {}, true);
    if (!Array.isArray(rows) || rows.length === 0) {
      renderEmpty(container, "暂无历史 PDF 报告。");
      return;
    }
    container.replaceChildren();
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>时间</th><th>录音文件</th><th>PDF</th><th>操作</th></tr></thead>";
    const tbody = document.createElement("tbody");
    rows.forEach((row) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `<td>${row.createTime || ""}</td><td>${row.audioName || ""}</td><td>${row.pdfName || ""}</td><td></td>`;
      const actions = document.createElement("div");
      actions.className = "table-actions";
      actions.append(
        createButton("查看", "secondary-button small-button", () => viewPdfReport(row.pdfName)),
        createButton("下载", "secondary-button small-button", () => downloadPdf(row.pdfName)),
        createButton("删除", "danger-button small-button", () => deletePdf(row.pdfName))
      );
      tr.lastElementChild.append(actions);
      tbody.append(tr);
    });
    table.append(tbody);
    container.append(table);
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  }
}

async function viewPdfReport(pdfName) {
  try {
    const data = await api(`/audio/pdf/view/${encodeURIComponent(pdfName)}`, {}, true);
    const container = $("historyReportPreview");
    container.hidden = false;
    container.innerHTML = "";
    const title = document.createElement("h4");
    title.textContent = pdfName;
    const transcription = document.createElement("pre");
    transcription.textContent = data.transcription || "未提供转写文本。";
    const report = document.createElement("pre");
    report.textContent = data.report || "未提供报告。";
    container.append(title, labeledBlock("历史转写", transcription), labeledBlock("历史报告", report));
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  }
}

function downloadPdf(pdfName) {
  fetch(`/audio/pdf/${encodeURIComponent(pdfName)}`, {
    headers: { Authorization: `Bearer ${state.token}` }
  })
    .then((response) => {
      if (!response.ok) throw new Error("PDF 下载失败");
      return response.blob();
    })
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      window.open(url, "_blank", "noopener");
      window.setTimeout(() => URL.revokeObjectURL(url), 30000);
    })
    .catch((error) => setStatus($("diagnosisStatus"), error.message, "error"));
}

async function deletePdf(pdfName) {
  if (!window.confirm(`确定删除报告 ${pdfName} 吗？`)) return;
  try {
    const result = await api(`/audio/pdf/delete/${encodeURIComponent(pdfName)}`, { method: "DELETE" }, true);
    setStatus($("diagnosisStatus"), result.msg || "报告已删除。", result.success === false ? "error" : "success");
    await loadPdfList();
  } catch (error) {
    setStatus($("diagnosisStatus"), error.message, "error");
  }
}

function switchWorkspaceView(viewId) {
  document.querySelectorAll(".workspace-view").forEach((view) => {
    view.hidden = view.id !== viewId;
  });
  document.querySelectorAll("[data-view]").forEach((button) => {
    const active = button.dataset.view === viewId;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  if (viewId === "audioListView") loadAudioList();
  if (viewId === "detectView") refreshAudioSelectors();
  if (viewId === "diagnosisView") {
    refreshAudioSelectors();
    loadScreeningTasks().then((tasks) => {
      if (tasks.some((task) => !["COMPLETED", "FAILED", "CANCELLED"].includes(task.status))) {
        startScreeningPolling();
      }
    });
    loadPdfList();
  }
  if (viewId === "adminView") {
    loadAdminUsers();
    loadAdminFiles();
    loadAdminStats();
  }
}

function switchAdminView(viewId) {
  document.querySelectorAll(".admin-panel").forEach((view) => {
    view.hidden = view.id !== viewId;
  });
  document.querySelectorAll("[data-admin-view]").forEach((button) => {
    const active = button.dataset.adminView === viewId;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  if (viewId === "adminUsersPanel") loadAdminUsers();
  if (viewId === "adminManagePanel") loadAdminFullUsers();
  if (viewId === "adminAudioPanel") loadAdminAudios();
  if (viewId === "adminUploadPanel") loadAdminFiles();
  if (viewId === "adminDetectPanel") {
    loadAdminFiles();
    loadAdminStats();
  }
}

async function loadAdminUsers() {
  if (state.role !== "ADMIN") return;
  try {
    const users = await api("/admin/users", {}, true);
    const container = $("adminUsersTable");
    if (!Array.isArray(users) || users.length === 0) {
      renderEmpty(container, "暂无用户。");
      return;
    }
    container.replaceChildren();
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>ID</th><th>用户名</th><th>注册时间</th><th>操作</th></tr></thead>";
    const tbody = document.createElement("tbody");
    users.forEach((user) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `<td>${user.id}</td><td>${user.username || ""}</td><td>${user.createTime || ""}</td><td></td>`;
      const actions = document.createElement("div");
      actions.className = "table-actions";
      actions.append(
        createButton("查看", "secondary-button small-button", () => loadAdminUserProfile(user.id)),
        createButton("踢下线", "danger-button small-button", () => kickUser(user.username))
      );
      tr.lastElementChild.append(actions);
      tbody.append(tr);
    });
    table.append(tbody);
    container.append(table);
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function loadAdminUserProfile(id) {
  try {
    const profile = await api(`/admin/user/${id}`, {}, true);
    alert(JSON.stringify(profile || {}, null, 2));
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function kickUser(username) {
  try {
    await api(`/admin/kick?username=${encodeURIComponent(username)}`, { method: "POST" }, true);
    setStatus($("adminStatus"), `${username} 已被踢下线。`, "success");
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function loadAdminFullUsers() {
  if (state.role !== "ADMIN") return;
  try {
    const users = await api("/admin/users/full", {}, true);
    state.adminFullUsers = Array.isArray(users) ? users : [];
    renderAdminManageTable();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

function renderAdminManageTable() {
  const container = $("adminManageTable");
  if (!state.adminFullUsers.length) {
    renderEmpty(container, "暂无用户资料。");
    return;
  }
  container.replaceChildren();
  const table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>姓名</th><th>年龄</th><th>电话</th><th>量表</th><th>操作</th></tr></thead>";
  const tbody = document.createElement("tbody");
  state.adminFullUsers.forEach((user, index) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${user.id}</td>
      <td><input data-field="username" data-index="${index}" value="${user.username || ""}"></td>
      <td><select data-field="role" data-index="${index}"><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></td>
      <td><input data-field="name" data-index="${index}" value="${user.name || ""}"></td>
      <td><input data-field="age" data-index="${index}" type="number" value="${user.age ?? ""}"></td>
      <td><input data-field="phone" data-index="${index}" value="${user.phone || ""}"></td>
      <td class="mini-fields">
        <input data-field="mmse" data-index="${index}" type="number" placeholder="MMSE" value="${user.mmse ?? ""}">
        <input data-field="moca" data-index="${index}" type="number" placeholder="MOCA" value="${user.moca ?? ""}">
        <input data-field="hkbc" data-index="${index}" type="number" placeholder="HKBC" value="${user.hkbc ?? ""}">
      </td>
      <td></td>`;
    tr.querySelector("select").value = user.role || "USER";
    const actions = document.createElement("div");
    actions.className = "table-actions";
    actions.append(
      createButton("保存", "secondary-button small-button", () => saveAdminUser(index)),
      createButton("删除", "danger-button small-button", () => deleteAdminUser(user.id))
    );
    tr.lastElementChild.append(actions);
    tbody.append(tr);
  });
  table.append(tbody);
  container.append(table);
  container.querySelectorAll("[data-field]").forEach((input) => {
    input.addEventListener("input", () => {
      const index = Number(input.dataset.index);
      const field = input.dataset.field;
      state.adminFullUsers[index][field] = input.type === "number" && input.value !== "" ? Number(input.value) : input.value;
    });
  });
}

async function saveAdminUser(index) {
  try {
    await api("/admin/user/full/update", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(state.adminFullUsers[index])
    }, true);
    setStatus($("adminStatus"), "用户信息已保存。", "success");
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function deleteAdminUser(id) {
  if (!window.confirm(`确定删除用户 ${id} 吗？`)) return;
  try {
    await api(`/admin/user/${id}`, { method: "DELETE" }, true);
    setStatus($("adminStatus"), "用户已删除。", "success");
    await loadAdminFullUsers();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function loadAdminAudios() {
  if (state.role !== "ADMIN") return;
  const params = new URLSearchParams();
  if ($("adminAudioUsername").value.trim()) params.set("username", $("adminAudioUsername").value.trim());
  if ($("adminAudioName").value.trim()) params.set("name", $("adminAudioName").value.trim());
  try {
    const rows = await api(`/admin/audios/full${params.toString() ? `?${params}` : ""}`, {}, true);
    const container = $("adminAudioTable");
    if (!Array.isArray(rows) || rows.length === 0) {
      renderEmpty(container, "暂无音频。");
      return;
    }
    container.replaceChildren();
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>ID</th><th>用户</th><th>姓名</th><th>文件</th><th>时长</th><th>上传时间</th><th>操作</th></tr></thead>";
    const tbody = document.createElement("tbody");
    rows.forEach((row) => {
      const fileName = String(row.filePath || "").split(/[\\/]/).pop();
      const tr = document.createElement("tr");
      tr.innerHTML = `<td>${row.id}</td><td>${row.userId ?? ""}</td><td>${row.name || ""}</td><td>${fileName}</td><td>${row.duration ?? 0}</td><td>${row.uploadTime || ""}</td><td></td>`;
      const actions = document.createElement("div");
      actions.className = "table-actions";
      actions.append(
        createButton("播放", "secondary-button small-button", () => playAdminManagedAudio(row)),
        createButton("删除", "danger-button small-button", () => deleteAdminAudio(row.id))
      );
      tr.lastElementChild.append(actions);
      tbody.append(tr);
    });
    table.append(tbody);
    container.append(table);
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

function playAdminManagedAudio(row) {
  const fileName = String(row.filePath || "").split(/[\\/]/).pop();
  const path = row.userId === 0
    ? `/admin/audios/admin/file/${encodeURIComponent(fileName)}`
    : `/admin/audio/file/${encodeURIComponent(fileName)}`;
  return playProtectedAudio("adminAudioPlayer", path, "adminStatus");
}

async function deleteAdminAudio(id) {
  if (!window.confirm(`确定删除音频 ${id} 吗？`)) return;
  try {
    await api(`/admin/audio/${id}`, { method: "DELETE" }, true);
    setStatus($("adminStatus"), "音频已删除。", "success");
    await loadAdminAudios();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function uploadAdminAudios() {
  const files = Array.from($("adminUploadFiles").files || []);
  if (!files.length) {
    setStatus($("adminStatus"), "请先选择音频文件。", "error");
    return;
  }
  const form = new FormData();
  files.forEach((file) => form.append("files", file));
  try {
    await api("/admin/audios/upload", { method: "POST", body: form }, true);
    setStatus($("adminStatus"), "管理员音频上传完成。", "success");
    $("adminUploadFiles").value = "";
    await loadAdminFiles();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function loadAdminFiles() {
  if (state.role !== "ADMIN") return;
  try {
    const files = await api("/admin/audios/admin", {}, true);
    const records = Array.isArray(files) ? files : [];
    fillSelect($("adminDetectAudioSelect"), records);
    const container = $("adminFileTable");
    if (!records.length) {
      renderEmpty(container, "暂无管理员音频文件。");
      return;
    }
    container.replaceChildren();
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>文件名</th><th>操作</th></tr></thead>";
    const tbody = document.createElement("tbody");
    records.forEach((fileName) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `<td>${fileName}</td><td></td>`;
      const actions = document.createElement("div");
      actions.className = "table-actions";
      actions.append(
        createButton("播放", "secondary-button small-button", () => {
          playProtectedAudio(
            "adminFilePlayer",
            `/admin/audios/admin/file/${encodeURIComponent(fileName)}`,
            "adminStatus"
          );
        }),
        createButton("删除", "danger-button small-button", () => deleteAdminFile(fileName))
      );
      tr.lastElementChild.append(actions);
      tbody.append(tr);
    });
    table.append(tbody);
    container.append(table);
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function deleteAdminFile(fileName) {
  if (!window.confirm(`确定删除管理员音频 ${fileName} 吗？`)) return;
  try {
    await api(`/admin/audios/admin/${encodeURIComponent(fileName)}`, { method: "DELETE" }, true);
    setStatus($("adminStatus"), "管理员音频已删除。", "success");
    await loadAdminFiles();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function runAdminDetect() {
  const fileName = $("adminDetectAudioSelect").value;
  if (!fileName) {
    setStatus($("adminStatus"), "请选择管理员音频。", "error");
    return;
  }
  try {
    const result = await api("/admin/detect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fileName })
    }, true);
    renderDetectResult($("adminDetectResult"), result);
    setStatus($("adminStatus"), "管理员检测完成。", "success");
    await loadAdminStats();
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

async function loadAdminStats() {
  if (state.role !== "ADMIN") return;
  try {
    const stats = await api("/admin/stats", {}, true);
    $("adminStats").textContent = `总检测次数：${stats.total ?? 0}`;
  } catch (error) {
    setStatus($("adminStatus"), error.message, "error");
  }
}

function bindEvents() {
  $("chatTab").addEventListener("click", () => switchTab("chat"));
  $("screeningTab").addEventListener("click", () => switchTab("screening"));
  $("workspaceTab").addEventListener("click", () => switchTab("workspace"));
  const primaryTabs = Array.from(document.querySelectorAll(".tabs > .tab"));
  primaryTabs.forEach((tab, index) => {
    tab.addEventListener("keydown", (event) => {
      const previous = event.key === "ArrowLeft" || event.key === "ArrowUp";
      const next = event.key === "ArrowRight" || event.key === "ArrowDown";
      if (!previous && !next && event.key !== "Home" && event.key !== "End") return;
      event.preventDefault();
      const targetIndex = event.key === "Home"
        ? 0
        : event.key === "End"
          ? primaryTabs.length - 1
          : (index + (previous ? -1 : 1) + primaryTabs.length) % primaryTabs.length;
      primaryTabs[targetIndex].focus();
      primaryTabs[targetIndex].click();
    });
  });
  $("fontToggle").addEventListener("click", () => {
    const enabled = document.body.classList.toggle("large-text");
    $("fontToggle").setAttribute("aria-pressed", String(enabled));
    $("fontToggle").textContent = enabled ? "恢复字号" : "放大文字";
  });
  $("chatForm").addEventListener("submit", (event) => {
    event.preventDefault();
    askQuestion($("chatInput").value);
  });
  $("modelSettingsForm").addEventListener("submit", saveAssistantModelSettings);
  $("modelProvider").addEventListener("change", () => renderModelSettingsEditor($("modelProvider").value));
  $("clearModelApiKey").addEventListener("click", clearSelectedModelApiKey);
  $("toggleModelApiKey").addEventListener("click", () => {
    const input = $("modelApiKey"); const visible = input.type === "text";
    input.type = visible ? "password" : "text";
    $("toggleModelApiKey").textContent = visible ? "显示 Key" : "隐藏 Key";
  });
  $("newConversation").addEventListener("click", createConversation);
  document.querySelectorAll("[data-question]").forEach((button) => {
    button.addEventListener("click", () => askQuestion(button.dataset.question));
  });
  $("loginForm").addEventListener("submit", (event) => {
    event.preventDefault();
    authenticate(false);
  });
  $("registerButton").addEventListener("click", () => authenticate(true));
  $("logoutButton").addEventListener("click", () => logout());
  $("startRecording").addEventListener("click", startRecording);
  $("stopRecording").addEventListener("click", stopRecording);
  $("audioFile").addEventListener("change", selectAudioFile);
  $("uploadAudio").addEventListener("click", uploadAudio);
  $("refreshHistory").addEventListener("click", loadHistory);
  $("saveProfile").addEventListener("click", saveProfile);
  $("loadProfile").addEventListener("click", loadProfile);
  $("changePassword").addEventListener("click", changePassword);
  $("loadAudioList").addEventListener("click", loadAudioList);
  $("runDetect").addEventListener("click", runDetect);
  $("refreshDetectAudio").addEventListener("click", refreshAudioSelectors);
  $("runDiagnosis").addEventListener("click", runDiagnosis);
  if ($("savePdf")) $("savePdf").addEventListener("click", savePdf);
  $("refreshPdfList").addEventListener("click", loadPdfList);
  document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => switchWorkspaceView(button.dataset.view));
  });
  document.querySelectorAll("[data-admin-view]").forEach((button) => {
    button.addEventListener("click", () => switchAdminView(button.dataset.adminView));
  });
  $("loadAdminUsers").addEventListener("click", loadAdminUsers);
  $("loadAdminFullUsers").addEventListener("click", loadAdminFullUsers);
  $("loadAdminAudios").addEventListener("click", loadAdminAudios);
  $("uploadAdminAudios").addEventListener("click", uploadAdminAudios);
  $("loadAdminFiles").addEventListener("click", loadAdminFiles);
  $("runAdminDetect").addEventListener("click", runAdminDetect);
  $("loadAdminStats").addEventListener("click", loadAdminStats);
}

initializeImageTasks();
bindEvents();
switchTab("chat");
switchWorkspaceView("profileView");
switchAdminView("adminUsersPanel");
updateAuthUi();
