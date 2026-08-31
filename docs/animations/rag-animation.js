(() => {
  const scenarios = {
    vector: {
      label: "向量检索命中",
      dim: ["rag-local", "rag-web"],
      steps: [
        ["rag-user", "接收用户问题", "用户在已有会话中询问阿尔茨海默病相关问题。", [["payload", "最近总忘记刚说过的话，是正常衰老吗？"], ["client", "anonymous UUID / 登录用户 JWT"]], "POST message/stream"],
        ["rag-api", "锁定本轮会话", "校验会话归属，以数据库原子状态锁避免同一会话并发生成，并检查 100 轮上限。", [["state", "IDLE → GENERATING"], ["transport", "text/event-stream"]], "emit start"],
        ["rag-memory", "装载精简上下文", "优先读取 Redis；未命中时由 MySQL 回源。使用滚动概要加尚未被概要覆盖的近期消息。", [["summary", "已覆盖 20 轮"], ["recent", "最近 6 条消息"]], "memory cache hit"],
        ["rag-router", "判断问题类别", "关键词路由将问题归入症状类，急症与超范围问题会直接进入专用规则分支。", [["category", "SYMPTOMS"], ["emergency", "false"]], "route=SYMPTOMS"],
        ["rag-embedding", "生成查询向量", "调用 BGE-M3，把当前问题转换为稠密向量。", [["model", "BAAI/bge-m3"], ["input", "1 条 query"]], "embedding ok"],
        ["rag-qdrant", "执行相似度检索", "在 Qdrant 集合中查找相似知识，使用 Top-K 和分数阈值过滤低相关片段。", [["topK", "4"], ["threshold", "0.72"]], "qdrant hit=3"],
        ["rag-docs", "整理证据片段", "返回与记忆力下降、正常衰老和就医建议相关的 3 条证据。", [["documents", "3"], ["source", "INTRODUCTION / SYMPTOMS"]], "knowledge selected"],
        ["rag-prompt", "组装受控 Prompt", "将系统规则、滚动概要、近期对话、知识证据和当前问题组合起来。", [["thinking", "disabled"], ["context", "summary + recent + docs"]], "prompt ready"],
        ["rag-model", "模型开始生成", "DeepSeek 根据证据组织回答；服务端忽略 reasoning_content，并过滤 think 标签。", [["provider", "DeepSeek"], ["mode", "stream=true"]], "upstream delta..."],
        ["rag-stream", "发送 SSE 增量", "把模型文本按 delta 事件推送给前端，并在最后发送 complete。", [["events", "start → delta × N → complete"], ["think", "不下发"]], "emit delta"],
        ["rag-browser", "逐字呈现回答", "页面持续拼接文本，用户无需等待整段回答生成结束。", [["render", "incremental"], ["connection", "open"]], "browser append"],
        ["rag-save", "保存消息并刷新记忆", "回答落 MySQL，失效 Redis 热缓存；每到第 10 轮，概要任务在独立执行器中异步运行。", [["conversation", "持久化"], ["summary", "10 轮边界触发"]], "state=IDLE; done"]
      ]
    },
    fallback: {
      label: "Embedding 失败，本地降级",
      dim: ["rag-web"],
      steps: [
        ["rag-user", "接收用户问题", "用户询问照护沟通方法。", [["payload", "家人重复问同一件事，我应该怎么回应？"], ["client", "conversation owner"]], "POST message/stream"],
        ["rag-api", "锁定本轮会话", "校验归属与轮数上限，写入用户消息并发出 start。", [["state", "IDLE → GENERATING"], ["turn", "≤ 100"]], "emit start"],
        ["rag-memory", "装载精简上下文", "从 Redis / MySQL 取得滚动概要和近期消息。", [["strategy", "summary + recent"], ["cache", "Redis"]], "memory loaded"],
        ["rag-router", "判断问题类别", "问题被归类到照护应对。", [["category", "COPING"], ["emergency", "false"]], "route=COPING"],
        ["rag-embedding", "向量服务异常", "Embedding 请求超时或返回错误；检索器捕获异常，不中断整次回答。", [["result", "timeout"], ["action", "fallback"]], "WARN embedding failed"],
        ["rag-local", "切换本地关键词检索", "扫描 classpath 中 300 条知识，综合类别、关键词、短语和二元词组评分。", [["documents", "300"], ["minScore", "16"]], "local candidates=7"],
        ["rag-docs", "取得本地证据", "按评分选择最多 4 条照护建议。", [["topK", "4"], ["dependency", "无需外部向量服务"]], "local hit=4"],
        ["rag-prompt", "组装受控 Prompt", "把概要、近期消息与本地证据一起交给模型。", [["context", "summary + recent + local docs"], ["thinking", "disabled"]], "prompt ready"],
        ["rag-model", "模型生成回答", "模型用平静、简洁的方式说明先回应情绪、避免争辩和使用提示物。", [["provider", "DeepSeek"], ["stream", "true"]], "upstream delta..."],
        ["rag-stream", "发送 SSE 增量", "只转发最终回答文本，不发送思考字段。", [["event", "delta"], ["filter", "reasoning_content / think"]], "emit delta"],
        ["rag-browser", "逐段呈现", "前端实时显示已经到达的内容。", [["latency", "首段优先"], ["render", "incremental"]], "browser append"],
        ["rag-save", "保存并释放会话锁", "落库、清缓存，并在需要时异步更新概要。", [["state", "GENERATING → IDLE"], ["storage", "MySQL"]], "complete"]
      ]
    },
    web: {
      label: "知识库无命中，转联网",
      dim: ["rag-prompt", "rag-model"],
      steps: [
        ["rag-user", "接收用户问题", "用户的问题需要项目知识库之外的更新资料。", [["payload", "最近是否有新的阿尔茨海默病治疗进展？"], ["need", "时效性信息"]], "POST message/stream"],
        ["rag-api", "锁定本轮会话", "检查会话归属、状态和 100 轮边界。", [["state", "IDLE → GENERATING"], ["SSE", "connected"]], "emit start"],
        ["rag-memory", "读取对话记忆", "只装载摘要和未总结的近期消息，避免 100 轮历史全部进入 Prompt。", [["history", "bounded"], ["source", "Redis / MySQL"]], "memory loaded"],
        ["rag-router", "识别为科普问题", "通过关键词路由进入 INTRODUCTION 类检索。", [["category", "INTRODUCTION"], ["outOfScope", "false"]], "route=INTRODUCTION"],
        ["rag-embedding", "生成查询向量", "BGE-M3 正常返回向量。", [["result", "ok"], ["query", "最新治疗进展"]], "embedding ok"],
        ["rag-qdrant", "向量结果未过阈值", "相似结果均低于 0.72，不把低相关内容当作证据。", [["bestScore", "0.61"], ["threshold", "0.72"]], "qdrant hit=0"],
        ["rag-local", "本地检索仍无可靠命中", "关键词评分低于最低分 16，最终知识片段为空。", [["localScore", "12"], ["documents", "0"]], "knowledge empty"],
        ["rag-web", "调用联网检索", "按配置调用带 Web Search 工具的 DeepSeek 接口，取得有时效性的答复。此路径内部先完成检索与生成。", [["tool", "web_search"], ["mode", "内部非流式"]], "web search completed"],
        ["rag-stream", "服务端切片为 SSE", "联网结果已经完整返回，Java 按固定长度切片，继续保持前端统一的 delta 协议。", [["chunk", "约 24 字符"], ["events", "delta × N"]], "server chunking"],
        ["rag-browser", "逐段呈现联网回答", "前端无需区分上游是真流式还是服务端切片。", [["protocol", "same SSE contract"], ["render", "incremental"]], "browser append"],
        ["rag-save", "保存最终回答", "回答持久化，释放会话状态，并维护缓存与滚动概要。", [["storage", "MySQL"], ["state", "IDLE"]], "complete"]
      ]
    }
  };

  const allNodeIds = Array.from(document.querySelectorAll(".flow-node")).map(node => node.id);
  const board = document.getElementById("ragBoard");
  const packet = document.getElementById("ragPacket");
  const playButton = document.getElementById("playButton");
  const stepButton = document.getElementById("stepButton");
  const resetButton = document.getElementById("resetButton");
  const scenarioSelect = document.getElementById("scenarioSelect");
  const speedSelect = document.getElementById("speedSelect");
  const terminal = document.getElementById("terminalBody");
  let index = -1;
  let playing = false;
  let timer;

  function currentScenario() { return scenarios[scenarioSelect.value]; }

  function movePacket(node) {
    const boardBox = board.getBoundingClientRect();
    const box = node.getBoundingClientRect();
    packet.style.opacity = "1";
    packet.style.transform = `translate(${box.left - boardBox.left + box.width / 2 - 16}px, ${box.top - boardBox.top + box.height / 2 - 16}px)`;
  }

  function render() {
    const scenario = currentScenario();
    const steps = scenario.steps;
    const safeIndex = Math.max(index, 0);
    allNodeIds.forEach(id => {
      const node = document.getElementById(id);
      node.classList.remove("active", "done", "dim");
      if (scenario.dim.includes(id)) node.classList.add("dim");
    });
    steps.slice(0, safeIndex).forEach(step => document.getElementById(step[0]).classList.add("done"));
    if (index >= 0) {
      const step = steps[index];
      const node = document.getElementById(step[0]);
      node.classList.remove("dim", "done");
      node.classList.add("active");
      movePacket(node);
      document.getElementById("stepTitle").textContent = step[1];
      document.getElementById("stepDetail").textContent = step[2];
      const list = document.getElementById("dataList");
      list.replaceChildren();
      step[3].forEach(([key, value]) => {
        const dt = document.createElement("dt");
        const dd = document.createElement("dd");
        dt.textContent = key;
        dd.textContent = value;
        list.append(dt, dd);
      });
    } else {
      packet.style.opacity = "0";
      document.getElementById("stepTitle").textContent = "等待开始";
      document.getElementById("stepDetail").textContent = "点击“播放”或“单步”，观察请求、证据和回答在系统中移动。";
      document.getElementById("dataList").replaceChildren();
    }
    terminal.replaceChildren();
    steps.slice(0, index + 1).forEach((step, lineIndex) => {
      const line = document.createElement("div");
      line.className = lineIndex === index ? "log-line current" : "log-line";
      line.textContent = `${String(lineIndex + 1).padStart(2, "0")}  ${step[4]}`;
      terminal.append(line);
    });
    terminal.scrollTop = terminal.scrollHeight;
    document.getElementById("scenarioBadge").textContent = scenario.label;
    document.getElementById("progressText").textContent = `${Math.max(index + 1, 0)} / ${steps.length}`;
    document.getElementById("progressBar").style.width = `${Math.max(index + 1, 0) / steps.length * 100}%`;
  }

  function pause() {
    playing = false;
    clearTimeout(timer);
    playButton.textContent = "▶ 播放";
  }

  function advance() {
    const steps = currentScenario().steps;
    if (index >= steps.length - 1) {
      pause();
      return;
    }
    index += 1;
    render();
    if (playing) timer = setTimeout(advance, 1250 / Number(speedSelect.value));
  }

  playButton.addEventListener("click", () => {
    if (playing) return pause();
    if (index >= currentScenario().steps.length - 1) index = -1;
    playing = true;
    playButton.textContent = "❚❚ 暂停";
    advance();
  });
  stepButton.addEventListener("click", () => { pause(); advance(); });
  resetButton.addEventListener("click", () => { pause(); index = -1; render(); });
  scenarioSelect.addEventListener("change", () => { pause(); index = -1; render(); });
  speedSelect.addEventListener("change", () => {
    if (playing) { clearTimeout(timer); timer = setTimeout(advance, 300); }
  });
  window.addEventListener("resize", () => {
    if (index >= 0) movePacket(document.getElementById(currentScenario().steps[index][0]));
  });
  render();
})();
