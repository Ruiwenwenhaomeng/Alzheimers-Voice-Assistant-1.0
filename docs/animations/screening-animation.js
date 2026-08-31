(() => {
  const taskColors = { A: "#31d9ff", B: "#b998ff", C: "#57f2bd" };
  const states = [
    { title:"任务 A 开始上传", detail:"用户甲提交看图说话音频并确认筛查授权。", tasks:{ A:[0,"上传音频"] }, log:"A  POST /screening/tasks" },
    { title:"A 的任务与事件原子落库", detail:"同一数据库事务创建 QUEUED 任务和 screening.requested.v1 Outbox 事件。", tasks:{ A:[1,"QUEUED · 0%"], B:[0,"上传音频"] }, log:"A  task + outbox committed" },
    { title:"A 进入 RabbitMQ，B 正在落库", detail:"Outbox Publisher 使用发布确认把 A 投递到请求队列；用户乙的任务同步写入事务。", tasks:{ A:[2,"请求队列"], B:[1,"QUEUED · 0%"] }, queues:[1,0,0], log:"A  screening.requested.v1 published" },
    { title:"Worker-1 领取 A", detail:"prefetch=1 保证 Worker 一次只持有一条未确认消息。A 开始质量检查和语音转写；B 进入请求队列，C 开始上传。", tasks:{ A:[3,"转写 · 20%"], B:[2,"请求队列"], C:[0,"上传音频"] }, workers:{"python-1":"A · TRANSCRIBING"}, queues:[1,0,0], log:"A  transcription.started.v1" },
    { title:"两个 Python Worker 并行", detail:"Worker-2 领取 B；A 与 B 同时转写。C 的任务和 Outbox 事件完成落库。", tasks:{ A:[3,"转写 · 20%"], B:[3,"转写 · 20%"], C:[1,"QUEUED · 0%"] }, workers:{"python-1":"A · TRANSCRIBING","python-2":"B · TRANSCRIBING"}, log:"B  transcription.started.v1" },
    { title:"C 在请求队列等待", detail:"两个 Worker 都被占用，所以 C 即使已经进入 RabbitMQ 也不会抢占正在处理的任务。", tasks:{ A:[3,"特征 · 50%"], B:[3,"转写 · 20%"], C:[2,"等待 Worker"] }, workers:{"python-1":"A · FEATURES","python-2":"B · TRANSCRIBING"}, queues:[1,0,0], waiting:["C"], log:"C  queued; all python workers busy" },
    { title:"单任务内继续顺序执行", detail:"A 进入 LLM 分析，B 进入特征提取；C 仍在请求队列等待。", tasks:{ A:[3,"LLM · 70%"], B:[3,"特征 · 50%"], C:[2,"等待 Worker"] }, workers:{"python-1":"A · LLM_ANALYZING","python-2":"B · FEATURES"}, queues:[1,0,0], waiting:["C"], log:"A  features → llm" },
    { title:"A 写出分析产物", detail:"Worker-1 原子写入 analysis.json 并计算 SHA256，然后发布完成事件；B 正在做 LLM。", tasks:{ A:[4,"JSON + SHA"], B:[3,"LLM · 70%"], C:[2,"请求队列"] }, workers:{"python-2":"B · LLM_ANALYZING"}, queues:[1,1,0], log:"A  screening.analysis.completed.v1" },
    { title:"C 被释放出的 Worker-1 领取", detail:"A 进入 Java 结果线程池；与此同时 Worker-1 从请求队列取走 C，流水线继续前进。", tasks:{ A:[5,"结果持久化"], B:[4,"JSON + SHA"], C:[3,"转写 · 20%"] }, workers:{"python-1":"C · TRANSCRIBING","result-1":"A · RESULT_PERSISTING"}, queues:[0,1,0], log:"A  result listener validates artifact" },
    { title:"结果线程池也可并行", detail:"Result-2 处理 B，Result-1 完成 A 的诊断报告；C 继续提取特征。", tasks:{ A:[6,"PDF_QUEUED · 85%"], B:[5,"结果持久化"], C:[3,"特征 · 50%"] }, workers:{"python-1":"C · FEATURES","result-2":"B · RESULT_PERSISTING"}, queues:[0,0,1], log:"A  pdf.requested.v1" },
    { title:"PDF 单线程开始处理 A", detail:"PDF 线程领取 A。B 已生成诊断报告并加入 PDF 队列；C 进入 LLM 分析。", tasks:{ A:[7,"生成 PDF · 90%"], B:[6,"PDF_QUEUED · 85%"], C:[3,"LLM · 70%"] }, workers:{"python-1":"C · LLM_ANALYZING","pdf-1":"A · PDF_GENERATING"}, queues:[0,0,1], waiting:["B"], log:"A  PDF_GENERATING" },
    { title:"B 等待 PDF 线程", detail:"A 仍占用唯一 PDF 线程，因此 B 停留在 PDF 队列；C 写出 analysis.json。", tasks:{ A:[7,"生成 PDF · 90%"], B:[6,"等待 PDF"], C:[4,"JSON + SHA"] }, workers:{"pdf-1":"A · PDF_GENERATING"}, queues:[0,1,1], waiting:["B"], log:"B  pdf queued; pdf-1 busy" },
    { title:"A 完成，C 处理结果", detail:"A 的 PDF 文件与 pdf_report 已持久化，任务变为 COMPLETED；Result-1 校验并保存 C。", tasks:{ A:[8,"完成 · 100%"], B:[6,"等待 PDF"], C:[5,"结果持久化"] }, workers:{"result-1":"C · RESULT_PERSISTING"}, queues:[0,0,1], complete:1, log:"A  pdf.completed.v1" },
    { title:"PDF 线程转而处理 B", detail:"线程释放后从队列领取 B；C 的诊断报告完成并发布 PDF 请求。", tasks:{ A:[8,"可下载 PDF"], B:[7,"生成 PDF · 90%"], C:[6,"PDF_QUEUED · 85%"] }, workers:{"pdf-1":"B · PDF_GENERATING"}, queues:[0,0,1], complete:1, log:"B  PDF_GENERATING; C pdf queued" },
    { title:"C 在 PDF 队列等待", detail:"B 仍占用唯一 PDF 线程；C 不会丢失，状态和队列消息都已持久化。", tasks:{ A:[8,"可下载 PDF"], B:[7,"生成 PDF · 90%"], C:[6,"等待 PDF"] }, workers:{"pdf-1":"B · PDF_GENERATING"}, queues:[0,0,1], waiting:["C"], complete:1, log:"C  pdf queued; pdf-1 busy" },
    { title:"B 完成，C 开始生成 PDF", detail:"B 变为 COMPLETED，PDF 线程立即领取 C。", tasks:{ A:[8,"可下载 PDF"], B:[8,"完成 · 100%"], C:[7,"生成 PDF · 90%"] }, workers:{"pdf-1":"C · PDF_GENERATING"}, complete:2, log:"B  completed; C PDF_GENERATING" },
    { title:"C 的 PDF 正在落盘", detail:"服务端生成 PDF、保存文件记录和报告关联，随后发布完成事件。", tasks:{ A:[8,"可下载 PDF"], B:[8,"可下载 PDF"], C:[7,"生成 PDF · 90%"] }, workers:{"pdf-1":"C · PDF_GENERATING"}, complete:2, log:"C  writing pdf_report" },
    { title:"三个异步任务全部完成", detail:"即使用户中途离开网页，任务仍由队列和 Worker 推进；重新进入后可从数据库查询状态并下载 PDF。", tasks:{ A:[8,"完成 · 100%"], B:[8,"完成 · 100%"], C:[8,"完成 · 100%"] }, complete:3, log:"all tasks COMPLETED" }
  ];

  const taskElements = { A:document.getElementById("task-a"), B:document.getElementById("task-b"), C:document.getElementById("task-c") };
  const threadIds = ["python-1", "python-2", "result-1", "result-2", "pdf-1"];
  const playButton = document.getElementById("playButton");
  const terminal = document.getElementById("terminalBody");
  let index = -1;
  let playing = false;
  let timer;

  function render() {
    const state = index >= 0 ? states[index] : null;
    Object.entries(taskElements).forEach(([key, element]) => {
      const task = state?.tasks[key];
      element.classList.remove("waiting", "done");
      if (!task) {
        element.style.opacity = "0";
        element.textContent = "等待进入";
        element.style.setProperty("--stage", "0");
        return;
      }
      element.style.opacity = "1";
      element.style.setProperty("--stage", String(task[0]));
      element.textContent = `${key} · ${task[1]}`;
      if (state.waiting?.includes(key)) element.classList.add("waiting");
      if (task[0] === 8) element.classList.add("done");
    });
    document.querySelectorAll(".stage-cell").forEach(cell => cell.classList.remove("active"));
    if (state) [...new Set(Object.values(state.tasks).map(task => task[0]))].forEach(stage => document.getElementById(`stage-${stage}`).classList.add("active"));
    threadIds.forEach(id => {
      const slot = document.getElementById(id);
      slot.classList.remove("busy");
      slot.style.removeProperty("--slot-color");
      slot.querySelector("small").textContent = "空闲";
      const work = state?.workers?.[id];
      if (work) {
        const taskKey = work.charAt(0);
        slot.classList.add("busy");
        slot.style.setProperty("--slot-color", taskColors[taskKey]);
        slot.querySelector("small").textContent = work;
      }
    });
    document.getElementById("stepTitle").textContent = state?.title || "等待开始";
    document.getElementById("stepDetail").textContent = state?.detail || "播放后可看到不同颜色的任务跨服务流动，以及线程被占用、释放和重新调度。";
    document.getElementById("tickBadge").textContent = `T+${String(Math.max(index + 1, 0)).padStart(2, "0")}`;
    const queues = state?.queues || [0,0,0];
    document.getElementById("requestCount").textContent = queues[0];
    document.getElementById("resultCount").textContent = queues[1];
    document.getElementById("pdfCount").textContent = queues[2];
    document.getElementById("completeCount").textContent = `${state?.complete || 0} / 3`;
    document.getElementById("progressText").textContent = `${Math.max(index + 1, 0)} / ${states.length}`;
    document.getElementById("progressBar").style.width = `${Math.max(index + 1, 0) / states.length * 100}%`;
    terminal.replaceChildren();
    states.slice(0, index + 1).forEach((item, lineIndex) => {
      const line = document.createElement("div");
      line.className = lineIndex === index ? "log-line current" : "log-line";
      line.textContent = `${String(lineIndex + 1).padStart(2, "0")}  ${item.log}`;
      terminal.append(line);
    });
    terminal.scrollTop = terminal.scrollHeight;
  }

  function pause() {
    playing = false;
    clearTimeout(timer);
    playButton.textContent = "▶ 播放";
  }

  function advance() {
    if (index >= states.length - 1) return pause();
    index += 1;
    render();
    if (playing) timer = setTimeout(advance, 1300 / Number(document.getElementById("speedSelect").value));
  }

  playButton.addEventListener("click", () => {
    if (playing) return pause();
    if (index >= states.length - 1) index = -1;
    playing = true;
    playButton.textContent = "❚❚ 暂停";
    advance();
  });
  document.getElementById("stepButton").addEventListener("click", () => { pause(); advance(); });
  document.getElementById("resetButton").addEventListener("click", () => { pause(); index = -1; render(); });
  document.getElementById("speedSelect").addEventListener("change", () => {
    if (playing) { clearTimeout(timer); timer = setTimeout(advance, 300); }
  });
  render();
})();
